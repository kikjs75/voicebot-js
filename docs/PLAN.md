# 구현 계획

## 목차

- [현재 상태](#현재-상태)
- [단계별 계획](#단계별-계획)
  - [Phase 1 — 외부 API 설정 가이드](#phase-1--외부-api-설정-가이드)
  - [Phase 2 — sim profile 완성](#phase-2--sim-profile-완성)
  - [Phase 3 — E2E 동작 확인 (sim)](#phase-3--e2e-동작-확인-sim)
  - [Phase 4 — real profile 구현](#phase-4--real-profile-구현)
  - [Phase 5 — E2E 동작 확인 (real)](#phase-5--e2e-동작-확인-real)
  - [Phase 6 — CTI WebSocket 구현](#phase-6--cti-websocket-구현)
- [진행 현황](#진행-현황)

---

## 현재 상태

[↑ 목차](#목차)

### 완료된 것

| 파일 | 설명 |
|---|---|
| `service/stt/SttService.java` | STT 인터페이스 |
| `service/stt/SimulatorSttService.java` | sim profile 구현체 |
| `service/llm/LlmService.java` | LLM 인터페이스 |
| `service/llm/SimulatorLlmService.java` | sim profile 구현체 |
| `service/tts/TtsService.java` | TTS 인터페이스 |
| `service/tts/SimulatorTtsService.java` | sim profile 구현체 |
| `config/WebClientConfig.java` | WebClient 빈 설정 |
| `application.yml` / `application-sim.yml` / `application-real.yml` | 환경별 설정 |
| `simulators/stt`, `llm`, `tts`, `call` | Node.js 시뮬레이터 4종 |
| `docker-compose.yml`, `docker-compose.sim.yml` | 인프라 + 시뮬레이터 구성 |

### CLAUDE.md 기준 누락된 것

| 파일 | 우선순위 | 비고 |
|---|---|---|
| `call/CallController.java` | 필수 | HTTP 진입점 |
| `call/CallHandler.java` | 필수 | STT→LLM→TTS 오케스트레이션 |
| `call/CallSession.java` | 필수 | 콜 상태 모델 |
| `domain/CallRecord.java` | 필수 | JPA 엔티티 |
| `repository/CallRecordRepository.java` | 필수 | DB 저장 |
| `service/stt/RtzrWebSocketSttService.java` | real profile | RTZR WebSocket STT |
| `service/llm/ClaudeApiLlmService.java` | real profile | Anthropic API 연동 |
| `service/tts/GoogleCloudTtsService.java` | real profile | Google Cloud TTS 연동 |

---

## 단계별 계획

[↑ 목차](#목차)

### Phase 1 — 외부 API 설정 가이드

> 목표: real profile 구현 전에 각 외부 API의 인증 방식 / 요청·응답 스펙을 문서화한다.

**산출물**
- `docs/EXTERNAL-API.md` ✅
- `.env.example` 키 구조 수정 ✅
- `application-real.yml` 설정 구조 수정 ✅
- `application-real-grpc.yml` 신규 생성 ✅

| 항목 | 내용 |
|---|---|
| RTZR STT | WebSocket 스트리밍, 토큰 발급, 오디오 포맷, 응답 파싱 |
| CLOVA Speech STT | gRPC 스트리밍, 도메인 Secret Key, 16kHz 업샘플링 |
| CLOVA Voice TTS | form-urlencoded REST, Client ID/Secret, sampling-rate=8000 |
| Anthropic Claude | API 키, messages 구조, 응답 파싱 |
| `.env` 키 매핑 | 각 API 키 → 환경변수명 대응표 |

**STT Profile 구조**

| Profile | 구현체 | 프로토콜 |
|---|---|---|
| `sim` | SimulatorSttService | REST (기존 유지) |
| `real` | RtzrWebSocketSttService | WebSocket (기본) |
| `real-grpc` | ClovaSpeechSttService | gRPC (선택) |

**SttService 인터페이스 변경**

```java
// 기존: String recognize(byte[] audioData, String callId)
// 변경: Reactive Streaming
Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId);
record SttResult(String text, boolean isFinal) {}
```

완료 기준: 이 문서만 보고 real profile 서비스 구현체를 작성할 수 있는 상태.

---

### Phase 2 — sim profile 완성

> 목표: 시뮬레이터로 전체 파이프라인이 동작하는 최소 구현.

**구현 순서**

1. `call/CallSession.java` — 콜 상태 모델 (callId, 대화 이력, 상태)
2. `domain/CallRecord.java` — JPA 엔티티 (callId, STT 텍스트, LLM 응답, 처리시간)
3. `repository/CallRecordRepository.java` — Spring Data JPA
4. `call/CallHandler.java` — STT→LLM→TTS 오케스트레이션 + PERF 로그
5. `call/CallController.java` — `POST /call/incoming` 엔드포인트

완료 기준: `./mvnw spring-boot:run -Dspring-boot.run.profiles=sim` 으로 앱이 기동되고 `/call/incoming` 요청을 받을 수 있는 상태.

---

### Phase 3 — E2E 동작 확인 (sim)

> 목표: call-simulator UI → Spring Boot → stt/llm/tts 시뮬레이터 전체 흐름 검증.

**검증 항목**

| 항목 | 확인 방법 |
|---|---|
| 기본 콜 흐름 | call-simulator(8085)에서 콜 발신 → 응답 수신 |
| PERF 로그 | STT/LLM/TTS/전체 처리시간 로그 출력 여부 |
| DB 저장 | CallRecord 가 MariaDB에 정상 저장되는지 |
| Redis 세션 | callId 기준 세션 상태가 저장/조회되는지 |
| 에러 케이스 | 시뮬레이터 다운 시 Spring 응답 확인 |

완료 기준: 위 5개 항목 모두 통과.

---

### Phase 4 — real profile 구현

> 목표: 실제 외부 API와 연동되는 구현체 작성. Phase 1 문서를 기준으로 구현.

**구현 순서**

1. `service/stt/ClovaSpeechSttService.java` — Clova Speech REST 호출
2. `service/llm/ClaudeApiLlmService.java` — Anthropic Messages API 호출
3. `service/tts/ClovaVoiceTtsService.java` — Clova Voice REST 호출
4. `.env` API 키 설정 후 `SPRING_PROFILES_ACTIVE=real` 로 기동 확인

완료 기준: real profile로 앱이 기동되고 `/call/incoming` 요청을 받을 수 있는 상태.

---

### Phase 5 — E2E 동작 확인 (real)

> 목표: 실제 외부 서비스(Clova Speech, Claude, Clova Voice)로 전체 파이프라인 검증.

**검증 항목**

| 항목 | 확인 방법 |
|---|---|
| STT | 실제 음성 파일 → Clova Speech → 텍스트 변환 정확도 확인 |
| LLM | 변환된 텍스트 → Claude API → 응답 품질 확인 |
| TTS | Claude 응답 → Clova Voice → 음성 출력 품질 확인 |
| PERF 목표 | STT < 1,000ms / LLM < 3,000ms / TTS < 1,000ms / 전체 < 5,000ms |
| 비용/사용량 | 각 외부 API 콘솔에서 호출 횟수 및 비용 확인 |

완료 기준: 실제 음성 입력 → STT → LLM → TTS → 음성 출력 전체 동작 + PERF 목표 달성.

---

---

### Phase 6 — CTI WebSocket 구현

> 목표: 기존 HTTP 방식을 유지하면서 실시간 음성 스트리밍을 위한 WebSocket 엔드포인트(`/ws/cti`) 추가.
> 기존 소스 수정 없이 파일 2개(WebSocketConfig, CtiWebSocketHandler) + 프론트엔드(frontend/) 추가.

**구현 의도**

현재 `/call/incoming`은 오디오 파일을 HTTP body에 담아 일괄 전송하는 방식이다.
실제 CTI 환경에서는 전화 통화 중 음성이 실시간으로 스트리밍되므로,
WebSocket으로 음성 청크를 250ms 간격으로 수신하면서 STT → LLM → TTS 파이프라인을 처리해야 한다.
`reference/voicebot-demo/`의 데모 소스(`CtiSimulator.jsx`, `CtiPipeline_Spring.java`)가 이 구조의 원형이다.

**구현 범위**

| 파일 | 작업 |
|---|---|
| `pom.xml` | `spring-boot-starter-websocket` 의존성 추가 |
| `config/WebSocketConfig.java` | 신규: `/ws/cti` 핸들러 등록 |
| `call/CtiWebSocketHandler.java` | 신규: WebSocket 오케스트레이터 (Sinks 브리지) |
| `frontend/` | 신규: Vite React 프로젝트 + CtiSimulator.jsx |

**구현 단계**

1. `pom.xml` 의존성 추가
2. `WebSocketConfig.java` 작성
3. `CtiWebSocketHandler.java` 작성 (Sinks.Many 브리지)
4. `frontend/` Vite 프로젝트 생성 + CtiSimulator.jsx 연결

**테스트 단계**

| 단계 | Profile | 방법 | 담당 |
|---|---|---|---|
| E2E 자체 테스트 - sim | sim | WebSocket 연결 → 음성 청크 전송 → STT_FINAL/LLM_RESULT/TTS_TEXT 수신 확인 | Claude |
| E2E 자체 테스트 - real | real-google | 동일 테스트를 실제 외부 API(RTZR/Claude/Google TTS)로 실행 | Claude |
| 담당자 수동 테스트 | real-google | 마이크 입력 → 실시간 STT/LLM/TTS 결과 확인 | 담당자 직접 |

**완료 기준**

- [ ] `/ws/cti` WebSocket 연결 수립
- [ ] 음성 청크 수신 → STT → LLM → TTS 파이프라인 동작
- [ ] 브라우저에서 STT_FINAL / LLM_RESULT / TTS_TEXT 수신 확인
- [ ] 기존 `/call/incoming` HTTP 방식 정상 동작 유지

**진행 중 특이사항**

1. **callId=null 버그** — STT 콜백이 비동기 실행될 때 `afterConnectionClosed`가 먼저 `callIdMap`을 정리해 null이 되는 문제. subscribe 클로저에서 callId를 직접 캡처하는 방식으로 해결.
2. **Reactor NIO 스레드 block() 오류** — STT 콜백이 `reactor-http-nio` 스레드에서 실행되는데, `SimulatorLlmService.chat()`이 내부적으로 `.block()`을 호출해 오류 발생. `.publishOn(Schedulers.boundedElastic())`으로 블로킹 허용 스레드로 전환하여 해결.
3. **application.yml DB/Redis localhost 잘못된 기본값** — `application.yml`의 `localhost` 설정이 devcontainer 환경에서 dead code였음. `${DB_HOST:mariadb}`, `${REDIS_HOST:redis}`로 수정해 모든 profile이 올바른 Docker 호스트명을 사용하도록 통일.
4. **real-google E2E 테스트 음성 파일** — real RTZR STT는 실제 음성 데이터 필요. `/tmp/korean-test.pcm` (WAV 헤더 포함, 약 4.4초 한국어 음성)을 사용. WAV 헤더 44바이트를 제외한 raw PCM을 4096바이트 청크로 스트리밍.

---

## 진행 현황

[↑ 목차](#목차)

| Phase | 상태 |
|---|---|
| Phase 1 — 외부 API 설정 가이드 | ✅ 완료 |
| Phase 2 — sim profile 완성 | ✅ 완료 |
| Phase 3 — E2E 동작 확인 (sim) | ✅ 완료 |
| Phase 4 — real profile 구현 | ✅ 완료 |
| Phase 5 — E2E 동작 확인 (real) | ✅ 완료 |
| Phase 6 — CTI WebSocket 구현 | ✅ E2E 자체 테스트 sim/real 완료 (담당자 수동 테스트 대기) |
