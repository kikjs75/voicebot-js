# Architecture

## 전체 구성도

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

## 콜 처리 흐름

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

## Profile 구조

| Profile | STT | LLM | TTS |
|---|---|---|---|
| `sim` | SimulatorSttService → :8081 | SimulatorLlmService → :8082 | SimulatorTtsService → :8083 |
| `real` | RtzrWebSocketSttService | ClaudeApiLlmService | GoogleCloudTtsService |
