# cpp-ws-server 소스 분석

## 목차

- [1. 전체 역할](#1-전체-역할)
- [2. 파일 구조와 역할](#2-파일-구조와-역할)
- [3. C++ 문법 / 라이브러리 쉽게 이해하기](#3-c-문법--라이브러리-쉽게-이해하기)
- [4. main.cpp — 시작점](#4-maincpp--시작점)
- [5. WsServer — 연결 수락](#5-wsserver--연결-수락)
- [6. WsSession — 핵심 (연결 1개 = 전화 1건)](#6-wssession--핵심-연결-1개--전화-1건)
- [7. 핵심 파이프라인: STT → LLM → TTS](#7-핵심-파이프라인-stt--llm--tts)
- [8. 전송 큐 (writeQueue\_) 동작 원리](#8-전송-큐-writequeue_-동작-원리)
- [9. RtzrWebSocketSttService — RTZR STT 연결](#9-rtzrwebsocketsttservice--rtzr-stt-연결)
- [10. RtzrTokenManager — 토큰 자동 갱신](#10-rtzrtokenmanager--토큰-자동-갱신)
- [11. SpringLlmService / SpringTtsService — Spring 호출](#11-springllmservice--springttsservice--spring-호출)
- [12. Logger.h — 로그 구조](#12-loggerh--로그-구조)
- [13. 현재 구조의 특징 및 한계](#13-현재-구조의-특징-및-한계)
- [14. 핵심 흐름 한눈에 보기](#14-핵심-흐름-한눈에-보기)

---

> C++ STL / Boost를 처음 보는 분을 위해 Java 비유를 최대한 활용해 설명합니다.

---

## 1. 전체 역할

[↑ 목차](#목차)

```
브라우저 (CtiSimulator.jsx)
    │ WebSocket (음성 binary + CTI 이벤트 JSON)
    ▼
cpp-ws-server (:9090)          ← 이 서버
    │ WebSocket (SSL)          │ HTTP (libcurl)
    ▼                          ▼
RTZR STT 서버            Spring Boot (:8080)
(openapi.vito.ai)            /api/cti/llm/chat
                             /api/cti/tts/synthesize
```

**요약**: 브라우저에서 음성을 받아 → RTZR로 텍스트 변환 → Spring LLM/TTS 호출 → 결과를 브라우저로 돌려주는 **중간 서버**

---

## 2. 파일 구조와 역할

[↑ 목차](#목차)

```
cpp-ws-server/
├── main.cpp                              ← 진입점 (설정 읽고 서버 시작)
├── src/
│   ├── Logger.h                          ← 로그 설정 (파일 + 콘솔)
│   ├── WsServer.h / WsServer.cpp         ← WebSocket 서버 + 세션 관리
│   ├── CallSession.h                     ← 통화 세션 데이터 구조
│   └── service/
│       ├── SttService.h                  ← STT 인터페이스
│       ├── LlmService.h                  ← LLM 인터페이스
│       ├── TtsService.h                  ← TTS 인터페이스
│       ├── RtzrTokenManager.h/.cpp       ← RTZR 토큰 자동 갱신
│       ├── RtzrWebSocketSttService.h/.cpp ← RTZR STT 실제 구현
│       ├── SpringLlmService.h/.cpp       ← Spring LLM HTTP 호출
│       └── SpringTtsService.h/.cpp       ← Spring TTS HTTP 호출
```

> Java의 패키지/인터페이스 구조와 거의 동일합니다.
> `.h` = Java의 `interface` (선언만), `.cpp` = Java의 `class` (구현)

---

## 3. C++ 문법 / 라이브러리 쉽게 이해하기

[↑ 목차](#목차)

### 3-1. `std::shared_ptr<T>` / `std::make_shared<T>()` — 자동 메모리 관리

#### C++에서 메모리 관리가 왜 필요한가

Java는 GC가 있어서 걱정할 필요가 없습니다:
```java
// Java — new 하면 끝. GC가 알아서 정리
RtzrTokenManager tokenMgr = new RtzrTokenManager(clientId, clientSecret);
```

C++는 GC가 없어서 직접 지워야 합니다:
```cpp
// C++ 기본 방식 — 직접 new/delete 해야 함
RtzrTokenManager* tokenMgr = new RtzrTokenManager(clientId, clientSecret);
// ... 사용 ...
delete tokenMgr;  // 깜빡하면 메모리 누수! 프로그램이 죽음
```

`delete`를 깜빡하거나 예외가 발생하면 메모리가 새거나 프로그램이 죽습니다.
그래서 나온 것이 `shared_ptr`입니다.

#### shared_ptr — "참조 카운터가 달린 포장지"

```
┌──────────────────────────────────────────────┐
│  shared_ptr (포장지)                          │
│                                              │
│  실제 객체 주소 ──────▶ RtzrTokenManager     │
│  참조 카운트: 2         (실제 메모리)         │
└──────────────────────────────────────────────┘
```

**규칙**: 참조 카운트가 **0이 되는 순간** 자동으로 `delete`

```cpp
{
    auto a = std::make_shared<RtzrTokenManager>(...);  // 참조 카운트: 1
    auto b = a;   // b도 같은 객체를 가리킴            // 참조 카운트: 2
}
// a, b 모두 범위를 벗어남 → 참조 카운트: 0 → 자동으로 delete!
```

| | Java GC | shared_ptr |
|---|---|---|
| 삭제 시점 | GC가 결정 (불확실) | 카운트 0이 되는 즉시 |
| 수동 삭제 | 불필요 | 불필요 |
| 순환 참조 | GC가 해결 | 해결 못함 |

#### make_shared — "포장지 씌워서 만들기"

```cpp
std::make_shared<RtzrTokenManager>(clientId, clientSecret)
//               └─────────────┘   └─────────────────────┘
//               어떤 클래스?       생성자에 넘길 인자들
```

Java와 비교:
```java
// Java
new RtzrTokenManager(clientId, clientSecret)
// → RtzrTokenManager 객체 반환
```
```cpp
// C++
std::make_shared<RtzrTokenManager>(clientId, clientSecret)
// → shared_ptr<RtzrTokenManager> 반환 (포장지에 담긴 객체)
```

차이는 딱 하나 — Java는 객체 그 자체, C++은 "자동 삭제 포장지에 담긴 객체"를 반환.

#### auto — 타입 자동 추론 (Java의 var와 동일)

```cpp
// 풀어서 쓰면
std::shared_ptr<RtzrTokenManager> tokenMgr = std::make_shared<RtzrTokenManager>(...);

// auto로 줄이면 (완전히 동일)
auto tokenMgr = std::make_shared<RtzrTokenManager>(...);
```

```java
// Java 10+의 var와 완전히 같은 개념
var tokenMgr = new RtzrTokenManager(clientId, clientSecret);
```

#### 이 코드에서 왜 shared_ptr를 쓰는가

```cpp
// main.cpp — tokenMgr를 여러 곳에 공유
auto tokenMgr = std::make_shared<RtzrTokenManager>(...);

auto server = std::make_shared<WsServer>(..., tokenMgr, ...);
// WsSession 1, 2, 3 ... 도 tokenMgr를 공유
```

```
tokenMgr ◀── main       (카운트 1)
         ◀── WsServer   (카운트 2)
         ◀── WsSession1 (카운트 3)
         ◀── WsSession2 (카운트 4)
```

모든 참조가 사라질 때 딱 한 번 자동 삭제 → 메모리 누수 없음

---

### 3-2. `boost::asio::io_context` — 이벤트 루프

#### 왜 필요한가 — 라면 끓이기 비유

**블로킹 방식 (이벤트 루프 없음)**
```
손님A 라면 주문
    │
    │ 냄비 앞에서 멍하니 기다림... (아무것도 못 함)
    │
물 끓음 → 라면 넣음
    │
    │ 또 멍하니 기다림...
    │
완성
```
손님 3명이 동시에 주문하면 → 알바 3명(스레드 3개) 필요

**이벤트 루프 방식**
```
손님A 물 올림 → "끓으면 알려줘" 등록
손님B 물 올림 → "끓으면 알려줘" 등록
손님C 물 올림 → "끓으면 알려줘" 등록

알바는 카운터에서 다른 일 하면서 대기...

"손님B 물 끓었어요!" → 손님B 라면 넣음
"손님A 물 끓었어요!" → 손님A 라면 넣음
"손님C 물 끓었어요!" → 손님C 라면 넣음
```
알바 **1명(스레드 1개)**이 손님 3명을 동시에 처리.

#### 코드에 대입

```cpp
net::io_context ioc;   // 알바 대기소 만들기

server->run();         // "새 손님(연결) 오면 알려줘" 등록

ioc.run();             // 알바가 카운터에 서서 알림 기다리기 시작
                       // 알림 오면 처리, 없으면 대기 — 무한반복
```

`ioc.run()` 내부를 상상으로 풀면:
```
while (true) {
    할일 = 완료된이벤트꺼내기();
    if (할일 없음) { 이벤트올때까지대기(); continue; }
    할일에등록된콜백호출();
}
```

#### async_read가 어떻게 동작하는가

```cpp
// "데이터 오면 이 콜백 불러줘" 등록만 하고 즉시 리턴
ws_.async_read(buffer, [](ec, size) {
    처리();   // 데이터가 실제로 도착했을 때 여기가 호출됨
});
// ← 여기는 블로킹 없이 즉시 실행됨
```

```
async_read 호출
    └── 이벤트 목록에 등록: "buffer에 데이터 오면 콜백 호출"
    └── 즉시 리턴 (기다리지 않음)

... ioc가 계속 돌다가 ...

네트워크에서 데이터 도착!
    └── 이벤트 목록에서 꺼냄
    └── 등록했던 콜백 호출
```

#### 한 줄 요약

> **`ioc.run()` = 알바가 카운터에 서서 "끓었어요/도착했어요" 알림을 기다리는 것**
> 알림이 오면 → 등록해둔 콜백 호출 / 알림이 없으면 → 대기

---

### 3-3. `async_*` 함수들 — 비동기 콜백

```cpp
ws_.async_read(readBuf_,
    [self = shared_from_this()](beast::error_code ec, size_t) {
        // 읽기 완료 후 여기가 호출됨
        self->doRead();
    });
```

```java
// Java 비유
channel.read().thenAccept(data -> {
    // 읽기 완료 후 여기가 호출됨
    doRead();
});
// WebFlux의 Mono/Flux 구독과 동일한 패턴
```

- `async_read` = "읽기 시작하고, 완료되면 람다 콜백 호출해줘"
- 블로킹 없이 이벤트 루프가 계속 다른 일 처리 가능

---

### 3-4. `net::strand` — 스레드 안전 직렬 실행

```cpp
net::strand<net::io_context::executor_type> strand_;

// strand 위에서 실행 → 동시에 2개가 실행되지 않음이 보장됨
ws_.async_read(readBuf_,
    net::bind_executor(strand_, [self](ec, size) { ... }));
```

```java
// Java 비유
synchronized(this) {
    // 한 번에 하나만 실행됨
}
// 또는 single-thread executor와 동일
```

- 멀티스레드 환경에서 `WsSession`의 멤버 변수를 보호
- `synchronized` 블록 없이도 순서가 보장됨

---

### 3-5. `std::thread` — 별도 스레드 실행

```cpp
// LLM/TTS는 블로킹 HTTP 호출이라 별도 스레드로 뺀다
std::thread([self = shared_from_this(), hist]() mutable {
    auto llmRaw = self->llm_->chat(hist, self->callId_);
    // ...
}).detach();
```

```java
// Java 비유
new Thread(() -> {
    String llmRaw = llm.chat(hist, callId);
    // ...
}).start();
```

- `.detach()` = Java의 `.start()` — 스레드를 독립 실행 후 관리 포기
- libcurl(HTTP 클라이언트)이 동기 블로킹이라 이벤트 루프를 막지 않으려고 별도 스레드 사용

---

### 3-6. `std::deque<WriteItem>` — 전송 큐

```cpp
std::deque<WriteItem> writeQueue_;
// WriteItem은 string(JSON) 또는 vector<uint8_t>(바이너리) 중 하나
```

```java
// Java 비유
Queue<Object> writeQueue = new ArrayDeque<>();
// Object는 String(JSON) 또는 byte[](바이너리)
```

- 여러 메시지를 순서대로 보내기 위한 큐
- `doWrite()`가 큐에서 하나씩 꺼내 전송 → 완료되면 다음 꺼냄

---

### 3-7. `std::variant<string, vector<uint8_t>>` — 두 타입 중 하나

```cpp
using WriteItem = std::variant<std::string, std::vector<uint8_t>>;
```

```java
// Java 비유 — sealed interface나 Object로 표현
// String 또는 byte[] 중 하나를 담는 컨테이너
```

---

### 3-8. 라이브러리 정리

| 라이브러리 | Java 대응 | 역할 |
|---|---|---|
| `boost::asio` | Netty EventLoop | 비동기 I/O 이벤트 루프 |
| `boost::beast` | Netty WebSocket/HTTP | WebSocket + HTTP 프로토콜 |
| `libcurl` | OkHttpClient | HTTP 클라이언트 (동기) |
| `nlohmann/json` | Jackson / Gson | JSON 파싱/생성 |
| `spdlog` | Logback / Log4j | 로깅 |

---

## 4. main.cpp — 시작점

[↑ 목차](#목차)

### 4-1. `#include` — 필요한 파일 가져오기

```cpp
#include "src/Logger.h"                        // 로그 기능
#include "src/WsServer.h"                      // WebSocket 서버
#include "src/service/RtzrTokenManager.h"      // RTZR 토큰 관리
#include "src/service/SpringLlmService.h"      // Spring LLM 호출
#include "src/service/SpringTtsService.h"      // Spring TTS 호출
#include <boost/asio.hpp>                      // 이벤트 루프 라이브러리
```

```java
// Java 비유
import com.voicebot.WsServer;
import com.voicebot.service.RtzrTokenManager;
// ...
```

---

### 4-2. `namespace net = boost::asio` — 별명 붙이기

```cpp
namespace net = boost::asio;

// 이제 boost::asio::io_context 대신
net::io_context ioc;   // 이렇게 줄여서 쓸 수 있음
```

```java
// Java 비유 — import로 패키지 경로를 생략하는 것과 동일
import boost.asio.*;
```

---

### 4-3. `envOr` — 환경변수 읽기

```cpp
static std::string envOr(const char* name, const char* def) {
    const char* v = std::getenv(name);   // 환경변수 읽기
    return v ? v : def;                  // 없으면 기본값 반환
}
```

```java
// Java 비유
String envOr(String name, String def) {
    String v = System.getenv(name);
    return v != null ? v : def;          // 삼항연산자 — 완전히 동일
}
```

`v ? v : def` = "v가 있으면 v, 없으면 def" — Java 삼항연산자와 동일.

---

### 4-4. `main()` — Spring Boot의 main과 동일

```cpp
int main() {          // C++
```
```java
public static void main(String[] args) {   // Java
```

반환값 `int`는 OS에 전달하는 종료 코드. `return 0` = 정상 종료.

---

### 4-5. 환경변수 읽어서 포트 설정

```cpp
const auto port = static_cast<unsigned short>(
    std::stoi(envOr("PORT", "9090")));
```

단계별로 풀면:
```
envOr("PORT", "9090")          → "9090"  (문자열)
std::stoi(...)                 → 9090    (정수)      ← Java의 Integer.parseInt()
static_cast<unsigned short>()  → 9090    (포트 전용 타입으로 변환)
                                                      ← Java의 (short) 캐스팅
```

```java
// Java 비유
int port = Integer.parseInt(
    System.getenv("PORT") != null ? System.getenv("PORT") : "9090");
```

---

### 4-6. 서비스 객체 생성 — Spring의 @Bean과 동일

```cpp
// RTZR 토큰 관리자 생성 + 자동 갱신 시작
auto tokenMgr = std::make_shared<RtzrTokenManager>(clientId, clientSecret);
tokenMgr->startScheduler();    // 5분마다 토큰 갱신 스레드 시작

// Spring 호출 서비스 생성
auto llm = std::make_shared<SpringLlmService>(springUrl);
auto tts = std::make_shared<SpringTtsService>(springUrl);
```

`->` 는 Java의 `.` 과 동일:
```cpp
tokenMgr->startScheduler();   // C++
tokenMgr.startScheduler();    // Java
```

```java
// Java @Bean 비유
@Bean RtzrTokenManager tokenMgr() { return new RtzrTokenManager(...); }
@Bean SpringLlmService llm()      { return new SpringLlmService(...); }
@Bean SpringTtsService tts()      { return new SpringTtsService(...); }
```

---

### 4-7. 서버 시작

```cpp
net::io_context ioc;                                          // 이벤트 루프 생성

auto server = std::make_shared<WsServer>(ioc, port, tokenMgr, llm, tts);
server->run();                                                // "연결 오면 알려줘" 등록

LOG_INFO("[MAIN] 서버 시작 port={} spring={}", port, springUrl);

ioc.run();     // 알바가 카운터에 서기 시작 — 여기서 무한 대기
return 0;      // 서버 종료 시 여기 도달
```

---

### 4-8. 전체 흐름 한눈에

```
main() 시작
    │
    ├── 로그 초기화
    ├── 환경변수 읽기 (PORT, SPRING_URL, RTZR 키)
    ├── 서비스 객체 생성 (tokenMgr, llm, tts)
    ├── tokenMgr 토큰 자동 갱신 시작
    ├── WsServer 생성 (포트 9090 열기)
    ├── server->run() → "연결 오면 알려줘" 등록
    │
    └── ioc.run() → 무한 대기 (여기서 서버 운영)
                    연결 요청 오면 → WsSession 생성
                    음성 도착하면 → STT 처리
                    ...
```

```java
// Java Spring Boot 비유
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);  // = ioc.run()
    }
}
```

| C++ | Java Spring Boot |
|---|---|
| `main()` | `main()` |
| `io_context` | Netty EventLoop |
| `shared_ptr<Service>` | `@Bean` |
| `ioc.run()` | `SpringApplication.run()` |

---

## 5. WsServer — 연결 수락

[↑ 목차](#목차)

```
WsServer
    │
    └── doAccept()  ← accept 대기
            │ 연결 요청 도착
            ▼
        WsSession 생성 (연결 1개 = 전화 1건)
            │
            └── doAccept()  ← 다음 연결 대기 (재귀적으로 계속)
```

```java
// Java 비유 — Spring WebSocket HandlerRegistry
// 새 연결마다 새 핸들러 인스턴스 생성하는 것과 동일
```

---

## 6. WsSession — 핵심 (연결 1개 = 전화 1건)

[↑ 목차](#목차)

### 멤버 변수

```cpp
ws::stream<tcp::socket>  ws_;        // WebSocket 연결 자체
net::strand              strand_;    // 직렬 실행 보장 (synchronized 역할)
net::io_context&         ioc_;       // 이벤트 루프 참조

std::string sessionId_;              // "S1", "S2", ...
std::string callId_;                 // "CTI-S1", "CTI-S2", ...
std::vector<LlmService::Message> history_;  // 대화 이력 (user/assistant 메시지)

std::shared_ptr<RtzrTokenManager>       tokenMgr_;
std::shared_ptr<LlmService>             llm_;
std::shared_ptr<TtsService>             tts_;
std::shared_ptr<RtzrWebSocketSttService> stt_;  // 발화마다 재생성

std::atomic<bool>      cancelled_{false};  // 통화 종료 플래그
std::deque<WriteItem>  writeQueue_;        // 전송 대기 큐
bool writing_ = false;                     // 현재 전송 중 여부
```

### 생명주기

```
WsSession 생성
    │
    ▼
doAccept()          ← WebSocket 핸드셰이크
    │ 성공
    ▼
startStt()          ← RTZR WebSocket 연결 시작
doRead()            ← 브라우저 메시지 수신 루프 시작
    │
    ├── binary frame 수신 → stt_->sendChunk(audio)
    │
    ├── text frame 수신 → handleTextMessage()
    │       └── CALL_END → stt_->complete()
    │
    └── 연결 종료 → cancelled_ = true, stt_->complete()
```

---

## 7. 핵심 파이프라인: STT → LLM → TTS

[↑ 목차](#목차)

```cpp
// STT 최종 결과가 도착하면 호출됨
void handleFinalStt(const std::string& text) {

    // 1. STT 결과를 브라우저로 전송
    sendJson({{"type", "STT_FINAL"}, {"text", text}});
    sendJson({{"type", "BOT_THINKING"}});

    // 2. 대화 이력에 사용자 발화 추가
    history_.push_back({"user", text});

    // 3. LLM/TTS는 블로킹 HTTP → 별도 스레드에서 실행
    std::thread([self, hist]() {

        // 4. Spring LLM 호출 (POST /api/cti/llm/chat)
        auto llmRaw = self->llm_->chat(hist, self->callId_);

        // 5. LLM 응답이 JSON이면 intent/response 파싱
        //    JSON이 아니면 raw 텍스트를 response로 사용
        std::string intent   = "기타";
        std::string response = llmRaw;
        try {
            auto j   = json::parse(llmRaw);
            intent   = j.value("intent",   "기타");
            response = j.value("response", llmRaw);
        } catch (...) {}

        // 6. Spring TTS 호출 (POST /api/cti/tts/synthesize)
        auto audioBytes = self->tts_->synthesize(response, self->callId_);

        // 7. 결과를 strand 위에서 브라우저로 전송 (스레드 안전)
        net::post(self->strand_, [self, intent, response, hist, audioBytes]() {
            self->history_ = hist;                         // 대화 이력 업데이트
            self->sendJson({{"type", "LLM_RESULT"}, ...}); // 의도 + 응답 텍스트
            self->sendJson({{"type", "TTS_TEXT"}, ...});   // TTS 텍스트
            self->sendBinary(audioBytes);                  // MP3 오디오
            self->startStt();                              // 다음 발화 대기
            self->sendJson({{"type", "BOT_READY"}});
        });

    }).detach();
}
```

**중요 포인트**: LLM 응답이 JSON이면 `intent`와 `response`를 분리해서 처리한다.
→ **LLM 모드 설계(LLM-MODE-DESIGN.md)와 이미 연결점이 있음**

---

## 8. 전송 큐 (writeQueue_) 동작 원리

[↑ 목차](#목차)

WebSocket은 동시에 여러 메시지를 보낼 수 없다. 하나가 완료되어야 다음을 보낼 수 있다.

```
sendJson(STT_FINAL)   → queue: [STT_FINAL]
sendJson(BOT_THINKING)→ queue: [STT_FINAL, BOT_THINKING]
sendJson(LLM_RESULT)  → queue: [STT_FINAL, BOT_THINKING, LLM_RESULT]
sendBinary(MP3)       → queue: [STT_FINAL, BOT_THINKING, LLM_RESULT, MP3]

doWrite() → STT_FINAL 전송 완료 → doWrite() → BOT_THINKING → ...
```

```java
// Java 비유 — Netty의 ChannelOutboundBuffer와 동일한 개념
// channel.writeAndFlush()를 순서대로 호출하는 것과 같음
```

---

## 9. RtzrWebSocketSttService — RTZR STT 연결

[↑ 목차](#목차)

발화가 끝날 때마다 새로 연결하고, 종료 시 닫는 구조.

```
startStt() 호출
    │
    ▼
doConnect()          ← openapi.vito.ai:443 TCP 연결
    │
    ▼
doSslHandshake()     ← TLS/SSL 암호화 핸드셰이크 (HTTPS의 S)
    │
    ▼
doWsHandshake()      ← WebSocket 업그레이드 + Authorization 토큰 첨부
    │ 연결 완료
    ├── doRead()     ← RTZR에서 인식 결과 수신 루프
    └── doWrite()    ← 대기 중이던 오디오 청크 전송 시작

sendChunk() 호출될 때마다 → queue에 추가 → doWrite()로 순차 전송

complete() 호출 → "EOS" 텍스트 프레임 전송 → RTZR이 최종 결과 반환
```

### RTZR WebSocket 경로

```
wss://openapi.vito.ai/v1/transcribe:streaming
    ?sample_rate=16000
    &encoding=LINEAR16      ← raw PCM 포맷
    &use_itn=true           ← 숫자/날짜 정규화 (이천이십육 → 2026)
    &use_disfluency_filter=true  ← 어, 음 같은 발화 필터
    &use_punctuation=false  ← 문장부호 없음
```

---

## 10. RtzrTokenManager — 토큰 자동 갱신

[↑ 목차](#목차)

```
생성자 → refreshToken()    ← 즉시 토큰 발급

startScheduler()
    └── 별도 스레드 시작
            └── 5분마다 체크:
                만료까지 10분 미만이면 → refreshToken()
```

```java
// Java 비유
@Scheduled(fixedDelay = 300_000)
public void refreshIfNeeded() {
    if (expireAt - System.currentTimeMillis()/1000 < 600) {
        refreshToken();
    }
}
```

토큰 발급 엔드포인트:
```
POST https://openapi.vito.ai/v1/authenticate
Body: client_id=...&client_secret=...
Response: { "access_token": "...", "expire_at": 1234567890 }
```

---

## 11. SpringLlmService / SpringTtsService — Spring 호출

[↑ 목차](#목차)

```
POST http://localhost:8080/api/cti/llm/chat
Content-Type: application/json
Body: [{"role":"user","content":"배송 언제 오나요?"}]

Response: (LLM 응답 텍스트 또는 JSON 문자열)
```

```
POST http://localhost:8080/api/cti/tts/synthesize
Content-Type: application/json
Body: "배송 관련 안내드립니다."

Response: MP3 바이너리
```

> libcurl = Java의 OkHttpClient와 동일. 동기 블로킹 HTTP 클라이언트.

---

## 12. Logger.h — 로그 구조

[↑ 목차](#목차)

Spring Logback 스타일을 C++로 재현:

```
logs/
├── cpp-ws.log              ← 오늘 로그 (항상 이 파일)
├── cpp-ws.2026-06-04.log  ← 어제 로그 (자동 rotate)
└── cpp-ws.2026-06-03.log  ← 그제 로그
```

- 날짜가 바뀌면 현재 파일을 날짜 이름으로 rename → 새 파일 생성
- 7일치 초과분은 자동 삭제

---

## 13. 현재 구조의 특징 및 한계

[↑ 목차](#목차)

### 특징

| 항목 | 내용 |
|---|---|
| 연결 방식 | 브라우저 ↔ cpp-ws-server: WebSocket |
| STT 방식 | RTZR WebSocket (실시간 스트리밍) |
| LLM/TTS 방식 | Spring REST API 위임 (libcurl 동기 호출) |
| 대화 이력 | `history_` 벡터 (메모리, 재시작 시 소멸) |
| 동시 통화 | WsSession이 연결마다 독립 생성 → 다중 통화 지원 |

### LLM 응답 처리 (현재)

```cpp
// LLM 응답이 JSON이면 intent/response 분리
// JSON이 아니면 통째로 response로 사용
try {
    auto j   = json::parse(llmRaw);
    intent   = j.value("intent",   "기타");    // ← 이미 intent 필드 파싱 중
    response = j.value("response", llmRaw);   // ← response 필드 파싱 중
} catch (...) {}
```

**→ LLM-MODE-DESIGN.md에서 설계한 JSON 응답 구조와 이미 호환된다.**
Spring에서 JSON을 반환하면 C++ 쪽 코드 변경 없이 intent/response가 분리됨.

### 한계 / 개선 고려 사항

| 항목 | 현재 | 개선 방향 |
|---|---|---|
| 대화 이력 저장 | 메모리만 | MongoDB 연동 (Spring 쪽에서 담당) |
| LLM 모드 전환 | 없음 | Spring의 LLM 모드 설정으로 제어 |
| STT 오류 복구 | 단순 오류 메시지 | 재시도 로직 |
| 인증 | 없음 | 향후 필요 시 추가 |

---

## 14. 핵심 흐름 한눈에 보기

[↑ 목차](#목차)

```
브라우저                cpp-ws-server              외부
   │                        │
   │── binary(음성) ──────▶│ stt_->sendChunk()
   │                        │──── PCM chunk ─────▶ RTZR
   │                        │◀─── interim text ────│
   │◀── STT_INTERIM ────────│
   │                        │◀─── final text ──────│
   │◀── STT_FINAL ──────────│
   │◀── BOT_THINKING ───────│
   │                        │── POST /llm/chat ──▶ Spring
   │                        │◀── JSON response ────│
   │                        │── POST /tts/synth ─▶ Spring
   │                        │◀── MP3 bytes ─────────│
   │◀── LLM_RESULT(JSON) ───│
   │◀── TTS_TEXT(JSON) ─────│
   │◀── binary(MP3) ────────│
   │◀── BOT_READY ──────────│
   │                        │ startStt() → 다음 발화 대기
```
