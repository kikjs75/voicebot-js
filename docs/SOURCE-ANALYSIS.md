# 소스 분석 가이드

현재 소스의 핵심 흐름을 파악하기 위한 문서.
코드 수정 전 반드시 이 문서를 먼저 읽는다.

---

## 핵심 파일 읽는 순서

```
── HTTP 파이프라인 ──────────────────────────────────────────
1. src/main/java/com/voicebot/call/CallController.java        ← HTTP 진입점
2. src/main/java/com/voicebot/call/CallHandler.java           ← STT→LLM→TTS 오케스트레이션
3. src/main/java/com/voicebot/call/CallSession.java           ← 콜 세션 모델

── WebSocket 파이프라인 ─────────────────────────────────────
4. src/main/java/com/voicebot/config/WebSocketConfig.java     ← /ws/cti 핸들러 등록
5. src/main/java/com/voicebot/call/CtiWebSocketHandler.java   ← WebSocket 오케스트레이션

── 외부 서비스 구현체 ───────────────────────────────────────
6. src/main/java/com/voicebot/service/stt/SttService.java              ← STT 인터페이스
7. src/main/java/com/voicebot/service/stt/RtzrWebSocketSttService.java ← STT 구현 (real)
8. src/main/java/com/voicebot/service/llm/LlmService.java              ← LLM 인터페이스
9. src/main/java/com/voicebot/service/llm/ClaudeApiLlmService.java     ← LLM 구현 (real)
10. src/main/java/com/voicebot/service/tts/TtsService.java             ← TTS 인터페이스
11. src/main/java/com/voicebot/service/tts/GoogleCloudTtsService.java  ← TTS 구현 (real)
```

---

## 1. HTTP 파이프라인 — CallHandler

`POST /call/incoming` 요청을 처리하는 동기 파이프라인.

```
process(audioData, callId)
  │
  ├─ 1. sttService.recognize(Flux.just(audioData), callId)
  │       .filter(isFinal)            ← final:true 만 통과
  │       .timeout(30초)              ← 무음/무응답 타임아웃
  │       .onErrorReturn("")          ← 타임아웃 시 빈 문자열
  │       [STT-PERF] 로그
  │
  ├─ 2. 빈 결과면 → "죄송합니다, 말씀을 잘 듣지 못했습니다" TTS 즉시 반환
  │
  ├─ 3. Redis에서 세션 로드 (없으면 신규 생성)
  │       key: "call:session:{callId}"  TTL: 1시간
  │
  ├─ 4. llmService.chat(messages, callId)
  │       [LLM-PERF] 로그
  │
  ├─ 5. Redis에 세션 저장 (대화 이력 누적)
  │
  ├─ 6. ttsService.synthesize(llmResponse, callId)
  │       [TTS-PERF] 로그
  │
  ├─ 7. [CALL-PERF] 전체 elapsed 로그
  │
  └─ 8. CallRecord DB 저장 + byte[] 반환
```

---

## 2. WebSocket 파이프라인 — CtiWebSocketHandler

`/ws/cti` WebSocket 연결을 처리하는 실시간 스트리밍 파이프라인.
연결 1개 = 전화 통화 1건. 연속 발화를 지원한다.

### 세션 생명주기

| 시점 | 동작 |
|---|---|
| `afterConnectionEstablished` | callId 발급, Sink 생성, STT 구독 시작 |
| `handleBinaryMessage` | `sink.tryEmitNext(chunk)` — 음성 청크 전달 |
| `handleTextMessage` | CTI_EVENT(CALL_START / CALL_END) 처리 |
| `handleFinalStt` 완료 | `startNextSttSession` — 새 Sink 생성 + STT 재구독 |
| `afterConnectionClosed` | Sink complete, Map 정리 |

### 발화 1회 처리 흐름

```
[브라우저 마이크]
  │ PCM 16kHz 16-bit mono binary frame
  ▼
handleBinaryMessage() → sink.tryEmitNext(chunk)
  │
  ▼
Sinks.Many<byte[]>.asFlux()
  │
  ▼
SttService.recognize(flux, callId)   ← RTZR WebSocket STT
  │ isFinal == true
  ▼
handleFinalStt()
  ├─ sendJson: STT_FINAL
  ├─ sendJson: BOT_THINKING           ← 프론트엔드 마이크 차단
  ├─ LlmService.chat(history, callId) ← Claude API 호출
  │    └─ JSON 파싱: {intent, response}
  ├─ sendJson: LLM_RESULT(intent, response)
  ├─ TtsService.synthesize(response, callId)
  ├─ sendJson: TTS_TEXT
  ├─ startNextSttSession()            ← 새 Sink + STT 재구독
  └─ sendJson: BOT_READY              ← 프론트엔드 마이크 재개
```

### 연속 발화 전체 흐름

```
청크 도착 → sink.tryEmitNext()
청크 도착 → sink.tryEmitNext()   ← 계속 RTZR로 전송
청크 도착 → sink.tryEmitNext()
        ↓
RTZR: final=true
        ↓ .filter(isFinal) 통과
result → handleFinalStt(session, callId, result.text(), capturedHistory)
        ↓
history.add(Message("user", finalText))      ← 사용자 발화 누적
llmService.chat(history)                     ← 전체 이력으로 LLM 호출
history.add(Message("assistant", response))  ← 응답 누적
ttsService.synthesize(response)              ← TTS
sendJson(BOT_READY)                          ← 브라우저 전송
        ↓
startNextSttSession(session, callId, history)
  ├─ oldSink.tryEmitComplete()   ← 기존 Sink 명시적 종료 (audioStream 구독 정리)
  ├─ 새 Sink 생성 → sinkMap 덮어쓰기
  └─ 새 구독 시작 → 다음 청크 대기
        ↓
발화 2 청크 도착 → 새 Sink → RTZR → final=true → handleFinalStt(history 누적된 채로)
        ↓
반복...
```

STT는 발화마다 새로 시작하지만 대화 이력(history)은 통화 내내 끊기지 않고 이어진다.

### capturedHistory — 클로저 캡처

`historyMap`에서 꺼낸 List 객체를 변수에 직접 담아 람다 안에서 사용한다.

```java
List<LlmService.Message> capturedHistory = historyMap.get(session.getId());
sttService.recognize(...)
    .subscribe(result -> handleFinalStt(..., capturedHistory));
//                                           ↑ 람다가 바깥 변수를 기억 = 클로저
```

`afterConnectionClosed`에서 `historyMap.remove()`가 실행돼도 `capturedHistory`가 List 객체를 직접 참조하고 있으므로 GC되지 않는다. `startNextSttSession`에서 `history`로 넘길 때도 새로 만들지 않고 같은 객체를 계속 전달하므로 발화가 거듭될수록 한 List에 누적된다.

```
발화 1 후: [user:"안녕하세요", assistant:"반갑습니다"]
발화 2 후: [user:"안녕하세요", assistant:"반갑습니다", user:"요금문의", assistant:"요금제 안내..."]
발화 3 후: [..., user:"LTE 요금제", assistant:"..."]
```

LLM 호출 시 이 전체 이력을 매번 전달하므로 Claude가 앞 대화 맥락을 알고 답할 수 있다.

**메모리 정리:** List는 마지막 STT 구독이 종료될 때 참조가 끊겨 GC가 수거한다. 통화 세션 수명과 동일하다.

### 서버 → 브라우저 메시지 타입

| type | 시점 | 주요 필드 |
|---|---|---|
| `STT_INTERIM` | 중간 인식 결과 | `text` |
| `STT_FINAL` | 최종 인식 결과 | `text` |
| `BOT_THINKING` | LLM 처리 시작 | — |
| `LLM_RESULT` | LLM 응답 완료 | `intent`, `response` |
| `TTS_TEXT` | TTS 처리 완료 | `text` |
| `BOT_READY` | 다음 발화 대기 | — |
| `ERROR` | 파이프라인 오류 | `message` |

---

## 3. STT — RtzrWebSocketSttService

RTZR WebSocket STT API를 OkHttp로 연결하는 구현체.

```
recognize(audioStream: Flux<byte[]>, callId)
  │
  ├─ Flux.create() — OkHttp WebSocket 콜백 → Reactor 브릿지
  │
  ├─ WebSocket 연결
  │    Authorization: Bearer {token}
  │    wss://openapi.vito.ai/v1/transcribe:streaming
  │      ?sample_rate=16000
  │      &encoding=LINEAR16
  │      &use_itn=true
  │      &use_disfluency_filter=true
  │      &use_profanity_filter=false
  │      &use_punctuation=false
  │
  ├─ audioStream 구독 → binary frame 전송 (ByteString)
  │
  ├─ audioStream 완료 → "EOS" 전송
  │
  ├─ onMessage: JSON 파싱 → SttResult(text, isFinal) emit
  │    final=false → 중간 결과 emit
  │    final=true  → 확정 결과 emit → WebSocket close(1000)
  │
  └─ onClosed → emitter.complete()
```

**토큰 관리**
- `@PostConstruct`로 기동 시 발급
- `@Scheduled(fixedRate=300_000)` — 5분마다 만료 확인, 10분 이내면 갱신
- 만료 시각: Unix timestamp (`expire_at`)

---

## 4. LLM — ClaudeApiLlmService

Claude API(`claude-sonnet-4-6`)를 호출하는 구현체.

**요청 구조**
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "system": "당신은 한국어 콜센터 AI 상담원입니다...",
  "messages": [ {"role": "user", "content": "..."}, ... ]
}
```

**시스템 프롬프트 규칙**
- 반드시 JSON 형식으로만 응답: `{"intent": "...", "response": "..."}`
- intent: 환불 / 배송문의 / 기술지원 / 요금문의 / 예약 / 기타
- response: 2~3문장 이내 구어체 한국어, 마크다운/이모지 금지

**응답 파싱 (`CtiWebSocketHandler`)**
```
llmRaw = "{"intent":"기술지원","response":"노트북 전원 버튼을..."}"
        ↓ JSON 파싱
intent   = "기술지원"
response = "노트북 전원 버튼을..."
        ↓ 파싱 실패 시 llmRaw 그대로 사용 (안전 처리)
```

---

## 5. 설정 파일 구조

```
src/main/resources/
├── application.yml          ← 공통 설정 (DB, Redis, 로그레벨)
├── application-sim.yml      ← sim profile (시뮬레이터 URL)
├── application-real.yml     ← real profile (RTZR + Claude + Google TTS)
└── logback-spring.xml       ← 로그 파일 자동 저장 (logs/app.log, 7일 롤링)
```

**Profile 구성**

| Profile | STT | LLM | TTS |
|---|---|---|---|
| `sim` | SimulatorSttService → :8081 | SimulatorLlmService → :8082 | SimulatorTtsService → :8083 |
| `real` | RtzrWebSocketSttService | ClaudeApiLlmService | GoogleCloudTtsService |

---

## 6. 주요 로그 태그

| 태그 | 위치 | 의미 |
|---|---|---|
| `[STT-PERF]` | CallHandler | HTTP STT 처리시간 |
| `[LLM-PERF]` | CallHandler | HTTP LLM 처리시간 |
| `[TTS-PERF]` | CallHandler | HTTP TTS 처리시간 |
| `[CALL-PERF]` | CallHandler | HTTP 전체 처리시간 |
| `[CTI-LLM-PERF]` | CtiWebSocketHandler | WebSocket LLM 처리시간 |
| `[CTI-TTS-PERF]` | CtiWebSocketHandler | WebSocket TTS 처리시간 |
| `[STT-RTZR]` | RtzrWebSocketSttService | RTZR 연결/메시지/오류 |
| `[CTI]` | CtiWebSocketHandler | WebSocket 세션 이벤트 |
