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

### refreshToken() — 토큰 발급

RTZR 서버에 아이디/비번을 보내서 토큰을 받아오는 함수.

```java
// 1. 요청 본문 만들기
String body = "client_id=" + URLEncoder.encode(clientId, ...) +
              "&client_secret=" + URLEncoder.encode(clientSecret, ...);
// URLEncoder.encode: 특수문자를 URL 안전 형태로 변환 ("안녕!" → "안녕%21")

// 2. RTZR 인증 서버에 POST 요청
Map response = webClient.post()
    .uri("https://openapi.vito.ai/v1/authenticate")
    .contentType(MediaType.APPLICATION_FORM_URLENCODED)  // 폼 형식
    .bodyValue(body)
    .retrieve()
    .bodyToMono(Map.class)  // JSON 응답 → Map으로 변환
    .block();               // 결과 나올 때까지 대기 (동기)

// 3. 토큰 저장
// 응답: {"access_token": "eyJhbGci...", "expire_at": 1780421420}
accessToken.set((String) response.get("access_token"));
expireAt = ((Number) response.get("expire_at")).longValue();
```

**JSON → Map으로 받는 이유**

JSON 구조와 Map 구조가 동일하기 때문이다.

```
JSON: {"access_token": "eyJ...", "expire_at": 1780421420}
Map:   key="access_token" → value="eyJ..."
       key="expire_at"    → value=1780421420
```

전용 클래스(`TokenResponse`)를 만들지 않아도 `response.get("key")`로 꺼낼 수 있어 간편하다.

**synchronized**

```java
private synchronized void refreshToken() {
```

여러 스레드가 동시에 호출해도 한 번에 하나만 실행되도록 잠근다. 동시에 두 번 토큰을 발급받는 낭비를 막는다.

**AtomicReference\<String\>**

```java
private final AtomicReference<String> accessToken = new AtomicReference<>("");
```

토큰은 여러 스레드에서 동시에 읽힌다. 일반 `String`으로 선언하면 읽는 중에 다른 스레드가 덮어쓸 수 있다.

```
일반 String:      읽는 중... (다른 스레드가 끼어들어 변경) → 잘못된 값
AtomicReference:  읽기 완전히 완료 후 다음 스레드 진행   → 항상 올바른 값
```

`synchronized`는 메서드 전체를 잠그는 무거운 방법, `AtomicReference`는 변수 하나만 안전하게 보호하는 가벼운 방법이다.

### scheduleTokenRefresh() — 주기적 토큰 갱신

```java
@Scheduled(fixedRate = 300_000)  // 5분(300,000ms)마다 자동 실행
public void scheduleTokenRefresh() {
    long now = System.currentTimeMillis() / 1000;  // 현재 시각 (ms → 초)
    if (expireAt - now < 600) {                    // 만료까지 10분(600초) 이내면
        refreshToken();                             // 갱신
    }
}
```

`300_000`의 `_`는 Java 7부터 지원하는 숫자 구분자다. 의미 없이 읽기 편하게 끊어줄 뿐이다.

```java
300_000 == 300000  // 완전히 동일 (쉼표 대신 _ 사용)
```

타임라인:

```
토큰 발급 (만료 = 지금 + 6시간)
    ↓
5분마다 체크
    만료까지 10분 초과 → 갱신 안 함
    만료까지 10분 이내 → refreshToken() 호출 → 갱신
```

만료 직전에 미리 갱신해서 토큰이 끊기는 일을 방지한다.

### WebClient vs OkHttpClient

이 클래스에서 HTTP 클라이언트가 두 종류 사용된다.

| | WebClient | OkHttpClient |
|---|---|---|
| 용도 | HTTP 요청 전용 (REST API) | HTTP + WebSocket 둘 다 |
| 출처 | Spring WebFlux 공식 | Square 오픈소스 |
| 방식 | Reactor 기반 (Mono/Flux) | 콜백 기반 |
| 사용 위치 | 토큰 발급 (`refreshToken`) | RTZR WebSocket 연결 |

```
토큰 발급  → HTTP POST 한 번  → WebClient   (간단, Spring 공식)
RTZR 연결 → WebSocket 지속   → OkHttpClient (WebSocket 안정적 지원)
```

Spring WebFlux의 WebClient도 WebSocket을 지원하지만, OkHttp가 WebSocket 콜백 처리가 더 직관적이어서 RTZR 연결에 채택했다.

### recognize() 내부 상세

크게 4개 덩어리로 구성된다.

**1. RTZR WebSocket 연결**

```java
return Flux.create(emitter -> {
    // emitter = Flux로 데이터를 밀어넣는 손
    Request request = new Request.Builder()
            .url(buildWsUrl())                                    // wss://openapi.vito.ai/...
            .header("Authorization", "Bearer " + accessToken.get())  // 인증 토큰
            .build();
    WebSocket ws = okHttpClient.newWebSocket(request, new WebSocketListener() { ... });
```

`Flux.create(emitter -> {...})`는 내가 직접 데이터를 밀어넣는 Flux를 만드는 방법이다.
`emitter`가 Sink처럼 `next()` / `complete()` / `error()`를 직접 호출한다.

**2. RTZR 콜백 4가지**

```java
onOpen()    → 연결 성공 시 로그만 찍음

onMessage() → RTZR이 인식 결과 보낼 때
              // {"alternatives":[{"text":"안녕하세요"}], "final": false}
              // {"alternatives":[{"text":"안녕하세요"}], "final": true}
              emitter.next(new SttResult(text, isFinal))  // Flux로 흘려보냄
              isFinal=true 면 ws.close()                  // 연결 끊기

onClosed()  → emitter.complete()  // Flux 완료

onFailure() → emitter.error()     // Flux 오류
```

핵심은 `onMessage()`다. RTZR JSON을 파싱해서 `emitter.next()`로 Flux에 흘려보낸다.

```
RTZR → onMessage("안녕하...",  final=false) → emitter.next(SttResult("안녕하...",  false)) → Flux
RTZR → onMessage("안녕하세요", final=true)  → emitter.next(SttResult("안녕하세요", true))  → Flux
                                               ws.close()
RTZR → onClosed()                          → emitter.complete()                            → Flux 종료
```

**3. audioStream 구독 → RTZR로 전송**

```java
audioStream.subscribe(
    chunk -> ws.send(ByteString.of(chunk)),  // 청크 올 때마다 RTZR로 전송
    error -> { ws.close(1000, "error"); },   // 오류 시 연결 닫기
    () -> { ws.send("EOS"); }               // Sink 완료 시 "끝났어" 신호
);
```

`EOS`는 "더 이상 보낼 음성 없어, 최종 결과 줘" 신호다.

**4. emitter.onCancel**

```java
emitter.onCancel(() -> ws.close(1000, "cancelled"));
```

Flux 구독자(CtiWebSocketHandler)가 구독을 취소하면 RTZR WebSocket도 닫는다. 리소스 정리용이다.

**전체 흐름**

```
recognize() 호출
    ↓
RTZR WebSocket 연결 열림 + audioStream 구독 시작
    ↓
[Sink에 청크 들어올 때마다]
    chunk → ws.send() → RTZR
    ↓
[RTZR이 결과 보낼 때마다]
    onMessage() → emitter.next(SttResult) → Flux로 흘러나감
    ↓
[Sink 완료 시]
    ws.send("EOS") → RTZR 최종 결과 전송 → ws.close()
    onClosed() → emitter.complete() → Flux 종료
```

### SttResult — 인식 결과 데이터 클래스

```java
public interface SttService {
    Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId);

    record SttResult(String text, boolean isFinal) {}
    //     ↑ SttService 인터페이스 안에 정의
}
```

`record`는 Java 16 문법으로 데이터만 담는 클래스를 짧게 선언하는 방법이다.
아래 두 코드는 완전히 동일하다:

```java
// record 문법 (짧게)
record SttResult(String text, boolean isFinal) {}

// 일반 클래스 (길게)
class SttResult {
    private final String text;
    private final boolean isFinal;
    public SttResult(String text, boolean isFinal) { ... }
    public String text() { return text; }
    public boolean isFinal() { return isFinal; }
}
```

`SttResult`가 `SttService` 안에 정의되어 있어서 밖에서 참조할 때 `SttService.SttResult`로 접근한다:

```java
.filter(SttService.SttResult::isFinal)
// 풀어쓰면:
.filter(result -> result.isFinal())  // isFinal=true인 것만 통과
```

**emitter와 Flux 연결 고리**

```
RTZR WebSocket         emitter              Flux<SttResult>
onMessage() 호출 → emitter.next() ──→ [SttResult, SttResult, ...] → 구독자에게 흘러감
onClosed()  호출 → emitter.complete() → Flux 종료

CtiWebSocketHandler
    .filter(SttService.SttResult::isFinal)  // isFinal=true만 통과
    .subscribe(result -> handleFinalStt())  // 최종 결과 처리
```

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

**Claude API 실제 응답 JSON 구조**

```json
{
  "id": "msg_123",
  "type": "message",
  "content": [
    {
      "type": "text",
      "text": "{\"intent\":\"요금문의\",\"response\":\"요금제 안내해드릴게요\"}"
    }
  ],
  "model": "claude-sonnet-4-6"
}
```

**응답에서 텍스트 추출**

```java
Map<?, ?> response = ...bodyToMono(Map.class).block();
// Map<?, ?>: key/value 타입이 불확실할 때 쓰는 와일드카드
// Map<String, Object>로 써도 되지만 컴파일러 경고 방지용으로 <?> 사용

List<?> content = (List<?>) response.get("content");
// content 배열 꺼내기 → [{"type":"text","text":"..."}]

Map<?, ?> first = (Map<?, ?>) content.get(0);
// 첫 번째 항목 꺼내기 (Claude는 항상 1개만 반환)

return (String) first.get("text");
// 실제 텍스트 추출 → "{\"intent\":\"요금문의\",...}"
```

```
response (전체 Map)
    ↓ .get("content")
content (List — 배열)
    ↓ .get(0)
first (Map — 첫 번째 항목)
    ↓ .get("text")
"{\"intent\":\"요금문의\"...}"  ← 최종 반환값
```

**응답 파싱 (`CtiWebSocketHandler`)**

`chat()`이 반환한 JSON 문자열을 `objectMapper.readTree()`로 객체화한다.

```java
// objectMapper.readTree() = JSON 문자열 → JsonNode 객체 (트리 구조)
JsonNode node = objectMapper.readTree(llmRaw);
intent   = node.path("intent").asText("기타");   // "요금문의"
response = node.path("response").asText(llmRaw); // "요금제 안내해드릴게요"
// 파싱 실패 시 llmRaw 그대로 사용 (안전 처리)
```

`readTree()` vs 다른 방법:

```java
// readTree() → JsonNode (key로 직접 탐색, 현재 사용)
JsonNode node = objectMapper.readTree(json);
node.path("intent").asText();

// readValue() → 특정 클래스로 변환 (전용 클래스 필요)
MyClass obj = objectMapper.readValue(json, MyClass.class);

// readValue() → Map으로 변환
Map map = objectMapper.readValue(json, Map.class);
```

별도 클래스 없이 key로 바로 꺼낼 수 있어서 `readTree()`를 사용한다.

---

## 5. TTS — GoogleCloudTtsService

Google Cloud TTS API를 호출하는 구현체.

### @PostConstruct — 서비스 계정 키 로드

```java
credentials = GoogleCredentials
    .fromStream(new FileInputStream(credentialsPath))  // JSON 키 파일 읽기
    .createScoped(SCOPE);  // 사용할 권한 범위 지정
```

RTZR처럼 직접 HTTP 인증 요청을 하는 게 아니라 Google 공식 라이브러리(`google-auth-library`)가 토큰 발급/갱신을 대신 처리한다.

### synthesize() — TTS 호출

**1. 토큰 발급**

```java
String token = getAccessToken();  // credentials.refreshIfExpired() → 토큰 반환
```

**2. Google TTS API 요청**

Google TTS API가 요구하는 JSON 형식:
```json
{
  "input":       {"text": "안녕하세요"},
  "voice":       {"languageCode": "ko-KR", "name": "ko-KR-Neural2-A"},
  "audioConfig": {"audioEncoding": "LINEAR16", "sampleRateHertz": 8000}
}
```

`Map.of`로 이 구조를 만든다. Map 안에 Map을 넣어 중첩 JSON 구조를 표현한다:

```java
Map.of(
    "input",       Map.of("text", text),
    "voice",       Map.of("languageCode", languageCode, "name", voiceName),
    "audioConfig", Map.of("audioEncoding", audioEncoding, "sampleRateHertz", sampleRateHertz)
)
```

`WebClient`가 `Map` → JSON 문자열로 자동 직렬화(Jackson 라이브러리)해서 HTTP Body에 담아 전송한다.

**3. 오디오 추출**

```java
String audioContent = (String) response.get("audioContent");
return Base64.getDecoder().decode(audioContent);
```

Google TTS 응답:
```json
{"audioContent": "UklGRiQAAABXQVZFZm10IBAA..."}
```

`audioContent`는 Base64 인코딩된 오디오 바이트다. `Base64.getDecoder().decode()`로 실제 바이트로 변환한다.

### 토큰 관리 — RTZR STT와 비교

```java
// 5분마다 주기적 갱신
@Scheduled(fixedRate = 300_000)
public void scheduleTokenRefresh() {
    credentials.refreshIfExpired();  // 만료됐으면 자동 갱신
}

// 호출 시마다 스레드 안전 처리
private synchronized String getAccessToken() {
    credentials.refreshIfExpired();
    return credentials.getAccessToken().getTokenValue();
}
```

| | RTZR STT | Google TTS |
|---|---|---|
| `synchronized` | ✅ | ✅ |
| `@Scheduled` | ✅ 5분마다 | ✅ 5분마다 |
| 갱신 방식 | 직접 HTTP POST | `refreshIfExpired()` (라이브러리 위임) |

`refreshIfExpired()`가 내부적으로 만료 여부를 판단해서 필요할 때만 토큰을 갱신한다. 5분마다 `@Scheduled`로 미리 갱신해두면 `synthesize()` 호출 시 토큰 갱신 지연을 방지할 수 있다.

---

## 6. 설정 파일 구조

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

## 6. 브라우저(React) ↔ Spring 연결 구조

### 브라우저에서 연결 (CtiSimulator.jsx)

```javascript
const ws = new WebSocket("ws://localhost:8080/ws/cti");
ws.binaryType = "arraybuffer";

ws.onopen    = () => { ... }   // 연결됐을 때
ws.onmessage = (e) => { ... }  // 서버에서 메시지 왔을 때
ws.onclose   = () => { ... }   // 연결 끊겼을 때

ws.send(int16.buffer);           // 음성 청크 전송 (binary)
ws.send(JSON.stringify({...}));  // 이벤트 전송 (text)
```

브라우저는 `new WebSocket(url)` 하나로 끝이다. 연결 주소만 맞으면 된다.

### Spring에서 연결 받는 구조

두 파일이 역할을 나눈다:

```
WebSocketConfig          CtiWebSocketHandler
"어디서 받을지 등록"     "어떻게 처리할지 구현"
```

**WebSocketConfig — 주소 등록**

```java
registry.addHandler(ctiWebSocketHandler, "/ws/cti")
// 브라우저가 이 주소로 오면 CtiWebSocketHandler에게 넘겨줘
```

**AbstractWebSocketHandler — 왜 상속받나**

Spring WebSocket은 연결/메시지/종료 이벤트를 받으려면 정해진 인터페이스가 필요하다.
`AbstractWebSocketHandler`는 Spring이 미리 만들어둔 빈 틀이다:

```java
// Spring이 만들어둔 빈 틀
public abstract class AbstractWebSocketHandler {
    public void afterConnectionEstablished(session) {}   // 비어있음
    public void handleBinaryMessage(session, message) {} // 비어있음
    public void handleTextMessage(session, message) {}   // 비어있음
    public void afterConnectionClosed(session, status) {}// 비어있음
}

// CtiWebSocketHandler가 틀을 상속받아 필요한 메서드만 채워넣음
public class CtiWebSocketHandler extends AbstractWebSocketHandler {
    @Override
    public void afterConnectionEstablished(session) {
        // callId 발급, Sink 생성, STT 구독
    }
    @Override
    protected void handleBinaryMessage(session, message) {
        // sink.tryEmitNext(chunk)
    }
    @Override
    protected void handleTextMessage(session, message) {
        // CALL_END 처리
    }
    @Override
    public void afterConnectionClosed(session, status) {
        // Sink 완료, Map 정리
    }
}
```

메서드 안에 뭘 할지만 채워넣으면 된다. 언제 호출할지는 Spring이 알아서 한다.

### Spring이 자동으로 호출하는 흐름

```
브라우저: ws://localhost:8080/ws/cti 연결 요청
    ↓ Spring 내부: "/ws/cti" 핸들러 확인
    ↓ afterConnectionEstablished() 자동 호출

브라우저: binary 데이터 전송
    ↓ Spring 내부: binary 프레임 감지
    ↓ handleBinaryMessage() 자동 호출

브라우저: text 데이터 전송
    ↓ Spring 내부: text 프레임 감지
    ↓ handleTextMessage() 자동 호출

브라우저: 연결 끊음
    ↓ Spring 내부: 연결 종료 감지
    ↓ afterConnectionClosed() 자동 호출
```

### 전체 그림

```
브라우저 (React)                 Spring Boot
new WebSocket(url)
    │                            WebSocketConfig
    │ HTTP Upgrade 요청 ──────→  "/ws/cti" 등록 확인
    │ ←── 101 Switching ──────   CtiWebSocketHandler에게 넘김
    │
    │ binary(음성청크) ────────→  handleBinaryMessage()
    │                                   ↓ sink.tryEmitNext()
    │ text(CTI이벤트) ────────→  handleTextMessage()
    │
    │ ←── text(JSON) ──────────  sendJson() 호출 시
    │
    │ 연결 종료 ──────────────→  afterConnectionClosed()
```

`AbstractWebSocketHandler`를 상속받는 이유: Spring이 "이 메서드들을 구현해두면 때에 맞게 알아서 불러줄게" 라는 약속을 제공하기 때문이다.

---

## 7. WebSocket 방식 — Raw WS vs STOMP

### 현재 구현: Raw WebSocket

Spring WebSocket을 일반적으로 사용할 때는 **STOMP 프로토콜**을 얹어 쓴다.
현재는 음성 스트리밍 특성에 맞게 **Raw WebSocket**을 직접 구현했다.

| | STOMP | Raw WS (현재) |
|---|---|---|
| 용도 | 채팅, 알림, 브로드캐스트 | 음성 스트리밍, 1:1 파이프라인 |
| 메시지 형식 | 텍스트 전용 (헤더 포함) | 텍스트 + binary 혼용 가능 |
| 라우팅 | `/topic/*`, `/app/*` 자동 | 직접 구현 (`sendJson()`) |
| 구독 개념 | 토픽 구독 (1:N) | 연결 1개 = 통화 1건 (1:1) |
| Spring 구현 | `@MessageMapping`, `@SendTo` | `AbstractWebSocketHandler` 상속 |

### Raw WS가 음성 스트리밍에 적합한 이유

250ms마다 4096 바이트 binary를 전송할 때 STOMP는 매번 헤더를 붙여야 한다:

```
STOMP:                          Raw WS:
SEND\n                          [binary data]  ← 그냥 전송
destination:/app/audio\n
content-type:application/octet-stream\n
\n
[binary data]
```

헤더 오버헤드 없이 바로 전송할 수 있어 음성처럼 작은 데이터를 자주 보낼 때 효율적이다.
또한 같은 연결에서 binary(음성)와 text(JSON 이벤트)를 섞어 보낼 수 있다.

### Spring 구현 구조 비교

```
STOMP 방식:
  WebSocketConfig (implements WebSocketMessageBrokerConfigurer)
    └─ @MessageMapping("/chat.send") + @SendTo("/topic/chat")

Raw WS 방식 (현재):
  WebSocketConfig (implements WebSocketConfigurer)
    └─ registry.addHandler(ctiWebSocketHandler, "/ws/cti")

  CtiWebSocketHandler (extends AbstractWebSocketHandler)
    ├─ afterConnectionEstablished()  ← 연결 시
    ├─ handleBinaryMessage()         ← binary 수신 시 (음성 청크)
    ├─ handleTextMessage()           ← text 수신 시 (CTI 이벤트)
    └─ afterConnectionClosed()       ← 종료 시
```

### 전체 WebSocket 연결 구조

브라우저와 RTZR STT 모두 Raw WS로 연결된다. Spring Boot가 중간 브리지 역할을 한다.

```
브라우저 ←── Raw WS ──→ Spring Boot ←── Raw WS ──→ RTZR STT
           /ws/cti                    wss://openapi.vito.ai/...
        (Spring WebSocket)            (OkHttp WebSocket)

브라우저 → Spring: PCM 음성 청크 (binary, 250ms마다)
브라우저 → Spring: CTI 이벤트 (text JSON)
Spring  → 브라우저: STT_FINAL / BOT_THINKING / LLM_RESULT / TTS_TEXT / BOT_READY (text JSON)

Spring  → RTZR: PCM 음성 청크 (binary)
RTZR    → Spring: 인식 결과 JSON (text)
```

---

## 8. Sinks — 음성 청크 브리지

WebSocket 콜백(비동기)과 Reactor 스트림을 연결하는 다리.
밀어넣는 쪽(Producer)과 꺼내는 쪽(Consumer)을 분리해준다.

### 생성

```java
Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
//                                     ↑          ↑
//                               구독자 1개만     소비 느리면 버퍼에 쌓음
```

생성 직후에는 빈 파이프다. 데이터도 없고 구독자도 없다.

### Sink와 Flux의 관계

```
Sink       = 파이프 전체 (입구 + 출구)
asFlux()   = 파이프의 출구만 Flux로 변환
tryEmitNext() = 파이프 입구로 데이터 밀어넣기

[입구 → → → → → 출구]
  ↑                ↑
tryEmitNext()    asFlux()
```

### 음성 청크 흐름

```java
// 1. BinaryMessage → byte[] 변환 → Sink 입구로 밀어넣기
byte[] chunk = message.getPayload().array();
sink.tryEmitNext(chunk);

// 2. Sink 출구(asFlux())를 STT에 넘김
sttService.recognize(sink.asFlux(), callId)
// → Sink에 chunk가 들어올 때마다 Flux가 자동으로 RTZR로 흘려보냄
```

```
BinaryMessage (WebSocket 프레임)
    ↓ .getPayload().array()
byte[] chunk
    ↓ sink.tryEmitNext(chunk)
Sink 내부 버퍼 [chunk1, chunk2, chunk3 ...]
    ↓ sink.asFlux()
Flux<byte[]>  ← STT 서비스가 구독해서 RTZR로 전송
```

`Flux<byte[]>`는 새로운 형식이 아니라 **byte[]가 시간순으로 흘러나오는 스트림**이다.
Sink에 넣는 것도 `byte[]`, Flux로 나오는 것도 `byte[]` 그대로다.

### 종료

```java
sink.tryEmitComplete();   // 정상 종료 → 구독자 onComplete 수신
sink.tryEmitError(e);     // 오류 종료 → 구독자 onError 수신
```

현재 코드에서 `tryEmitComplete()` 호출 시점:
- `handleCallEnd()` — 사용자가 전화 끊을 때
- `startNextSttSession()` — 다음 발화 준비 시 기존 Sink 닫을 때
- `afterConnectionClosed()` — WebSocket 연결 종료 시

### WebSocket과 Flux의 관계

WebSocket과 Flux는 직접적인 관계가 없다. Flux는 WebSocket의 요구사항이 아니다.

**WebSocket은 콜백 방식이다**

Spring이 WebSocket 프레임이 도착할 때마다 자동으로 메서드를 호출한다.

```java
// 청크 올 때마다 Spring이 자동 호출 (콜백)
handleBinaryMessage(session, message) {
    byte[] chunk = message.getPayload().array();
    sink.tryEmitNext(chunk);
}

// 텍스트 올 때마다 Spring이 자동 호출 (콜백)
handleTextMessage(session, message) { ... }
```

"데이터 오면 이 메서드 불러줘" 라고 등록해두는 이벤트 방식이다.

**STT 서비스는 Flux 방식이다**

```java
// SttService — 인터페이스 (약속)
Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId);
// "Flux<byte[]> 받아서 Flux<SttResult> 돌려줄게" 라는 계약

// RtzrWebSocketSttService — 실제 구현체
// recognize() 내부에서 audioStream을 구독
audioStream.subscribe(
    chunk -> ws.send(ByteString.of(chunk)),  // 청크 올 때마다 RTZR로 전송
    error -> { ws.close(...); },             // 오류 시
    () -> { ws.send("EOS"); }               // 완료 시 EOS 전송
);
```

`recognize(sink.asFlux())`를 호출하는 순간 `RtzrWebSocketSttService`가 RTZR WebSocket을 열고
`audioStream`을 구독한다. 이후 Sink에 청크가 들어올 때마다 자동으로 받아서 RTZR로 보낸다.

```
CtiWebSocketHandler                RtzrWebSocketSttService
        │                                    │
        │  sttService.recognize(             │
        │      sink.asFlux(), callId) ──────→│ audioStream.subscribe(
        │                                    │     chunk -> ws.send(chunk)
        │                                    │ )  ← RTZR 연결 열림
        │
        │  sink.tryEmitNext(chunk1) ─────────┼──→ ws.send(chunk1) → RTZR
        │  sink.tryEmitNext(chunk2) ─────────┼──→ ws.send(chunk2) → RTZR
        │  sink.tryEmitNext(chunk3) ─────────┼──→ ws.send(chunk3) → RTZR
        │                                    │
        │                          RTZR → onMessage(final=true)
        │                                    │  emitter.next(SttResult)
        │ ←─────────────────────────────────│
        │  handleFinalStt() 호출             │
```

RTZR STT는 음성이 시간순으로 계속 흘러들어오는 구조라 Flux가 자연스럽다.

**두 방식이 달라서 Sink로 연결**

```
WebSocket 콜백 방식          STT 서비스 스트림 방식
(handleBinaryMessage)   →   (recognize(Flux<byte[]>))
    이벤트 발생 시 호출          구독해서 데이터 수신
         ↑                           ↑
         └──────── Sink ─────────────┘
                  (브리지)
```

Sink 없이 직접 연결할 수 없기 때문에 Sink를 중간 브리지로 사용한다.

---

## 9. Spring MVC vs Spring WebFlux

현재 프로젝트는 **Spring MVC** 구조다. Spring WebFlux가 아니다.

```
pom.xml 현재:
spring-boot-starter-web        ← Spring MVC (서블릿 기반)
spring-boot-starter-websocket  ← WebSocket

WebFlux였다면:
spring-boot-starter-webflux    ← Spring WebFlux (Netty 기반)
```

**Spring MVC인데 왜 WebClient와 Flux/Mono를 쓰나?**

Spring MVC에서도 Reactor 라이브러리(Flux/Mono)와 WebClient를 부분적으로 사용할 수 있다.

```
Spring MVC   → HTTP 요청 처리 방식 (서블릿 기반, 스레드 블로킹)
WebClient    → HTTP 클라이언트 도구 (MVC에서도 사용 가능)
Flux/Mono    → Reactor 라이브러리  (MVC에서도 사용 가능)
```

현재 구조: **Spring MVC + 부분적 Reactor 사용**

```
HTTP 요청 처리  → Spring MVC (서블릿)
외부 API 호출  → WebClient (Reactor)
음성 스트리밍  → Sinks/Flux (Reactor)
WebSocket     → Spring WebSocket (서블릿 기반)
```

**MVC 구조임을 알 수 있는 단서들**

```java
// LlmService.chat()에서 .block() 사용
.bodyToMono(Map.class)
.block();  // ← WebFlux였다면 .block() 없이 Flux/Mono 체인으로만 연결해야 함

// publishOn(Schedulers.boundedElastic()) 사용
.publishOn(Schedulers.boundedElastic())
// ← MVC의 NIO 스레드에서 .block() 호출을 막기 위한 스레드 전환
//   WebFlux였다면 이 처리가 불필요
```

---

## 10. 주요 로그 태그

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

---

## 11. 프론트엔드 — CtiSimulator.jsx

`frontend/src/CtiSimulator.jsx` — CTI WebSocket 테스트 UI.

### useCallback

```javascript
const connectWs = useCallback(() => {
    ...
}, [addLog]);
```

React에서 함수를 메모이제이션(기억)하는 훅이다. 컴포넌트가 리렌더링될 때마다 함수가 새로 만들어지는 것을 막는다.

```
useCallback 없이: 렌더링마다 connectWs 새로 생성
useCallback 사용: addLog가 바뀔 때만 connectWs 새로 생성, 나머지는 재사용
```

`[addLog]`는 의존성 배열이다. `connectWs` 안에서 `addLog`를 사용하므로 `addLog`가 바뀌면 최신 `addLog`를 참조하는 새 함수로 교체된다.

### ws vs wsRef — 왜 다르게 쓰나

```javascript
// connectWs 안에서
const ws = new WebSocket(WS_URL);  // 지역 변수 (함수 안에서만 존재)
wsRef.current = ws;                // Ref에 저장 (컴포넌트 전체에서 접근 가능)
```

`ws`는 `connectWs` 함수 안에서만 사는 지역 변수다. 함수가 끝나면 사라진다.
`wsRef`는 컴포넌트 전체에서 접근할 수 있는 전역 보관함이다.

```
connectWs() 실행:
  ws = new WebSocket(...)  ← 연결 생성
  wsRef.current = ws       ← 보관함에 저장

handleStartCall()에서:
  wsRef.current.send(...)  ← 보관함에서 꺼내서 사용
  (ws는 이미 사라졌으니 직접 접근 불가)
```

`useState`로 저장하면 값이 바뀔 때마다 리렌더링이 발생한다. WebSocket 객체는 화면에 표시할 필요가 없으니 리렌더링 없이 값만 저장하는 `useRef`가 적합하다.

### startMicStream — 마이크 스트리밍

**1단계: 마이크 권한 요청**

```javascript
const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
        channelCount: 1,          // mono (스테레오 불필요)
        echoCancellation: false,  // 에코 제거 끔 (RTZR이 직접 처리)
        noiseSuppression: false,  // 노이즈 제거 끔
        autoGainControl: false,   // 자동 볼륨 조절 끔
        deviceId: selectedMicId   // 선택한 마이크 장치
    }
});
```

**2단계: AudioContext 생성 (16kHz 고정)**

```javascript
const audioContext = new AudioContext({ sampleRate: 16000 });
```

`sampleRate: 16000`으로 고정하면 브라우저가 마이크 입력을 자동으로 16kHz로 변환한다. RTZR이 16kHz(LINEAR16)를 요구하기 때문이다.

**3단계: 오디오 파이프라인 구성**

```javascript
const source    = audioContext.createMediaStreamSource(stream);
const processor = audioContext.createScriptProcessor(4096, 1, 1);
//                                                    ↑     ↑  ↑
//                                               버퍼크기 입력 출력(채널수)
```

4096 샘플 ÷ 16000 샘플/초 = 약 250ms. 250ms마다 `onaudioprocess` 콜백이 호출된다.

**4단계: float32 → int16 변환 후 전송**

```javascript
processor.onaudioprocess = (e) => {
    const float32 = e.inputBuffer.getChannelData(0);
    // 브라우저 내부: float32 (-1.0 ~ 1.0)

    const int16 = new Int16Array(float32.length);
    for (let i = 0; i < float32.length; i++) {
        int16[i] = Math.max(-32768, Math.min(32767, float32[i] * 32768));
        // RTZR 요구: int16 (-32768 ~ 32767)
        // Math.max/min으로 범위 초과 방지
    }

    if (wsRef.current?.readyState === WebSocket.OPEN && botReadyRef.current) {
        wsRef.current.send(int16.buffer);  // WebSocket으로 전송
        // botReadyRef.current = false이면 전송 안 함 (BOT_THINKING 중 차단)
    }
};
```

int16은 16비트 정수다. 2^16 = 65536개 → -32768 ~ 32767 범위.
RTZR이 `LINEAR16` 포맷을 요구하는데 이것이 16비트 정수 PCM이다.

**5단계: silentGain — 피드백 루프 방지**

```javascript
const silentGain = audioContext.createGain();
silentGain.gain.value = 0;           // 볼륨 0 (무음)
source.connect(processor);
processor.connect(silentGain);
silentGain.connect(audioContext.destination);
```

Web Audio API 규칙: 노드가 `destination`(스피커)에 연결되지 않으면 오디오 처리 자체가 동작하지 않는다. 그러나 마이크를 바로 스피커에 연결하면 피드백 루프(하울링)가 발생한다.

```
마이크 → 스피커 → 마이크 → 스피커 → ... (무한 반복 = 하울링)
```

`gain=0` 노드를 경유하면:
```
destination 연결 → onaudioprocess 정상 동작  ✅  (규칙 충족)
gain = 0        → 스피커로 소리 안 나옴      ✅  (피드백 루프 차단)
```

`createGain()`은 "소리는 흘러가지만 볼륨을 0으로 만드는 파이프"다. 연결은 유지하면서 소리만 없애는 용도다.
