# CLAUDE.md — Claude Code 전용 개발 가이드

이 파일은 Claude Code가 이 프로젝트를 이해하고 코드를 작성하기 위한 핵심 컨텍스트입니다.
새로운 기능 구현 전에 반드시 이 파일을 먼저 읽으세요.

---

## 프로젝트 개요

**음성봇 백엔드 서버** — 콜센터 자동화를 위한 STT/LLM/TTS 파이프라인.
전화 통화 음성을 받아 STT로 텍스트 변환 → LLM으로 응답 생성 → TTS로 음성 합성하여 반환.

---

## 기술 스택

| 항목 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3 |
| Build | Maven |
| DB | MariaDB 11 |
| Cache | Redis 7 |
| HTTP Client | WebClient (WebFlux) |
| 외부 STT | Clova Speech (profile: real) |
| 외부 LLM | Claude API - Anthropic (profile: real) |
| 외부 TTS | Clova Voice (profile: real) |

---

## 핵심 설계 원칙

### 1. 인터페이스 기반 어댑터 패턴

외부 서비스(STT/LLM/TTS)는 반드시 인터페이스를 통해서만 호출한다.
구현체는 Spring Profile로 교체한다. 비즈니스 로직은 인터페이스만 의존해야 한다.

```
SttService (interface)
  ├── SimulatorSttService  @Profile("sim")
  └── ClovaSpeechSttService @Profile("real")

LlmService (interface)
  ├── SimulatorLlmService  @Profile("sim")
  └── ClaudeApiLlmService  @Profile("real")

TtsService (interface)
  ├── SimulatorTtsService  @Profile("sim")
  └── ClovaVoiceTtsService @Profile("real")
```

### 2. Profile 전환

- `sim`: 시뮬레이터 서버를 바라봄 (로컬 개발)
- `real`: 실제 외부 API 호출 (운영/스테이징)
- 전환: `SPRING_PROFILES_ACTIVE=real` 환경변수로 교체. 코드 변경 없음.

### 3. 콜 세션 관리

- 모든 메서드에 `callId` 파라미터를 전달한다.
- `callId`는 Redis에 세션 상태를 저장하는 키로 사용된다.
- 로그에는 항상 `callId`를 포함해야 한다.

---
### 4. 성능 측정 로그

STT → LLM → TTS 각 단계의 처리 시간을 반드시 측정하고 기록한다.

- 측정 단위: 밀리초(ms)
- 로그 레벨: `log.info`
- 형식: `[서비스명-PERF] callId={} elapsed={}ms`

**구현 패턴**
\```java
long start = System.currentTimeMillis();
String result = sttService.recognize(audio, callId);
log.info("[STT-PERF] callId={} elapsed={}ms", callId, System.currentTimeMillis() - start);
\```

**측정 대상**

| 단계 | 로그 태그 | 목표 응답시간 |
|------|-----------|-------------|
| STT  | `[STT-PERF]`  | < 1,000ms |
| LLM  | `[LLM-PERF]`  | < 3,000ms |
| TTS  | `[TTS-PERF]`  | < 1,000ms |
| 전체 파이프라인 | `[CALL-PERF]` | < 5,000ms |

---

## 패키지 구조

```
src/main/java/com/voicebot/
├── VoicebotApplication.java
├── call/                    # 콜 흐름 제어 (진입점)
│   ├── CallController.java
│   ├── CallHandler.java     # STT → LLM → TTS 오케스트레이션
│   └── CallSession.java     # 콜 상태 모델
├── service/
│   ├── stt/
│   │   ├── SttService.java
│   │   ├── SimulatorSttService.java
│   │   └── ClovaSpeechSttService.java
│   ├── llm/
│   │   ├── LlmService.java
│   │   ├── SimulatorLlmService.java
│   │   └── ClaudeApiLlmService.java
│   └── tts/
│       ├── TtsService.java
│       ├── SimulatorTtsService.java
│       └── ClovaVoiceTtsService.java
├── domain/                  # JPA Entity
│   └── CallRecord.java
├── repository/
│   └── CallRecordRepository.java
└── config/
    └── WebClientConfig.java
```

---

## 코딩 컨벤션

- Lombok 사용: `@Slf4j`, `@RequiredArgsConstructor`, `@Builder`
- 예외: 커스텀 예외 클래스 대신 `RuntimeException` 상속 (`VoicebotException`)
- 로그: `log.debug("[서비스명] callId={} ...", callId, ...)` 형식 통일
- 테스트: 단위 테스트는 `@ExtendWith(MockitoExtension.class)` 사용

---

## 환경변수 (.env)

```
# 시뮬레이터 URL (sim profile)
STT_URL=http://localhost:8081
LLM_URL=http://localhost:8082
TTS_URL=http://localhost:8083

# 실제 서비스 API 키 (real profile)
CLOVA_SPEECH_API_KEY=
ANTHROPIC_API_KEY=
CLOVA_VOICE_API_KEY=
```

---

## 자주 하는 작업

### 로컬 실행 (시뮬레이터)
```bash
# 인프라 + 시뮬레이터 시작
docker compose -f docker-compose.yml -f docker-compose.sim.yml up -d

# 앱 실행
./mvnw spring-boot:run -Dspring-boot.run.profiles=sim
```

### 빌드
```bash
./mvnw clean package -DskipTests
```

### 실제 서비스 전환
```bash
SPRING_PROFILES_ACTIVE=real ./mvnw spring-boot:run
```

## 상세 문서 참조
@docs/ARCHITECTURE.md
@docs/SIMULATOR.md
@docs/SETUP.md
@docs/dev-environment.md
