# C++ WebSocket 서버 구현 계획

현재 Java Spring의 WebSocket 수신 부분을 C++로 대체하는 신규 프로젝트 계획.
기존 Spring Boot 프로젝트는 변경 없이 유지하고 C++ 버전을 병행 운영한다.

---

## 목적

- Java Spring WebSocket과 C++ WebSocket을 동시에 운영
- 브라우저에서 WebSocket URL(포트)만 바꿔 두 버전을 전환
- 실시간 STT 스트리밍 유지
- 성능 비교 및 C++ 구현 검토

---

## 전체 구조 비교

### 현재 (Java Spring 버전 — :8080)

```
브라우저 → WebSocket(:8080/ws/cti) → CtiWebSocketHandler.java
                                            ├─ RTZR WebSocket (STT 실시간)
                                            ├─ ClaudeApiLlmService (LLM)
                                            └─ GoogleCloudTtsService (TTS)
```

### 신규 (C++ 버전 — :9090)

```
브라우저 → WebSocket(:9090/ws/cti) → C++ WebSocket 서버
                                            ├─ RTZR WebSocket (STT 실시간, C++ 직접 연결)
                                            ├─ POST /api/cti/llm/chat    → Spring :8080
                                            └─ POST /api/cti/tts/synthesize → Spring :8080
```

---

## 브라우저 전환 방법

코드 변경 없이 환경변수만 바꿔 전환한다.

```javascript
// frontend/src/CtiSimulator.jsx
const WS_URL = import.meta.env.VITE_WS_URL || "ws://localhost:8080/ws/cti";
```

```bash
# Java 버전 실행
VITE_WS_URL=ws://localhost:8080/ws/cti npm run dev

# C++ 버전 실행
VITE_WS_URL=ws://localhost:9090/ws/cti npm run dev
```

---

## 변경 범위

### Spring Boot — 최소 변경

기존 코드는 전혀 건드리지 않는다. REST 컨트롤러 1개만 추가한다.

```java
// 신규: src/main/java/com/voicebot/call/CtiRestController.java
@RestController
@RequestMapping("/api/cti")
@RequiredArgsConstructor
public class CtiRestController {

    private final LlmService llmService;
    private final TtsService ttsService;

    @PostMapping("/llm/chat")
    public String chat(@RequestBody List<LlmService.Message> messages) {
        return llmService.chat(messages, "CPP");
    }

    @PostMapping("/tts/synthesize")
    public byte[] synthesize(@RequestBody String text) {
        return ttsService.synthesize(text, "CPP");
    }
}
```

STT는 C++이 RTZR에 직접 연결하므로 Spring에 추가 없음.

| 파일 | 변경 여부 |
|---|---|
| CtiWebSocketHandler.java | 변경 없음 (Java 버전 그대로 유지) |
| LlmService / ClaudeApiLlmService | 변경 없음 (재사용) |
| TtsService / GoogleCloudTtsService | 변경 없음 (재사용) |
| CtiRestController.java | **신규 추가** |

---

### C++ 서버 — 신규 프로젝트

`CtiWebSocketHandler.java`와 동일한 로직을 C++로 구현한다.

**역할**

```
1. WebSocket 서버 열기 (:9090/ws/cti)
2. 브라우저에서 PCM 청크 수신 (binary)
3. RTZR WebSocket에 실시간 전달 (STT)
4. RTZR final 결과 수신
5. Spring POST /api/cti/llm/chat 호출 (LLM)
6. Spring POST /api/cti/tts/synthesize 호출 (TTS)
7. 결과를 브라우저로 WebSocket JSON 전송
8. BOT_THINKING / BOT_READY 상태 메시지 전송
9. 연속 발화 지원 (발화마다 RTZR 재연결)
```

**처리 흐름**

```
브라우저: binary(PCM 청크) 전송
    ↓
C++ 서버: 청크 수신 → RTZR WebSocket으로 실시간 전달
    ↓
RTZR: final=true 결과 반환
    ↓
C++ 서버: STT_FINAL → 브라우저 전송
          BOT_THINKING → 브라우저 전송
          POST /api/cti/llm/chat → Spring
    ↓
Spring LLM: Claude API 호출 → JSON 응답 반환
    ↓
C++ 서버: LLM_RESULT → 브라우저 전송
          POST /api/cti/tts/synthesize → Spring
    ↓
Spring TTS: Google TTS 호출 → 오디오 반환
    ↓
C++ 서버: TTS_TEXT → 브라우저 전송
          BOT_READY → 브라우저 전송
          RTZR 재연결 (다음 발화 대기)
```

**사용 라이브러리**

| 역할 | 라이브러리 |
|---|---|
| WebSocket 서버 (브라우저 연결) | Boost.Beast 또는 libwebsockets |
| WebSocket 클라이언트 (RTZR 연결) | Boost.Beast 또는 libwebsockets |
| HTTP 클라이언트 (Spring 호출) | libcurl 또는 cpp-httplib |
| JSON 파싱/생성 | nlohmann/json |
| 빌드 시스템 | CMake |

---

## 서비스 인터페이스 설계 — Java 대응 C++ 구조

Java의 어댑터 패턴을 C++에서도 동일하게 적용한다.
`WsServer`(Java의 `CtiWebSocketHandler` 대응)는 인터페이스만 의존한다.

> **LLM/TTS도 인터페이스를 유지하는 이유:**
> Java 설계 원칙("외부 서비스는 반드시 인터페이스를 통해서만 호출")을 C++에서도 동일하게 적용한다.
> 지금은 `SpringLlmService`/`SpringTtsService`지만 나중에 C++에서 Claude API/Google TTS를 직접 호출하는
> 구현체로 교체할 때 `WsServer` 변경이 없다.

### SttService

```cpp
// SttService.h — Java SttService 인터페이스 대응
struct SttResult {
    std::string text;    // Java: String text
    bool isFinal;        // Java: boolean isFinal
};

using SttCallback      = std::function<void(SttResult)>;
using SttErrorCallback = std::function<void(std::string)>;

class SttService {
public:
    // Java: Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId)
    // C++:  Flux 대신 콜백 방식 (onResult, onError)
    virtual void recognize(const std::string& callId,
                           SttCallback onResult,
                           SttErrorCallback onError) = 0;

    // Java: sink.tryEmitNext(chunk) 대응 — 청크 전달
    virtual void sendChunk(const std::vector<uint8_t>& chunk) = 0;

    // Java: sink.tryEmitComplete() 대응 — 스트림 종료
    virtual void complete() = 0;

    virtual ~SttService() = default;
};

// RtzrWebSocketSttService — Java RtzrWebSocketSttService 대응
class RtzrWebSocketSttService : public SttService {
    void recognize(...) override;  // RTZR WebSocket 직접 연결
    void sendChunk(...) override;
    void complete() override;
};
```

**Java Flux vs C++ 콜백 비교**

```
Java:
  sttService.recognize(sink.asFlux(), callId)
      .filter(isFinal)
      .subscribe(result -> handleFinalStt(...))

C++:
  sttService->recognize(callId,
      [](SttResult r) { if (r.isFinal) handleFinalStt(r.text); },  // onResult
      [](std::string err) { /* 오류 처리 */ }                       // onError
  );
```

### LlmService

```cpp
// LlmService.h — Java LlmService 인터페이스 대응
struct Message {
    std::string role;     // "user" or "assistant"
    std::string content;
};

class LlmService {
public:
    // Java: String chat(List<Message> messages, String callId)
    virtual std::string chat(const std::vector<Message>& messages,
                             const std::string& callId) = 0;
    virtual ~LlmService() = default;
};

// SpringLlmService — POST /api/cti/llm/chat 호출
class SpringLlmService : public LlmService {
    std::string chat(...) override;  // libcurl HTTP POST
};
```

### TtsService

```cpp
// TtsService.h — Java TtsService 인터페이스 대응
class TtsService {
public:
    // Java: byte[] synthesize(String text, String callId)
    virtual std::vector<uint8_t> synthesize(const std::string& text,
                                            const std::string& callId) = 0;
    virtual ~TtsService() = default;
};

// SpringTtsService — POST /api/cti/tts/synthesize 호출
class SpringTtsService : public TtsService {
    std::vector<uint8_t> synthesize(...) override;  // libcurl HTTP POST
};
```

### Java vs C++ 대응표

| Java | C++ |
|---|---|
| `SttService` (interface) | `SttService` (abstract class) |
| `RtzrWebSocketSttService` | `RtzrWebSocketSttService` |
| `LlmService` (interface) | `LlmService` (abstract class) |
| `ClaudeApiLlmService` | `SpringLlmService` (Spring REST 호출) |
| `TtsService` (interface) | `TtsService` (abstract class) |
| `GoogleCloudTtsService` | `SpringTtsService` (Spring REST 호출) |
| `Flux<byte[]>` | 콜백 (`SttCallback`) |
| `SttResult` (record) | `SttResult` (struct) |
| `LlmService.Message` (record) | `Message` (struct) |
| `CtiWebSocketHandler` | `WsServer` |

---

## 프로젝트 디렉토리 구조 (안)

```
voicebot-js/
├── src/                              ← 기존 Spring Boot (변경 없음)
├── frontend/                         ← 기존 React (환경변수만 추가)
└── cpp-ws-server/                    ← 신규 C++ 프로젝트
    ├── CMakeLists.txt
    ├── main.cpp
    └── src/
        ├── WsServer.h/cpp            ← CtiWebSocketHandler 대응 (오케스트레이터)
        ├── service/
        │   ├── SttService.h          ← STT 인터페이스
        │   ├── RtzrWebSocketSttService.h/cpp  ← STT 구현체 (RTZR 직접)
        │   ├── LlmService.h          ← LLM 인터페이스
        │   ├── SpringLlmService.h/cpp ← LLM 구현체 (Spring REST)
        │   ├── TtsService.h          ← TTS 인터페이스
        │   └── SpringTtsService.h/cpp ← TTS 구현체 (Spring REST)
        └── CallSession.h             ← 세션/이력 관리
```

---

## 브라우저 ↔ C++ 메시지 프로토콜

Java 버전과 **완전히 동일**하다. 브라우저 코드 변경 없음.

**브라우저 → C++ 서버**

| 형식 | 내용 |
|---|---|
| binary | PCM 16kHz 16-bit mono 청크 |
| text JSON | `{"type":"CTI_EVENT","event":"CALL_START",...}` |
| text JSON | `{"type":"CTI_EVENT","event":"CALL_END"}` |

**C++ 서버 → 브라우저**

| type | 시점 |
|---|---|
| `STT_FINAL` | RTZR 최종 인식 결과 |
| `BOT_THINKING` | LLM 처리 시작 |
| `LLM_RESULT` | LLM 응답 완료 (intent + response) |
| `TTS_TEXT` | TTS 처리 완료 |
| `BOT_READY` | 다음 발화 대기 |
| `ERROR` | 파이프라인 오류 |

---

## 검토 항목 결정 내용

| # | 항목 | 결정 |
|---|---|---|
| 1 | C++ 프로젝트 위치 | 현재 저장소 안 `cpp-ws-server/` |
| 2 | RTZR 토큰 관리 | C++ 독립 구현 (Java 로직 그대로 이식) |
| 3 | 대화 이력 관리 | C++ 자체 관리 (Java `historyMap`과 동일 구조) |
| 4 | 빌드 환경 | devcontainer Dockerfile에 추가 |
| 5 | Spring REST 인증 | 인증 없음 (내부 통신) |
| 6 | 포트 | `devcontainer.json` `forwardPorts`에 9090 추가 |

### 빌드 환경 — devcontainer Dockerfile 추가 패키지

```dockerfile
RUN apt-get install -y \
    cmake \
    g++ \
    libboost-dev \
    libboost-system-dev \
    libssl-dev \
    libcurl4-openssl-dev
```

### 대화 이력 관리 — C++ 구조

Java `historyMap`과 동일한 구조로 관리한다.

```cpp
struct Message {
    std::string role;     // "user" or "assistant"
    std::string content;
};

// sessionId → 대화 이력
std::map<std::string, std::vector<Message>> historyMap;
```

### RTZR 토큰 관리 — C++ 구현

Java `RtzrWebSocketSttService.refreshToken()`과 동일한 로직. Java의 스레드 안전 처리도 C++ 방식으로 동일하게 구현한다.

| Java | C++ 대응 |
|---|---|
| `synchronized` | `std::mutex` + `std::lock_guard` |
| `AtomicReference<String>` | `std::string` + `std::mutex` |
| `@Scheduled` | `std::thread` + `sleep_for` 루프 |

```cpp
class RtzrTokenManager {
private:
    std::string accessToken;      // AtomicReference<String> 대응
    long expireAt = 0;
    std::mutex tokenMutex;        // synchronized 대응

    void refreshToken() {
        std::lock_guard<std::mutex> lock(tokenMutex);
        // libcurl로 POST https://openapi.vito.ai/v1/authenticate 호출
        // 응답에서 access_token, expire_at 갱신
    }

public:
    std::string getAccessToken() {
        std::lock_guard<std::mutex> lock(tokenMutex);  // 읽기도 보호
        return accessToken;
    }

    // @Scheduled 대응 — 별도 스레드에서 5분마다 실행
    void startScheduler() {
        std::thread([this]() {
            while (true) {
                std::this_thread::sleep_for(std::chrono::minutes(5));
                long now = std::time(nullptr);
                if (expireAt - now < 600) {  // 10분 이내면 갱신
                    refreshToken();
                }
            }
        }).detach();
    }
};
```
