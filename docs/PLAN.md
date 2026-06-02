# 구현 계획

## 현재 상태

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
| `service/stt/RtzrWebSocketSttService.java` | real profile | RTZR WebSocket STT (기본) |
| `service/stt/ClovaSpeechSttService.java` | real-grpc profile | CLOVA Speech gRPC STT (선택) |
| `service/llm/ClaudeApiLlmService.java` | real profile | Anthropic API 연동 |
| `service/tts/ClovaVoiceTtsService.java` | real profile | CLOVA Voice TTS 연동 |

---

## 단계별 계획

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

## 진행 현황

| Phase | 상태 |
|---|---|
| Phase 1 — 외부 API 설정 가이드 | ✅ 완료 |
| Phase 2 — sim profile 완성 | 대기 |
| Phase 3 — E2E 동작 확인 (sim) | 대기 |
| Phase 4 — real profile 구현 | 대기 |
| Phase 5 — E2E 동작 확인 (real) | 대기 |
