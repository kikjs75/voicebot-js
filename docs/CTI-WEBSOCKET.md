# CTI WebSocket 구현 설계

## 개요

기존 HTTP 방식(`POST /call/incoming`)을 유지하면서,
실시간 음성 스트리밍을 위한 WebSocket 엔드포인트(`/ws/cti`)를 추가한다.
기존 소스 수정 없이 파일 2개만 추가하는 구조.

---

## 엔드포인트 비교

| 방식 | 엔드포인트 | 용도 |
|---|---|---|
| HTTP POST | `/call/incoming` | 기존 — 파일/배치 테스트 |
| WebSocket | `/ws/cti` | 신규 — 실시간 스트리밍 (CTI 연동) |

---

## 추가 파일

기존 코드는 일절 수정하지 않는다.

```
src/main/java/com/voicebot/
├── config/
│   └── WebSocketConfig.java       ← 신규: /ws/cti 핸들러 등록
└── call/
    └── CtiWebSocketHandler.java   ← 신규: WebSocket 오케스트레이터
```

---

## 구현 방법 검토

WebSocket 음성 청크를 기존 `SttService.recognize(Flux<byte[]>)`에 연결하는 방법을 검토했다.

| 방법 | 실시간 STT | 코드 변경 범위 | 검토 결과 |
|---|---|---|---|
| **Sinks.Many** | ✅ | 신규 파일 2개만 | ✅ 채택 |
| Flux.create() + FluxSink 저장 | ✅ | 신규 파일 2개만 | Sinks.Many의 구버전 방식 |
| Spring WebFlux WebSocket | ✅ | 프로젝트 구조 전체 변경 | ❌ 변경 범위 과다 |
| 청크 누적 후 일괄 전송 | ❌ | 신규 파일 2개만 | ❌ 실시간 처리 불가 |

**Sinks.Many 채택 이유**
- Reactor 공식 권장 방식 (Flux.create 방식의 후속)
- 스레드 안전 + 백프레셔 지원
- 기존 `SttService` 인터페이스(`Flux<byte[]>`) 변경 없이 연결 가능
- Spring MVC 기반 유지 (WebFlux 전환 불필요)

---

## Sinks 브리지 설계

`SttService.recognize()`는 `Flux<byte[]>`를 받는다.
WebSocket은 콜백(`handleBinaryMessage`)으로 청크를 던진다.
두 세계를 연결하는 다리로 `Sinks.Many<byte[]>`를 사용한다.

```
[브라우저 마이크]
     │ 250ms마다 음성 청크 전송 (binary frame)
     ▼
handleBinaryMessage()
     │ sink.tryEmitNext(chunk)
     ▼
Sinks.Many<byte[]>  ──→  sink.asFlux()
     │
     ▼
SttService.recognize(flux, callId)   ← 기존 인터페이스 그대로
     │
     ▼ isFinal == true
LlmService.chat()                    ← 기존 인터페이스 그대로
     │
     ▼
TtsService.synthesize()              ← 기존 인터페이스 그대로
     │
     ▼
session.sendMessage(JSON)            ← 결과를 브라우저로 역전송
```

---

## WebSocket 세션 생명주기

| 시점 | 동작 |
|---|---|
| `afterConnectionEstablished` | Sink 생성, STT 구독 시작, callId 발급 |
| `handleBinaryMessage` | `sink.tryEmitNext(chunk)` |
| `handleTextMessage` | CTI_EVENT(CALL_START / CALL_END) 처리 |
| `afterConnectionClosed` | `sink.tryEmitComplete()` → 스트림 종료 |

WebSocket 연결 1개 = 전화 통화 1건.
연결마다 독립적인 Sink를 `Map<sessionId, Sink>`로 관리한다.

---

## 브라우저 → 서버 메시지 형식

### CTI 이벤트 (JSON)
```json
{ "type": "CTI_EVENT", "event": "CALL_START", "callerNumber": "010-1234-5678", "receiverNumber": "1588-0000" }
{ "type": "CTI_EVENT", "event": "CALL_END" }
```

### 음성 청크 (binary)
- 포맷: `audio/webm;codecs=opus` (마이크) 또는 raw PCM (파일 테스트)
- 청크 크기: 4096 bytes
- 전송 간격: 250ms (마이크 스트리밍 시)

#### WAV 파일을 사용할 때 헤더 44바이트를 제외하는 이유

RTZR STT는 raw PCM 데이터만 기대한다. WAV 파일은 헤더(설명서) + PCM(실제 소리)로 구성되므로
헤더를 포함해 전송하면 RTZR이 첫 44바이트를 소리 데이터로 오해해 잡음 또는 오류가 발생한다.

```
WAV 파일 구조
┌─────────────────────────────────┐
│  WAV 헤더 (44바이트)             │  ← 파일 설명서 (RTZR에게 불필요)
│  "RIFF....WAVEfmt ..."          │
│  - 샘플레이트: 16000Hz           │
│  - 비트수: 16bit                 │
│  - 채널수: 1 (mono)              │
├─────────────────────────────────┤
│  실제 음성 데이터 (나머지 전부)   │  ← RTZR이 원하는 부분
│  00 01 FF 3A 00 02 ...          │
└─────────────────────────────────┘
```

WAV 헤더 구성 (표준 44바이트):

| 필드 | 크기 |
|---|---|
| "RIFF" | 4 bytes |
| 파일 크기 | 4 bytes |
| "WAVE" | 4 bytes |
| "fmt " | 4 bytes |
| 청크 크기 | 4 bytes |
| 오디오 포맷 | 2 bytes |
| 채널 수 | 2 bytes |
| 샘플레이트 | 4 bytes |
| 바이트레이트 | 4 bytes |
| 블록 정렬 | 2 bytes |
| 비트 수 | 2 bytes |
| "data" | 4 bytes |
| 데이터 크기 | 4 bytes |
| **합계** | **44 bytes** |

코드로 보면:

```javascript
const data = fs.readFileSync('/tmp/korean-test.pcm');

// WAV 파일 통째로 전송 (❌ 헤더 포함 → RTZR 오인식)
ws.send(data);

// 헤더 44바이트 건너뛰고 전송 (✅ PCM만 → 정상 인식)
ws.send(data.slice(44));
```

---

## 서버 → 브라우저 메시지 형식

```json
{ "type": "STT_INTERIM", "text": "안녕하..." }
{ "type": "STT_FINAL",   "text": "안녕하세요 문의드릴게요" }
{ "type": "LLM_RESULT",  "intent": "문의", "response": "무엇을 도와드릴까요?" }
{ "type": "TTS_TEXT",    "text": "무엇을 도와드릴까요?" }
```

---

## pom.xml 의존성 추가

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

---

## 구현 시 주의사항

### STT 구독 Disposable 관리

`startNextSttSession()`은 발화마다 새로운 Reactor 구독을 만든다.
구독에는 `timeout(60s)`가 붙어 있어, 60초 안에 `isFinal=true` 결과가 오지 않으면 에러를 낸다.

**문제**: `sink.tryEmitComplete()`만 호출하면 timeout이 멈추지 않는다.

```
통화 종료 → sink 완료 신호 → RTZR에 EOS 전송
                                    ↓
                        RTZR이 연결 끊는 데 수십 초 걸림
                                    ↓
                       그 사이 timeout이 카운트 계속
                                    ↓
                         60초 → TimeoutException ERROR
```

`sink.tryEmitComplete()`는 upstream(오디오 입력)을 끝낼 뿐이다.
timeout은 downstream(STT 결과)을 기다리는데, RTZR이 응답하지 않으니 카운트가 멈추지 않는다.

**해결**: 세션 종료 시 `Disposable.dispose()`로 Reactor 체인을 직접 취소한다.

```java
// afterConnectionClosed()
Disposable d = disposableMap.remove(session.getId());
if (d != null && !d.isDisposed()) d.dispose();
```

`dispose()`는 Reactor 체인 전체에 취소 신호를 보내므로 timeout 타이머도 즉시 멈춘다.
`TimeoutException`이 뜨더라도 정상 종료 상황이므로 ERROR가 아닌 DEBUG로 처리한다.

---

## 참고 소스

`reference/voicebot-demo/` — 이 설계의 원형이 된 데모 코드.

| 파일 | 내용 |
|---|---|
| `CtiSimulator.jsx` | 프론트엔드 WebSocket 클라이언트 |
| `CtiPipeline_Spring.java` | Spring Boot WebSocket 서버 구현 예시 |

프론트엔드 실행 방법 → @docs/FRONTEND.md
