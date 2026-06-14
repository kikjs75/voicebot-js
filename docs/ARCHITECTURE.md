# Architecture

## 목차

- [전체 구성도](#전체-구성도)
- [엔드포인트](#엔드포인트)
- [콜 처리 흐름](#콜-처리-흐름)
  - [HTTP (기존)](#http-기존)
  - [WebSocket (신규 — /ws/cti)](#websocket-신규--wscti)
- [Profile 구조](#profile-구조)

---

## 전체 구성도

[↑ 목차](#목차)

```
┌─────────────────────────────────────────────────┐
│               개발환경 (devcontainer)              │
│  DevPod + IntelliJ + Claude Code                 │
└──────────────────┬──────────────────────────────┘
                   │ docker compose
┌──────────────────▼──────────────────────────────┐
│              내부 서비스 (Docker)                  │
│                                                  │
│  ┌─────────────┐    ┌──────────┐  ┌──────────┐  │
│  │ Spring Boot │───▶│ Postgres │  │  Redis   │  │
│  │  :8080      │    │  :5432   │  │  :6379   │  │
│  └──────┬──────┘    └──────────┘  └──────────┘  │
│         │                                        │
└─────────┼──────────────────────────────────────┘
          │ HTTP (profile: sim → simulator / real → 외부)
┌─────────▼──────────────────────────────────────┐
│           외부/시뮬레이터 서비스                    │
│                                                  │
│  STT           LLM              TTS              │
│  RTZR (WS)     Claude API       Google Cloud TTS │
│  (sim: :8081)  (sim: :8082)     (sim: :8083)     │
│                                                  │
│  Call Simulator (:8084/:8085 UI)                 │
└────────────────────────────────────────────────┘
```

## 엔드포인트

[↑ 목차](#목차)

| 방식 | 엔드포인트 | 구현 위치 |
|---|---|---|
| HTTP POST | `/call/incoming` | `CallController` → `CallHandler` |
| WebSocket | `/ws/cti` | `WebSocketConfig` → `CtiWebSocketHandler` |

## 콜 처리 흐름

[↑ 목차](#목차)

### HTTP (기존)

```
전화 수신
  │
  ▼
CallController (POST /call/incoming)
  │
  ▼
CallHandler.process()
  │
  ├─ 1. SttService.recognize(audio, callId)
  │       └─ 텍스트 반환
  │
  ├─ 2. LlmService.chat(messages, callId)
  │       └─ 응답 텍스트 반환
  │
  ├─ 3. TtsService.synthesize(text, callId)
  │       └─ 오디오 바이트 반환
  │
  └─ 4. 오디오 응답 전송 + CallRecord 저장
```

### WebSocket (신규 — /ws/cti)

```
브라우저 (CtiSimulator.jsx)
  │
  ├─ CTI_EVENT(CALL_START) JSON 전송
  │
  ├─ 음성 청크 binary 전송 (250ms 간격)
  │       └─ Sinks.Many<byte[]> 브리지
  │               └─ SttService.recognize(flux, callId)
  │                       └─ STT_FINAL → LlmService → TtsService
  │
  └─ 결과 수신 (STT_FINAL / LLM_RESULT / TTS_TEXT JSON)
```

상세 설계 → @docs/CTI-WEBSOCKET.md

## Profile 구조

[↑ 목차](#목차)

| Profile | STT | LLM | TTS |
|---|---|---|---|
| `sim` | SimulatorSttService → :8081 | SimulatorLlmService → :8082 | SimulatorTtsService → :8083 |
| `real` | RtzrWebSocketSttService | ClaudeApiLlmService | GoogleCloudTtsService |
