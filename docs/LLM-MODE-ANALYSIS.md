# LLM 다중 모드 소스 분석

커밋 `733f7c1 feat(llm): LLM 다중 모드 구현 (ANTHROPIC / INTERNAL / HYBRID)` 에서 추가된 구조.

---

## 목차

- [한 줄 요약](#한-줄-요약)
- [파일 구조](#파일-구조)
  - [각 파일 역할](#각-파일-역할)
- [모드 3가지 비교](#모드-3가지-비교)
  - [ANTHROPIC 모드](#anthropic-모드)
  - [INTERNAL 모드](#internal-모드)
  - [HYBRID 모드](#hybrid-모드)
- [어떻게 모드를 바꾸는가](#어떻게-모드를-바꾸는가)
- [클래스 관계도](#클래스-관계도)
- [HYBRID 모드 상세 흐름](#hybrid-모드-상세-흐름)
  - [1단계: 마지막 사용자 발화 추출](#1단계-마지막-사용자-발화-추출)
  - [2단계: Claude에게 의도 분류 요청](#2단계-claude에게-의도-분류-요청)
  - [3단계: MongoDB에서 대본 조회](#3단계-mongodb에서-대본-조회)
  - [4단계: 분기](#4단계-분기)
  - [분기 시나리오 예시](#분기-시나리오-예시)
- [도메인 구조](#도메인-구조)
  - [IntentResult — Claude 분류 결과](#intentresult--claude-분류-결과)
  - [IntentPlaybook — MongoDB 대본 문서](#intentplaybook--mongodb-대본-문서)
- [실제 로그로 보는 흐름](#실제-로그로-보는-흐름)
  - [성공 케이스 (Playbook hit)](#성공-케이스-playbook-hit)
  - [Claude fallback 케이스 (신뢰도 부족)](#claude-fallback-케이스-신뢰도-부족)
  - [분류 실패 케이스 (Claude 백틱 응답)](#분류-실패-케이스-claude-백틱-응답)
- [분석 내용](#분석-내용)
  - [LlmService.java](#llmservicejava-설명해줘)
  - [LlmModeRouter](#llmmoderouter-설명해줘-특히-llmstrategy-strategy-switch-modetouppercase-이-부분-이해-안-가)
  - [ClaudeApiLlmService.java](#claudeapillmservicejava-설명해줘)
  - [PlaybookService.java](#playbookservicejava-설명해줘)
  - [HybridStrategy.java](#hybridstrategyjava-설명해줘)
  - [AnthropicStrategy.java](#anthropicstrategyjava-설명해줘)
  - [InternalStrategy.java](#internalstrategyjava-설명해줘)
  - [CtiRestController](#ctirestcontroller-설명해줘)
  - [CtiWebSocketHandler](#ctiwebsockethandler-설명해줘--함수별로-설명-필요)
  - [RtzrWebSocketSttService.java](#rtzrwebsocketsttservicejava-아주-쉽게-설명해줘)
  - [GoogleCloudTtsService](#googlecloudttsservice-클래스-쉽게-설명해줘)
  - [CallController, CallHandler](#http-파이프라인-본체--callcontroller-callhandler-쉽게-설명해줘)
  - [Config 3종 (RedisConfig, WebClientConfig, WebSocketConfig)](#config-3종--redisconfig-webclientconfig-websocketconfig-쉽게-설명해줘)
  - [Docker / devcontainer 운영](#docker-에-대해서-알고-싶어-image--container-관계-주요-명령어와-의미-존재-이유-등-근본적인-개념부터-설명-그리고-container-생성--사용-후-삭제가-낫은지-너무-많아져서-지저분한-것-같기도-하고)

---

## 한 줄 요약

[↑ 목차](#목차)

> **"사용자 발화가 들어오면 어떻게 응답을 만들 것인가"** 를 3가지 전략 중 하나로 선택할 수 있게 만든 구조.

| 모드 | 응답 방식 |
|---|---|
| ANTHROPIC | Claude가 전부 알아서 답변 |
| INTERNAL | Claude가 의도만 분류, 응답은 미리 작성해둔 대본 |
| HYBRID | 대본 있으면 대본, 없으면 Claude가 직접 답변 |

---

## 파일 구조

[↑ 목차](#목차)

```
service/llm/
├── LlmService.java              ← 인터페이스 (계약서)
├── LlmModeRouter.java           ← 라우터 (모드 선택기)
├── ClaudeApiLlmService.java     ← Claude API 호출 담당
├── SimulatorLlmService.java     ← 시뮬레이터 (sim profile)
├── PlaybookService.java         ← MongoDB에서 대본 조회
│
└── strategy/
    ├── LlmStrategy.java         ← 전략 인터페이스
    ├── AnthropicStrategy.java   ← ANTHROPIC 모드 구현
    ├── InternalStrategy.java    ← INTERNAL 모드 구현
    └── HybridStrategy.java      ← HYBRID 모드 구현
```

### 각 파일 역할

**`LlmService.java`** — 인터페이스 (계약서)
```java
public interface LlmService {
    String chat(List<Message> messages, String callId);

    record Message(String role, String content) {}
}
```
비즈니스 로직은 이 인터페이스만 바라본다. 구현체가 뭔지 몰라도 된다.

---

**`LlmModeRouter.java`** — 라우터 (핵심)
```java
@Service
@Profile("real")
public class LlmModeRouter implements LlmService {

    @Value("${voicebot.llm.mode:ANTHROPIC}")
    private String mode;  // 환경변수로 모드 결정

    public String chat(List<Message> messages, String callId) {
        LlmStrategy strategy = switch (mode.toUpperCase()) {
            case "INTERNAL" -> internalStrategy;
            case "HYBRID"   -> hybridStrategy;
            default         -> anthropicStrategy;
        };
        return strategy.execute(messages, callId);
    }
}
```
`VOICEBOT_LLM_MODE` 환경변수를 읽어서 어떤 전략을 쓸지 결정한다.

---

**`ClaudeApiLlmService.java`** — Claude API 호출 담당
- `chat()`: 대화 전체를 Claude에게 보내고 응답 받기 (자유 응답)
- `classifyIntent()`: 사용자 발화가 어떤 의도인지 분류 요청 (JSON 응답)
- `stripCodeBlock()`: Claude가 백틱으로 감싸서 보낼 때 제거

---

**`PlaybookService.java`** — MongoDB 대본 조회
```java
public Optional<IntentPlaybook> findByIntent(String intent) {
    return repository.findByIntent(intent);
}
```
"배송문의"라는 intent를 넘기면 MongoDB에서 해당 응답 대본을 찾아준다.

---

## 모드 3가지 비교

[↑ 목차](#목차)

### ANTHROPIC 모드

```
사용자: "배송비가 얼마예요?"
    │
    ▼
Claude API (대화 전체 전달)
    │
    ▼
Claude가 알아서 답변
{"intent": "배송문의", "response": "배송비는 주문 금액에 따라 달라집니다..."}
```

- Claude를 가장 자유롭게 활용
- 응답 품질은 좋지만 비용이 많이 듦
- 답변이 매번 달라질 수 있음 (비결정적)

---

### INTERNAL 모드

```
사용자: "배송비가 얼마예요?"
    │
    ▼ Claude에게 의도만 물어봄
classifyIntent() → {"intent": "배송문의", "confidence": 0.95}
    │
    ▼ MongoDB에서 대본 조회
findByIntent("배송문의") → "배송 관련 안내드립니다. 일반 배송은..."
    │
    ├─ 대본 있고 신뢰도 >= 0.7 → 대본 그대로 응답
    │
    └─ 대본 없거나 신뢰도 낮음 → "죄송합니다. 잠시 후 상담원을 연결해드리겠습니다."
```

- Claude 호출을 최소화 (분류만 사용)
- 응답이 항상 일정함 (대본 기반)
- 대본에 없는 질문은 고정된 fallback 응답

---

### HYBRID 모드

```
사용자: "배송비가 얼마예요?"
    │
    ▼ Claude에게 의도만 물어봄
classifyIntent() → {"intent": "배송문의", "confidence": 0.95}
    │
    ▼ MongoDB에서 대본 조회
findByIntent("배송문의") → 대본 발견
    │
    ├─ 대본 있고 신뢰도 >= 0.7 → 대본 응답
    │
    └─ 대본 없거나 신뢰도 낮음
            │
            ▼ Claude에게 전체 대화 전달
        chat() → Claude가 동적으로 응답 생성
```

- 대본 있으면 INTERNAL처럼, 없으면 ANTHROPIC처럼 동작
- 비용과 품질의 균형
- **현재 운영 중인 모드**

---

## 어떻게 모드를 바꾸는가

[↑ 목차](#목차)

환경변수 `VOICEBOT_LLM_MODE` 하나로 결정한다. **코드 변경 없음.**

```bash
# ANTHROPIC 모드
VOICEBOT_LLM_MODE=ANTHROPIC \
nohup mvn spring-boot:run -Dspring-boot.run.profiles=real > logs/real-app.log 2>&1 &

# INTERNAL 모드
VOICEBOT_LLM_MODE=INTERNAL \
nohup mvn spring-boot:run -Dspring-boot.run.profiles=real > logs/real-app.log 2>&1 &

# HYBRID 모드
VOICEBOT_LLM_MODE=HYBRID \
nohup mvn spring-boot:run -Dspring-boot.run.profiles=real > logs/real-app.log 2>&1 &
```

`application.yml` 설정:
```yaml
voicebot:
  llm:
    mode: ${VOICEBOT_LLM_MODE:ANTHROPIC}   # 환경변수 없으면 ANTHROPIC이 기본값
    hybrid:
      confidence-threshold: 0.7            # HYBRID 모드 신뢰도 기준
    internal:
      confidence-threshold: 0.7
      fallback-response: "죄송합니다. 잠시 후 상담원을 연결해드리겠습니다."
```

---

## 클래스 관계도

[↑ 목차](#목차)

```
CtiWebSocketHandler / CallHandler
        │
        │ LlmService (인터페이스)
        ▼
┌─────────────────────────────────────────────┐
│              LlmModeRouter                  │  ← real profile
│  (환경변수 VOICEBOT_LLM_MODE 읽어서 선택)    │
└──────────┬──────────┬───────────────────────┘
           │          │              │
           ▼          ▼              ▼
  Anthropic      Internal       Hybrid
  Strategy       Strategy       Strategy
     │              │           │       │
     │              │           │       │
     ▼          ┌───┴───┐   ┌──┴──┐  ┌─┴────────┐
  Claude       Claude  Play  Claude  Play  Claude
  API chat()  classify book  classify book  chat()
              Intent()       Intent()
                  │              │
                  └──────────────┘
                         │
                         ▼
                  PlaybookService
                         │
                         ▼
               IntentPlaybookRepository
                         │
                         ▼
                      MongoDB
                  (intent_playbook)

※ sim profile: SimulatorLlmService 사용 (LlmModeRouter 미사용)
```

---

## HYBRID 모드 상세 흐름

[↑ 목차](#목차)

`HybridStrategy.execute()` 코드 흐름을 단계별로 설명한다.

### 1단계: 마지막 사용자 발화 추출

```java
String userText = extractLastUserText(messages);
// messages 중 role="user"인 가장 마지막 메시지를 꺼냄
// 대화 히스토리 전체에서 "지금 한 말"만 분류에 사용
```

### 2단계: Claude에게 의도 분류 요청

```java
IntentResult intentResult = claudeApiLlmService.classifyIntent(userText, callId);
```

Claude에게 이런 프롬프트를 보낸다:
```
당신은 콜센터 상담 AI입니다.
고객 발화를 분석하여 반드시 아래 JSON 형식으로만 응답하세요.

intent는 반드시 다음 중 하나여야 합니다:
인사 | 배송문의 | 반품환불 | 교환 | 결제 | 회원 | 주문조회 | 상담원연결 | 종료 | 기타

{"intent": "배송문의", "confidence": 0.95}
```

Claude 응답:
```json
{"intent": "배송문의", "confidence": 0.95}
```

파싱 결과: `IntentResult(intent="배송문의", confidence=0.95)`

### 3단계: MongoDB에서 대본 조회

```java
Optional<IntentPlaybook> playbookOpt = playbookService.findByIntent("배송문의");
```

MongoDB `intent_playbook` 컬렉션에서 intent가 "배송문의"인 문서를 찾는다.

### 4단계: 분기

```java
if (playbookOpt.isPresent() && intentResult.confidence() >= 0.7) {
    // → 대본 응답
} else {
    // → Claude fallback
}
```

**대본 응답 경로:**
```java
IntentPlaybook playbook = playbookOpt.get();
// playbook.getResponse() = "배송 관련 안내드립니다. 일반 배송은..."
return {"intent":"배송문의","response":"배송 관련 안내드립니다..."}
```

**Claude fallback 경로:**
```java
return claudeApiLlmService.chat(messages, callId);
// 대화 전체 히스토리를 Claude에게 보내 자유롭게 응답 생성
```

### 분기 시나리오 예시

| 사용자 발화 | intent | confidence | 대본 | 결과 |
|---|---|---|---|---|
| "배송비 얼마예요?" | 배송문의 | 0.95 | ✅ 있음 | 대본 응답 |
| "환불 받고 싶어요" | 반품환불 | 0.65 | ✅ 있음 | Claude fallback (신뢰도 부족) |
| "아무 말이나 합니다" | 기타 | 0.85 | ✅ 있음 | 대본 응답 (기타 대본 사용) |
| "뭐 어쩌라고" | 기타 | 0.45 | ✅ 있음 | Claude fallback (신뢰도 부족) |

---

## 도메인 구조

[↑ 목차](#목차)

### IntentResult — Claude 분류 결과

```java
public record IntentResult(String intent, double confidence) {}
```

- `intent`: 분류된 의도 ("배송문의", "기타" 등)
- `confidence`: 신뢰도 (0.0 ~ 1.0)
- Claude가 JSON 파싱 실패 시 → `IntentResult("기타", 0.0)` 반환

---

### IntentPlaybook — MongoDB 대본 문서

```java
@Document(collection = "intent_playbook")
public class IntentPlaybook {
    @Id
    private String id;
    private String intent;              // 조회 키 (예: "배송문의")
    private String response;            // 응답 텍스트 (사전 작성된 답변)
    private String action;              // 액션 타입 (로그 출력용)
    private boolean escalate;           // 상담원 연결 여부
    private double confidenceThreshold; // 문서별 임계값 (현재 미사용)
}
```

**MongoDB에 저장된 대본 목록** (`mongo-init/01-playbook.js`):

| intent | action | response 요약 |
|---|---|---|
| 인사 | provide_info | "안녕하세요! 무엇을 도와드릴까요?" |
| 배송문의 | provide_info | "일반 배송은 2~3 영업일..." |
| 반품환불 | provide_info | "수령 후 7일 이내 신청 가능..." |
| 교환 | provide_info | "수령 후 7일 이내 신청 가능..." |
| 결제 | provide_info | "신용카드, 계좌이체, 무통장입금..." |
| 회원 | provide_info | "마이페이지에서 처리 가능..." |
| 주문조회 | request_order_number | "주문번호를 말씀해주시면..." |
| 상담원연결 | escalate | "상담원에게 연결해드리겠습니다." |
| 종료 | end_call | "이용해 주셔서 감사합니다." |
| 기타 | fallback | "죄송합니다. 잠시 후 상담원을..." |

---

## 실제 로그로 보는 흐름

[↑ 목차](#목차)

### 성공 케이스 (Playbook hit)

```
[LLM-MODE] callId=CPP mode=HYBRID
[INTENT]   callId=CPP intent=배송문의 confidence=0.95 elapsed=1581ms
[PLAYBOOK] intent=배송문의 hit=true elapsed=72ms
[PLAYBOOK] callId=CPP hit=true action=provide_info → Playbook 응답
```

→ 대본에서 "배송 관련 안내드립니다..." 바로 반환. Claude 추가 호출 없음.

---

### Claude fallback 케이스 (신뢰도 부족)

```
[LLM-MODE] callId=CPP mode=HYBRID
[INTENT]   callId=CPP intent=기타 confidence=0.45 elapsed=1316ms
[PLAYBOOK] intent=기타 hit=true elapsed=1ms
[PLAYBOOK] callId=CPP hit=false confidence=0.45 → Claude fallback
[LLM-PERF] callId=CPP elapsed=2847ms
```

→ 기타 대본은 있지만 신뢰도 0.45 < 0.7 → Claude에게 전체 대화 전달 → 동적 응답.

---

### 분류 실패 케이스 (Claude 백틱 응답)

```
[INTENT] callId=CPP 분류 실패 → 기타 반환:
         Unexpected character ('`') | raw=```json\n{"intent":"기타"...}\n```
[PLAYBOOK] intent=기타 hit=true elapsed=2ms
[PLAYBOOK] callId=CPP hit=false confidence=0.0 → Claude fallback
```

→ Claude가 JSON을 백틱으로 감싸 반환 → `stripCodeBlock()`으로 제거 후 재파싱.
  파싱 실패 시 confidence=0.0 → Claude fallback 진입.
  (`ClaudeApiLlmService.java`에 `stripCodeBlock()` 방어 코드 추가됨)

## 분석 내용
[↑ 목차](#목차)

### LlmService.java 설명해줘.
```
답변 : ● 코드가 짧지만 역할이 중요합니다.

  LlmService.java

  인터페이스 — "LLM 서비스는 이렇게 생겼어야 한다"는 계약서입니다.

  public interface LlmService {
      String chat(List<Message> messages, String callId);

      record Message(String role, String content) {}
  }

  구성 요소

  chat() 메서드 — 유일한 메서드

  입력: messages (대화 이력), callId (세션 ID)
  출력: String (LLM 응답 텍스트)

  Message record — 메시지 한 줄을 표현

  record Message(String role, String content) {}

  // 사용 예
  new Message("user",      "배송비 얼마예요?")
  new Message("assistant", "배송비는 3,000원입니다.")

  role은 항상 "user" 또는 "assistant" 둘 중 하나.

  ---
  왜 인터페이스로 만들었는가

  구현체가 여러 개이기 때문입니다.

  LlmService (인터페이스)
    ├── LlmModeRouter       @Profile("real")  ← 운영
    └── SimulatorLlmService @Profile("sim")   ← 개발

  CallHandler, CtiWebSocketHandler는 LlmService만 바라봅니다. 운영/개발 환경이 바뀌어도 비즈니스 로직 코드는 변경이 없습니다.

  // CallHandler.java — 어떤 구현체인지 모른다
  private final LlmService llmService;  // 인터페이스만 의존

  llmService.chat(messages, callId);  // sim이든 real이든 동일하게 호출
  
>> Message 는 클래스이고 생성 시에는 record 키워드에서 의해서 한 줄로 표현해야한다는건가?
답변: ● 아닙니다. record가 클래스의 일종이긴 하지만, 역할과 의미가 다릅니다.

  record란

  Java 16에서 추가된 데이터 운반 전용 클래스입니다.

  record Message(String role, String content) {}

  이 한 줄이 아래 클래스 전체와 동일합니다.

  class Message {
      private final String role;
      private final String content;

      public Message(String role, String content) {  // 생성자
          this.role = role;
          this.content = content;
      }

      public String role()    { return role; }       // getter
      public String content() { return content; }    // getter

      public boolean equals(Object o) { ... }        // equals
      public int hashCode()           { ... }        // hashCode
      public String toString()        { ... }        // toString
  }

  record가 이걸 자동으로 만들어줍니다.

  생성 방법

  한 줄로 표현해야 한다는 건 아니고, 일반 클래스와 똑같이 생성합니다.

  // 생성
  Message m = new Message("user", "배송비 얼마예요?");

  // 읽기 (getter 이름이 필드명과 동일)
  m.role();     // "user"
  m.content();  // "배송비 얼마예요?"

  일반 클래스와 차이

  ┌────────┬─────────────┬────────────────────┐
  │        │ 일반 클래스 │       record       │
  ├────────┼─────────────┼────────────────────┤
  │ 불변성 │ 선택        │ 항상 불변 (final)  │
  ├────────┼─────────────┼────────────────────┤
  │ 생성자 │ 직접 작성   │ 자동 생성          │
  ├────────┼─────────────┼────────────────────┤
  │ getter │ 직접 작성   │ 자동 생성          │
  ├────────┼─────────────┼────────────────────┤
  │ 용도   │ 범용        │ 데이터 담는 용도만 │
  └────────┴─────────────┴────────────────────┘

  이 프로젝트에서 쓰는 이유

  Message는 role과 content를 담아서 전달하기만 하면 됩니다. 로직이 없고 데이터만 있으니 record가 딱 맞습니다.

```

### CallHandler, CtiWebSocketHandler 역할을 설명해줘.
```
● 두 클래스의 차이

  둘 다 STT → LLM → TTS 파이프라인을 실행하는 오케스트레이터입니다. 차이는 통신 방식입니다.

  ┌────────────┬──────────────────────┬────────────────────────────┐
  │            │     CallHandler      │    CtiWebSocketHandler     │
  ├────────────┼──────────────────────┼────────────────────────────┤
  │ 방식       │ HTTP (요청-응답)     │ WebSocket (실시간 양방향)  │
  ├────────────┼──────────────────────┼────────────────────────────┤
  │ 음성 입력  │ 파일 통째로          │ 250ms 청크 스트림          │
  ├────────────┼──────────────────────┼────────────────────────────┤
  │ 응답 시점  │ 처리 완료 후 한 번에 │ 단계마다 즉시 전송         │
  ├────────────┼──────────────────────┼────────────────────────────┤
  │ 엔드포인트 │ POST /call/incoming  │ ws://localhost:8080/ws/cti │
  └────────────┴──────────────────────┴────────────────────────────┘

  ---
  CallHandler — HTTP 방식

  클라이언트
    │ POST /call/incoming (음성 파일 전체)
    ▼
  CallHandler.process()
    │
    ├─ 1. STT: 음성 파일 → 텍스트
    ├─ 2. Redis에서 세션 조회 (대화 이력 유지)
    ├─ 3. LLM: 텍스트 → 응답
    ├─ 4. TTS: 응답 → 오디오
    ├─ 5. MariaDB에 CallRecord 저장
    │
    └─ 오디오(byte[]) 반환

  특징
  - 한 번 요청하면 전부 처리 후 결과 반환 (동기)
  - 대화 이력을 Redis에 저장 (call:session:{callId}, 1시간 TTL)
  - 처리 시간 4단계 모두 로그로 측정 ([STT-PERF], [LLM-PERF], [TTS-PERF], [CALL-PERF])

  ---
  CtiWebSocketHandler — WebSocket 방식

  브라우저 (CTI Simulator)
    │ WebSocket 연결
    ▼
  afterConnectionEstablished()
    └─ callId 발급, Sink 생성, STT 구독 시작

    │ 음성 청크 binary 전송 (250ms마다)
    ▼
  handleBinaryMessage()
    └─ sink.tryEmitNext(chunk) → STT로 흘려보냄

    │ STT가 최종 인식 완료
    ▼
  handleFinalStt()
    ├─ STT_FINAL 전송     → 브라우저에 인식 결과 표시
    ├─ BOT_THINKING 전송  → "응답 생성 중..." 표시
    ├─ LLM 호출
    ├─ LLM_RESULT 전송    → intent + response 전달
    ├─ TTS 호출
    ├─ TTS_TEXT 전송      → TTS 텍스트 전달
    ├─ 오디오 binary 전송 → 실제 음성 재생
    ├─ startNextSttSession() → 다음 발화 대기
    └─ BOT_READY 전송     → "말씀하세요" 상태

  특징
  - 처리 단계마다 브라우저로 즉시 결과 전송 (비동기, 실시간)
  - 음성 청크를 Sinks.Many<byte[]>로 STT 스트림에 연결 (Reactor)
  - 발화마다 STT 세션을 새로 시작 (startNextSttSession)
  - 세션 종료 시 Sink + Disposable 명시적 정리 (afterConnectionClosed)

  ---
  핵심 구조 차이 — Sink 브리지

  CtiWebSocketHandler에서 가장 중요한 부분입니다.

  // WebSocket 연결 시 Sink 생성
  Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();

  // STT 서비스에 Sink를 Flux로 연결
  sttService.recognize(sink.asFlux(), callId)

  // 음성 청크가 올 때마다 Sink에 밀어넣음
  sink.tryEmitNext(chunk);

  브라우저 청크 → handleBinaryMessage() → sink.tryEmitNext()
                                                 │
                                                 ▼ (Flux로 변환)
                                           STT 서비스

  WebSocket의 콜백 방식과 Reactor의 스트림 방식을 이어주는 다리 역할입니다.
  
>> 그러면 CallHandler은 처음 버전이고  CtiWebSocketHandler 은 추후 Front 만들면서 실시간 음성 처리를 위해서 만드것이고 현재는 이 버전을 사용중인거지?
● 네, 맞습니다.

  히스토리

  1단계: CallHandler (HTTP)
    - 최초 구현
    - 배치/파일 테스트 용도
    - POST /call/incoming 으로 음성 파일 전달

          ↓ CTI Simulator 프론트 개발

  2단계: CtiWebSocketHandler (WebSocket)
    - 실시간 음성 스트리밍 지원
    - 브라우저 마이크 연결
    - ws://localhost:8080/ws/cti

  현재 사용 현황

  ┌───────────────┬───────────────────────────┬────────────────────────────┐
  │               │        CallHandler        │    CtiWebSocketHandler     │
  ├───────────────┼───────────────────────────┼────────────────────────────┤
  │ 현재 사용     │ 테스트/레거시             │ 운영 중                    │
  ├───────────────┼───────────────────────────┼────────────────────────────┤
  │ 진입점        │ POST /call/incoming       │ ws://localhost:8080/ws/cti │
  ├───────────────┼───────────────────────────┼────────────────────────────┤
  │ 클라이언트    │ curl, E2E 테스트 스크립트 │ CTI Simulator (React)      │
  ├───────────────┼───────────────────────────┼────────────────────────────┤
  │ cpp-ws-server │ ❌                        │ ✅ (연결 대상)             │
  └───────────────┴───────────────────────────┴────────────────────────────┘

  중요한 점

  CallHandler는 삭제하지 않고 유지 중입니다. 이유는:
  - 자동화 테스트 (curl로 음성 파일 보내는 E2E 테스트)
  - 운영 중 디버깅 (파일 단위로 특정 발화 재현)

  두 클래스 모두 LlmService 인터페이스를 쓰기 때문에 LLM 파이프라인은 동일합니다. 진입 방식만 다릅니다.

```

### LlmModeRouter 설명해줘. 특히, LlmStrategy strategy = switch (mode.toUpperCase()) {... 이 부분 이해 안 가.
```
● LlmModeRouter 전체 구조

  역할: chat()이 호출되면 환경변수를 보고 3개의 전략 중 하나를 골라서 실행하는 선택기입니다.

  ---
  switch 부분 설명

  LlmStrategy strategy = switch (mode.toUpperCase()) {
      case "INTERNAL" -> internalStrategy;
      case "HYBRID"   -> hybridStrategy;
      default         -> anthropicStrategy;
  };

  mode 는 환경변수 VOICEBOT_LLM_MODE 값입니다. (예: "HYBRID")

  이 switch는 "어떤 전략 객체를 쓸지 선택해서 strategy 변수에 담는다" 는 코드입니다.

  일반 if-else로 바꾸면 똑같습니다:

  LlmStrategy strategy;

  if (mode.toUpperCase().equals("INTERNAL")) {
      strategy = internalStrategy;
  } else if (mode.toUpperCase().equals("HYBRID")) {
      strategy = hybridStrategy;
  } else {
      strategy = anthropicStrategy;   // "ANTHROPIC" 또는 아무거나
  }

  ---
  strategy 변수에 담긴 것

  LlmStrategy는 인터페이스입니다. 3개의 구현체가 있습니다.

  LlmStrategy (인터페이스)
    ├── AnthropicStrategy  → ANTHROPIC 모드 구현체
    ├── InternalStrategy   → INTERNAL 모드 구현체
    └── HybridStrategy     → HYBRID 모드 구현체

  strategy 변수에는 셋 중 하나가 담깁니다. 그리고:

  return strategy.execute(messages, callId);

  이 한 줄로 실행합니다. strategy가 뭐든 상관없이 .execute()만 부르면 됩니다.

  ---
  비유

  mode = "HYBRID"

  switch 결과:
    strategy = hybridStrategy  (HybridStrategy 객체)

  strategy.execute() 호출
    → HybridStrategy.execute() 실행
    → classifyIntent() + findByIntent() + Claude fallback 로직

  mode = "INTERNAL"

  switch 결과:
    strategy = internalStrategy  (InternalStrategy 객체)

  strategy.execute() 호출
    → InternalStrategy.execute() 실행
    → classifyIntent() + findByIntent() + 고정 fallback 로직

  핵심: LlmModeRouter는 전략을 직접 실행하지 않습니다. "누가 실행할지 고르는 것" 만 담당합니다. 실제 로직은 각 Strategy 클래스 안에 있습니다.


```

### 아래의 참조소스 멤버변수는 클래스 생성 시 인스턴스가 자동으로 생성되는건지? 그래서 chat() 내의 swtich 문에서 사용할 수 있는건지?
```
>> 참조 소소
private final AnthropicStrategy anthropicStrategy;
private final InternalStrategy internalStrategy;
private final HybridStrategy hybridStrategy;

● 네, 맞습니다. Spring이 자동으로 주입합니다.

  왜 자동으로 생성되는가

  @RequiredArgsConstructor + @Service 조합 때문입니다.

  @Service                    // ← Spring이 이 클래스를 관리
  @RequiredArgsConstructor    // ← Lombok: final 필드를 생성자 파라미터로 만들어줌
  public class LlmModeRouter implements LlmService {

      private final AnthropicStrategy anthropicStrategy;  // final → 생성자 주입 대상
      private final InternalStrategy internalStrategy;
      private final HybridStrategy hybridStrategy;
  }

  @RequiredArgsConstructor가 아래 생성자를 자동 생성합니다:

  public LlmModeRouter(
      AnthropicStrategy anthropicStrategy,
      InternalStrategy internalStrategy,
      HybridStrategy hybridStrategy
  ) {
      this.anthropicStrategy = anthropicStrategy;
      this.internalStrategy  = internalStrategy;
      this.hybridStrategy    = hybridStrategy;
  }

  ---
  Spring이 하는 일

  앱 기동 시 Spring이 순서대로 처리합니다:

  1. @Service 붙은 클래스를 전부 찾음
     → AnthropicStrategy, InternalStrategy, HybridStrategy, LlmModeRouter ...

  2. 각 클래스의 인스턴스를 생성 (Bean 등록)

  3. LlmModeRouter 생성 시 생성자 파라미터 3개 필요
     → 이미 만들어 둔 AnthropicStrategy, InternalStrategy, HybridStrategy 인스턴스를 꽂아줌

  4. LlmModeRouter 인스턴스 완성

  ---
  그래서 switch에서 쓸 수 있는 이유

  chat()이 호출되는 시점에는 이미 3개 필드가 모두 채워진 상태입니다.

  // chat() 호출 시점
  // anthropicStrategy = AnthropicStrategy 인스턴스 (이미 있음)
  // internalStrategy  = InternalStrategy 인스턴스  (이미 있음)
  // hybridStrategy    = HybridStrategy 인스턴스    (이미 있음)

  LlmStrategy strategy = switch (mode.toUpperCase()) {
      case "INTERNAL" -> internalStrategy;   // 이미 있는 객체를 꺼냄
      case "HYBRID"   -> hybridStrategy;     // 이미 있는 객체를 꺼냄
      default         -> anthropicStrategy;  // 이미 있는 객체를 꺼냄
  };

  새로 만드는 게 아니라 Spring이 앱 시작 때 만들어 놓은 객체를 꺼내서 쓰는 것입니다.

>> @RequiredArgsConstructor 의해서 생성자 자동 만들어지고 그 생성자로 주입이 되는건지?
● 네, 정확합니다.

  흐름

  @RequiredArgsConstructor
          ↓
  Lombok이 생성자 자동 생성
          ↓
  Spring이 그 생성자를 통해 Bean 주입

  ---
  단계별로

  1단계: Lombok이 생성자 만들어줌

  // @RequiredArgsConstructor 가 이 생성자를 자동 생성
  public LlmModeRouter(
      AnthropicStrategy anthropicStrategy,
      InternalStrategy internalStrategy,
      HybridStrategy hybridStrategy
  ) {
      this.anthropicStrategy = anthropicStrategy;
      this.internalStrategy  = internalStrategy;
      this.hybridStrategy    = hybridStrategy;
  }

  2단계: Spring이 그 생성자를 호출

  // Spring이 내부적으로 하는 일
  new LlmModeRouter(
      applicationContext.getBean(AnthropicStrategy.class),
      applicationContext.getBean(InternalStrategy.class),
      applicationContext.getBean(HybridStrategy.class)
  );

  ---
  왜 생성자 주입을 쓰는가

  Spring에서 의존성 주입 방법은 3가지입니다.

  // 방법 1: 생성자 주입 (권장)
  @RequiredArgsConstructor
  private final AnthropicStrategy anthropicStrategy;

  // 방법 2: 필드 주입
  @Autowired
  private AnthropicStrategy anthropicStrategy;

  // 방법 3: Setter 주입
  @Autowired
  public void setStrategy(AnthropicStrategy s) { ... }

  생성자 주입을 권장하는 이유:
  - final로 선언 → 한 번 주입되면 변경 불가 (안전)
  - 객체 생성 시점에 반드시 주입 → null 가능성 없음
  - @RequiredArgsConstructor로 생성자 코드 작성 불필요
```

### ClaudeApiLlmService.java 설명해줘.
```
● 전체 구조

  이 클래스는 Claude API를 직접 호출하는 유일한 클래스입니다. 메서드가 2개입니다.

  ClaudeApiLlmService
    ├── chat()           → "자유롭게 응답해줘" (대화용)
    └── classifyIntent() → "이게 무슨 의도야?" (분류용)

  ---
  필드

  @Value("${voicebot.llm.api-key}")
  private String apiKey;   // ANTHROPIC_API_KEY 환경변수 값

  @Value("${voicebot.llm.model:claude-sonnet-4-6}")
  private String model;    // 기본값: claude-sonnet-4-6

  private final WebClient webClient;      // HTTP 클라이언트
  private final ObjectMapper objectMapper; // JSON 파싱 도구

  ---
  chat() — 자유 응답

  언제 호출: ANTHROPIC 모드 전체, HYBRID/INTERNAL 모드의 fallback 시

  // 1. LlmService.Message → Map 변환 (Claude API 형식으로)
  List<Map<String, String>> messageList = messages.stream()
      .map(m -> Map.of("role", m.role(), "content", m.content()))
      .toList();

  LlmService.Message("user", "배송비?") 를 Claude API가 받는 형식인 {"role":"user","content":"배송비?"} 로 바꿉니다.
  
  ---
  단계별로

  messages = [
      LlmService.Message("user",      "배송비?"),
      LlmService.Message("assistant", "3,000원입니다."),
      LlmService.Message("user",      "언제 와요?")
  ]

  .stream() — 리스트를 하나씩 꺼낼 준비

  .map(m -> Map.of("role", m.role(), "content", m.content())) — 하나씩 변환

  LlmService.Message("user", "배송비?")
          ↓
  {"role": "user", "content": "배송비?"}

  .toList() — 변환된 것들을 다시 리스트로

  messageList = [
      {"role": "user",      "content": "배송비?"},
      {"role": "assistant", "content": "3,000원입니다."},
      {"role": "user",      "content": "언제 와요?"}
  ]

  왜 변환하는가

  LlmService.Message는 우리 코드 내부 형식이고, Claude API는 Map<String, String> 형태의 JSON 형식을 요구하기 때문입니다.

  우리 형식                       Claude API 형식
  LlmService.Message   →   {"role": "...", "content": "..."}

  // 2. Claude API 호출
  Map<?, ?> response = webClient.post()
      .uri("https://api.anthropic.com/v1/messages")
      .header("x-api-key", apiKey)
      .bodyValue(Map.of(
          "model", model,
          "max_tokens", 1024,
          "system", "...(상담원 역할 프롬프트)...",
          "messages", messageList
      ))
      .retrieve()
      .bodyToMono(Map.class)
      .block();   // ← 응답 올 때까지 대기

  // 3. 응답에서 텍스트 꺼내기: 여기서 text 내용은 String 이여서 그대로 리턴.
  List<?> content = (List<?>) response.get("content");
  Map<?, ?> first = (Map<?, ?>) content.get(0);
  return stripCodeBlock((String) first.get("text"));

  Claude API 응답 구조가 이렇게 생겼기 때문입니다:
  {
    "content": [
      { "type": "text", "text": "{\"intent\":\"배송문의\",\"response\":\"...\"}" }
    ]
  }

  content[0].text 를 꺼내는 것입니다.

  ---
  classifyIntent() — 의도 분류

  언제 호출: HYBRID / INTERNAL 모드에서 사용자 발화의 의도를 파악할 때

  chat()과 다른 점 3가지:

  ┌────────────┬────────────────────────┬──────────────────────────────────┐
  │            │         chat()         │         classifyIntent()         │
  ├────────────┼────────────────────────┼──────────────────────────────────┤
  │ 프롬프트   │ 상담원 역할, 자유 응답 │ intent + confidence JSON만       │
  ├────────────┼────────────────────────┼──────────────────────────────────┤
  │ max_tokens │ 1024                   │ 100 (짧은 JSON만 받으면 됨)      │
  ├────────────┼────────────────────────┼──────────────────────────────────┤
  │ 반환값     │ String                 │ IntentResult(intent, confidence) │
  └────────────┴────────────────────────┴──────────────────────────────────┘

  // Claude에게 보내는 프롬프트 (INTENT_SYSTEM_PROMPT)
  "intent는 반드시 다음 중 하나여야 합니다:
  인사 | 배송문의 | 반품환불 | 교환 | 결제 | 회원 | 주문조회 | 상담원연결 | 종료 | 기타
  {"intent": "배송문의", "confidence": 0.95}"

  // 응답 파싱: 여기서 text 내용은 JSON 이기에 objectMapper 으로 JSON 파싱한다.
  rawText = (String) first.get("text");        // '{"intent":"배송문의","confidence":0.95}'
  String text = stripCodeBlock(rawText);        // 백틱 제거
  JsonNode node = objectMapper.readTree(text);  // JSON 파싱
  String intent     = node.path("intent").asText("기타");    // 없으면 기타
  double confidence = node.path("confidence").asDouble(0.5); // 없으면 0.5

  return new IntentResult(intent, confidence);

  실패 시 (파싱 오류, 네트워크 오류 등):
  } catch (Exception e) {
      log.warn("... | raw={}", rawText);           // 원문 로그
      return new IntentResult("기타", 0.0);        // 기본값 반환
  }
  
● text 필드 안에 들어있습니다.

  {
    "content": [
      {
        "type": "text",
        "text": "{\"intent\": \"배송문의\", \"confidence\": 0.95}"
      }
    ]
  }

  즉, "Claude가 만든 실제 답변" 자리에 {"intent": "배송문의", "confidence": 0.95} 가 들어옵니다.
  

  ---
  stripCodeBlock() — 백틱 제거

  private String stripCodeBlock(String text) {
      return text.replaceAll("(?s)```[a-z]*\\s*", "")  // ```json 제거
                 .replaceAll("(?s)```\\s*", "")          // ``` 제거
                 .trim();
  }

  Claude가 가끔 이렇게 반환할 때:
  ```json
  {"intent": "기타", "confidence": 0.3}

  백틱을 제거해서 순수 JSON만 남깁니다.
  
  ---
  정리

  Claude API 응답 구조(content[0].text)는 항상 동일합니다. 안에 들어있는 내용이 다른 겁니다.

  ┌──────────────────┬─────────────────────┬─────────────────────┐
  │      메서드      │    프롬프트 지시    │   text 안의 내용    │
  ├──────────────────┼─────────────────────┼─────────────────────┤
  │ chat()           │ "상담원처럼 답변해" │ intent + response   │
  ├──────────────────┼─────────────────────┼─────────────────────┤
  │ classifyIntent() │ "의도만 분류해"     │ intent + confidence │
  └──────────────────┴─────────────────────┴─────────────────────┘

  Claude가 프롬프트 지시에 따라 다른 형태의 JSON을 만들어 주는 것입니다.  
```

###  PlaybookService.java 설명해줘.
```
● 코드가 아주 짧습니다. 역할도 단순합니다.

  한 줄 요약

  "이 의도에 해당하는 대본이 MongoDB에 있어?" 하고 찾아주는 클래스입니다.

  ---
  코드 설명

  public Optional<IntentPlaybook> findByIntent(String intent) {

  intent = "배송문의", "인사" 같은 의도 문자열을 받습니다.

  Optional<IntentPlaybook> result = repository.findByIntent(intent);

  MongoDB에서 해당 의도의 대본을 찾습니다.

  Optional은 결과가 있을 수도, 없을 수도 있을 때 쓰는 타입입니다.

  // 대본이 있으면
  result.isPresent() → true
  result.get()       → IntentPlaybook 객체

  // 대본이 없으면
  result.isPresent() → false

  log.info("[PLAYBOOK] intent={} hit={} elapsed={}ms", ...);

  찾았는지(hit=true/false)와 걸린 시간을 로그로 남깁니다.

  ---
  흐름

  classifyIntent() 결과: "배송문의"
          │
          ▼
  findByIntent("배송문의")
          │
          ▼
  MongoDB 조회
          │
          ├─ 있으면 → Optional(IntentPlaybook) 반환
          └─ 없으면 → Optional.empty() 반환

  ---
  이 클래스가 필요한 이유

  repository.findByIntent()를 직접 쓰면 되는데 왜 서비스 클래스를 만들었는가 — 로그 측정 때문입니다.

  long start = System.currentTimeMillis();
  // ... 조회 ...
  log.info("elapsed={}ms", System.currentTimeMillis() - start);

  MongoDB 조회 시간을 [PLAYBOOK] 태그로 기록합니다. 성능 모니터링용입니다.

>> 관련된 IntentPlaybookRepository.java, IntentPlaybook.java MongoRepository<IntentPlaybook, String> 등 연관해서 좀 더 자세히 말해줘.
● 전체 흐름

  PlaybookService
      │ findByIntent("배송문의") 호출
      ▼
  IntentPlaybookRepository
      │ MongoDB 쿼리 실행
      ▼
  MongoDB (intent_playbook 컬렉션)
      │ 문서 반환
      ▼
  IntentPlaybook 객체

  ---
  IntentPlaybook.java — MongoDB 문서 모양

  @Document(collection = "intent_playbook")  // MongoDB 컬렉션 이름
  public class IntentPlaybook {
      @Id
      private String id;                  // MongoDB 문서 고유 ID
      private String intent;              // 조회 키 ("배송문의")
      private String response;            // 응답 텍스트 (사용자에게 줄 말)
      private String action;              // 액션 타입 (로그용)
      private boolean escalate;           // 상담원 연결 여부
      private double confidenceThreshold; // 현재 미사용
  }

  MongoDB에 저장된 실제 문서 모양:

  {
    "_id": "배송문의",
    "intent": "배송문의",
    "response": "배송 관련 안내드립니다. 일반 배송은 2~3 영업일...",
    "action": "provide_info",
    "escalate": false,
    "confidenceThreshold": 0.7
  }

  @Document가 "이 클래스는 MongoDB의 intent_playbook 컬렉션과 연결된다" 고 선언하는 어노테이션입니다.

  ---
  IntentPlaybookRepository.java — 조회 담당

  public interface IntentPlaybookRepository extends MongoRepository<IntentPlaybook, String> {
      Optional<IntentPlaybook> findByIntent(String intent);
  }

  MongoRepository<IntentPlaybook, String>

  MongoRepository<어떤 문서를, ID 타입은>
                    │                │
                IntentPlaybook      String
             (위에서 만든 클래스)   (@Id 필드 타입)

  MongoRepository를 상속받으면 아래 기능이 자동으로 생깁니다.

  repository.findAll()        // 전체 조회
  repository.findById("배송문의") // ID로 조회
  repository.save(playbook)   // 저장
  repository.delete(playbook) // 삭제

  코드 한 줄도 안 짜도 Spring Data가 구현체를 자동 생성합니다.

  findByIntent(String intent)

  Optional<IntentPlaybook> findByIntent(String intent);

  이것만 직접 선언했습니다. 메서드 이름만 보고 Spring이 자동으로 쿼리를 만들어줍니다.

  findByIntent("배송문의")
      ↓
  db.intent_playbook.findOne({ intent: "배송문의" })

  findBy + 필드명 규칙을 따르면 SQL/쿼리를 직접 안 짜도 됩니다.

  ---
  전체 연결 정리

  MongoDB 컬렉션 (intent_playbook)
      ↕ 매핑
  IntentPlaybook.java          @Document(collection="intent_playbook")
      ↕ 사용
  IntentPlaybookRepository     MongoRepository 상속 → 자동 CRUD
      ↕ 사용
  PlaybookService              findByIntent() 호출 + 로그 측정
      ↕ 사용
  HybridStrategy / InternalStrategy
  
>> IntentPlaybook 구현 부분이 JPA 인지?
● 아닙니다. Spring Data MongoDB입니다.

  JPA vs Spring Data MongoDB 비교

  ┌────────────────────┬─────────────────────────────────┬─────────────────────────────┐
  │                    │               JPA               │     Spring Data MongoDB     │
  ├────────────────────┼─────────────────────────────────┼─────────────────────────────┤
  │ 대상 DB            │ 관계형 DB (MariaDB, PostgreSQL) │ MongoDB                     │
  ├────────────────────┼─────────────────────────────────┼─────────────────────────────┤
  │ 클래스 선언        │ @Entity                         │ @Document                   │
  ├────────────────────┼─────────────────────────────────┼─────────────────────────────┤
  │ 컬렉션/테이블 지정 │ @Table(name="...")              │ @Document(collection="...") │
  ├────────────────────┼─────────────────────────────────┼─────────────────────────────┤
  │ ID                 │ @Id (jakarta.persistence)       │ @Id (springframework.data)  │
  ├────────────────────┼─────────────────────────────────┼─────────────────────────────┤
  │ Repository         │ JpaRepository                   │ MongoRepository             │
  └────────────────────┴─────────────────────────────────┴─────────────────────────────┘

  ---
  이 프로젝트에서 둘 다 씁니다

  MariaDB  ← JPA
    └── CallRecord.java          @Entity
    └── CallRecordRepository     JpaRepository

  MongoDB  ← Spring Data MongoDB
    └── IntentPlaybook.java      @Document
    └── IntentPlaybookRepository MongoRepository

  @Document는 MongoDB 전용 어노테이션입니다. import를 보면 바로 구분할 수 있습니다.

  // JPA
  import jakarta.persistence.Entity;
  import jakarta.persistence.Table;

  // Spring Data MongoDB
  import org.springframework.data.mongodb.core.mapping.Document;
  
>> repository.findById("배송문의") 와 findByIntent("배송문의") 와 db.intent_playbook.findOne({ intent: "배송문의" }) 관계를 좀 더 쉽게 설명해줘.
● findById vs findByIntent

  // findById — @Id 필드로 찾기
  repository.findById("배송문의")
  → db.intent_playbook.findOne({ _id: "배송문의" })

  // findByIntent — intent 필드로 찾기
  repository.findByIntent("배송문의")
  → db.intent_playbook.findOne({ intent: "배송문의" })

  찾는 필드가 다릅니다.

  ---
  MongoDB 문서 구조로 보면

  {
    "_id": "배송문의",       ← findById가 찾는 필드
    "intent": "배송문의",   ← findByIntent가 찾는 필드
    "response": "배송 관련 안내드립니다...",
    "action": "provide_info"
  }

  이 문서에서는 _id와 intent 값이 우연히 같아서 결과가 동일하게 나오지만, 찾는 위치가 다릅니다.

  ---
  Spring이 자동으로 쿼리 만드는 규칙

  findBy + 필드명
     ↓
  해당 필드로 MongoDB 조회

  findByIntent  → { intent: "..." }
  findByAction  → { action: "..." }
  findByEscalate → { escalate: true }

  메서드 이름만 규칙에 맞게 선언하면 Spring이 쿼리를 자동으로 만들어줍니다. 쿼리를 직접 짤 필요가 없습니다.

```

### HybridStrategy.java 설명해줘.
```
● 한 줄 요약

  대본 있으면 대본, 없으면 Claude — 이 판단을 하는 클래스입니다.

  ---
  execute() 흐름

  1단계: 마지막 사용자 발화 추출

  String userText = extractLastUserText(messages);

  대화 이력에서 사용자가 가장 마지막에 한 말만 꺼냅니다.

  messages = [
    user:      "안녕하세요"
    assistant: "안녕하세요!"
    user:      "배송비 얼마예요?"  ← 이것만 꺼냄
  ]
  userText = "배송비 얼마예요?"

  의도 분류는 전체 대화가 아니라 지금 한 말만 보면 되기 때문입니다.

  ---
  2단계: 의도 분류

  IntentResult intentResult = claudeApiLlmService.classifyIntent(userText, callId);
  // → IntentResult(intent="배송문의", confidence=0.95)

  ---
  3단계: 대본 조회

  Optional<IntentPlaybook> playbookOpt = playbookService.findByIntent("배송문의");

  ---
  4단계: 분기

  if (playbookOpt.isPresent() && intentResult.confidence() >= confidenceThreshold) {

  조건 2개를 동시에 만족해야 대본 응답합니다.

  조건 1: 대본이 MongoDB에 존재하는가?
  조건 2: 신뢰도가 0.7 이상인가?

  둘 다 YES → 대본 응답
  하나라도 NO → Claude fallback

  ---
  대본 응답 경로

  String escaped = playbook.getResponse()
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n");
  return "{\"intent\":\"배송문의\",\"response\":\"" + escaped + "\"}";

  escaped는 응답 텍스트 안의 특수문자를 JSON에서 깨지지 않게 처리하는 것입니다.

  원문:  배송비는 "무료"입니다.
  escaped: 배송비는 \"무료\"입니다.

  최종 반환:
  {"intent":"배송문의","response":"배송비는 \"무료\"입니다."}

  ---
  Claude fallback 경로

  return claudeApiLlmService.chat(messages, callId);

  이때는 마지막 발화만이 아니라 대화 전체 이력을 Claude에게 넘깁니다. 문맥을 보고 자연스럽게 답변하도록 하기 위해서입니다.

  ---
  extractLastUserText() 설명

  messages.stream()
      .filter(m -> "user".equals(m.role()))  // user 메시지만
      .reduce((a, b) -> b)                   // 마지막 것만 남김
      .map(LlmService.Message::content)      // content 꺼냄
      .orElse("");                           // 없으면 빈 문자열

  .reduce((a, b) -> b) 는 "둘 중 뒤에 있는 걸 남겨라" 를 반복해서 최종적으로 마지막 하나만 남깁니다.

  user 메시지 목록: ["안녕하세요", "배송비?", "언제 와요?"]

  reduce:
    ("안녕하세요", "배송비?") → "배송비?"
    ("배송비?", "언제 와요?") → "언제 와요?"

  결과: "언제 와요?"
  
>> .reduce() 기능에 대해서 좀 더 알려줘.
● reduce 기본 개념

  리스트를 하나의 값으로 줄이는 연산입니다.

  .reduce((a, b) -> b)

  a = 이전 값, b = 현재 값, 결과로 b를 남깁니다.

  ---
  단계별로

  리스트: ["안녕", "배송비?", "언제 와요?"]

  1회: a="안녕"    b="배송비?"   → b 선택 → "배송비?"
  2회: a="배송비?" b="언제 와요?" → b 선택 → "언제 와요?"

  최종 결과: "언제 와요?"

  항상 뒤에 있는 걸 선택하므로 결국 마지막 값이 남습니다.

  ---
  다른 예시로 이해

  // 합계 구하기
  [1, 2, 3, 4].stream()
      .reduce((a, b) -> a + b)
  // 1회: a=1, b=2 → 3
  // 2회: a=3, b=3 → 6
  // 3회: a=6, b=4 → 10
  // 결과: 10

  // 최댓값 구하기
  [3, 1, 5, 2].stream()
      .reduce((a, b) -> a > b ? a : b)
  // 1회: a=3, b=1 → 3
  // 2회: a=3, b=5 → 5
  // 3회: a=5, b=2 → 5
  // 결과: 5

  // 마지막 값 구하기
  ["안녕", "배송비?", "언제 와요?"].stream()
      .reduce((a, b) -> b)
  // 1회: a="안녕", b="배송비?" → "배송비?"
  // 2회: a="배송비?", b="언제 와요?" → "언제 와요?"
  // 결과: "언제 와요?"

  ---
  왜 reduce를 썼는가

  마지막 user 메시지를 꺼내는 더 단순한 방법도 있습니다.

  // 방법 1: reduce 사용
  messages.stream()
      .filter(m -> "user".equals(m.role()))
      .reduce((a, b) -> b)
      .map(LlmService.Message::content)
      .orElse("");

  // 방법 2: List로 변환 후 마지막 꺼내기
  List<Message> userMessages = messages.stream()
      .filter(m -> "user".equals(m.role()))
      .toList();
  userMessages.get(userMessages.size() - 1).content();

  방법 1이 스트림을 끊지 않고 한 줄로 처리할 수 있어서 사용했습니다.

>> \\ > \\\\, \" > \\\", \n > \\n 뭐 이런 걸 설명해줘야
● 맞습니다. Java 코드 안에서의 표현으로 설명해야 합니다.

  Java 코드에서 문자열 표현 규칙

  Java 코드에서 \는 항상 2개로 써야 실제 \ 1개입니다.

  Java 코드     실제 문자
  "\\"      →   \
  "\\\\"    →   \\
  "\""      →   "
  "\\\""    →   \"
  "\\n"     →   \n
  "\n"      →   줄바꿈(엔터)

  ---
  각 replace 설명

  .replace("\", "\\")

  Java코드  "찾는것"   →  실제문자
  "\\"           →   \

  Java코드  "바꿀것"   →  실제문자
  "\\\\"         →   \\

  결과: 실제문자 \ 를 실제문자 \\ 로 교체

  ---
  .replace(""", "\"")

  Java코드  "찾는것"   →  실제문자
  "\""           →   "

  Java코드  "바꿀것"   →  실제문자
  "\\\""         →   \"

  결과: 실제문자 " 를 실제문자 \" 로 교체

  ---
  .replace("\n", "\n")

  Java코드  "찾는것"   →  실제문자
  "\n"           →   줄바꿈(엔터)

  Java코드  "바꿀것"   →  실제문자
  "\\n"          →   \n (두 글자)

  결과: 실제 줄바꿈을 \n 두 글자로 교체

  ---
  한눈에 정리

  ┌────────────────────┬───────────┬────────────────────┬───────────┐
  │ Java 코드 (찾는것) │ 실제 문자 │ Java 코드 (바꿀것) │ 실제 문자 │
  ├────────────────────┼───────────┼────────────────────┼───────────┤
  │ "\\"               │ \         │ "\\\\"             │ \\        │
  ├────────────────────┼───────────┼────────────────────┼───────────┤
  │ "\""               │ "         │ "\\\""             │ \"        │
  ├────────────────────┼───────────┼────────────────────┼───────────┤
  │ "\n"               │ 줄바꿈    │ "\\n"              │ \n        │
  └────────────────────┴───────────┴────────────────────┴───────────┘

```

### AnthropicStrategy.java 설명해줘.
```
● AnthropicStrategy — 가장 단순

  public String execute(List<LlmService.Message> messages, String callId) {
      return claudeApiLlmService.chat(messages, callId);
  }

  코드 한 줄입니다. 그냥 Claude에게 전부 넘깁니다.

  사용자 발화
      ↓
  claudeApiLlmService.chat()
      ↓
  Claude 답변 반환

  의도 분류도 없고, 대본 조회도 없습니다. Claude가 알아서 다 합니다.

```

### InternalStrategy.java 설명해줘.
```
  ---
  InternalStrategy — HYBRID와 거의 같지만 fallback이 다름

  흐름은 HybridStrategy와 동일합니다.

  1. 마지막 사용자 발화 추출
  2. Claude로 의도 분류
  3. MongoDB 대본 조회
  4. 분기

  차이점은 fallback 처리 하나입니다.

  ┌──────────────────────────┬────────────────────┬──────────────────┐
  │                          │   HybridStrategy   │ InternalStrategy │
  ├──────────────────────────┼────────────────────┼──────────────────┤
  │ 대본 있고 신뢰도 >= 0.7  │ 대본 응답          │ 대본 응답        │
  ├──────────────────────────┼────────────────────┼──────────────────┤
  │ 대본 없거나 신뢰도 < 0.7 │ Claude chat() 호출 │ 고정 문자열 반환 │
  └──────────────────────────┴────────────────────┴──────────────────┘

  // HybridStrategy fallback
  return claudeApiLlmService.chat(messages, callId);  // Claude 호출

  // InternalStrategy fallback
  return toJson(intentResult.intent(), fallbackResponse);  // 고정 문자열

  fallbackResponse 는 설정값입니다.

  voicebot:
    llm:
      internal:
        fallback-response: "죄송합니다. 잠시 후 상담원을 연결해드리겠습니다."

  ---
  InternalStrategy 전용 메서드

  toJson() — HybridStrategy에서 직접 문자열 조합하던 것을 메서드로 분리

  private String toJson(String intent, String response) {
      String escaped = response.replace(...);
      return "{\"intent\":\"" + intent + "\",\"response\":\"" + escaped + "\"}";
  }

  대본 응답, fallback 응답 둘 다 같은 JSON 형식으로 만들어야 하므로 중복을 없애기 위해 메서드로 뺐습니다.

  ---
  3개 Strategy 최종 비교

  AnthropicStrategy
    → Claude chat() 바로 호출

  InternalStrategy
    → 의도 분류 → 대본 있으면 대본
                  대본 없으면 "죄송합니다..." (고정)

  HybridStrategy
    → 의도 분류 → 대본 있으면 대본
                  대본 없으면 Claude chat() 호출
```

### 참조 소스1 설명해줘. 그리고 왜 참조 소스2 되는지?
```
>> 참조 소스1
public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {
>> 참조 소스2
callRecordRepository.save(CallRecord.builder()
                  .callId(callId)
                  .sttText(sttText)
                  .llmResponse(llmResponse)
                  .sttElapsedMs(sttElapsed)
                  .llmElapsedMs(llmElapsed)
                  .ttsElapsedMs(ttsElapsed)
                  .totalElapsedMs(totalElapsed)
                  .build());

● CallRecordRepository 설명

  public interface CallRecordRepository extends JpaRepository<CallRecord, Long>

  ---
  extends JpaRepository 의미

  JpaRepository를 상속받으면 구현 없이 DB 조작 메서드를 바로 사용할 수 있습니다.

  // 내가 작성한 것: 선언만
  public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {
      List<CallRecord> findByCallId(String callId);
  }

  // JpaRepository가 이미 가지고 있는 것들 (자동 제공)
  save(entity)           // INSERT / UPDATE
  findById(id)           // SELECT WHERE id = ?
  findAll()              // SELECT *
  deleteById(id)         // DELETE WHERE id = ?
  count()                // SELECT COUNT(*)

  직접 SQL 한 줄도 안 써도 됩니다.

  ---
  <CallRecord, Long> 의미

  JpaRepository<CallRecord, Long>
                │           │
                │           └─ PK(id) 타입: Long
                └─ 관리할 엔티티 클래스: CallRecord

  "CallRecord 테이블을 다루고, PK는 Long 타입" 이라고 알려주는 것입니다.

  ---
  왜 .save()가 되는가

  callRecordRepository.save(
      CallRecord.builder()
          .callId(callId)
          .sttText(sttText)
          ...
          .build()         // ← CallRecord 객체 완성
  );

  흐름

  CallRecord.builder()...build()
      ↓
  CallRecord 객체 생성
      ↓
  callRecordRepository.save(callRecord)
      ↓
  JpaRepository가 자동으로 SQL 실행
      ↓
  INSERT INTO call_records (call_id, stt_text, ...) VALUES (?, ?, ...)

  ---
  전체 연결 구조

  CallRecordRepository      ← 내가 선언한 인터페이스
      extends
  JpaRepository             ← Spring이 제공하는 인터페이스
      ↑
  SimpleJpaRepository       ← Spring이 런타임에 자동 생성하는 구현체
                               (내가 작성하지 않아도 됨)

  @Autowired (또는 @RequiredArgsConstructor)로 주입받으면
  Spring이 SimpleJpaRepository 구현체를 자동으로 연결해줍니다.

  ---
  한 줄 요약

  ▎ JpaRepository를 상속하면 선언만으로 DB 조작 메서드를 무료로 얻는다.
  ▎ .save()는 그 중 하나이고, CallRecord.builder().build()로 만든 객체를 넘기면 INSERT가 실행된다.

>> 아래와 함수 설명과 연관된 클래스들(CallSession 등)을 설명해줘.
❯     private CallSession getOrCreateSession(String callId) {
          Object stored = redisTemplate.opsForValue().get(SESSION_PREFIX + callId);
          if (stored instanceof CallSession session) {
              return session;
          }
          return CallSession.builder()
                  .callId(callId)
                  .state("ACTIVE")
                  .startedAt(System.currentTimeMillis())
                  .build();
      }

● CallSession 클래스

  @Data                  // getter/setter 자동 생성
  @Builder               // builder() 패턴 제공
  @NoArgsConstructor     // 빈 생성자 (Redis 역직렬화에 필요)
  @AllArgsConstructor    // 전체 필드 생성자
  public class CallSession implements Serializable {

      private String callId;                          // 콜 식별자

      @Builder.Default
      private List<Map<String, String>> messages      // 대화 이력
          = new ArrayList<>();                        // 기본값: 빈 리스트

      private String state;                           // 상태 (ACTIVE 등)
      private long startedAt;                         // 통화 시작 시각 (ms)
  }

  implements Serializable — Redis에 저장할 때 객체를 바이트로 변환(직렬화)해야 하므로 필수입니다.

  @Builder.Default — @Builder는 기본적으로 필드 초기화를 무시합니다. @Builder.Default를 붙여야 new ArrayList()가 유지됩니다.

  ---
  Redis 구조

  Redis 키                       값
  ─────────────────────────────────────────
  call:session:CALL-001    →   CallSession 객체
  call:session:CALL-002    →   CallSession 객체
  call:session:CALL-003    →   CallSession 객체

  키 = SESSION_PREFIX("call:session:") + callId

  ---
  getOrCreateSession() 흐름

  private CallSession getOrCreateSession(String callId) {

      // Redis에서 조회
      Object stored = redisTemplate.opsForValue().get("call:session:" + callId);

      // 있으면 꺼내서 반환
      if (stored instanceof CallSession session) {
          return session;
      }

      // 없으면 새로 만들어서 반환
      return CallSession.builder()
              .callId(callId)
              .state("ACTIVE")
              .startedAt(System.currentTimeMillis())
              .build();
  }

  첫 번째 전화
    getOrCreateSession("CALL-001")
      Redis 조회 → 없음
      → 새 CallSession 생성 (messages = 빈 리스트)

  두 번째 발화 (같은 통화)
    getOrCreateSession("CALL-001")
      Redis 조회 → 있음 (이전 대화 이력 포함)
      → 저장된 CallSession 반환

  ---
  process() 전체 흐름과 세션 역할

  // 1. STT: 음성 → 텍스트
  String sttText = sttService.recognize(...);

  // 2. 세션 꺼내기 + 사용자 발화 추가
  CallSession session = getOrCreateSession(callId);
  session.getMessages().add(Map.of("role", "user", "content", sttText));
  //  messages = [
  //    {"role":"user", "content":"배송 어디까지 왔어요?"}  ← 방금 추가
  //  ]

  // 3. LLM: 전체 대화 이력을 넘겨서 응답 생성
  String llmResponse = llmService.chat(toLlmMessages(session.getMessages()), callId);

  // 4. 어시스턴트 응답도 이력에 추가 + Redis 저장
  session.getMessages().add(Map.of("role", "assistant", "content", llmResponse));
  saveSession(callId, session);
  //  messages = [
  //    {"role":"user",      "content":"배송 어디까지 왔어요?"},
  //    {"role":"assistant", "content":"확인해드리겠습니다."}  ← 방금 추가
  //  ]

  다음 발화 때 getOrCreateSession()이 이 이력을 꺼내서 LLM에 전달하므로 대화 맥락이 유지됩니다.

  ---
  한 줄 요약

  ▎ CallSession은 한 통화의 대화 이력을 담는 객체이고, Redis에 저장해서 발화마다 꺼내 쓴다. TTL 1시간이 지나면 자동 삭제된다.

>> `stored instanceof CallSession session` 이 문법 설명해줘.
● instanceof 패턴 매칭 (Java 16+)

  ---
  기존 방식 (Java 15 이하)

  Object stored = redisTemplate.opsForValue().get(...);

  // 1단계: 타입 확인
  if (stored instanceof CallSession) {
      // 2단계: 캐스팅 (별도로 해야 함)
      CallSession session = (CallSession) stored;
      return session;
  }

  ---
  새 방식 (Java 16+, 패턴 매칭)

  if (stored instanceof CallSession session) {
      //                              └─ 타입 확인 + 캐스팅 + 변수 선언 한 번에
      return session;   // 바로 사용 가능
  }

  확인 + 캐스팅 + 변수 선언을 한 줄로 처리합니다.

  ---
  동작 원리

  stored instanceof CallSession session
      │                   │         │
      │                   │         └─ 캐스팅 결과를 담을 변수 이름
      │                   └─ 이 타입인지 확인
      └─ 확인할 대상

  stored가 CallSession 타입이면?
    → true + session 변수에 캐스팅해서 담기
  stored가 null이거나 다른 타입이면?
    → false + session 변수 없음

  ---
  왜 필요한가

  Redis는 Object 타입으로 반환합니다. 실제로 안에 뭐가 들었는지 꺼내보기 전까지 모릅니다.

  Object stored = redisTemplate.opsForValue().get(...);
  //  └─ Object: CallSession일 수도, null일 수도, 다른 뭔가일 수도 있음

  그래서 타입을 확인한 후 안전하게 꺼내는 과정이 필요합니다.

  ---
  한 줄 요약

  ▎ stored instanceof CallSession session = "stored가 CallSession이면 session 변수에 담아줘" — 확인과 캐스팅을 동시에 처리하는 Java 16 문법.

>> `@Builder.Default` 왜 필요한지 더 설명해줘.
● @Builder.Default 왜 필요한가

  ---
  문제 상황

  @Builder
  public class CallSession {
      private List<Map<String, String>> messages = new ArrayList<>();  // 기본값 설정
  }

  이렇게 쓰면 기본값이 무시됩니다.

  CallSession session = CallSession.builder()
          .callId("CALL-001")
          .build();

  session.getMessages();  // null  ← new ArrayList() 가 적용 안 됨
  session.getMessages().add(...)  // NullPointerException 발생!

  ---
  왜 무시되는가

  @Builder가 생성하는 코드를 보면:

  // Lombok이 자동 생성하는 Builder 내부 클래스
  public static class CallSessionBuilder {
      private String callId;
      private List<Map<String, String>> messages;  // ← 초기값 없음 (null)
      //  원본 클래스의 = new ArrayList() 를 여기서 복사하지 않음

      public CallSession build() {
          return new CallSession(callId, messages);  // null 그대로 전달
      }
  }

  Builder는 원본 클래스의 필드 초기화(= new ArrayList())를 무시하고 자기 필드를 새로 만들기 때문입니다.

  ---
  @Builder.Default 해결

  @Builder
  public class CallSession {
      @Builder.Default
      private List<Map<String, String>> messages = new ArrayList<>();
  }

  Lombok이 이렇게 생성합니다:

  public static class CallSessionBuilder {
      private List<Map<String, String>> messages = new ArrayList<>();  // ← 초기값 포함
  }

  CallSession session = CallSession.builder()
          .callId("CALL-001")
          .build();

  session.getMessages();  // []  ← 빈 리스트 정상 반환
  session.getMessages().add(Map.of("role", "user", "content", "안녕"));  // 정상 동작

  ---
  한 줄 요약

  ▎ @Builder는 원본 필드 초기화를 무시한다. @Builder.Default를 붙여야 = new ArrayList()가 Builder에도 적용된다.

>> ❯ @Builder.Default — @Builder는 기본적으로 필드 초기화를 무시합니다. @Builder.Default를 붙여야 new ArrayList()가 유지됩니다. => 좀 전 말한 것과 연관되는건데. 이해가 안 돼! 좀 더 쉽게 설명해줘.

● 핵심 원인부터

  @Builder는 원본 클래스를 직접 생성하지 않습니다.
  중간에 Builder라는 별도 클래스를 거칩니다.

  ---
  두 클래스가 존재한다

  // 1. 원본 클래스 (내가 작성)
  public class CallSession {
      private List<Map<String, String>> messages = new ArrayList<>();  // 초기값
  }

  // 2. Builder 클래스 (Lombok이 자동 생성)
  public class CallSessionBuilder {
      private List<Map<String, String>> messages;  // 초기값 없음 → null
  }

  @Builder가 Builder 클래스를 새로 만들 때 원본의 초기값을 복사하지 않습니다.

  ---
  객체가 만들어지는 경로

  @Builder 없을 때:
    new CallSession()
        ↓
    원본 클래스 생성 → messages = new ArrayList() 적용됨 ✅

  @Builder 있을 때:
    CallSession.builder().build()
        ↓
    CallSessionBuilder 생성 → messages = null (초기값 무시)
        ↓
    build() → CallSession 생성 → messages = null 그대로 전달 ❌

  원본 클래스를 거치지 않고 Builder가 직접 값을 넣어주기 때문에
  원본의 = new ArrayList()가 실행될 기회가 없습니다.

  ---
  @Builder.Default 해결

  @Builder
  public class CallSession {
      @Builder.Default
      private List<Map<String, String>> messages = new ArrayList<>();
  }

  @Builder.Default를 붙이면 Lombok이 Builder 클래스에도 초기값을 넣어줍니다.

  CallSessionBuilder 생성 → messages = new ArrayList() ✅
      ↓
  build() → CallSession 생성 → messages = [] 전달 ✅

  ---
  한 줄 요약

  ▎ @Builder는 원본을 건너뛰고 Builder 클래스를 통해 객체를 만든다.
  ▎ 원본의 초기값은 Builder 클래스에 없으므로 @Builder.Default로 명시해야 한다.


>> `@Data` 설명해줘.
● @Data 설명

  Lombok이 제공하는 어노테이션으로, 자주 쓰는 메서드 5종을 한 번에 자동 생성합니다.

  ---
  @Data = 아래 5개를 한 번에

  @Data
  public class CallSession {
      private String callId;
      private String state;
  }

  Lombok이 자동으로 만들어주는 것:

  // 1. @Getter — 모든 필드의 getter
  public String getCallId() { return callId; }
  public String getState()  { return state; }

  // 2. @Setter — 모든 필드의 setter
  public void setCallId(String callId) { this.callId = callId; }
  public void setState(String state)   { this.state = state; }

  // 3. @ToString — 로그 찍을 때 유용
  public String toString() {
      return "CallSession(callId=CALL-001, state=ACTIVE)";
  }

  // 4. @EqualsAndHashCode — 두 객체가 같은지 비교
  public boolean equals(Object o) { ... }  // callId, state 값으로 비교
  public int hashCode()           { ... }

  // 5. @RequiredArgsConstructor — final 필드 생성자
  public CallSession() { ... }

  ---
  왜 쓰는가

  // @Data 없을 때: 직접 작성해야 함 (반복 코드 수십 줄)
  public String getCallId() { return callId; }
  public void setCallId(String callId) { this.callId = callId; }
  public String getState() { return state; }
  public void setState(String state) { this.state = state; }
  public String toString() { ... }
  public boolean equals(Object o) { ... }
  public int hashCode() { ... }

  // @Data 있을 때: 한 줄로 끝
  @Data
  public class CallSession { ... }

  ---
  CallSession에서 실제로 쓰이는 곳

  // getter — 대화 이력 꺼낼 때
  session.getMessages().add(Map.of("role", "user", "content", sttText));

  // setter — Redis에서 꺼낸 후 값 변경할 때
  session.setState("ENDED");

  // toString — 로그 찍을 때
  log.debug("session={}", session);
  // → session=CallSession(callId=CALL-001, state=ACTIVE, messages=[...])

  ---
  한 줄 요약

  ▎ @Data = getter + setter + toString + equals + hashCode 를 한 번에 자동 생성.

```

### CtiRestController 설명해줘.
```
● CtiRestController 설명

  cpp-ws-server가 Spring Boot를 호출할 때 쓰는 REST API 창구입니다.

  ---
  역할

  cpp-ws-server (C++)
      │
      ├─ POST /api/cti/llm/chat       → LLM 응답 요청
      └─ POST /api/cti/tts/synthesize → TTS 음성 요청

  cpp-ws-server는 WebSocket으로 음성을 받고 STT는 자체 처리합니다.
  STT 결과 텍스트를 LLM/TTS에 넘길 때 이 컨트롤러를 통해 Spring Boot에 요청합니다.

  ---
  각 엔드포인트

  POST /api/cti/llm/chat

  @PostMapping("/llm/chat")
  public String chat(@RequestBody List<LlmService.Message> messages) {
      return llmService.chat(messages, "CPP");
  }

  요청 (JSON)
  [
    {"role": "user",      "content": "배송 어디까지 왔어요?"},
    {"role": "assistant", "content": "확인해드리겠습니다."},
    {"role": "user",      "content": "빨리 알려주세요"}
  ]

  응답 (String)
  {"intent": "배송문의", "response": "지금 바로 확인해드리겠습니다."}

  callId를 "CPP"로 고정 — cpp-ws-server에서 오는 요청임을 구분하는 표시.

  ---
  POST /api/cti/tts/synthesize

  @PostMapping("/tts/synthesize")
  public byte[] synthesize(@RequestBody String text) {
      return ttsService.synthesize(text, "CPP");
  }

  요청 (String)
  "지금 바로 확인해드리겠습니다."

  응답 (byte[])
  음성 오디오 바이너리 데이터

  ---
  전체 흐름에서 위치

  전화 수신
      ↓
  cpp-ws-server  ── WebSocket ──▶ 음성 수신
      │
      ├─ STT (자체 처리, RTZR)
      │
      ├─ POST /api/cti/llm/chat  ──▶ CtiRestController ──▶ LlmService
      │                                                      ↓
      │                                                   LLM 응답
      │
      └─ POST /api/cti/tts/synthesize ──▶ CtiRestController ──▶ TtsService
                                                                 ↓
                                                             음성 바이너리

  ---
  CallController vs CtiRestController

  ┌────────────┬─────────────────────┬──────────────────────┐
  │            │   CallController    │  CtiRestController   │
  ├────────────┼─────────────────────┼──────────────────────┤
  │ 엔드포인트 │ POST /call/incoming │ POST /api/cti/*      │
  ├────────────┼─────────────────────┼──────────────────────┤
  │ 호출자     │ 브라우저/테스트     │ cpp-ws-server        │
  ├────────────┼─────────────────────┼──────────────────────┤
  │ STT        │ Spring이 처리       │ cpp-ws-server가 처리 │
  ├────────────┼─────────────────────┼──────────────────────┤
  │ 대화 이력  │ Redis 세션 관리     │ cpp-ws-server가 관리 │
  └────────────┴─────────────────────┴──────────────────────┘

  ---
  한 줄 요약

  ▎ cpp-ws-server가 LLM/TTS를 쓸 수 있도록 열어둔 REST 창구. STT는 cpp-ws-server가 직접 처리하고 나머지만 Spring에 위임한다.

```

### CtiWebSocketHandler 설명해줘. => 함수별로 설명 필요.
```
● CtiWebSocketHandler 설명

  브라우저(또는 cpp-ws-server)와 WebSocket으로 연결해서 실시간 음성 파이프라인을 처리하는 핸들러입니다.

  ---
  4개의 Map — 세션별 상태 관리

  // WebSocket 세션 1개 = 전화 통화 1건
  // 세션마다 독립적으로 관리

  Map<String, Sinks.Many<byte[]>>      sinkMap;       // 음성 청크 받는 통로
  Map<String, List<LlmService.Message>> historyMap;   // 대화 이력
  Map<String, String>                   callIdMap;    // callId (CTI-XXXXXXXX)
  Map<String, Disposable>               disposableMap; // STT 구독 (취소용)

  ---
  생명주기 — 4단계

  1. 연결됨 afterConnectionEstablished

  // callId 발급
  String callId = "CTI-" + UUID.randomUUID()...;

  // Sink 생성 — 음성 청크를 흘려보낼 파이프
  Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();

  // STT 구독 시작
  sttService.recognize(sink.asFlux(), callId)
      .filter(SttResult::isFinal)       // 최종 결과만
      .timeout(Duration.ofSeconds(60))  // 60초 무응답 시 종료
      .subscribe(result -> handleFinalStt(...));

  ---
  2. 음성 청크 수신 handleBinaryMessage

  // 브라우저가 250ms마다 보내는 음성 데이터
  byte[] chunk = message.getPayload().array();
  sink.tryEmitNext(chunk);  // Sink로 흘려보냄 → STT가 받아서 처리

  브라우저 마이크
    → binary 전송 (250ms 간격)
      → handleBinaryMessage()
        → sink.tryEmitNext(chunk)
          → SttService.recognize(flux)
            → RTZR STT

  ---
  3. STT 최종 결과 handleFinalStt

  STT 완료 → 브라우저에 STT_FINAL 전송
           → LLM 호출 (대화 이력 전달)
           → LLM 응답 파싱 (intent / response)
           → 브라우저에 LLM_RESULT 전송
           → TTS 호출
           → 브라우저에 TTS_TEXT + 음성 binary 전송
           → 다음 발화를 위해 새 Sink 생성 (startNextSttSession)
           → 브라우저에 BOT_READY 전송

  ---
  4. 연결 종료 afterConnectionClosed

  sink.tryEmitComplete();   // STT 스트림 종료
  disposable.dispose();     // STT 구독 취소 (타임아웃 오보 방지)
  historyMap.remove(...);   // 대화 이력 삭제
  callIdMap.remove(...);    // callId 삭제

  ---
  발화마다 새 Sink — startNextSttSession

  한 Sink는 한 발화에만 사용합니다.
  STT 결과가 나오면 이전 Sink를 닫고 새 Sink를 만들어 다음 발화를 준비합니다.

  1번 발화: sink1 → STT → LLM → TTS → sink1 완료
                                           ↓
  2번 발화:                             sink2 생성 → STT 재구독
  3번 발화:                             sink3 생성 → STT 재구독

  ---
  브라우저와 주고받는 메시지

  브라우저 → 서버
    binary: 음성 청크 (250ms 간격)
    JSON:   {"type": "CTI_EVENT", "event": "CALL_END"}

  서버 → 브라우저
    {"type": "STT_FINAL",   "text": "배송 어디까지 왔어요?"}
    {"type": "BOT_THINKING"}
    {"type": "LLM_RESULT",  "intent": "배송문의", "response": "확인해드리겠습니다."}
    {"type": "TTS_TEXT",    "text": "확인해드리겠습니다."}
    binary: 음성 오디오 데이터
    {"type": "BOT_READY"}

  ---
  CallHandler vs CtiWebSocketHandler

  ┌───────────┬──────────────┬──────────────────────┐
  │           │ CallHandler  │ CtiWebSocketHandler  │
  ├───────────┼──────────────┼──────────────────────┤
  │ 프로토콜  │ HTTP         │ WebSocket            │
  ├───────────┼──────────────┼──────────────────────┤
  │ 음성 전달 │ 한 번에 전체 │ 실시간 청크 스트리밍 │
  ├───────────┼──────────────┼──────────────────────┤
  │ 대화 이력 │ Redis        │ 메모리 내 historyMap │
  ├───────────┼──────────────┼──────────────────────┤
  │ 용도      │ 배치/테스트  │ 실시간 CTI 연동      │
  └───────────┴──────────────┴──────────────────────┘

  ---
  한 줄 요약

  ▎ WebSocket 연결 1개 = 전화 통화 1건. 음성 청크를 Sink로 받아 STT → LLM → TTS 파이프라인을 실시간으로 처리하고 결과를 브라우저에 역전송한다.


>> 함수별로 설명 필요.
```

### public class CtiWebSocketHandler extends AbstractWebSocketHandler { 설명해줘.
```
● CtiWebSocketHandler 설명

  브라우저(CTI 시뮬레이터)와 Spring Boot 서버 사이 WebSocket 연결 1개 = 전화 통화 1건을 처리하는 핵심 클래스입니다.

  ---
  전체 흐름

  브라우저
    │ binary (음성 청크)
    ▼
  handleBinaryMessage()
    └─ sink.tryEmitNext(chunk)  →  Sinks.Many<byte[]>
                                          │
                                          ▼
                                 sttService.recognize(flux)
                                          │ isFinal == true
                                          ▼
                                 handleFinalStt()
                                    ├─ llmService.chat()
                                    ├─ ttsService.synthesize()
                                    └─ session.sendMessage() → 브라우저

  ---
  상태 관리 — 4개의 Map

  ┌───────────────┬───────────┬────────────────────┬─────────────────────────────────────┐
  │      Map      │    키     │         값         │                역할                 │
  ├───────────────┼───────────┼────────────────────┼─────────────────────────────────────┤
  │ sinkMap       │ sessionId │ Sinks.Many<byte[]> │ 음성 청크를 STT에 흘려보내는 브리지 │
  ├───────────────┼───────────┼────────────────────┼─────────────────────────────────────┤
  │ historyMap    │ sessionId │ List<Message>      │ LLM 다화턴 대화 이력                │
  ├───────────────┼───────────┼────────────────────┼─────────────────────────────────────┤
  │ callIdMap     │ sessionId │ "CTI-XXXXXXXX"     │ 로그 추적용 콜 ID                   │
  ├───────────────┼───────────┼────────────────────┼─────────────────────────────────────┤
  │ disposableMap │ sessionId │ Disposable         │ STT 구독 취소 핸들러                │
  └───────────────┴───────────┴────────────────────┴─────────────────────────────────────┘

  ---
  메서드별 역할

  afterConnectionEstablished (연결 시작)

  - callId 발급 (CTI- + UUID 8자리)
  - Sinks.Many<byte[]> 생성 → STT 구독 시작
  - publishOn(Schedulers.boundedElastic()) — LLM이 내부적으로 block()을 쓰므로 NIO 스레드에서 실행하면 안 됨

  handleBinaryMessage (음성 청크 수신)

  - 250ms 간격으로 오는 PCM 청크를 sink.tryEmitNext()로 STT 스트림에 흘림

  handleTextMessage (JSON 이벤트 수신)

  - CTI_EVENT / CALL_END 수신 시 sink.tryEmitComplete() → STT 스트림 종료

  afterConnectionClosed (연결 종료)

  - sink 완료 → Disposable 명시적 취소 → Map 정리 순서
  - dispose() 가 중요한 이유: sink complete 신호가 RTZR STT 서버까지 전파되는 데 시간이 걸리고, 그 사이 60초 timeout이 발화해 불필요한 에러가 찍힘

  handleFinalStt (STT 최종 결과 처리)

  파이프라인 실행:
  1. STT_FINAL → 브라우저 전송
  2. BOT_THINKING → 브라우저 전송 (로딩 표시)
  3. llmService.chat(history, callId) — Claude가 JSON {"intent":"...","response":"..."} 반환
  4. JSON 파싱 실패 시 raw 텍스트를 response로 폴백
  5. ttsService.synthesize(response) → audio binary → 브라우저 전송
  6. startNextSttSession() 호출 — 다음 발화를 위해 새 Sink + STT 구독 시작

  startNextSttSession (발화 턴 리셋)

  - 이전 Sink/Disposable 폐기 → 새 Sink 생성 → STT 재구독
  - 멀티턴 대화가 이걸로 동작: history는 클로저로 계속 공유됨

  ---
  핵심 설계 포인트

  - Sinks.Many = WebSocket 콜백과 Reactor 스트림 사이의 다리. 콜백(push) → 스트림(pull)로 세계를 변환
  - history 클로저 캡처: capturedHistory를 연결 시점에 로컬 변수로 캡처해서, historyMap.remove() 이후에도 대화 이력이 STT 콜백 안에서 살아있음
  - TimeoutException = 정상: 60초 안에 발화가 없으면 timeout이 발생하는데, 통화 종료 상황과 동일하므로 DEBUG 레벨로만 기록

>> AbstractWebSocketHandler 설명해줘.
● Spring Framework 내장 클래스입니다. 소스를 확인해볼게요.

  Searched for 2 patterns (ctrl+o to expand)

● Bash(cd /tmp && jar xf /home/vscode/.m2/repository/org/springframework/spring-websocket/6.1.8/spring-websocket-6.1.8-sources.jar org/springframework/web/socket/handl…)
  ⎿       1     /*
          2      * Copyright 2002-2023 the original author or authors.
          3      *
     … +75 lines (ctrl+o to expand)
  ⎿  Shell cwd was reset to /workspaces/voicebot-js

● AbstractWebSocketHandler 설명

  Spring WebSocket 6.1.8 소스 기준입니다.

  ---
  구조

  WebSocketHandler (interface)
          │
  AbstractWebSocketHandler (abstract class)  ← 여기
          │
  CtiWebSocketHandler (우리 코드)

  WebSocketHandler 인터페이스를 구현하되, 모든 메서드를 빈 구현으로 채워 놓은 편의 추상 클래스입니다. 자식 클래스는 필요한 메서드만 골라서 @Override하면 됩니다.

  ---
  메서드 목록

  handleMessage() — 핵심 메서드 (직접 오버라이드 불필요)

  public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
      if (message instanceof TextMessage)   → handleTextMessage()
      if (message instanceof BinaryMessage) → handleBinaryMessage()
      if (message instanceof PongMessage)   → handlePongMessage()
  }

  Spring이 프레임 타입을 판별해서 아래 세 메서드 중 하나로 자동 분기합니다. 우리가 직접 호출할 일 없습니다.

  ---
  오버라이드 가능한 메서드 6개

  ┌──────────────────────────────┬────────────┬──────────────────────────────────────┐
  │            메서드            │ 기본 구현  │              호출 시점               │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ afterConnectionEstablished() │ 빈 메서드  │ 연결 수립 직후                       │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ handleTextMessage()          │ 빈 메서드  │ Text 프레임 수신 시                  │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ handleBinaryMessage()        │ 빈 메서드  │ Binary 프레임 수신 시                │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ handlePongMessage()          │ 빈 메서드  │ Pong 프레임 수신 시 (heartbeat 응답) │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ handleTransportError()       │ 빈 메서드  │ 전송 오류 발생 시                    │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ afterConnectionClosed()      │ 빈 메서드  │ 연결 종료 후                         │
  ├──────────────────────────────┼────────────┼──────────────────────────────────────┤
  │ supportsPartialMessages()    │ false 반환 │ 분할 프레임 지원 여부                │
  └──────────────────────────────┴────────────┴──────────────────────────────────────┘

  ---
  CtiWebSocketHandler에서 쓴 것만 추리면

  // ✅ 오버라이드
  afterConnectionEstablished()  → Sink 생성, STT 구독 시작
  handleBinaryMessage()         → 음성 청크 → sink.tryEmitNext()
  handleTextMessage()           → CTI_EVENT(CALL_END) 처리
  afterConnectionClosed()       → Sink 종료, Disposable 취소, Map 정리

  // ❌ 사용 안 함 (빈 메서드 그대로)
  handlePongMessage()
  handleTransportError()
  supportsPartialMessages()

  ---
  핵심 포인트

  - Template Method 패턴: handleMessage()가 타입 분기를 담당하고, 자식 클래스는 각 타입 핸들러만 구현
  - 빈 메서드 기본 구현: 인터페이스를 직접 구현하면 6개 메서드 전부 구현해야 하지만, 이 클래스 덕분에 필요한 것만 오버라이드하면 됨
  - Pong 프레임: WebSocket ping/pong은 연결 생존 확인용. 별도 처리 안 해도 Spring이 자동 응답하므로 보통 오버라이드하지 않음

>> Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer(); 좀 더 설명해줘. Sinks 뭔지?
● Sinks란?

  "데이터를 직접 밀어 넣을 수 있는 Flux" 입니다.

  ---
  보통 Flux vs Sinks

  // 보통 Flux — 데이터 소스가 처음부터 정해져 있음
  Flux.just("a", "b", "c")
  Flux.fromList(list)

  // Sinks — 나중에 원할 때 데이터를 밀어 넣을 수 있음
  Sinks.Many<byte[]> sink = ...;
  sink.tryEmitNext(chunk);  // 내가 원하는 시점에 직접 투입
  sink.asFlux();            // 이걸 Flux처럼 구독해서 소비

  ▎ Sinks = 입구(push) 와 출구(Flux) 가 분리된 파이프

  ---
  코드 한 줄씩 분해

  Sinks.many()               // 여러 개의 데이터를 흘려보낼 수 있는 Sink
       .unicast()            // 구독자가 딱 1명만 허용
       .onBackpressureBuffer() // 소비자가 느리면 버퍼에 쌓아 둠

  .many()

  데이터가 1개면 one(), 여러 개면 many().
  음성 청크는 계속 들어오므로 many().

  .unicast()

  ┌─────────────┬───────────┬─────────────────────────┐
  │    종류     │ 구독자 수 │          용도           │
  ├─────────────┼───────────┼─────────────────────────┤
  │ unicast()   │ 1명만     │ 단일 STT 서비스에 전달  │
  ├─────────────┼───────────┼─────────────────────────┤
  │ multicast() │ 여러 명   │ 여러 서비스에 동시 전달 │
  └─────────────┴───────────┴─────────────────────────┘

  STT 서비스 하나만 듣고 있으면 되므로 unicast().

  .onBackpressureBuffer()

  브라우저가 250ms마다 청크 전송
          ↓
  STT 처리가 잠깐 느린 순간
          ↓
  버퍼에 임시 보관 → 처리 가능해지면 꺼내서 전달

  버퍼 없으면 처리 못한 청크는 그냥 버려집니다.

  ---
  이 프로젝트에서 역할

  handleBinaryMessage()          afterConnectionEstablished()
        │                                   │
        │  sink.tryEmitNext(chunk)          │  sttService.recognize(sink.asFlux())
        ▼                                   ▼
  ┌─────────────────────────────────────────────────┐
  │            Sinks.Many<byte[]>                   │
  │                                                 │
  │  [입구]  →  버퍼  →  [출구 = asFlux()]  →  STT │
  └─────────────────────────────────────────────────┘

  WebSocket 콜백(handleBinaryMessage)은 데이터를 밀어 넣는(push) 세계,
  STT는 Flux로 구독하는(pull) 세계인데,
  Sinks가 이 둘을 연결해주는 다리입니다.
  
>> STT는 Flux로 구독하는(pull) 세계인데 => Rtzr STT 에서 우리쪽을 구독한다는 말인가?
 ● 아닙니다. 표현이 헷갈렸습니다. 정확히 정정하면:

  ---
  실제 방향

  우리 코드가 sink.asFlux()를 구독
          ↓
  청크가 들어올 때마다 RTZR에 전송

  RTZR이 우리를 구독하는 게 아니라, 우리 내부에서 Flux를 구독해서 RTZR로 보내는 것입니다.

  ---
  더 정확한 그림

  브라우저
    │ binary 청크
    ▼
  sink.tryEmitNext(chunk)      ← push (밀어 넣기)
    │
    ▼
  Sinks.Many<byte[]>  (버퍼)
    │
    ▼
  sink.asFlux()                ← 우리 코드가 여기를 구독
    │
    ▼
  RtzrWebSocketSttService      ← Flux에서 꺼내서 RTZR WebSocket으로 전송
    │
    ▼
  RTZR 서버                    ← 음성 받아서 STT 처리 후 결과 반환

  ---
  "pull 세계"라고 표현한 이유

  Reactor(Flux)는 구독자가 "나 준비됐어" 신호를 보내야 데이터가 흐르는 구조라서 pull이라고 표현했습니다. 하지만 RTZR과의 관계와 혼동될 수 있는 표현이었습니다. 앞으로는 "Flux로 처리하는 세계" 라고 표현하겠습니다.

>> 그럼 우리 코드는 소비자이고 sink.asFlux() 는 생산자가 만든 구독이야?
● 반대입니다.

  ---
  정확한 역할

  ┌───────────────┬──────────────────────────────────────────────────────────┐
  │     역할      │                           대상                           │
  ├───────────────┼──────────────────────────────────────────────────────────┤
  │ 생산자        │ 브라우저 (음성 청크를 보냄) → sink.tryEmitNext()         │
  ├───────────────┼──────────────────────────────────────────────────────────┤
  │ Sinks         │ 생산자와 소비자 사이 파이프                              │
  ├───────────────┼──────────────────────────────────────────────────────────┤
  │ sink.asFlux() │ 파이프의 출구 — 소비자가 붙을 수 있는 형태로 변환한 것   │
  ├───────────────┼──────────────────────────────────────────────────────────┤
  │ 소비자        │ sttService.recognize(sink.asFlux()) — Flux를 받아서 처리 │
  └───────────────┴──────────────────────────────────────────────────────────┘

  ---
  sink.asFlux()는 생산자가 아닙니다

  // asFlux() = 파이프 출구를 Flux 형태로 꺼내는 것
  // 데이터를 만드는 게 아니라 "여기서 꺼내 가세요" 하는 출구

  sttService.recognize(sink.asFlux(), callId)
  //                   └─ 출구를 넘겨줌
  //         └─ 이 출구에서 데이터를 꺼내 쓰는 소비자

  ---
  한 줄 정리

  브라우저 (생산자)
    → sink.tryEmitNext()로 파이프에 밀어 넣음
    → sink.asFlux()는 그 파이프의 출구
    → sttService가 출구를 붙잡고 데이터를 꺼내 쓰는 소비자

  sink.asFlux()는 출구 자체이고, 그 출구를 sttService가 구독하는 것입니다.

>> sttService.recognize() 내부에서 Flux를 어떻게 소비해?
● 핵심은 124번째 줄입니다.

  ---
  Flux 소비 방법 — audioStream.subscribe()

  audioStream.subscribe(
      chunk -> ws.send(ByteString.of(chunk)),  // 청크 올 때마다 RTZR로 전송
      error -> { ws.close(1000, "error"); ... }, // 에러 시 WebSocket 닫기
      () -> { ws.send("EOS"); }                 // 스트림 끝나면 EOS 전송
  );

  audioStream = 아까 sink.asFlux()로 넘긴 Flux입니다.
  .subscribe() 로 직접 구독해서 청크가 들어올 때마다 RTZR WebSocket으로 전송합니다.

  ---
  전체 흐름 다시 정리

  브라우저
    │ 음성 청크 (binary)
    ▼
  sink.tryEmitNext(chunk)
    │
    ▼
  Sinks 버퍼
    │
    ▼
  audioStream.subscribe()        ← 여기서 소비
    │ ws.send(chunk)
    ▼
  RTZR WebSocket 서버             ← 음성 받아서 STT 처리
    │ onMessage() 콜백
    ▼
  emitter.next(new SttResult())  ← 결과를 Flux로 내보냄
    │
    ▼
  .filter(isFinal).subscribe()   ← CtiWebSocketHandler에서 최종 결과 처리

  ---
  구조적으로 보면

  recognize() 안에는 Flux가 2개 있습니다.

  ┌─────────────────────────────┬────────────────────────────────────────────────────┐
  │            Flux             │                        역할                        │
  ├─────────────────────────────┼────────────────────────────────────────────────────┤
  │ audioStream (입력)          │ 브라우저 → RTZR 방향. .subscribe()로 소비해서 전송 │
  ├─────────────────────────────┼────────────────────────────────────────────────────┤
  │ Flux.create(emitter) (출력) │ RTZR → 우리 코드 방향. STT 결과를 반환             │
  └─────────────────────────────┴────────────────────────────────────────────────────┘

  입력 Flux를 소비해서 RTZR에 보내고, RTZR 응답을 출력 Flux로 내보내는 중계 구조입니다.

>> `Flux.create(emitter)` 설명해줘
● Flux.create(emitter) 설명

  ---
  한 줄 정의

  ▎ 콜백 기반 코드를 Flux로 포장하는 방법

  RTZR WebSocket은 onMessage() 콜백으로 결과를 줍니다.
  그런데 우리 코드는 Flux로 결과를 받고 싶습니다.
  Flux.create()가 이 둘을 연결합니다.

  ---
  구조

  Flux<SttResult> resultFlux = Flux.create(emitter -> {
      // 이 안에서 emitter로 데이터를 밀어 넣으면
      // 밖에서 Flux로 꺼낼 수 있음
  });

  emitter = Flux 안으로 데이터를 밀어 넣는 입구

  ┌────────────────────┬─────────────────────────┐
  │   emitter 메서드   │          역할           │
  ├────────────────────┼─────────────────────────┤
  │ emitter.next(값)   │ 데이터 1개 전달         │
  ├────────────────────┼─────────────────────────┤
  │ emitter.error(e)   │ 에러 전달 + 스트림 종료 │
  ├────────────────────┼─────────────────────────┤
  │ emitter.complete() │ 정상 종료               │
  └────────────────────┴─────────────────────────┘

  ---
  이 코드에서 동작

  Flux.create(emitter -> {

      // 1. RTZR WebSocket 연결
      WebSocket ws = okHttpClient.newWebSocket(request, new WebSocketListener() {

          @Override
          public void onMessage(WebSocket ws, String text) {
              // RTZR이 STT 결과를 콜백으로 줌
              emitter.next(new SttResult(recognized, isFinal));  // Flux 안으로 밀어 넣기
          }

          @Override
          public void onClosed(...) {
              emitter.complete();  // 스트림 종료
          }

          @Override
          public void onFailure(...) {
              emitter.error(t);   // 에러 전달
          }
      });

      // 2. 브라우저 청크를 RTZR로 전송
      audioStream.subscribe(
          chunk -> ws.send(chunk)
      );
  });

  ---
  시간 순서로 보면

  ① recognize() 호출
     → Flux.create() 실행
     → RTZR WebSocket 연결
     → audioStream.subscribe() 시작

  ② 브라우저 청크 도착
     → ws.send(chunk) → RTZR 전송

  ③ RTZR이 인식 결과 반환
     → onMessage() 콜백 발동
     → emitter.next(SttResult)    ← Flux 밖으로 결과 나옴

  ④ CtiWebSocketHandler에서 수신
     → .filter(isFinal)
     → handleFinalStt()

  ---
  Sinks vs Flux.create 비교

  둘 다 "콜백 → Flux" 변환이지만 쓰임새가 다릅니다.

  ┌──────────────────┬───────────────────────────────┬────────────────────────────────┐
  │                  │             Sinks             │          Flux.create           │
  ├──────────────────┼───────────────────────────────┼────────────────────────────────┤
  │ 누가 밀어 넣나   │ 외부에서 (sink.tryEmitNext()) │ 내부 콜백에서 (emitter.next()) │
  ├──────────────────┼───────────────────────────────┼────────────────────────────────┤
  │ 이 프로젝트 용도 │ 브라우저 청크 수신            │ RTZR STT 결과 수신             │
  ├──────────────────┼───────────────────────────────┼────────────────────────────────┤
  │ 비유             │ 택배 투입구 (밖에서 넣음)     │ 자판기 (안에서 나옴)           │
  └──────────────────┴───────────────────────────────┴────────────────────────────────┘

✻ Baked for 14s

※ recap: CtiWebSocketHandler의 WebSocket 처리 흐름을 공부 중. Sinks와 Flux.create의 역할을 설명했고, 다음 질문을 기다리는 중. (disable recaps in /config)

>> `emitter.onCancel()` 설명해줘
● emitter.onCancel() 설명

  ---
  한 줄 정의

  ▎ 구독자가 "그만 받을게" 했을 때 실행할 코드를 등록하는 것

  ---
  코드

  emitter.onCancel(() -> ws.close(1000, "cancelled"));

  ---
  언제 발동하나

  Reactor 체인에서 취소 신호가 오는 3가지 상황입니다.

  // CtiWebSocketHandler에서
  Disposable d = sttService.recognize(...)
                           .subscribe(...);

  d.dispose();  // ← 이 순간 취소 신호 발생 → emitter.onCancel() 실행

  ┌─────────────────────┬───────────────────────────────────────┐
  │ 취소 신호 발생 상황 │               코드 위치               │
  ├─────────────────────┼───────────────────────────────────────┤
  │ 통화 종료           │ afterConnectionClosed() → d.dispose() │
  ├─────────────────────┼───────────────────────────────────────┤
  │ 다음 발화 준비      │ startNextSttSession() → old.dispose() │
  ├─────────────────────┼───────────────────────────────────────┤
  │ 60초 타임아웃       │ .timeout(Duration.ofSeconds(60)) 내부 │
  └─────────────────────┴───────────────────────────────────────┘

  ---
  onCancel 없으면 어떻게 되나

  d.dispose() 호출
    │
    ▼
  Reactor 체인은 취소됨

    BUT

    RTZR WebSocket은 아직 연결 중
    → 서버 리소스 계속 점유
    → 불필요한 네트워크 연결 유지

  ---
  onCancel 있으면

  d.dispose() 호출
    │
    ▼
  Reactor 체인 취소
    │
    ▼
  emitter.onCancel() 실행
    │
    ▼
  ws.close(1000, "cancelled")  ← RTZR WebSocket 연결도 깔끔하게 닫힘

  ---
  한 줄 정리

  ▎ 구독자가 떠날 때 뒷정리 코드를 등록하는 훅.
  ▎ 여기서는 Reactor 체인이 취소되면 RTZR WebSocket도 함께 닫아주는 역할.
  
 
```

### CtiWebSocketHandler.afterConnectionEstablished() 아주 쉽게 설명해줘.
```
● afterConnectionEstablished() — 쉽게 설명

  "브라우저가 전화를 걸었을 때 전화를 받는 순간" 에 딱 한 번 실행됩니다.

  ---
  비유로 이해하기

  콜센터 상담원이 전화를 받는 순간을 생각하세요.

  브라우저가 WebSocket 연결
          ↓
  afterConnectionEstablished() 자동 실행
          ↓
  "여보세요~" 준비 완료

  ---
  코드 4줄 요약

  public void afterConnectionEstablished(WebSocketSession session) {
      // 1. 이 통화의 고유 번호 발급 (로그 추적용)
      String callId = "CTI-" + UUID...;
      callIdMap.put(session.getId(), callId);

      // 2. 대화 이력 노트 꺼내 놓기 (LLM 다화턴용)
      historyMap.put(session.getId(), new ArrayList<>());

      // 3. 음성이 흘러들어올 파이프 설치
      Sinks.Many<byte[]> sink = Sinks.many().unicast()...;
      sinkMap.put(session.getId(), sink);

      // 4. STT 귀 열기 — 파이프에서 소리 오면 받아서 처리
      Disposable d = sttService.recognize(sink.asFlux(), callId)
              .filter(isFinal)
              .subscribe(result -> handleFinalStt(...));
      disposableMap.put(session.getId(), d);
  }

  ---
  한 줄씩 더 쉽게

  ┌─────────────────────────────┬────────────────────────────────────────────────┐
  │            동작             │                      비유                      │
  ├─────────────────────────────┼────────────────────────────────────────────────┤
  │ callId 발급                 │ 이 통화에 접수 번호 붙이기                     │
  ├─────────────────────────────┼────────────────────────────────────────────────┤
  │ historyMap 초기화           │ 빈 메모지 꺼내 놓기 (나중에 대화 내용 적을 것) │
  ├─────────────────────────────┼────────────────────────────────────────────────┤
  │ Sink 생성                   │ 마이크선 꽂기 (아직 소리는 안 옴)              │
  ├─────────────────────────────┼────────────────────────────────────────────────┤
  │ sttService.recognize() 구독 │ 귀 열기 — 소리 오면 자동으로 STT 처리 시작     │
  └─────────────────────────────┴────────────────────────────────────────────────┘

  ---
  핵심

  ▎ 이 메서드는 준비 단계입니다.
  ▎ 실제 음성 데이터는 아직 안 옵니다.
  ▎ 브라우저가 마이크를 누르면 그때부터 handleBinaryMessage()로 청크가 들어옵니다.

  afterConnectionEstablished()  →  준비 (파이프 + 귀 설치)
  handleBinaryMessage()         →  실전 (음성 청크가 파이프로 흘러들어옴)

```

###  위헤서 설명한 내용 중에 ".publishOn(boundedElastic) — 스레드 전환" 잘 이해가 안 돼.
```
>> 질문
  1) 원래는 NIO 스레드인데 일반 스레드(boundedElastic 스레드)로 바꾼다?
  2) publishOn() 역할 및 의미
  3) 스레드 전환 하는 이유는 subscribe 내의 handleFinalStt() 내에 동기화(Blocking) 있어서 하는건지?
  4. .publishOn(boundedElastic) — 스레드 전환
  
>> 참조
    .publishOn(Schedulers.boundedElastic())

    왜 스레드를 바꾸나?

    기본 스레드: NIO 스레드 (네트워크 I/O 전담)
      → block() 호출 금지 (다른 요청 전부 멈춤)

    LlmService.chat() 내부에서 block() 사용 중
      → NIO 스레드에서 실행하면 서버 전체가 멈춤 💥

    boundedElastic 스레드: blocking 작업 전용 스레드풀
      → 여기서 실행하면 안전 ✅
      
● publishOn(boundedElastic) 자세히 설명

  ---
  1) 원래는 NIO 스레드인데 boundedElastic으로 바꾼다?

  맞습니다. 정확합니다.

  RTZR onMessage() 콜백
    → OkHttp NIO 스레드에서 실행
    → emitter.next(SttResult)
    → .filter()
    → .timeout()
    → 여기까지 전부 NIO 스레드

  .publishOn(boundedElastic)  ← 이 지점에서 스레드 전환
    → 이후는 boundedElastic 스레드에서 실행
    → .subscribe() 안의 handleFinalStt()

  ---
  2) publishOn() 역할

  체인 중간에 "이 지점부터는 다른 스레드에서 실행해" 라고 선언하는 것입니다.

  Flux.just("data")
      .map(x -> x)           // 스레드 A
      .publishOn(스케줄러)    // ← 전환점
      .map(x -> x)           // 스레드 B
      .subscribe(...)        // 스레드 B

  publishOn 아래쪽 체인 전체가 지정한 스케줄러 스레드에서 실행됩니다.

  ---
  3) handleFinalStt() 안에 blocking이 있어서?

  정확합니다.

  // handleFinalStt() 내부
  String llmRaw = llmService.chat(history, callId);  // ← 여기

  // ClaudeApiLlmService 내부 (추정)
  return webClient.post()
         ...
         .bodyToMono(String.class)
         .block();  // ← blocking 호출

  block()은 응답이 올 때까지 현재 스레드를 멈추고 기다립니다.

  ---
  4) NIO 스레드에서 block() 하면 왜 위험한가

  NIO 스레드가 뭔지부터 이해해야 합니다.

  NIO 스레드 = 네트워크 I/O 전담 스레드
    - 스레드 수가 매우 적음 (보통 CPU 코어 수)
    - 수천 개의 네트워크 연결을 이 몇 개의 스레드가 처리
    - 절대 멈추면 안 됨

  [NIO 스레드 1개가 처리하는 것들]

  연결 A 음성 청크 수신
  연결 B STT 결과 수신
  연결 C 브라우저 응답 전송
  연결 D RTZR WebSocket 관리
  ...

  여기서 block() 호출하면:

  NIO 스레드: LLM 응답 기다리는 중... (3초)
    │
    └─ 이 3초 동안 위의 연결 A, B, C, D 전부 멈춤 💥

  ---
  boundedElastic은 왜 안전한가

  boundedElastic 스레드풀
    - blocking 작업 전용
    - 필요하면 스레드를 새로 만듦 (최대 개수 제한 있음)
    - 이 스레드가 멈춰도 NIO 스레드는 계속 동작

  [NIO 스레드]           [boundedElastic 스레드]

  계속 동작 중 ✅         LLM 응답 기다리는 중...
  연결 A 처리             (3초 멈춰도 NIO는 무관)
  연결 B 처리
  연결 C 처리

  ---
  전체 정리

  RTZR 콜백 (NIO 스레드)
    → emitter.next()
    → .filter()
    → .timeout()
    → .publishOn(boundedElastic)  ← 스레드 전환
    → .subscribe()
         → handleFinalStt()
              → llmService.chat()
                   → block()      ← boundedElastic에서 실행이므로 안전 ✅

  ▎ publishOn(boundedElastic)은
  ▎ "LLM의 block() 때문에 NIO 스레드가 멈추는 것"을 막기 위한 안전장치입니다.

```

### `subscribeOn` 이랑 `publishOn` 차이가 뭐야?
```
● subscribeOn vs publishOn 차이

  ---
  한 줄 정의

  ┌─────────────┬─────────────────────────┬─────────────────────────┐
  │             │       어디서 실행       │        영향 범위        │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ subscribeOn │ 구독 시작 스레드 지정   │ 체인 전체 (위아래 모두) │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ publishOn   │ 이 지점부터 스레드 전환 │ 선언 아래쪽만           │
  └─────────────┴─────────────────────────┴─────────────────────────┘

  ---
  그림으로 비교

  publishOn

  Flux 생성          → 스레드 A
  .map()             → 스레드 A
  .publishOn(B)      ← 전환점
  .map()             → 스레드 B
  .subscribe()       → 스레드 B

  subscribeOn

  Flux 생성          → 스레드 B  ← 전체가 B
  .map()             → 스레드 B
  .subscribeOn(B)    ← 어디 있든 전체에 영향
  .map()             → 스레드 B
  .subscribe()       → 스레드 B

  ---
  코드로 비교

  // publishOn — 선언 아래쪽만 전환
  Flux.just("data")
      .map(x -> "위: " + Thread.currentThread().getName())  // NIO 스레드
      .publishOn(Schedulers.boundedElastic())
      .map(x -> "아래: " + Thread.currentThread().getName()) // boundedElastic 스레드
      .subscribe(System.out::println);

  // 출력
  // 위: nio-thread-1
  // 아래: boundedElastic-1

  // subscribeOn — 체인 전체가 지정한 스레드
  Flux.just("data")
      .map(x -> "위: " + Thread.currentThread().getName())  // boundedElastic 스레드
      .subscribeOn(Schedulers.boundedElastic())
      .map(x -> "아래: " + Thread.currentThread().getName()) // boundedElastic 스레드
      .subscribe(System.out::println);

  // 출력
  // 위: boundedElastic-1
  // 아래: boundedElastic-1

  ---
  언제 쓰나

  ┌─────────────┬─────────────────────────────┬───────────────────────────────────────────┐
  │             │            용도             │                   예시                    │
  ├─────────────┼─────────────────────────────┼───────────────────────────────────────────┤
  │ subscribeOn │ 데이터 생성 자체가 blocking │ 파일 읽기, JDBC 쿼리                      │
  ├─────────────┼─────────────────────────────┼───────────────────────────────────────────┤
  │ publishOn   │ 체인 중간부터 blocking      │ 우리 코드처럼 STT는 NIO, LLM부터 blocking │
  └─────────────┴─────────────────────────────┴───────────────────────────────────────────┘

  ---
  우리 코드에 대입

  RTZR 콜백 (NIO 스레드)     ← 데이터 생성은 NIO가 적합
    → .filter()              → NIO 스레드
    → .timeout()             → NIO 스레드
    → .publishOn(boundedElastic)  ← 여기서부터만 전환
    → .subscribe()           → boundedElastic 스레드
         → llmService.chat() → block() 안전 ✅

  subscribeOn을 쓰면 RTZR 콜백까지 boundedElastic으로 바뀌어서
  NIO 스레드의 장점(빠른 네트워크 처리)을 못 씁니다.
  그래서 publishOn이 정확한 선택입니다.
```

### CtiWebSocketHandler.handleBinaryMessage() 아주 쉽게 설명해줘.
```
● handleBinaryMessage() 쉽게 설명

  ---
  한 줄 정의

  ▎ 브라우저에서 음성 청크가 오면 파이프에 밀어 넣는 것

  ---
  코드

  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
      Sinks.Many<byte[]> sink = sinkMap.get(session.getId());
      if (sink != null) {
          byte[] chunk = message.getPayload().array();
          sink.tryEmitNext(chunk);
      }
  }

  ---
  줄별 설명

  // 1. 이 세션의 파이프 꺼내기
  Sinks.Many<byte[]> sink = sinkMap.get(session.getId());

  // 2. 파이프가 있으면 (통화 중이면)
  if (sink != null) {

      // 3. 음성 데이터 꺼내기
      byte[] chunk = message.getPayload().array();

      // 4. 파이프에 밀어 넣기 → STT로 흘러감
      sink.tryEmitNext(chunk);
  }

  ---
  비유

  브라우저 마이크 (250ms마다)
    │
    │ "안..." 음성 청크
    ▼
  handleBinaryMessage()
    │
    │ sink.tryEmitNext(chunk)
    ▼
  파이프 (Sinks)
    │
    ▼
  RTZR STT

  택배 상자가 계속 오면 컨베이어 벨트에 올려놓는 것과 같습니다.
  이 메서드는 올려놓기만 합니다. 처리는 STT가 합니다.

  ---
  핵심

  afterConnectionEstablished()에서 파이프를 설치했고,
  handleBinaryMessage()는 그 파이프에 음성 데이터를 던져 넣는 역할만 합니다.
```

### 아래 질문1,2 답변해줘.
```
>> 질문
  public void afterConnectionEstablished(WebSocketSession session) {
  ...
      Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
      sinkMap.put(session.getId(), sink); => 1) 이 부분은 새롭게 파이프 만드는 것 같아.
  ...
      Disposable d = sttService.recognize(sink.asFlux(), callId)
              .filter(SttService.SttResult::isFinal) => 2) 그러면 이 부분이 해당 세션에 파이프가 이것이다라고 연결하는건가? 여기가 아니라면 어디?


● 답변

  ---
  1) sinkMap.put(session.getId(), sink) — 새 파이프 만드는 것 맞나?

  반은 맞고 반은 다릅니다.

  // 파이프 생성
  Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();

  // 파이프를 세션ID로 보관
  sinkMap.put(session.getId(), sink);

  ┌─────────────────┬───────────────────────────────────────────────────┐
  │      코드       │                       역할                        │
  ├─────────────────┼───────────────────────────────────────────────────┤
  │ Sinks.many()... │ 파이프 생성                                       │
  ├─────────────────┼───────────────────────────────────────────────────┤
  │ sinkMap.put()   │ 나중에 handleBinaryMessage()에서 꺼내 쓰려고 보관 │
  └─────────────────┴───────────────────────────────────────────────────┘

  파이프 생성 + 보관 두 가지를 합니다.

  ---
  2) sttService.recognize(sink.asFlux()) — 파이프 연결하는 곳?

  맞습니다. 여기가 연결하는 곳입니다.

  Sinks.Many<byte[]> sink = ...;   // 파이프 생성
  sinkMap.put(session.getId(), sink); // 파이프 보관

  sttService.recognize(sink.asFlux(), callId)  // ← 파이프 출구를 STT에 연결

  sink.asFlux()가 핵심입니다.

  sink          = 파이프 전체
  sink.asFlux() = 파이프 출구만 꺼낸 것 → STT에 넘겨줌

  ---
  전체 그림

  [파이프 생성]
  Sinks.many()...
        │
        ▼
  [파이프 보관]                    [파이프 출구를 STT에 연결]
  sinkMap.put(sessionId, sink)    sttService.recognize(sink.asFlux())
        │                                    │
        │                                    ▼
        │                               STT가 출구 구독 시작
        │
        ▼
  [나중에 handleBinaryMessage에서]
  sinkMap.get(sessionId)          ← 보관된 파이프 꺼냄
  sink.tryEmitNext(chunk)         ← 파이프 입구로 청크 투입
                                          │
                                          ▼
                                     STT로 흘러감 ✅

  ▎ 파이프 입구(tryEmitNext)와 출구(asFlux)가 분리되어 있고,
  ▎ recognize(sink.asFlux())가 입구와 STT를 연결하는 지점입니다.
  
>> 그러면 sink 생성하고 나중에 사용을 위해서 session id 로 보관하고 STT 로 흘려 보내기 위해서 출구를 asFlux 로 STT 와 연결했다. 이건가? 그렇다면 파이프과 STT 간의 1:1인데 다른 1:1과 구분은 callId 로 구분하는건가?
● 맞습니다. 정확하게 이해하셨습니다.

  ---
  정리

  Sinks.Many<byte[]> sink = Sinks.many()...;  // 파이프 생성
  sinkMap.put(session.getId(), sink);          // 나중 사용을 위해 보관
  sttService.recognize(sink.asFlux(), callId) // 출구를 STT에 연결

  생성 → 보관 → 연결 3단계 맞습니다.

  ---
  파이프:STT = 1:1, 구분은 뭐로?

  구분 기준이 2개 있습니다.

  sessionId  →  sinkMap에서 파이프 구분
  callId     →  RTZR 서버에서 통화 구분 (로그 추적)

  ┌───────────┬────────────────────────────────────┬──────────────────────────────────┐
  │  구분자   │             쓰이는 곳              │               역할               │
  ├───────────┼────────────────────────────────────┼──────────────────────────────────┤
  │ sessionId │ sinkMap, historyMap, disposableMap │ 우리 서버 내부에서 세션 구분     │
  ├───────────┼────────────────────────────────────┼──────────────────────────────────┤
  │ callId    │ RTZR, LLM, TTS 호출 시             │ 외부 서비스 + 로그에서 통화 구분 │
  └───────────┴────────────────────────────────────┴──────────────────────────────────┘

  ---
  그림으로 보면

  통화 A                              통화 B
  sessionId: "abc"                    sessionId: "xyz"
  callId: "CTI-11111111"              callId: "CTI-22222222"
        │                                   │
        ▼                                   ▼
  sinkMap["abc"] = sink A             sinkMap["xyz"] = sink B
        │                                   │
        ▼                                   ▼
  STT.recognize(sinkA, "CTI-11111111") STT.recognize(sinkB, "CTI-22222222")
        │                                   │
        ▼                                   ▼
  RTZR 연결 A                         RTZR 연결 B

  ▎ sessionId는 우리 서버 안에서 파이프를 찾는 열쇠,
  ▎ callId는 외부 서비스와 로그에서 이 통화가 뭔지 추적하는 열쇠입니다.
  
>> STT 내에서도 우리쪽을 구분해야하는데 그것이 CallID 이다?
● 맞습니다.

  ---
  RTZR 입장에서 보면

  RTZR은 우리 서버로부터 여러 WebSocket 연결을 동시에 받습니다.

  우리 서버
    ├─ 통화 A → RTZR WebSocket 연결 1
    ├─ 통화 B → RTZR WebSocket 연결 2
    └─ 통화 C → RTZR WebSocket 연결 3

  RTZR 자체는 WebSocket 연결마다 자동으로 구분합니다.
  연결 자체가 이미 1:1이라 혼선은 없습니다.

  ---
  그러면 callId는 어디서 구분에 쓰이나?

  RTZR 구분용이 아니라 우리 로그에서 추적용입니다.

  log.info("[STT-RTZR] 메시지 수신 callId={} raw={}", callId, text);
  log.info("[STT-RTZR] 연결됨 callId={} status={}", callId, response.code());
  log.error("[STT-RTZR] WebSocket 오류 callId={}", callId, t);

  로그에서
    [STT-RTZR] callId=CTI-11111111 text=안녕하세요  ← 통화 A
    [STT-RTZR] callId=CTI-22222222 text=문의드립니다 ← 통화 B

  callId 없으면 동시에 여러 통화가 진행될 때 어느 통화의 로그인지 구분 불가합니다.

  ---
  한 줄 정리

  ▎ RTZR은 WebSocket 연결 자체로 통화를 구분하고,
  ▎ callId는 우리 로그에서 어느 통화인지 추적하기 위한 식별자입니다.

>> 우리 쪽에서 asFlux() 로 STT 와 연결되면 자체적으로 구분할 수 있고 callid 는 우리가 구분을 위해서 두는 것이다. 맞어?
● 맞습니다. 정확합니다.

  ---
  정리

  asFlux() 연결
    → 객체 참조로 1:1 연결
    → 코드 레벨에서 자동 구분
    → 혼선 없음

  callId
    → 사람이 읽는 로그에서 구분
    → 외부 서비스(RTZR, LLM, TTS) 호출 시 추적
    → 코드가 아닌 운영/디버깅을 위한 식별자

  ---
  비유

  asFlux()  =  전화선 (물리적으로 연결되어 있어서 혼선 없음)
  callId    =  접수 번호 (상담원이 "3번 고객" 하고 부르는 것)

  전화선이 이미 1:1로 연결되어 있으니 혼선은 없습니다.
  callId는 로그와 디버깅에서 사람이 식별하기 위한 번호입니다.

```

### CtiWebSocketHandler.handleTextMessage() 아주 쉽게 설명해줘. 
```
● handleTextMessage() 쉽게 설명

  ---
  한 줄 정의

  ▎ 브라우저에서 JSON 이벤트가 오면 처리하는 것

  ---
  코드

  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
      Map<String, Object> event = objectMapper.readValue(message.getPayload(), Map.class);
      String type = (String) event.get("type");
      String callId = callIdMap.get(session.getId());

      log.info("[CTI] 이벤트 수신 type={} callId={}", type, callId);

      if ("CTI_EVENT".equals(type)) {
          String ctiEvent = (String) event.get("event");
          if ("CALL_END".equals(ctiEvent)) {
              handleCallEnd(session);
          }
      }
  }

  ---
  줄별 설명

  // 1. JSON 문자열 → Map으로 변환
  Map<String, Object> event = objectMapper.readValue(message.getPayload(), Map.class);

  // 2. type 꺼내기
  String type = (String) event.get("type");

  // 3. CTI_EVENT 타입이면
  if ("CTI_EVENT".equals(type)) {

      // 4. event 꺼내기
      String ctiEvent = (String) event.get("event");

      // 5. CALL_END면 통화 종료 처리
      if ("CALL_END".equals(ctiEvent)) {
          handleCallEnd(session);  // sink.tryEmitComplete() → STT 스트림 종료
      }
  }

  ---
  브라우저가 보내는 JSON

  { "type": "CTI_EVENT", "event": "CALL_END" }

  ---
  흐름

  브라우저가 통화 종료 버튼 클릭
    │
    │ { "type": "CTI_EVENT", "event": "CALL_END" }
    ▼
  handleTextMessage()
    │
    ▼
  handleCallEnd()
    │
    ▼
  sink.tryEmitComplete()  ← 파이프 종료
    │
    ▼
  STT 스트림 종료 → RTZR에 EOS 전송

  ---
  핵심

  현재는 CALL_END 하나만 처리합니다.
  CALL_START 같은 다른 이벤트가 와도 아무것도 안 합니다.
  
>> sink.tryEmitComplete() 은 파이피프 자체를 종료하고 없애는 것. 이 신호를 STT(RtzrWebSocketSttService) 의 audioStream.subscribe( 의 () -> {
                          log.debug("[STT-RTZR] EOS 전송 callId={}", callId);
                          ws.send("EOS");
                      } 호출되면서 Rtzr STT 와의 연결도 종료한다. 맞어?
                      
● 맞습니다. 정확합니다.

  ---
  신호 전파 흐름

  handleCallEnd()
    │
    │ sink.tryEmitComplete()
    ▼
  Sinks 파이프 완료 신호 발생
    │
    ▼
  audioStream.subscribe(
      chunk -> ws.send(chunk),   // 데이터
      error -> ...,              // 에러
      () -> { ws.send("EOS"); }  // ← 완료 신호 → 여기 실행
  )
    │
    │ ws.send("EOS")
    ▼
  RTZR 서버
    │ EOS 받으면 STT 처리 종료
    │ 마지막 결과 전송 후 WebSocket 닫음
    ▼
  onClosed() → emitter.complete()
    │
    ▼
  STT 결과 Flux 종료

  ---
  한 가지만 정정

  ▎ "파이프 자체를 종료하고 없애는 것"

  종료는 맞지만 없애지는 않습니다.

  sink.tryEmitComplete()  // 완료 신호 발행 (더 이상 데이터 못 넣음)
                          // 객체는 메모리에 남아있음

  // 실제로 없애는 건 afterConnectionClosed()
  sinkMap.remove(session.getId())  // ← 여기서 Map에서 제거 → GC 대상

  ---
  한 줄 정리

  ▎ tryEmitComplete() → 파이프 입구 닫기 → audioStream 완료 신호 → EOS 전송 → RTZR 연결 종료까지 연쇄적으로 전파됩니다.

>>  public Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId) {
          return Flux.create(emitter -> {
  에서의 emitter 는 sink(파이프,CtiWebSocketHandler)을 말하는건가?
  
● 아닙니다. 다른 것입니다.

  ---
  둘 다 "밀어 넣는 입구"지만 별개입니다

  ┌───────────┬──────────────────────────────┬───────────────────────────────────┐
  │           │  sink (CtiWebSocketHandler)  │ emitter (RtzrWebSocketSttService) │
  ├───────────┼──────────────────────────────┼───────────────────────────────────┤
  │ 타입      │ Sinks.Many<byte[]>           │ FluxSink<SttResult>               │
  ├───────────┼──────────────────────────────┼───────────────────────────────────┤
  │ 데이터    │ byte[] 음성 청크             │ SttResult 인식 결과               │
  ├───────────┼──────────────────────────────┼───────────────────────────────────┤
  │ 생성 위치 │ afterConnectionEstablished() │ Flux.create() 내부                │
  ├───────────┼──────────────────────────────┼───────────────────────────────────┤
  │ 누가 넣나 │ handleBinaryMessage()        │ RTZR onMessage() 콜백             │
  └───────────┴──────────────────────────────┴───────────────────────────────────┘

  ---
  전체 그림

  브라우저
    │ 음성 청크
    ▼
  sink.tryEmitNext(chunk)        ← Sinks (byte[] 파이프)
    │
    ▼
  audioStream.subscribe()
    │ ws.send(chunk)
    ▼
  RTZR 서버
    │ STT 결과
    ▼
  onMessage() 콜백
    │
    ▼
  emitter.next(SttResult)        ← FluxSink (SttResult 파이프)
    │
    ▼
  .filter(isFinal)
    │
    ▼
  handleFinalStt()

  ---
  한 줄 정리

  ▎ sink는 음성 청크를 STT로 보내는 파이프,
  ▎ emitter는 STT 결과를 우리 코드로 보내는 파이프입니다.
  ▎ 파이프가 2개 직렬로 연결된 구조입니다.
  

>> "sink.tryEmitComplete() 은 파이피프 자체를 종료하고 없애는 것" 에서 없애는 것은 아니라고 했음. 그러면 없애는 것은 언제?
● 파이프 없애는 시점

  ---
  afterConnectionClosed() 에서 없앱니다

  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {

      // 1. 파이프 완료 신호 + Map에서 제거 (없애기)
      Sinks.Many<byte[]> sink = sinkMap.remove(session.getId());  // ← 여기서 없앰
      if (sink != null) sink.tryEmitComplete();

      // 2. STT 구독 취소
      Disposable d = disposableMap.remove(session.getId());
      if (d != null && !d.isDisposed()) d.dispose();

      // 3. 나머지 Map 정리
      historyMap.remove(session.getId());
      callIdMap.remove(session.getId());
  }

  ---
  단계별로 보면

  tryEmitComplete()       →  파이프 입구 닫기 (신호만)
                              객체는 메모리에 살아있음

  sinkMap.remove()        →  Map에서 참조 제거
                              아무도 참조 안 하면 GC 대상

  GC                      →  실제 메모리에서 사라짐

  ---
  한 줄 정리

  ▎ tryEmitComplete() = 파이프 사용 종료 선언
  ▎ sinkMap.remove() = 파이프 참조 제거 → GC가 실제로 없앰

```

### CtiWebSocketHandler.afterConnectionClosed() 아주 쉽게 설명해줘.
```
● afterConnectionClosed() 쉽게 설명

  ---
  한 줄 정의

  ▎ 통화가 끊겼을 때 뒷정리하는 것

  ---
  코드

  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {

      // 1. 파이프 종료 + Map에서 제거
      Sinks.Many<byte[]> sink = sinkMap.remove(session.getId());
      if (sink != null) sink.tryEmitComplete();

      // 2. STT 구독 취소
      Disposable d = disposableMap.remove(session.getId());
      if (d != null && !d.isDisposed()) d.dispose();

      // 3. 대화 이력 제거
      historyMap.remove(session.getId());

      // 4. callId 제거
      callIdMap.remove(session.getId());
  }

  ---
  비유

  호텔 체크아웃할 때 프런트가 하는 일과 같습니다.

  투숙객 퇴실 (WebSocket 연결 종료)
    │
    ▼
  프런트 체크아웃 처리 (afterConnectionClosed)
    ├─ 객실 열쇠 반납 (sinkMap.remove)
    ├─ 미니바 정산 종료 (disposable.dispose)
    ├─ 투숙 기록 정리 (historyMap.remove)
    └─ 예약 번호 삭제 (callIdMap.remove)

  ---
  순서가 중요한 이유

  // sinkMap.remove() 먼저
  Sinks.Many<byte[]> sink = sinkMap.remove(session.getId());
  if (sink != null) sink.tryEmitComplete();

  // disposable.dispose() 나중에
  Disposable d = disposableMap.remove(session.getId());
  if (d != null && !d.isDisposed()) d.dispose();

  tryEmitComplete() 를 먼저 해서 파이프를 닫고,
  dispose() 로 STT 구독을 취소합니다.

  dispose() 가 필요한 이유는 tryEmitComplete() 신호가 RTZR까지 전파되는 데 시간이 걸리는 동안 60초 타임아웃이 발화할 수 있기 때문입니다.

  ---
  한 줄 정리

  ▎ 4개의 Map에서 이 세션 관련 데이터를 전부 제거하고,
  ▎ 파이프와 STT 구독을 깔끔하게 닫는 뒷정리 메서드입니다.
  
>> " dispose() 가 필요한 이유는 tryEmitComplete() 신호가 RTZR까지 전파되는 데 시간이 걸리는 동안 60초 타임아웃이 발화할 수 있기 때문입니다." => tryEmitComplete 전파되는 시간이 걸리는 그 동안 음성신호가 들어올 수 있으니 dispose() 한다. 이거야?
● 아닙니다. 다른 이유입니다.

  ---
  정확한 이유

  tryEmitComplete() 신호 전파 경로를 보면:

  tryEmitComplete()
    │
    ▼
  audioStream 완료
    │
    ▼
  ws.send("EOS")
    │
    ▼
  RTZR이 EOS 받고 마지막 결과 전송
    │
    ▼
  RTZR이 WebSocket 닫음 (onClosed)
    │
    ▼
  emitter.complete()

  이 과정이 수 초 걸릴 수 있습니다.

  ---
  그 사이에 타임아웃이 발화

  tryEmitComplete() 호출
    │
    ├─ RTZR 응답 기다리는 중... (수 초)
    │
    │  ← 이 공백 동안 .timeout(60초) 카운트 계속 진행
    │
    └─ 60초 지나면 TimeoutException 발생 💥
         → 에러 로그 찍힘 (정상 종료인데 에러처럼 보임)

  음성 신호가 들어오는 것과는 무관합니다.
  RTZR이 응답하는 데 걸리는 시간 동안 타임아웃 카운트가 멈추지 않아서 문제입니다.

  ---
  dispose()가 해결하는 것

  tryEmitComplete() 호출
    │
    ▼
  dispose() 호출
    │
    ▼
  Reactor 체인 즉시 취소 → timeout 타이머 즉시 멈춤 ✅

  RTZR 응답을 기다릴 필요 없이 체인을 즉시 끊어버려서 타임아웃이 발화할 틈을 없앱니다.

  ---
  한 줄 정리

  ▎ dispose()는 음성 신호 차단이 아니라,
  ▎ RTZR 응답 지연 동안 타임아웃이 불필요하게 발화하는 것을 막기 위한 것입니다.

```

### CtiWebSocketHandler.handleCallEnd() 아주 쉽게 설명해줘.
```
● handleCallEnd() 쉽게 설명

  ---
  한 줄 정의

  ▎ 통화 종료 이벤트가 오면 파이프를 닫는 것

  ---
  코드

  private void handleCallEnd(WebSocketSession session) {
      String callId = callIdMap.get(session.getId());
      log.info("[CTI] CALL_END callId={}", callId);

      Sinks.Many<byte[]> sink = sinkMap.get(session.getId());
      if (sink != null) sink.tryEmitComplete();
  }

  ---
  줄별 설명

  // 1. 로그용 callId 꺼내기
  String callId = callIdMap.get(session.getId());

  // 2. 이 세션의 파이프 꺼내기
  Sinks.Many<byte[]> sink = sinkMap.get(session.getId());

  // 3. 파이프 닫기 → EOS → RTZR 연결 종료
  if (sink != null) sink.tryEmitComplete();

  ---
  호출 경로

  브라우저 통화 종료 버튼 클릭
    │
    │ { "type": "CTI_EVENT", "event": "CALL_END" }
    ▼
  handleTextMessage()
    │
    ▼
  handleCallEnd()
    │
    ▼
  sink.tryEmitComplete()
    │
    ▼
  EOS → RTZR 연결 종료

  ---
  afterConnectionClosed() 와 차이

  ┌───────────┬─────────────────────────────┬───────────────────────────────┐
  │           │       handleCallEnd()       │    afterConnectionClosed()    │
  ├───────────┼─────────────────────────────┼───────────────────────────────┤
  │ 호출 시점 │ 브라우저가 CALL_END 전송 시 │ WebSocket 연결 자체가 끊길 때 │
  ├───────────┼─────────────────────────────┼───────────────────────────────┤
  │ 하는 일   │ 파이프만 닫기               │ 파이프 + 모든 Map 정리        │
  └───────────┴─────────────────────────────┴───────────────────────────────┘

  ▎ handleCallEnd()는 파이프만 닫고,
  ▎ 실제 뒷정리는 이후 afterConnectionClosed()가 합니다.
```

### CtiWebSocketHandler.handleFinalStt() 아주 쉽게 설명해줘.
```
● handleFinalStt() 쉽게 설명

  ---
  한 줄 정의

  ▎ STT 최종 결과가 오면 LLM → TTS → 브라우저 응답까지 처리하는 것

  ---
  코드 흐름

  private void handleFinalStt(WebSocketSession session, String callId,
                               String finalText, List<Message> history) {

      // 1. STT 결과 브라우저에 전송
      sendJson(session, Map.of("type", "STT_FINAL", "text", finalText));

      // 2. "생각 중..." 브라우저에 전송
      sendJson(session, Map.of("type", "BOT_THINKING"));

      // 3. 대화 이력에 사용자 발화 추가
      history.add(new Message("user", finalText));

      // 4. LLM 호출
      String llmRaw = llmService.chat(history, callId);

      // 5. LLM 응답 JSON 파싱
      String intent   = node.path("intent").asText();    // ex) "문의"
      String response = node.path("response").asText();  // ex) "무엇을 도와드릴까요?"

      // 6. 대화 이력에 봇 응답 추가
      history.add(new Message("assistant", response));

      // 7. LLM 결과 브라우저에 전송
      sendJson(session, Map.of("type", "LLM_RESULT", "intent", intent, "response", response));

      // 8. TTS 호출 → 오디오 생성
      byte[] audioBytes = ttsService.synthesize(response, callId);

      // 9. TTS 텍스트 + 오디오 브라우저에 전송
      sendJson(session, Map.of("type", "TTS_TEXT", "text", response));
      sendBinary(session, audioBytes);

      // 10. 다음 발화 준비
      startNextSttSession(session, callId, history);

      // 11. "준비 완료" 브라우저에 전송
      sendJson(session, Map.of("type", "BOT_READY"));
  }

  ---
  브라우저가 받는 메시지 순서

  STT_FINAL    → "안녕하세요 문의드립니다"   (사용자가 한 말)
  BOT_THINKING → (로딩 표시)
  LLM_RESULT   → "문의 / 무엇을 도와드릴까요?" (봇 응답)
  TTS_TEXT     → "무엇을 도와드릴까요?"       (TTS 텍스트)
  binary       → 🔊 오디오                   (실제 음성)
  BOT_READY    → (다음 발화 대기)

  ---
  비유

  콜센터 상담원이 고객 말을 듣고 답하는 과정과 같습니다.

  고객: "환불하고 싶어요"          ← STT 결과 (finalText)
    │
    ▼
  상담원: "잠깐만요..."             ← BOT_THINKING
    │
    ▼
  상담원이 매뉴얼 확인             ← llmService.chat()
    │
    ▼
  상담원: "환불 절차 안내해드릴게요" ← LLM_RESULT + TTS + 오디오
    │
    ▼
  상담원: "다음 말씀 하세요"        ← BOT_READY

  ---
  핵심

  history에 사용자 발화와 봇 응답을 계속 쌓아가면서
  LLM에 전달하기 때문에 다화턴 대화가 가능합니다.
```

### CtiWebSocketHandler.startNextSttSession() 아주 쉽게 설명해줘.
```
● startNextSttSession() 쉽게 설명

  ---
  한 줄 정의

  ▎ 봇이 답변을 끝내고 다음 발화를 받을 준비를 하는 것

  ---
  코드

  private void startNextSttSession(WebSocketSession session, String callId, List<Message> history) {

      // 1. 이전 STT 구독 취소
      Disposable old = disposableMap.remove(session.getId());
      if (old != null && !old.isDisposed()) old.dispose();

      // 2. 이전 파이프 닫기
      Sinks.Many<byte[]> oldSink = sinkMap.get(session.getId());
      if (oldSink != null) oldSink.tryEmitComplete();

      // 3. 새 파이프 생성
      Sinks.Many<byte[]> newSink = Sinks.many().unicast().onBackpressureBuffer();
      sinkMap.put(session.getId(), newSink);

      // 4. 새 STT 구독 시작
      Disposable d = sttService.recognize(newSink.asFlux(), callId)
              .filter(SttService.SttResult::isFinal)
              .timeout(Duration.ofSeconds(60))
              .publishOn(Schedulers.boundedElastic())
              .subscribe(
                      result -> handleFinalStt(session, callId, result.text(), history),
                      error  -> handleSttError(callId, error)
              );
      disposableMap.put(session.getId(), d);
  }

  ---
  왜 새 파이프가 필요한가

  발화 1회 = 파이프 1개
    │
    ▼
  STT 최종 결과 → 파이프 완료 (tryEmitComplete)
    │
    ▼
  이 파이프는 재사용 불가
    │
    ▼
  새 파이프 생성해야 다음 발화 받을 수 있음

  Sinks.unicast()는 한 번 완료되면 재사용이 불가능합니다.

  ---
  호출 위치

  // handleFinalStt() 마지막에 호출
  sendBinary(session, audioBytes);       // 오디오 전송
  startNextSttSession(...);              // ← 다음 발화 준비
  sendJson(session, Map.of("type", "BOT_READY")); // 준비 완료

  ---
  afterConnectionEstablished() 와 비교

  afterConnectionEstablished()   →  첫 번째 파이프 + STT 구독
  startNextSttSession()          →  두 번째~ 파이프 + STT 구독 (반복)

  하는 일이 거의 동일하고 history를 초기화하지 않는 것만 다릅니다.
  대화 이력은 통화 내내 계속 쌓아야 하기 때문입니다.

  ---
  한 줄 정리

  ▎ 이전 파이프/구독을 버리고 새것으로 교체해서
  ▎ 다음 발화를 받을 수 있는 상태로 리셋하는 메서드입니다.

```

### CtiWebSocketHandler.handleSttError() 아주 쉽게 설명해줘.
```
● handleSttError() 쉽게 설명

  ---
  한 줄 정의

  ▎ STT 에러가 났을 때 로그만 찍는 것

  ---
  코드

  private void handleSttError(String callId, Throwable error) {
      if (error instanceof TimeoutException) {
          log.debug("[CTI] STT 타임아웃 (정상 종료) callId={}", callId);
      } else {
          log.error("[CTI] STT 오류 callId={}", callId, error);
      }
  }

  ---
  에러 2가지 경우

  에러 종류
    │
    ├─ TimeoutException  →  log.debug (정상 종료)
    │
    └─ 그 외             →  log.error (진짜 오류)

  ---
  TimeoutException 을 정상 취급하는 이유

  60초 동안 발화 없음
    │
    ▼
  .timeout(60초) 발화 → TimeoutException

  BUT

  통화 중 60초 침묵은 흔한 상황
    → 에러가 아닌 정상 종료
    → log.debug 로만 기록

  ---
  호출 위치

  .subscribe(
      result -> handleFinalStt(...),   // 정상
      error  -> handleSttError(...)    // 에러 또는 타임아웃
  )

  ---
  한 줄 정리

  ▎ TimeoutException은 정상으로 보고 debug,
  ▎ 나머지는 진짜 오류로 보고 error 로그를 찍습니다.
  ▎ 로그만 찍고 끝나는 단순한 메서드입니다.
```

### CtiWebSocketHandler.sendJson() 아주 쉽게 설명해줘.
```
● sendJson() 쉽게 설명

  ---
  한 줄 정의

  ▎ Map을 JSON 문자열로 변환해서 브라우저로 전송하는 것

  ---
  코드

  private void sendJson(WebSocketSession session, Object data) throws Exception {
      if (session.isOpen()) {
          session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
      }
  }

  ---
  줄별 설명

  // 1. 연결이 살아있으면
  if (session.isOpen()) {

      // 2. Map → JSON 문자열 변환
      objectMapper.writeValueAsString(data)
      // Map.of("type", "STT_FINAL", "text", "안녕하세요")
      // → {"type":"STT_FINAL","text":"안녕하세요"}

      // 3. 브라우저로 전송
      session.sendMessage(new TextMessage(...));
  }

  ---
  session.isOpen() 체크하는 이유

  브라우저가 이미 연결을 끊었는데
  sendMessage() 호출하면 예외 발생 💥

  → isOpen() 으로 먼저 확인 후 전송

  ---
  호출 예시

  // handleFinalStt() 에서 이렇게 호출
  sendJson(session, Map.of("type", "STT_FINAL", "text", finalText));

  // 브라우저가 받는 것
  {"type":"STT_FINAL","text":"안녕하세요 문의드립니다"}

  ---
  한 줄 정리

  ▎ 연결 확인 → Map을 JSON으로 변환 → 전송.
  ▎ 3줄짜리 유틸 메서드입니다.
  
>> session.sendMessage() 에서 sendMessage() 는 WebSocketSession 의 기본 함수?
● 맞습니다.

  ---
  WebSocketSession 인터페이스

  public interface WebSocketSession {
      String getId();
      boolean isOpen();
      void sendMessage(WebSocketMessage<?> message) throws Exception;  // ← 여기
      void close() throws Exception;
      void close(CloseStatus status) throws Exception;
      // ...
  }

  Spring WebSocket이 제공하는 인터페이스입니다.
  AbstractWebSocketHandler와 마찬가지로 Spring 내장입니다.

  ---
  우리 코드에서 쓰는 것

  // JSON 전송 (TextMessage)
  session.sendMessage(new TextMessage("{ ... }"));

  // 오디오 전송 (BinaryMessage)
  session.sendMessage(new BinaryMessage(audioBytes));

  sendMessage() 하나로 Text/Binary 둘 다 보낼 수 있습니다.
  타입은 감싸는 클래스(TextMessage, BinaryMessage)로 구분합니다.

  ---
  한 줄 정리

  ▎ sendMessage()는 Spring WebSocketSession 인터페이스의 기본 메서드입니다.
  ▎ 우리가 구현한 게 아니라 Spring이 제공하는 것을 그대로 쓰는 것입니다.

>> TextMessage 클래스는 뭐야?
● TextMessage 클래스

  ---
  한 줄 정의

  ▎ WebSocket Text 프레임을 나타내는 Spring 클래스

  ---
  소스 확인

  jar xf spring-websocket-6.1.8-sources.jar

● Bash(cd /tmp && jar xf /home/vscode/.m2/repository/org/springframework/spring-websocket/6.1.8/spring-websocket-6.1.8-sources.jar org/springframework/web/socket/TextM…)
  ⎿       1     /*
          2      * Copyright 2002-2017 the original author or authors.
          3      *
     … +80 lines (ctrl+o to expand)
  ⎿  Shell cwd was reset to /workspaces/voicebot-js

● 구조

  WebSocketMessage<T>  (최상위 인터페이스)
          │
  AbstractWebSocketMessage<T>  (공통 구현)
          │
          ├── TextMessage    (String 페이로드)
          ├── BinaryMessage  (byte[] 페이로드)
          └── PongMessage    (heartbeat 응답)

  ---
  생성자 3가지

  // 1. 문자열로 생성 (우리가 쓰는 것)
  new TextMessage("{ \"type\": \"STT_FINAL\" }")

  // 2. byte[]로 생성 (UTF-8로 자동 변환)
  new TextMessage(bytes)

  // 3. 분할 전송용 (isLast=false면 다음 조각이 더 있다는 표시)
  new TextMessage("앞부분...", false)
  new TextMessage("뒷부분", true)

  ---
  우리 코드에서 쓰는 방식

  // Map → JSON 문자열 → TextMessage → 전송
  objectMapper.writeValueAsString(data)   // Map → "{...}"
  new TextMessage("{...}")                // 문자열 → TextMessage 포장
  session.sendMessage(TextMessage)        // 브라우저로 전송

  ---
  한 줄 정리

  ▎ JSON 문자열을 WebSocket Text 프레임으로 포장하는 봉투 역할입니다.
  ▎ Spring 내장 클래스이고 우리가 구현한 게 아닙니다.

```

### CtiWebSocketHandler.sendBinary() 아주 쉽게 설명해줘.
```
● sendBinary() 쉽게 설명

  ---
  한 줄 정의

  ▎ 오디오 바이트를 브라우저로 전송하는 것

  ---
  코드

  private void sendBinary(WebSocketSession session, byte[] data) throws Exception {
      if (session.isOpen() && data != null && data.length > 0) {
          session.sendMessage(new BinaryMessage(data));
      }
  }

      }
  }

      }
  }

  ---
  sendJson() 과 비교

  // sendJson — 문자열 (JSON)
  session.sendMessage(new TextMessage("{ \"type\": \"STT_FINAL\" }"));

  // sendBinary — 바이트 (오디오)
  session.sendMessage(new BinaryMessage(audioBytes));

  거의 동일하고 포장지만 다릅니다.

  ---
  조건 체크 3가지

  session.isOpen()   // 연결이 살아있어야
  data != null       // 데이터가 null이 아니어야
  data.length > 0    // 데이터가 비어있지 않아야

  sendJson()은 isOpen()만 체크했지만
  sendBinary()는 오디오 데이터가 없는 경우도 방어합니다.

  ---
  호출 위치

  // handleFinalStt() 에서
  byte[] audioBytes = ttsService.synthesize(response, callId);  // TTS 생성
  sendJson(session, Map.of("type", "TTS_TEXT", "text", response)); // 텍스트 먼저
  sendBinary(session, audioBytes);                                  // 오디오 다음

  ---
  한 줄 정리

  ▎ sendJson()의 오디오 버전.
  ▎ TTS가 만든 오디오를 브라우저로 전송합니다.
```

### `CtiWebSocketHandler` 전체 흐름 다시 정리해줘
```
● CtiWebSocketHandler 전체 흐름

  ---
  등장인물

  ┌─────────────────────┬───────────────────────────────────┐
  │        이름         │               역할                │
  ├─────────────────────┼───────────────────────────────────┤
  │ 브라우저            │ 음성 전송, 이벤트 전송, 결과 수신 │
  ├─────────────────────┼───────────────────────────────────┤
  │ CtiWebSocketHandler │ 전체 오케스트레이터               │
  ├─────────────────────┼───────────────────────────────────┤
  │ Sinks (파이프)      │ 음성 청크 브리지                  │
  ├─────────────────────┼───────────────────────────────────┤
  │ RTZR STT            │ 음성 → 텍스트                     │
  ├─────────────────────┼───────────────────────────────────┤
  │ Claude LLM          │ 텍스트 → 응답                     │
  ├─────────────────────┼───────────────────────────────────┤
  │ Google TTS          │ 응답 → 오디오                     │
  └─────────────────────┴───────────────────────────────────┘

  ---
  전체 흐름

  ① 브라우저 WebSocket 연결
          │
          ▼
  afterConnectionEstablished()
    - callId 발급
    - 대화 이력 초기화
    - 파이프 생성 + 보관 (sinkMap)
    - STT 구독 시작 (sink.asFlux() → RTZR)


  ② 브라우저 음성 청크 전송 (250ms마다)
          │ binary
          ▼
  handleBinaryMessage()
    - sinkMap에서 파이프 꺼냄
    - sink.tryEmitNext(chunk) → 파이프로 투입
          │
          ▼
  RtzrWebSocketSttService
    - audioStream.subscribe()
    - ws.send(chunk) → RTZR 전송


  ③ RTZR STT 결과 반환
          │
          ▼
  onMessage() 콜백
    - emitter.next(SttResult)
          │
          ▼
  .filter(isFinal)   — 중간 결과 버림
  .timeout(60초)     — 60초 침묵 시 종료
  .publishOn(boundedElastic)  — 스레드 전환
          │
          ▼
  handleFinalStt()
    - STT_FINAL      → 브라우저 전송
    - BOT_THINKING   → 브라우저 전송
    - history에 사용자 발화 추가
    - llmService.chat(history) → Claude 호출
    - LLM 응답 JSON 파싱 (intent / response)
    - history에 봇 응답 추가
    - LLM_RESULT     → 브라우저 전송
    - ttsService.synthesize() → Google TTS 호출
    - TTS_TEXT       → 브라우저 전송
    - sendBinary()   → 오디오 브라우저 전송
    - startNextSttSession()  — 다음 발화 준비
    - BOT_READY      → 브라우저 전송


  ④ 브라우저 CALL_END 전송 (통화 종료 버튼)
          │ { "type": "CTI_EVENT", "event": "CALL_END" }
          ▼
  handleTextMessage()
          │
          ▼
  handleCallEnd()
    - sink.tryEmitComplete() → 파이프 닫기
          │
          ▼
  audioStream 완료 → ws.send("EOS") → RTZR 연결 종료


  ⑤ WebSocket 연결 종료
          │
          ▼
  afterConnectionClosed()
    - sinkMap 제거 + tryEmitComplete()
    - disposableMap 제거 + dispose()
    - historyMap 제거
    - callIdMap 제거

  ---
  다화턴 대화 구조

  발화 1
    STT → LLM → TTS → startNextSttSession()
                              │
                              ▼
  발화 2  (history에 발화1 이력 포함)
    STT → LLM → TTS → startNextSttSession()
                              │
                              ▼
  발화 3  (history에 발화1,2 이력 포함)
    ...

  history가 계속 쌓이면서 Claude가 이전 대화를 기억합니다.

  ---
  4개 Map 역할 요약

  sinkMap       — sessionId → 파이프 (음성 청크 전달용)
  historyMap    — sessionId → 대화 이력 (다화턴용)
  callIdMap     — sessionId → callId (로그 추적용)
  disposableMap — sessionId → STT 구독 취소 핸들러

  ---
  한 줄 정리

  ▎ 브라우저 음성을 받아 STT → LLM → TTS 파이프라인을 실행하고
  ▎ 결과를 다시 브라우저로 돌려보내는 실시간 음성 대화 오케스트레이터입니다.

```

### `startNextSttSession()` 랑 `afterConnectionEstablished()` 차이 정리해줘
```
● startNextSttSession() vs afterConnectionEstablished() 차이

  ---
  한 줄 비교

  ┌──────────────────┬──────────────────────────────┬─────────────────────────────┐
  │                  │ afterConnectionEstablished() │    startNextSttSession()    │
  ├──────────────────┼──────────────────────────────┼─────────────────────────────┤
  │ 호출 시점        │ 통화 시작 (최초 1회)         │ 발화 끝날 때마다 (반복)     │
  ├──────────────────┼──────────────────────────────┼─────────────────────────────┤
  │ callId 발급      │ ✅                           │ ❌ (기존 callId 재사용)     │
  ├──────────────────┼──────────────────────────────┼─────────────────────────────┤
  │ 대화 이력 초기화 │ ✅ (빈 List 생성)            │ ❌ (기존 history 계속 사용) │
  ├──────────────────┼──────────────────────────────┼─────────────────────────────┤
  │ 파이프 생성      │ ✅                           │ ✅ (새것으로 교체)          │
  ├──────────────────┼──────────────────────────────┼─────────────────────────────┤
  │ 이전 파이프 정리 │ ❌ (없음)                    │ ✅                          │
  ├──────────────────┼──────────────────────────────┼─────────────────────────────┤
  │ STT 구독 시작    │ ✅                           │ ✅                          │
  └──────────────────┴──────────────────────────────┴─────────────────────────────┘

  ---
  코드 비교

  // afterConnectionEstablished() — 통화 시작
  callId = "CTI-" + UUID...;               // callId 새로 발급
  historyMap.put(sessionId, new ArrayList()); // 이력 새로 생성
  sink = Sinks.many()...;                  // 파이프 새로 생성
  sinkMap.put(sessionId, sink);
  sttService.recognize(sink.asFlux())...   // STT 구독 시작


  // startNextSttSession() — 발화 후 리셋
  old.dispose();                           // 이전 구독 취소
  oldSink.tryEmitComplete();               // 이전 파이프 닫기
  newSink = Sinks.many()...;              // 파이프 새로 생성
  sinkMap.put(sessionId, newSink);
  sttService.recognize(newSink.asFlux()).. // STT 구독 재시작

  ---
  흐름으로 보면

  WebSocket 연결
        │
        ▼
  afterConnectionEstablished()   ← callId 발급, history 생성, 파이프 생성
        │
        ▼
  발화 1 → STT → LLM → TTS
        │
        ▼
  startNextSttSession()          ← 파이프만 교체, history는 유지
        │
        ▼
  발화 2 → STT → LLM → TTS
        │
        ▼
  startNextSttSession()          ← 파이프만 교체, history는 유지
        │
        ▼
  발화 3 ...

  ---
  핵심 차이 한 줄

  ▎ afterConnectionEstablished()는 통화 전체를 초기화,
  ▎ startNextSttSession()는 파이프만 교체하고 대화 이력은 유지합니다.

```

### RtzrWebSocketSttService.java 아주 쉽게 설명해줘.
```
● RtzrWebSocketSttService 쉽게 설명

  ---
  한 줄 정의

  ▎ 음성 청크를 받아 RTZR WebSocket으로 전송하고 STT 결과를 반환하는 것

  ---
  전체 구조

  CtiWebSocketHandler
    │ sink.asFlux() (음성 청크 스트림)
    ▼
  RtzrWebSocketSttService.recognize()
    │
    ├─ RTZR WebSocket 연결
    ├─ 음성 청크 → RTZR 전송
    └─ STT 결과 → Flux<SttResult> 반환

  ---
  필드

  @Value("${voicebot.stt.rtzr.client-id}")
  private String clientId;          // RTZR 인증 ID

  @Value("${voicebot.stt.rtzr.client-secret}")
  private String clientSecret;      // RTZR 인증 Secret

  private String sampleRate = 8000; // 음성 샘플레이트
  private String encoding = "LINEAR16"; // 음성 인코딩 방식

  private AtomicReference<String> accessToken;  // RTZR 액세스 토큰
  private volatile long expireAt;               // 토큰 만료 시간

  ---
  메서드별 설명

  1. init() — 서버 시작 시 토큰 발급

  @PostConstruct
  public void init() {
      refreshToken();  // 서버 시작하자마자 토큰 발급
  }

  ---
  2. scheduleTokenRefresh() — 토큰 자동 갱신

  @Scheduled(fixedRate = 300_000)  // 5분마다 실행
  public void scheduleTokenRefresh() {
      if (토큰 만료 10분 이내) {
          refreshToken();  // 갱신
      }
  }

  토큰 만료까지 10분 이상   →  갱신 안 함
  토큰 만료까지 10분 이내   →  미리 갱신 ✅

  ---
  3. recognize() — 핵심 메서드

  public Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId) {
      return Flux.create(emitter -> {

          // ① RTZR WebSocket 연결
          WebSocket ws = okHttpClient.newWebSocket(url, new WebSocketListener() {

              void onOpen()    { /* 연결됨 로그 */ }

              void onMessage() {
                  // ② RTZR STT 결과 수신
                  emitter.next(new SttResult(text, isFinal));  // Flux로 내보냄
                  if (isFinal) ws.close();                     // 최종 결과면 닫기
              }

              void onClosed()  { emitter.complete(); }  // 정상 종료
              void onFailure() { emitter.error(t); }    // 오류 종료
          });

          // ③ 음성 청크 → RTZR 전송
          audioStream.subscribe(
              chunk -> ws.send(chunk),       // 청크 올 때마다 전송
              error -> ws.close(),           // 에러 시 닫기
              ()    -> ws.send("EOS")        // 스트림 끝나면 EOS 전송
          );

          // ④ 구독 취소 시 WebSocket 닫기
          emitter.onCancel(() -> ws.close());
      });
  }

  ---
  4. refreshToken() — 토큰 발급

  private void refreshToken() {
      // RTZR 인증 서버에 clientId + clientSecret 전송
      // → accessToken, expireAt 받아서 저장
  }

  ---
  전체 흐름

  서버 시작
    │
    ▼
  init() → refreshToken()  — 토큰 발급
    │
    │  (5분마다)
    ▼
  scheduleTokenRefresh()   — 토큰 자동 갱신


  통화 중
    │
    ▼
  recognize(audioStream, callId)
    │
    ├─ RTZR WebSocket 연결
    │
    ├─ audioStream.subscribe()
    │     │ 청크 올 때마다
    │     ▼
    │   ws.send(chunk) → RTZR
    │
    ├─ RTZR → onMessage()
    │     │
    │     ▼
    │   emitter.next(SttResult)  — 중간 결과
    │   emitter.next(SttResult)  — 최종 결과 (isFinal=true)
    │     │
    │     ▼
    │   ws.close() → onClosed() → emitter.complete()
    │
    └─ Flux<SttResult> 반환
            │
            ▼
        CtiWebSocketHandler
        .filter(isFinal)
        .subscribe(handleFinalStt)

  ---
  한 줄 정리

  ▎ 토큰을 관리하면서 RTZR WebSocket에 음성을 흘려보내고,
  ▎ STT 결과를 Flux<SttResult>로 돌려주는 RTZR 연동 어댑터입니다.

```

###  `refreshToken()` 아주 쉽게 설명해줘
```
● refreshToken() 쉽게 설명

  ---
  한 줄 정의

  ▎ RTZR API 를 쓰기 위한 입장권(토큰)을 발급받는 것

  ---
  코드

  private synchronized void refreshToken() {
      try {
          // 1. clientId + clientSecret 으로 인증 요청
          String body = "client_id=" + clientId + "&client_secret=" + clientSecret;

          Map<?, ?> response = webClient.post()
                  .uri("https://openapi.vito.ai/v1/authenticate")
                  .bodyValue(body)
                  .retrieve()
                  .bodyToMono(Map.class)
                  .block();

          // 2. 토큰 + 만료시간 저장
          accessToken.set((String) response.get("access_token"));
          expireAt = ((Number) response.get("expire_at")).longValue();

      } catch (Exception e) {
          log.error("[STT-RTZR] 토큰 발급 실패", e);
      }
  }

  ---
  비유

  RTZR API = 놀이공원
  clientId + clientSecret = 회원 아이디/비밀번호
  accessToken = 입장권
  expireAt = 입장권 만료 시간

  ---
  줄별 설명

  // 1. 아이디 + 비밀번호로 RTZR 인증 서버에 요청
  "https://openapi.vito.ai/v1/authenticate"

  // 2. 입장권 + 만료시간 받아서 저장
  accessToken = "eyJhbGci..."  // 이후 RTZR 호출 시 Header에 사용
  expireAt    = 1234567890     // Unix timestamp (초 단위)

  ---
  토큰 사용 위치

  // recognize() 에서 RTZR WebSocket 연결 시
  Request request = new Request.Builder()
          .url(buildWsUrl())
          .header("Authorization", "Bearer " + accessToken.get())  // ← 여기
          .build();

  ---
  synchronized 인 이유

  5분마다 스케줄러 실행
    +
  서버 시작 시 init() 실행

  → 동시에 refreshToken() 호출될 수 있음
  → synchronized 로 한 번에 하나만 실행 보장

  ---
  한 줄 정리

  ▎ RTZR 인증 서버에 아이디/비밀번호를 보내고
  ▎ 입장권(accessToken)을 받아서 저장하는 메서드입니다.

>> private final AtomicReference<String> accessToken = new AtomicReference<>(""); 뭐야?
● AtomicReference 쉽게 설명

  ---
  한 줄 정의

  ▎ 멀티스레드 환경에서 안전하게 값을 교체할 수 있는 변수

  ---
  왜 일반 String 안 쓰나?

  // 일반 String
  private String accessToken = "";

  // 스레드 A: 토큰 읽는 중
  // 스레드 B: 토큰 교체 중
  // → 동시에 발생하면 스레드 A가 깨진 값을 읽을 수 있음 💥

  ---
  AtomicReference 는

  private final AtomicReference<String> accessToken = new AtomicReference<>("");

  // 읽기
  accessToken.get()          // 현재 토큰 읽기

  // 쓰기
  accessToken.set("새토큰")  // 토큰 교체 (원자적으로 교체)

  set() 과 get() 이 원자적(atomic) 으로 동작합니다.

  ---
  원자적이란?

  일반 String 교체
    ① 메모리에서 기존값 지우기
    ② 새값 쓰기
    → ①과 ② 사이에 다른 스레드가 끼어들 수 있음 💥

  AtomicReference.set()
    ① + ② 를 한 번에 처리
    → 끼어들 틈 없음 ✅

  ---
  이 프로젝트에서 상황

  스레드 A: scheduleTokenRefresh() → accessToken.set(새토큰)
  스레드 B: recognize() → accessToken.get() 으로 토큰 읽기

  → 동시에 발생해도 안전 ✅

  ---
  final 인 이유

  private final AtomicReference<String> accessToken

  // AtomicReference 객체 자체는 교체 안 함
  // 안에 담긴 String 값만 교체
  // 그래서 final 가능

  ---
  한 줄 정리

  ▎ 여러 스레드가 동시에 토큰을 읽고 쓸 때
  ▎ 값이 깨지지 않도록 보호하는 스레드 안전 변수입니다.

>> 다음 질문 답변해줘.
  1) private volatile long expireAt = 0; 무엇이고
  2) volatile 키워드 의미?
  3) expireAt = ((Number) response.get("expire_at")).longValue(); 에서 왜 (Number) 하는지?

● 답변

  ---
  1) expireAt 이란?

  private volatile long expireAt = 0;

  ▎ 토큰 만료 시간을 저장하는 변수 (Unix timestamp, 초 단위)

  // 사용 위치 — scheduleTokenRefresh()
  long now = System.currentTimeMillis() / 1000;  // 현재 시간 (초)
  if (expireAt - now < 600) {                    // 만료까지 10분 이내면
      refreshToken();                            // 토큰 갱신
  }

  expireAt = 1234567890  (RTZR이 알려준 만료 시간)
  now      = 1234567300  (현재 시간)
  차이     = 590초 < 600초  →  갱신 ✅

  ---
  2) volatile 키워드 의미

  멀티스레드 환경에서 변수를 항상 메인 메모리에서 읽고 쓰도록 강제합니다.

  volatile 없으면

  CPU 코어 1 (스케줄러 스레드)   CPU 코어 2 (갱신 스레드)
    캐시: expireAt = 0            캐시: expireAt = 1234567890
         ↑                              ↑
         캐시에서 읽음                   메인 메모리에 썼지만
         → 오래된 값 읽음 💥             코어 1 캐시엔 반영 안 됨

  volatile 있으면

  CPU 코어 1 (스케줄러 스레드)   CPU 코어 2 (갱신 스레드)
         ↓                              ↓
         메인 메모리에서 직접 읽음       메인 메모리에 직접 씀
         → 항상 최신값 읽음 ✅

  ---
  AtomicReference vs volatile 비교

  ┌─────────┬───────────────────────────┬──────────────────────────┐
  │         │      AtomicReference      │         volatile         │
  ├─────────┼───────────────────────────┼──────────────────────────┤
  │ 타입    │ 객체 (String 등)          │ 기본 타입 (long, int 등) │
  ├─────────┼───────────────────────────┼──────────────────────────┤
  │ 용도    │ 읽기 + 교체 동시에 원자적 │ 읽기/쓰기 가시성 보장    │
  ├─────────┼───────────────────────────┼──────────────────────────┤
  │ 이 코드 │ accessToken (String)      │ expireAt (long)          │
  └─────────┴───────────────────────────┴──────────────────────────┘

  expireAt은 단순 읽기/쓰기만 하므로 volatile로 충분합니다.

  ---
  3) 왜 (Number) 캐스팅?

  expireAt = ((Number) response.get("expire_at")).longValue();

  response는 Map<?, ?> 타입입니다.
  JSON을 Map으로 변환할 때 숫자 타입이 뭐로 들어올지 불확실합니다.

  JSON: { "expire_at": 1234567890 }
          │
          ▼
  Jackson이 Map으로 변환할 때
    작은 숫자  →  Integer
    큰 숫자   →  Long    ← Unix timestamp는 큰 숫자

  // 직접 캐스팅하면 위험
  (Long) response.get("expire_at")   // Integer로 들어오면 ClassCastException 💥
  (Integer) response.get("expire_at") // Long으로 들어오면 ClassCastException 💥

  // Number로 받으면 안전
  ((Number) response.get("expire_at")).longValue()
  // Integer든 Long이든 Number의 자식
  // → longValue()로 꺼내면 항상 long ✅

  ---
  Number 상속 구조

  Number (추상 클래스)
    ├── Integer
    ├── Long
    ├── Double
    └── Float

  ▎ Number로 받으면 Integer든 Long이든 무조건 받을 수 있고
  ▎ .longValue()로 꺼내면 항상 long으로 변환됩니다.

>> volatile 은 기본적으로 효율성 때문에 캐시에서 가져오는 것을 항상 메인 메모리에서 가져와서 항상 최신 값을 참조해라. 맞어? 
● 맞습니다. 정확합니다.

  ---
  정리

  CPU 기본 동작
    메인 메모리 → CPU 캐시 → 연산
    (빠르지만 캐시가 오래된 값일 수 있음)

  volatile 적용
    메인 메모리 → 연산  (캐시 건너뜀)
    (느리지만 항상 최신값 보장)

  ---
  읽기 + 쓰기 둘 다 해당

  // 읽기 — 메인 메모리에서 직접 읽음
  if (expireAt - now < 600)

  // 쓰기 — 메인 메모리에 직접 씀 (다른 스레드 캐시도 무효화)
  expireAt = 1234567890;

  쓸 때도 메인 메모리에 직접 써서
  다른 스레드가 캐시에서 오래된 값을 읽는 것을 방지합니다.

  ---
  한 줄 정리

  ▎ 효율(캐시) 포기 → 정확성(최신값) 보장.
  ▎ 멀티스레드에서 항상 최신값을 읽고 쓰게 강제하는 키워드입니다.

>> .longValue() 으로 먼저 꺼내고서 (Number) 적용되는거지?
● 아닙니다. 반대입니다.

  ---
  실행 순서

  ((Number) response.get("expire_at")).longValue();

  // 1. response.get("expire_at")   →  Object 꺼내기
  // 2. (Number) 캐스팅              →  Number 로 변환
  // 3. .longValue()                →  long 으로 꺼내기

  ---
  괄호로 보면

  (  (Number) response.get("expire_at")  ) .longValue()
  │  └─────────────────────────────────┘   │
  │          먼저 Number로 캐스팅           │
  └─────────────────────────────────────────┘
                그 다음 longValue() 호출

  ---
  한 줄 정리

  ▎ (Number) 캐스팅 먼저 → 그 결과에 .longValue() 호출 순서입니다.

>> 참조 소스 각 체인을 설명해줘.
>>>> 참조 소스
Map<?, ?> response = webClient.post()
        .uri("https://openapi.vito.ai/v1/authenticate")
        .bodyValue(body)
        .retrieve()
        .bodyToMono(Map.class)
        .block();
        
● WebClient 체인 설명

  ---
  전체 흐름

  webClient.post()          → POST 요청 준비
    .uri(...)               → 목적지 URL 지정
    .bodyValue(body)        → 요청 본문 설정
    .retrieve()             → 요청 전송
    .bodyToMono(Map.class)  → 응답을 Map으로 변환
    .block()                → 결과가 올 때까지 대기

  ---
  줄별 설명

  1. webClient.post()

  // HTTP 메서드 지정
  // GET, POST, PUT, DELETE 중 POST 선택
  webClient.post()

  ---
  2. .uri("https://openapi.vito.ai/v1/authenticate")

  // 어디로 보낼지 URL 지정
  .uri("https://openapi.vito.ai/v1/authenticate")

  ---
  3. .bodyValue(body)

  // 요청 본문 설정
  // body = "client_id=xxx&client_secret=yyy"
  .bodyValue(body)

  HTTP 요청
    POST https://openapi.vito.ai/v1/authenticate
    Body: client_id=xxx&client_secret=yyy

  ---
  4. .retrieve()

  // 실제 HTTP 요청 전송
  // 응답을 받을 준비
  .retrieve()

  여기서 네트워크 요청이 실제로 나갑니다.

  ---
  5. .bodyToMono(Map.class)

  // 응답 JSON → Map 으로 변환
  .bodyToMono(Map.class)

  응답 JSON
  { "access_token": "eyJhbG...", "expire_at": 1234567890 }
          │
          ▼
  Map { "access_token" → "eyJhbG...", "expire_at" → 1234567890 }

  Mono = 결과가 1개인 비동기 컨테이너

  ---
  6. .block()

  // Mono가 완료될 때까지 현재 스레드 대기
  // 결과가 오면 Map으로 꺼냄
  .block()

  Mono<Map>  →  block()  →  Map (실제 값)
  (비동기)                   (동기)

  block() 없으면 Mono<Map> 그대로라 값을 꺼낼 수 없습니다.

  ---
  한 줄 정리

  준비(post) → URL → 본문 → 전송(retrieve) → 변환(bodyToMono) → 대기(block)

  ▎ RTZR 인증 서버에 POST 요청을 보내고
  ▎ 응답 JSON을 Map으로 변환해서 반환하는 동기 HTTP 호출입니다.

>> .bodyToMono(Map.class) => Mono 이면 응답은 1개이다. 라는건가?

● 맞습니다.

  ---
  Mono vs Flux

  ┌─────────────┬─────────────────────────┬──────────────────────┐
  │             │          Mono           │         Flux         │
  ├─────────────┼─────────────────────────┼──────────────────────┤
  │ 데이터 개수 │ 0개 또는 1개            │ 0개 ~ N개            │
  ├─────────────┼─────────────────────────┼──────────────────────┤
  │ 용도        │ 단건 응답               │ 스트림 응답          │
  ├─────────────┼─────────────────────────┼──────────────────────┤
  │ 예시        │ HTTP 응답, DB 단건 조회 │ 음성 청크, 목록 조회 │
  └─────────────┴─────────────────────────┴──────────────────────┘

  ---
  이 코드에서

  .bodyToMono(Map.class)

  HTTP 응답은 딱 1번 옵니다.

  요청 전송
    │
    ▼
  { "access_token": "eyJhbG...", "expire_at": 1234567890 }  ← 1개
    │
    ▼
  Mono<Map>  →  block()  →  Map

  ---
  만약 Flux 였다면

  // 여러 개 응답이 오는 경우 (스트리밍 등)
  .bodyToFlux(Map.class)

  STT 결과 1  →
  STT 결과 2  →  Flux<Map>
  STT 결과 3  →
  ...

  ---
  한 줄 정리

  ▎ HTTP 응답은 한 번만 오므로 Mono,
  ▎ 음성 청크처럼 계속 오는 데이터는 Flux 입니다.

```

### `buildWsUrl()` 아주 쉽게 설명해줘
```
● buildWsUrl() 쉽게 설명

  ---
  한 줄 정의

  ▎ RTZR WebSocket 접속 주소를 만드는 것

  ---
  코드

  private String buildWsUrl() {
      String url = "wss://openapi.vito.ai/v1/transcribe:streaming" +
             "?sample_rate=" + sampleRate +
             "&encoding=" + encoding +
             "&use_itn=true" +
             "&use_disfluency_filter=true" +
             "&use_profanity_filter=false" +
             "&use_punctuation=false";
      log.info("[STT-RTZR] URL={}", url);
      return url;
  }

  ---
  만들어지는 URL 예시

  wss://openapi.vito.ai/v1/transcribe:streaming
    ?sample_rate=8000
    &encoding=LINEAR16
    &use_itn=true
    &use_disfluency_filter=true
    &use_profanity_filter=false
    &use_punctuation=false

  ---
  옵션 하나씩 의미

  │          파라미터          │                   의미                    │
  ├────────────────────────────┼───────────────────────────────────────────┤
  │ sample_rate=8000           │ 음성 샘플레이트 (8kHz, 전화망 표준)       │
  ├────────────────────────────┼───────────────────────────────────────────┤
  │ encoding=LINEAR16          │ PCM 16bit 인코딩                          │
  ├────────────────────────────┼───────────────────────────────────────────┤
  │ use_itn=true               │ 숫자를 자연스럽게 변환 ("일이삼" → "123") │
  ├────────────────────────────┼───────────────────────────────────────────┤
  │ use_disfluency_filter=true │ 말더듬/간투사 제거 ("어... 그게...")      │
  ├────────────────────────────┼───────────────────────────────────────────┤
  │ use_profanity_filter=false │ 비속어 필터링 안 함                       │
  ├────────────────────────────┼───────────────────────────────────────────┤
  │ use_punctuation=false      │ 문장부호 자동 삽입 안 함                  │
  └────────────────────────────┴───────────────────────────────────────────┘

  ---
  사용 위치

  // recognize() 에서
  Request request = new Request.Builder()
          .url(buildWsUrl())   // ← 여기서 호출
          .header("Authorization", "Bearer " + accessToken.get())
          .build();

  ---
  한 줄 정리

  ▎ 옵션값들을 쿼리 파라미터로 이어붙여서
  ▎ RTZR WebSocket 접속 주소 문자열을 만드는 단순 빌더 메서드입니다.

```

### RtzrWebSocketSttService.scheduleTokenRefresh() 쉽게 설명해줘.

```
● scheduleTokenRefresh() 쉽게 설명

  ---
  한 줄 정의

  ▎ 5분마다 토큰이 곧 만료될지 확인하고, 그러면 미리 갱신하는 것

  ---
  코드

  @Scheduled(fixedRate = 300_000)  // 5분(300,000ms)마다 실행
  public void scheduleTokenRefresh() {
      long now = System.currentTimeMillis() / 1000;
      if (expireAt - now < 600) {  // 만료까지 10분(600초) 이내면
          log.info("[STT-RTZR] 토큰 갱신 시작");
          refreshToken();
      }
  }

  ---
  비유

  운전면허 갱신과 비슷합니다.

  5분마다 면허 확인 (스케줄러)
    │
    ▼
  "만료까지 10분 남았나?"
    │
    ├─ 아니오 → 그냥 둠
    └─ 예     → 미리 갱신

  ---
  시간 계산

  long now = System.currentTimeMillis() / 1000;  // 현재 시간 (초)
  if (expireAt - now < 600) { ... }               // 600초 = 10분

  expireAt = 1234567890  (토큰 만료 시간)
  now      = 1234567350
  차이     = 540초 < 600초  →  갱신 ✅

  expireAt = 1234567890
  now      = 1234560000
  차이     = 7890초 > 600초  →  안 갱신

  ---
  @Scheduled(fixedRate = 300_000) 의미

  @Scheduled(fixedRate = 300_000)

  ┌────────────┬───────────────────────────────────────┐
  │    부분    │                 의미                  │
  ├────────────┼───────────────────────────────────────┤
  │ @Scheduled │ Spring이 주기적으로 자동 실행         │
  ├────────────┼───────────────────────────────────────┤
  │ fixedRate  │ 이전 실행 시작 시점부터 일정 간격으로 │
  ├────────────┼───────────────────────────────────────┤
  │ 300_000    │ 300,000ms = 5분                       │
  └────────────┴───────────────────────────────────────┘

  ---
  왜 10분 여유를 두나

  딱 만료 시점에 갱신하면

  토큰 만료 직전 RTZR 호출
    │
    ▼
  토큰 만료됨 + 아직 안 갱신됨
    │
    ▼
  인증 실패 💥

  → 10분 여유 두고 미리 갱신해서 이런 상황 방지

  ---
  한 줄 정리

  ▎ 5분마다 깨어나서 "토큰 곧 만료되나?" 확인하고,
  ▎ 그렇다면 끊기기 전에 미리 갱신하는 안전장치입니다.
```

### RtzrWebSocketSttService.recognize() 쉽게 설명해줘.
```
● recognize() 쉽게 설명

  ---
  한 줄 정의

  ▎ 음성 청크를 받아서 RTZR로 보내고, STT 결과를 돌려주는 메서드

  ---
  입력과 출력

  public Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId)
  //                                └─ 입력: 음성 청크 스트림
  //     └─ 출력: STT 결과 스트림

  ---
  전체 구조 4단계

  return Flux.create(emitter -> {

      // ① RTZR WebSocket 연결 준비
      Request request = ...

      // ② WebSocket 연결 + 콜백 등록
      WebSocket ws = okHttpClient.newWebSocket(request, new WebSocketListener() {
          onOpen()    { ... }
          onMessage() { emitter.next(...) }   // 결과 오면 Flux로 내보냄
          onClosed()  { emitter.complete(); }
          onFailure() { emitter.error(t); }
      });

      // ③ 입력받은 음성 청크를 RTZR로 전송
      audioStream.subscribe(
          chunk -> ws.send(chunk),
          error -> ws.close(),
          ()    -> ws.send("EOS")
      );

      // ④ 구독 취소 시 WebSocket도 닫기
      emitter.onCancel(() -> ws.close());
  });

  ---
  그림으로 보면

  [입력]                         [출력]
  audioStream (음성 청크)         emitter (STT 결과)
        │                              ▲
        ▼                              │
    ws.send(chunk)              emitter.next(SttResult)
        │                              ▲
        ▼                              │
     ┌─────────────────────────────────┐
     │         RTZR WebSocket           │
     └─────────────────────────────────┘

  음성을 넣는 입구와 결과가 나오는 출구가 같은 WebSocket 연결을 통해 동시에 일어납니다.

  ---
  비유

  전화 통역사를 고용하는 것과 같습니다.

  음성 보내기 (audioStream.subscribe)
    → 통역사(RTZR)에게 계속 말을 들려줌

  결과 받기 (onMessage → emitter.next)
    → 통역사가 통역한 결과를 계속 전달받음

  통화 끝 (EOS / onClosed)
    → "그만 들어도 돼요" 신호 → 통역사가 마지막 정리 후 마침

  ---
  한 줄 정리

  ▎ WebSocket 콜백(콜백 세계)을 emitter로 포장해서 Flux(리액티브 세계)로 바꾸고,
  ▎ 동시에 입력 audioStream을 구독해서 RTZR로 실시간 전송하는 양방향 중계 메서드입니다.

>> onMessage() 안에서 JSON 파싱하는 부분 쉽게 설명해줘
● onMessage() JSON 파싱 쉽게 설명

  ---
  코드

  @Override
  public void onMessage(WebSocket webSocket, String text) {
      log.info("[STT-RTZR] 메시지 수신 callId={} raw={}", callId, text);
      try {
          JsonNode node = objectMapper.readTree(text);

          if (node.has("error")) {
              emitter.error(new RuntimeException("RTZR error: " + node.get("error").asText()));
              return;
          }

          boolean isFinal = node.path("final").asBoolean();
          JsonNode alternatives = node.path("alternatives");

          if (alternatives.isArray() && !alternatives.isEmpty()) {
              String recognized = alternatives.get(0).path("text").asText();
              emitter.next(new SttResult(recognized, isFinal));
              if (isFinal) {
                  webSocket.close(1000, "done");
              }
          }
      } catch (Exception e) {
          log.warn("[STT-RTZR] 응답 파싱 오류 callId={} raw={}", callId, text, e);
      }
  }

  ---
  RTZR이 보내는 JSON 예시

  {
    "final": true,
    "alternatives": [
      { "text": "안녕하세요 문의드립니다" }
    ]
  }

  ---
  단계별 설명

  1. JSON 문자열 → JsonNode 변환

  JsonNode node = objectMapper.readTree(text);
  문자열을 트리 구조로 분해해서 값을 꺼낼 수 있게 만듭니다.

  ---
  2. 에러 응답인지 확인

  if (node.has("error")) {
      emitter.error(...);  // Flux에 에러 전달
      return;               // 더 이상 진행 안 함
  }

  { "error": "invalid token" }  ← 이런 경우

  ---
  3. 최종 결과인지 확인

  boolean isFinal = node.path("final").asBoolean();

  isFinal = false  →  인식 중간 결과 ("안...", "안녕...")
  isFinal = true   →  최종 확정 결과 ("안녕하세요 문의드립니다")

  ---
  4. 인식된 텍스트 꺼내기

  JsonNode alternatives = node.path("alternatives");
  if (alternatives.isArray() && !alternatives.isEmpty()) {
      String recognized = alternatives.get(0).path("text").asText();

  "alternatives": [
    { "text": "안녕하세요 문의드립니다" },   ← [0] 첫 번째 (가장 정확도 높음)
    { "text": "안녕하세요 문의 드립니다" }   ← [1] 대안 후보 (사용 안 함)
  ]

  여러 인식 후보 중 첫 번째(가장 신뢰도 높은 것) 만 사용합니다.

  ---
  5. Flux로 결과 내보내기

  emitter.next(new SttResult(recognized, isFinal));
  if (isFinal) {
      webSocket.close(1000, "done");  // 최종 결과면 연결 닫기
  }

  ---
  .path() vs .get() 차이

  node.path("error")   // 없으면 빈 노드 반환 (안전)
  node.get("error")    // 없으면 null 반환 (NPE 위험)

  이 코드가 .path()를 쓰는 이유는 필드가 없어도 예외 없이 안전하게 처리하기 위함입니다.

  ---
  한 줄 정리

  ▎ RTZR이 보낸 JSON에서 에러 여부, 최종 여부, 인식된 텍스트를 꺼내서
  ▎ SttResult 객체로 만들어 Flux 밖으로 내보내는 파싱 로직입니다.

>> SttResult 클래스 쉽게 설명해줘
● SttResult 쉽게 설명

  ---
  코드

  record SttResult(String text, boolean isFinal) {}

  이게 전부입니다. 딱 1줄입니다.

  ---
  record란?

  ▎ Java의 데이터 묶음 전용 클래스 (Java 16+)

  record SttResult(String text, boolean isFinal) {}

  이 한 줄이 자동으로 만들어주는 것들:

  // 자동 생성되는 것 (record가 컴파일 시 만들어줌)
  class SttResult {
      private final String text;
      private final boolean isFinal;

      SttResult(String text, boolean isFinal) { ... }  // 생성자

      String text() { return text; }        // getter
      boolean isFinal() { return isFinal; } // getter

      equals(), hashCode(), toString()       // 자동 생성
  }

  ---
  필드 2개

  ┌─────────┬───────────────────────────────────────────┐
  │  필드   │                   의미                    │
  ├─────────┼───────────────────────────────────────────┤
  │ text    │ 인식된 텍스트 ("안녕하세요 문의드립니다") │
  ├─────────┼───────────────────────────────────────────┤
  │ isFinal │ 중간 결과(false) / 최종 결과(true)        │
  └─────────┴───────────────────────────────────────────┘

  ---
  사용 예시

  // 생성
  new SttResult("안녕하세요", true)

  // 값 꺼내기 — getter가 아니라 메서드처럼 호출
  result.text()      // "안녕하세요"
  result.isFinal()   // true

  // CtiWebSocketHandler 에서
  .filter(SttService.SttResult::isFinal)   // isFinal()이 true인 것만 통과
  ...
  result -> handleFinalStt(session, callId, result.text(), capturedHistory)
  //                                          ↑ text() 호출

  ---
  일반 class와 비교

  // record 사용 (1줄)
  record SttResult(String text, boolean isFinal) {}

  // 일반 class였다면 (15줄 이상)
  class SttResult {
      private final String text;
      private final boolean isFinal;
      public SttResult(String text, boolean isFinal) {
          this.text = text;
          this.isFinal = isFinal;
      }
      public String getText() { return text; }
      public boolean isFinal() { return isFinal; }
      @Override public boolean equals(Object o) { ... }
      @Override public int hashCode() { ... }
      @Override public String toString() { ... }
  }

  ---
  한 줄 정리

  ▎ text와 isFinal 두 값을 묶어서 전달하기 위한 불변 데이터 객체입니다.
  ▎ getter/생성자/equals 등을 Java가 자동 생성해주는 record 문법을 썼습니다.

```

### 다음의 질문에 답해줘.
```
>> 질문
1) 오케트라 WebSocket 콜백에서 Sinks.Many 로 Flux 구독해서 STT 로 데이터 들어오면 흘러가게하고 => public Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId) {...
2) STT OkHttp 콜백에서 Flux.create 로 Flux 구독해서 STT 응답오면 오케트라로 데이터 흐르게 한다. => sttService.recognize()..subscribe(result -> handleFinalStt(..) 맞어?


● 둘 다 살짝 헷갈린 부분이 있습니다. "누가 데이터를 만드는지(생산)" 와 "누가 구독하는지(소비)" 가 바뀌어 있습니다.

  ---
  정정 1) 음성 청크 흐름

  CtiWebSocketHandler.handleBinaryMessage()
    → sink.tryEmitNext(chunk)     ← 구독이 아니라 "투입"

  RtzrWebSocketSttService.recognize()
    → audioStream.subscribe(...)  ← 여기가 진짜 "구독"

  CtiWebSocketHandler는 Flux를 구독하지 않습니다. Sinks.Many를 만들고 거기에 데이터를 밀어 넣기만 합니다. 그 출구(sink.asFlux())를 구독하는 건 RtzrWebSocketSttService 내부입니다.

  // RtzrWebSocketSttService.recognize() 내부
  audioStream.subscribe(           // ← 구독은 여기서!
      chunk -> ws.send(chunk),
      ...
  );

  ---
  정정 2) STT 결과 흐름

  RtzrWebSocketSttService (OkHttp onMessage 콜백)
    → emitter.next(new SttResult(...))   ← 구독이 아니라 "투입"

  CtiWebSocketHandler.afterConnectionEstablished()
    → sttService.recognize(...).subscribe(result -> handleFinalStt(...))  ← 여기가 진짜 "구독"

  OkHttp 콜백(onMessage)도 구독이 아닙니다. Flux.create(emitter -> ...)로 만들어진 Flux 안에 결과를 밀어 넣기만 합니다. 그 Flux를 구독하는 건 CtiWebSocketHandler입니다.

  // CtiWebSocketHandler.afterConnectionEstablished() 내부
  Disposable d = sttService.recognize(sink.asFlux(), callId)
          .filter(...)
          .subscribe(result -> handleFinalStt(...));  // ← 구독은 여기서!

  ---
  표로 정리

  ┌─────────────────────────────────┬─────────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────────────────┐
  │              Flux               │                       생산(투입) 위치                       │                          구독(소비) 위치                           │
  ├─────────────────────────────────┼─────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────┤
  │ 음성 청크 (sink.asFlux())       │ CtiWebSocketHandler.handleBinaryMessage() → tryEmitNext()   │ RtzrWebSocketSttService.recognize() 내부 → audioStream.subscribe() │
  ├─────────────────────────────────┼─────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────┤
  │ STT 결과 (Flux.create(emitter)) │ RtzrWebSocketSttService OkHttp onMessage() → emitter.next() │ CtiWebSocketHandler.afterConnectionEstablished() → .subscribe()    │
  └─────────────────────────────────┴─────────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────────────────┘

  ---
  그림으로 한눈에

  [CtiWebSocketHandler]                         [RtzrWebSocketSttService]

  handleBinaryMessage()
    sink.tryEmitNext(chunk)  ──생산──▶  sink.asFlux()
                                              │
                                        audioStream.subscribe()  ──구독──┐
                                              │                          │
                                              ▼                          │
                                          ws.send(chunk) → RTZR ◀────────┘


                                        onMessage() 콜백
                                          emitter.next(result)  ◀──생산──┐
                                              │                          │
  afterConnectionEstablished()               │                          │
    .subscribe(result -> handleFinalStt)  ◀──구독──── Flux.create(emitter)

  ---
  한 줄 정리

  ▎ 각자 자기 쪽 Flux는 "만들고 투입"만 하고, 상대방 Flux를 "구독"합니다.
  ▎ CtiWebSocketHandler: 음성 Flux는 만들기만, STT결과 Flux는 구독
  ▎ RtzrWebSocketSttService: 음성 Flux는 구독, STT결과 Flux는 만들기만

```

### startNextSttSession()에서도 생산/구독 누가 하는지 정리해줘
```
● startNextSttSession() 생산/구독 정리

  구조는 afterConnectionEstablished()와 완전히 동일합니다. 파이프만 새것으로 교체할 뿐입니다.

  ---
  코드에서 역할 분리

  private void startNextSttSession(...) {

      // ① 이전 구독 정리 (소비 중단)
      old.dispose();

      // ② 이전 파이프 정리 (생산 중단)
      oldSink.tryEmitComplete();

      // ③ 새 파이프 생성 (생산 준비)
      Sinks.Many<byte[]> newSink = Sinks.many()...;
      sinkMap.put(session.getId(), newSink);

      // ④ STT 결과 구독 (소비 시작)
      Disposable d = sttService.recognize(newSink.asFlux(), callId)
              .filter(...)
              .subscribe(result -> handleFinalStt(...));
  }

  ---
  표로 정리

  ┌──────────────────────────────────────┬─────────────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────────┐
  │                 동작                 │                         생산(투입)                          │                            구독(소비)                             │
  ├──────────────────────────────────────┼─────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
  │ 음성 청크 (newSink.asFlux())         │ handleBinaryMessage() → tryEmitNext() (다음 음성이 오면)    │ RtzrWebSocketSttService 내부 → audioStream.subscribe()            │
  ├──────────────────────────────────────┼─────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
  │ STT 결과 (sttService.recognize(...)) │ RtzrWebSocketSttService OkHttp onMessage() → emitter.next() │ startNextSttSession() 자신 → .subscribe(result -> handleFinalStt) │
  └──────────────────────────────────────┴─────────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────┘

  ▎ startNextSttSession() 자체는 음성 청크를 생산도 구독도 하지 않습니다. 새 파이프(newSink)를 만들어 놓기만 하고, 실제 투입은 나중에 handleBinaryMessage()가 합니다. startNextSttSession()이 직접 하는 일은 STT 결과 Flux를 구독하는 것뿐입니다.

  ---
  그림

  startNextSttSession() 실행 시점
          │
          ├─ newSink 생성           ← 아직 아무 데이터도 없음 (빈 파이프)
          │
          └─ .subscribe(...)        ← 구독은 지금 시작됨


  잠시 후, 다음 발화 음성이 오면
          │
          ▼
  handleBinaryMessage()
    newSink.tryEmitNext(chunk)     ← 진짜 생산은 여기서 발생
          │
          ▼
  RtzrWebSocketSttService
    audioStream.subscribe()        ← 진짜 소비(전송)는 여기서 발생

  ---
  afterConnectionEstablished()와 비교

  ┌───────────────────────┬──────────────────────────────┬───────────────────────────────────┐
  │                       │ afterConnectionEstablished() │       startNextSttSession()       │
  ├───────────────────────┼──────────────────────────────┼───────────────────────────────────┤
  │ 파이프 생성           │ ✅ (최초)                    │ ✅ (교체)                         │
  ├───────────────────────┼──────────────────────────────┼───────────────────────────────────┤
  │ STT 결과 구독         │ ✅                           │ ✅                                │
  ├───────────────────────┼──────────────────────────────┼───────────────────────────────────┤
  │ 이전 파이프/구독 정리 │ ❌ (없음, 처음이라 없음)     │ ✅ (dispose(), tryEmitComplete()) │
  └───────────────────────┴──────────────────────────────┴───────────────────────────────────┘

  ▎ 둘 다 "파이프 생성 + STT 결과 구독"이라는 같은 역할을 하고, startNextSttSession()만 이전 것을 정리하는 단계가 추가로 붙어 있습니다.

```

### 그러면 Spring Flux 는 C++ CoRoutine 과 비슷한거네.
```
● 아닙니다. 비슷해 보이지만 다른 메커니즘입니다.

  ---
  핵심 차이

  ┌───────────┬──────────────────────────────────────────────┬────────────────────────────────────────────────────┐
  │           │                 C++20 코루틴                 │                    Reactor Flux                    │
  ├───────────┼──────────────────────────────────────────────┼────────────────────────────────────────────────────┤
  │ 정체      │ 언어 차원 기능 (컴파일러가 변환)             │ 라이브러리 (Reactive Streams 구현)                 │
  ├───────────┼──────────────────────────────────────────────┼────────────────────────────────────────────────────┤
  │ 코드 모양 │ 순차적으로 보이는 코드 (co_await)            │ 체인으로 조립하는 파이프라인 (.map().filter())     │
  ├───────────┼──────────────────────────────────────────────┼────────────────────────────────────────────────────┤
  │ 멈춤/재개 │ 함수 중간에서 멈추고 나중에 그 지점부터 재개 │ "재개" 개념 자체가 없음. 콜백을 미리 등록해두는 것 │
  ├───────────┼──────────────────────────────────────────────┼────────────────────────────────────────────────────┤
  │ 구현 원리 │ 컴파일러가 함수를 상태 머신으로 변환         │ 객체들이 서로를 감싸는 데코레이터 패턴             │
  └───────────┴──────────────────────────────────────────────┴────────────────────────────────────────────────────┘

  ---
  코드로 비교

  C++ 코루틴 — 순차적으로 보임
  Task<Result> handle() {
      auto sttResult = co_await getSttResult();   // 여기서 "멈춤"
      auto llmResult = co_await llmChat(sttResult); // 여기서 다시 "멈춤"
      co_return llmResult;
  }
  co_await 지점에서 실제로 함수가 멈추고, 나중에 그 지점 그대로 로컬 변수까지 복원해서 재개됩니다.

  Reactor Flux — 체인을 조립함
  sttResultMono
      .flatMap(result -> llmChat(result))   // "다음 줄"이 아니라 콜백을 등록
      .subscribe(llmResult -> ...);
  멈추고 재개되는 게 아니라, "이 결과가 오면 이걸 실행해" 라는 콜백 체인을 미리 만들어 두는 것입니다. 함수 실행 자체는 subscribe() 호출 시점에 끝나버립니다.

  ---
  진짜 비슷한 건 따로 있습니다

  ┌─────────────────────────────────┬─────────────────────────────────────────────────────────┐
  │     C++ 코루틴과 비슷한 것      │                          이유                           │
  ├─────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ Kotlin suspend 함수             │ 컴파일러가 상태 머신으로 변환 (코루틴과 거의 동일 원리) │
  ├─────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ C# async/await                  │ 동일 계열                                               │
  ├─────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ Java Project Loom (가상 스레드) │ 코드는 그냥 블로킹처럼 쓰는데 내부적으로 멈춤/재개 처리 │
  └─────────────────────────────────┴─────────────────────────────────────────────────────────┘

  ▎ Reactor Flux는 RxJava의 Observable / JS의 Promise(체이닝)와 같은 계열이고,
  ▎ C++ 코루틴은 Kotlin suspend / C# async-await와 같은 계열입니다.
  ▎ 둘 다 "논블로킹 비동기"라는 목표는 같지만, 코드를 쓰는 방식과 내부 구현 원리가 다릅니다.

>> 서로 콜백 지옥을 해결해주는 것은 같은 것 같은데. 맞어?
● 맞습니다. 목표는 같지만 푸는 방식이 다릅니다.

  ---
  같은 점

  콜백이 계속 중첩되는 문제 (callback hell)
    → 둘 다 해결하려는 목표

  ---
  다른 점 — "숨기기" vs "펼치기"

  콜백 지옥 (원본)

  rtzrClient.send(buffer, result -> {
      if (result.isFinal()) {
          llmClient.chat(result.text(), llmResult -> {
              ttsClient.synthesize(llmResult, audio -> {
                  session.send(audio);
              });
          });
      }
  });

  ---
  C++ 코루틴 — 콜백을 완전히 숨김

  Task<void> handle() {
      auto result = co_await rtzrClient.send(buffer);
      if (result.isFinal()) {
          auto llmResult = co_await llmClient.chat(result.text());
          auto audio = co_await ttsClient.synthesize(llmResult);
          session.send(audio);
      }
  }

  콜백이 코드에 안 보입니다. 컴파일러가 뒤에서 콜백으로 변환해줄 뿐, 보기에는 그냥 순차 코드입니다.

  ---
  Reactor Flux — 콜백을 납작하게 펼침

  sttFlux
      .filter(SttResult::isFinal)
      .flatMap(result -> llmClient.chat(result.text()))
      .flatMap(llmResult -> ttsClient.synthesize(llmResult))
      .subscribe(audio -> session.send(audio));

  콜백(람다)은 여전히 있습니다. 다만 중첩되지 않고 옆으로 나란히 이어집니다.

  ---
  한 줄 정리

  ┌──────────────┬───────────────────────────────────────┐
  │              │               해결 방식               │
  ├──────────────┼───────────────────────────────────────┤
  │ C++ 코루틴   │ 콜백을 안 보이게 숨김 (컴파일러 변환) │
  ├──────────────┼───────────────────────────────────────┤
  │ Reactor Flux │ 콜백을 중첩 없이 옆으로 펼침 (체이닝) │
  └──────────────┴───────────────────────────────────────┘

  ▎ 둘 다 "중첩"을 없앤다는 점에서 같은 문제를 풀지만,
  ▎ 코루틴은 콜백 자체를 안 보이게 만들고, Reactor는 콜백을 보이게 두되 일렬로 펼쳐놓는 방식입니다.

```

### GoogleCloudTtsService 클래스 쉽게 설명해줘.
```
● GoogleCloudTtsService 쉽게 설명

  ---
  한 줄 정의

  ▎ 텍스트를 받아서 Google TTS API로 음성(byte[])을 만들어주는 것

  ---
  RtzrWebSocketSttService 와 구조 비교

  ┌───────────┬───────────────────────────────┬──────────────────────────────────────────┐
  │           │    RtzrWebSocketSttService    │          GoogleCloudTtsService           │
  ├───────────┼───────────────────────────────┼──────────────────────────────────────────┤
  │ 통신 방식 │ WebSocket (실시간 스트림)     │ 일반 HTTP POST (한 번 요청 → 한 번 응답) │
  ├───────────┼───────────────────────────────┼──────────────────────────────────────────┤
  │ 인증      │ clientId/secret → accessToken │ GCP 서비스 계정 JSON 키 → accessToken    │
  ├───────────┼───────────────────────────────┼──────────────────────────────────────────┤
  │ 반환 타입 │ Flux<SttResult> (스트림)      │ byte[] (한 번에 통째로)                  │
  └───────────┴───────────────────────────────┴──────────────────────────────────────────┘

  TTS는 "텍스트 하나 주면 오디오 하나 받기" 라서 Flux 필요 없이 단순 동기 호출입니다.

  ---
  필드

  TTS_ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"  // 요청 보낼 주소
  SCOPE = "https://www.googleapis.com/auth/cloud-platform"                 // 권한 범위

  credentialsPath  // GCP 서비스 계정 키 파일 경로 (.json)
  languageCode     // ko-KR
  voiceName        // ko-KR-Neural2-A (음성 종류)
  audioEncoding    // LINEAR16
  sampleRateHertz  // 8000

  credentials  // GCP 인증 객체 (토큰 관리 담당)

  ---
  메서드별 설명

  1. init() — 서버 시작 시 자격증명 로드

  @PostConstruct
  public void init() {
      credentials = GoogleCredentials.fromStream(new FileInputStream(credentialsPath))
                                      .createScoped(SCOPE);
  }

  .env의 GOOGLE_APPLICATION_CREDENTIALS 경로에서
  서비스 계정 JSON 키 파일을 읽어서
  GoogleCredentials 객체로 만듦

  ---
  2. synthesize() — 핵심 메서드

  public byte[] synthesize(String text, String callId) {

      // ① 토큰 발급
      String token = getAccessToken();

      // ② 요청 본문 만들기
      Map<String, Object> requestBody = Map.of(
              "input", Map.of("text", text),
              "voice", Map.of("languageCode", "ko-KR", "name", "ko-KR-Neural2-A"),
              "audioConfig", Map.of("audioEncoding", "LINEAR16", "sampleRateHertz", 8000)
      );

      // ③ Google API 호출
      Map<?, ?> response = webClient.post()
              .uri(TTS_ENDPOINT)
              .header("Authorization", "Bearer " + token)
              .bodyValue(requestBody)
              .retrieve()
              .bodyToMono(Map.class)
              .block();

      // ④ 응답에서 오디오 꺼내기 (Base64 디코딩)
      String audioContent = (String) response.get("audioContent");
      return Base64.getDecoder().decode(audioContent);
  }

  왜 Base64 디코딩이 필요한가?

  Google TTS 응답은 JSON이라서 바이너리를 직접 못 담음
          ↓
  오디오 바이트를 텍스트로 변환해서 JSON에 담음 (Base64)
          ↓
  { "audioContent": "UklGRiQAAABXQVZFZm10..." }
          ↓
  우리가 다시 디코딩해서 원래 바이트로 복원

  ---
  3. scheduleTokenRefresh() — 5분마다 토큰 확인

  @Scheduled(fixedRate = 300_000)
  public void scheduleTokenRefresh() {
      credentials.refreshIfExpired();  // 만료 가까우면 자동 갱신
  }

  RTZR과 똑같은 패턴이지만, 여기는 refreshIfExpired()가 만료 여부를 알아서 판단해줍니다. RTZR처럼 직접 expireAt - now < 600 계산을 안 해도 됩니다. (Google 라이브러리가 더 편의 기능 제공)

  ---
  4. getAccessToken() — 토큰 꺼내기

  private synchronized String getAccessToken() {
      credentials.refreshIfExpired();              // 혹시 만료됐으면 즉시 갱신
      return credentials.getAccessToken().getTokenValue();
  }

  synchronized인 이유는 RTZR과 동일 — 여러 스레드가 동시에 토큰을 갱신/조회할 수 있어서입니다.

  ---
  전체 흐름

  서버 시작
    │
    ▼
  init() → GCP 자격증명 로드


  통화 중 (handleFinalStt에서 호출)
    │
    ▼
  synthesize(text, callId)
    │
    ├─ getAccessToken()       — 토큰 확인/갱신
    ├─ requestBody 생성       — 텍스트 + 음성 설정
    ├─ webClient.post().block() — Google API 호출 (동기)
    └─ Base64 디코딩 → byte[] 반환
    │
    ▼
  CtiWebSocketHandler가 받아서 브라우저로 전송

  ---
  주의할 점 — block() 위치

  .bodyToMono(Map.class)
  .block();   // ← 여기서 blocking

  synthesize() 자체가 **인터페이스 시그니처부터 동기(byte[] 반환, Mono 아님)**라서 내부에서 block()을 씁니다. 이게 바로 앞서 설명한 publishOn(boundedElastic)이 필요한 이유 중 하나입니다 — handleFinalStt()가 boundedElastic 스레드에서 실행돼야 이 block()이
  안전합니다.
  
```

### private synchronized String getAccessToken()  에서 synchronized 인 이유는 TTS 사용하는 여러 곳(통화)에서 getAccessToken() 을 공유해서 사용하기 위해서 필요한다. 맞어?
```
● "공유해서 사용"이라는 표현은 정확하지 않습니다. 더 구체적인 이유가 있습니다.

  ---
  진짜 이유 — "확인 + 갱신"이 한 묶음이라서

  private synchronized String getAccessToken() {
      credentials.refreshIfExpired();              // ① 확인하고 필요하면 갱신
      return credentials.getAccessToken().getTokenValue();  // ② 읽기
  }

  ①과 ②가 **하나의 묶음(원자적 단위)**으로 실행돼야 합니다. 단순히 "여러 곳에서 공유"하는 것 자체는 문제가 아닙니다. (예: 단순 getter라면 synchronized 없어도 됨)

  ---
  synchronized 없으면 생기는 문제

  시간 →

  스레드 A (통화1 TTS)        스레드 B (통화2 TTS)
    refreshIfExpired()
    → "만료됐네, 갱신 시작"
                                refreshIfExpired()
                                → "만료됐네, 갱신 시작"   ← A가 아직 안 끝났는데 또 시작!
    새 토큰 받아옴
    credentials 내부에 저장
                                새 토큰 받아옴 (중복 요청)
                                credentials 내부에 저장 (덮어씀)

    getAccessToken() 읽음   →  방금 B가 갱신한 토큰을 읽을 수도 있음
                                (혹은 그 반대, 타이밍에 따라 달라짐)

  문제는 "공유"가 아니라 "동시에 둘 다 갱신을 트리거하는 것" 입니다.
  - 불필요한 중복 네트워크 요청 (Google API에 두 번 갱신 요청)
  - credentials 내부 상태가 두 스레드가 동시에 쓰면서 일시적으로 꼬일 수 있음

  ---
  synchronized 있으면

  스레드 A: refreshIfExpired() + getAccessToken() 통째로 락 잡음
  스레드 B: A가 끝날 때까지 대기
          → A 끝나면 B 실행 (이미 갱신된 토큰이니 B는 갱신 스킵하고 바로 읽음)

  중복 갱신 자체가 발생하지 않습니다.

  ---
  RTZR의 AtomicReference 와 비교

  ┌─────────────┬─────────────────────────────────────┬─────────────────────────────────────────┐
  │             │       RTZR (AtomicReference)        │           TTS (synchronized)            │
  ├─────────────┼─────────────────────────────────────┼─────────────────────────────────────────┤
  │ 동작        │ 단순 get() / set() (단일 원자 연산) │ "확인 + 조건부 갱신 + 읽기" (복합 연산) │
  ├─────────────┼─────────────────────────────────────┼─────────────────────────────────────────┤
  │ 필요한 보호 │ 값 자체의 가시성/원자성만           │ 여러 단계가 끼어들지 못하게 통째로 잠금 │
  └─────────────┴─────────────────────────────────────┴─────────────────────────────────────────┘

  ▎ RTZR은 읽기/쓰기가 단순해서 AtomicReference로 충분하지만,
  ▎ Google TTS는 "확인-갱신-읽기"가 한 흐름으로 묶여야 해서 synchronized(락)가 필요합니다.

```

###  "synthesize() 자체가 **인터페이스 시그니처부터 동기(byte[] 반환, Mono 아님)**라서 내부에서 block()을 씁니다." 잘 이해 안 돼! 'Mono 아님'이라고 해놓고 왜 코딩은 'bodyToMono(Map.class)' 이지?
```
● 설명이 부족했습니다. 정정하겠습니다.

  ---
  두 가지를 구분해야 합니다

  ① 메서드 시그니처 (인터페이스 약속)
     byte[] synthesize(String text, String callId)
     → 호출하면 즉시 byte[] 값이 와야 함 (동기)

  ② WebClient 내부 동작 (라이브러리 특성)
     WebClient는 원래 리액티브 라이브러리라서
     호출하면 무조건 Mono/Flux를 반환함

  이 둘이 안 맞아서 .block()으로 강제로 끼워 맞추는 것입니다.

  ---
  코드로 보면

  public byte[] synthesize(String text, String callId) {   // ① 시그니처: byte[] 약속

      Map<?, ?> response = webClient.post()
              ...
              .bodyToMono(Map.class)   // ② WebClient가 강제로 Mono<Map>을 줌
              .block();                // ← 여기서 Mono를 까서 진짜 Map 값으로 꺼냄
                                        //    (① 약속을 지키기 위해 필요)

      return Base64.getDecoder().decode(audioContent);   // byte[] 반환
  }

  ---
  그림으로

  WebClient.post()....bodyToMono(Map.class)
          │
          ▼
     Mono<Map>           ← WebClient는 항상 이 형태로 줌 (리액티브)
          │
          │ .block()      ← "Mono야, 너 까서 진짜 값 내놔" 강제로 꺼냄
          ▼
       Map               ← 이제 평범한 동기 값
          │
          ▼
     byte[] 반환          ← synthesize()의 약속(시그니처)을 지킴

  ---
  "Mono 아님"이 가리키는 대상

  "Mono 아님" = synthesize()의 반환 타입이 byte[]라는 뜻
                (WebClient 내부에서 Mono를 쓰는 것과는 별개)

  즉, "WebClient는 Mono를 쓰지만, synthesize() 메서드 자체는 Mono를 반환하지 않고 byte[]를 반환한다" 가 정확한 의미입니다. 표현이 헷갈리게 됐습니다.

  ---
  비유

  WebClient = 항상 포장된 선물(Mono)로 물건을 주는 가게
  synthesize() = "포장 풀어서 줘" 라고 약속한 함수

  → 가게에서 포장된 선물(Mono)을 받고
  → 직접 포장을 뜯어서(block())
  → 약속한 대로 안의 물건(byte[])만 건네줌

```

### com.voicebot 패키지에 있는 주요 클래승 대한 분석은 대부분된 것 같은데. LLM-MODE-ANALYSIS.md 내용 보고 판단해줘. 충분한지? 더 할게 있는지?
```
● docs/LLM-MODE-ANALYSIS.md 전체(5938줄)를 다 읽었고, com.voicebot 패키지의 실제 클래스 29개와 대조해봤습니다.

  결론: 충분하지 않습니다. 비중 있는 클래스 몇 개가 비어 있습니다.

  충분히 다뤄진 것 (깊게, 메서드 단위로)

  ┌─────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │            영역             │                                               클래스                                                │
  ├─────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ WebSocket 실시간 파이프라인 │ CtiWebSocketHandler (전 메서드), RtzrWebSocketSttService (전 메서드), GoogleCloudTtsService         │
  ├─────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ LLM 모드 라우팅             │ LlmService, LlmModeRouter, ClaudeApiLlmService, AnthropicStrategy, HybridStrategy, InternalStrategy │
  ├─────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ MongoDB 연동                │ PlaybookService, IntentPlaybookRepository, IntentPlaybook                                           │
  ├─────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 기타                        │ CallSession, CtiRestController, CallRecordRepository/CallRecord (JPA)                               │
  ├─────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ Spring 내장(참고)           │ AbstractWebSocketHandler, TextMessage, Sinks, Flux                                                  │
  └─────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────┘

  빠진 것 — 정작 비중 있는 것들

  1. sim 프로파일 구현체 전체 — SimulatorLlmService, SimulatorSttService, SimulatorTtsService
  CLAUDE.md 기준 로컬 개발 기본값인데도 한 번도 안 다룸. real 쪽(Rtzr/Claude/Google)만 깊게 보고 정작 매일 띄우는 sim 쪽은 안 본 셈입니다.
  2. HTTP 파이프라인 본체 — CallController, CallHandler
  CtiWebSocketHandler와의 비교표에만 등장했지, CtiWebSocketHandler처럼 메서드별로 뜯어본 적이 없습니다. ARCHITECTURE.md의 "HTTP (기존)" 흐름의 실제 구현체인데 미답 상태.
  3. Config 3종 — RedisConfig, WebClientConfig, WebSocketConfig
  파일 경로만 언급됐을 뿐 내용 설명은 전혀 없음. 특히 WebSocketConfig는 /ws/cti 핸들러가 실제로 등록되는 지점이라 CtiWebSocketHandler를 이해했다면 자연스러운 다음 스텝입니다.
  4. 인터페이스 자체 — SttService, LlmService, TtsService, LlmStrategy
  구현체들은 다 봤지만 "인터페이스가 약속하는 계약이 정확히 뭔지"를 정면으로 본 적은 없습니다(대부분 구현체 설명 중 끼워서만 봄).
  5. IntentResult (domain) — 전혀 미언급.
  6. VoicebotApplication — 진입점인데 미언급(다만 보통 @SpringBootApplication + main()뿐이라 비중은 낮음).

  추천 순서

  비중 순서로 보면: Simulator 3종 → CallController/CallHandler → WebSocketConfig/WebClientConfig/RedisConfig → 인터페이스 4개 → IntentResult가 합리적입니다. Simulator를 안 보면 "코드 변경 없이 sim/real 전환"이라는 이 프로젝트 핵심 설계 원칙의 절반(sim
  쪽)을 실제로 본 적이 없는 상태가 됩니다.

  원하시면 이 순서대로 다음 걸 시작하겠습니다 — 어디부터 갈까요?

```

### HTTP 파이프라인 본체 — CallController, CallHandler 쉽게 설명해줘.
```
● CallController — 쉽게 설명

  한 줄 정의
  ▎ POST /call/incoming으로 들어온 음성 파일을 받아서 CallHandler에게 넘기고, 결과 오디오를 돌려주는 창구.

  @PostMapping(value = "/incoming",
          consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,  // 요청: 순수 바이너리
          produces = "audio/wav")                                // 응답: wav 오디오
  public ResponseEntity<byte[]> incoming(
          @RequestHeader("X-Call-Id") String callId,   // 헤더에서 callId 꺼냄
          @RequestBody byte[] audioData                // 바디 = 통째 음성 바이트
  ) {
      byte[] responseAudio = callHandler.process(audioData, callId);  // 위임
      return ResponseEntity.ok().contentType(...).body(responseAudio);
  }

  ┌───────────────────────────────────────────┬─────────────────────────────────────────────────────────┐
  │                   부분                    │                          의미                           │
  ├───────────────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ consumes = APPLICATION_OCTET_STREAM_VALUE │ "요청 본문은 그냥 날(raw) 바이너리다" — JSON 아님       │
  ├───────────────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ @RequestHeader("X-Call-Id")               │ HTTP 헤더값을 파라미터로 바로 꺼냄                      │
  ├───────────────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ @RequestBody byte[] audioData             │ 본문 전체를 byte 배열로 통째로 받음                     │
  ├───────────────────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ callHandler.process(...)                  │ 실제 일은 전부 여기로 위임 — Controller는 입출구 역할만 │
  └───────────────────────────────────────────┴─────────────────────────────────────────────────────────┘

  CtiWebSocketHandler와 비교하면: WebSocket은 음성이 250ms 청크로 여러 번 오지만, 이 컨트롤러는 음성 파일 전체가 한 번에 옵니다. 그래서 Sinks/Flux 다리가 필요 없고 그냥 byte[] 하나로 끝납니다.

  ---
  CallHandler — 쉽게 설명

  한 줄 정의
  ▎ STT → LLM → TTS를 순서대로 호출하고, 시간을 재고, 결과를 DB에 저장하는 오케스트레이터. CtiWebSocketHandler의 "한 번에 처리하는" 버전입니다.

  전체 흐름 (process())

  음성 파일 통째로 도착
        │
        ▼
  ① STT (recognize) ──────────────────────── [STT-PERF] 로그
        │ 텍스트 없으면 → 바로 "다시 말씀해주세요" TTS 반환하고 끝
        ▼
  ② Redis에서 세션 꺼내기/만들기 + 사용자 발화 추가
        ▼
  ③ LLM (chat) ────────────────────────────── [LLM-PERF] 로그
        │ 응답을 세션에 추가하고 Redis에 다시 저장
        ▼
  ④ TTS (synthesize) ─────────────────────── [TTS-PERF] 로그
        ▼
  ⑤ CallRecord DB에 저장 (텍스트, 응답, 각 단계 시간)
        ▼
  오디오 byte[] 반환

  CLAUDE.md의 "[서비스명-PERF] callId={} elapsed={}ms" 로깅 규칙이 여기서 그대로 지켜지고 있습니다.

  ① STT 부분 — 가장 복잡한 줄

  String sttText = sttService.recognize(Flux.just(audioData), callId)
          .filter(SttService.SttResult::isFinal)   // 최종 결과만
          .map(SttService.SttResult::text)          // SttResult → 텍스트만 꺼냄
          .collectList()                            // 여러 개 결과를 리스트로 모음
          .map(parts -> String.join(" ", parts))     // 리스트 → 하나의 문자열로 합침
          .timeout(Duration.ofSeconds(30))           // 30초 안에 안 끝나면 에러
          .onErrorReturn("")                         // 에러면 빈 문자열로 대체
          .block();                                  // 동기로 결과 기다림

  recognize()는 Flux<byte[]>를 받는 인터페이스라서, 파일 하나도 Flux.just(audioData)로 "원소 1개짜리 Flux"로 포장해서 넘깁니다. CtiWebSocketHandler는 .subscribe()로 비동기 처리했지만, 여긴 HTTP 요청-응답이라 결과가 나올 때까지 그냥 기다려야 하므로
  .block()을 씁니다.

  ┌─────────────────────────┬────────────────────────────────────────────────────────┐
  │         메서드          │                          비유                          │
  ├─────────────────────────┼────────────────────────────────────────────────────────┤
  │ .filter(isFinal)        │ 중간 결과(인식 중...) 버리고 최종 결과만               │
  ├─────────────────────────┼────────────────────────────────────────────────────────┤
  │ .collectList()          │ STT가 여러 문장으로 끊어 보낼 수도 있으니 일단 다 모음 │
  ├─────────────────────────┼────────────────────────────────────────────────────────┤
  │ String.join(" ", parts) │ 모은 문장들을 띄어쓰기로 이어 붙임                     │
  ├─────────────────────────┼────────────────────────────────────────────────────────┤
  │ .onErrorReturn("")      │ STT 실패해도 서버가 죽지 않고 "빈 텍스트"로 넘어감     │
  └─────────────────────────┴────────────────────────────────────────────────────────┘

  ② 세션 부분 — Redis로 대화 이력 유지

  CallSession session = getOrCreateSession(callId);   // Redis에서 꺼내거나 새로 만듦
  session.getMessages().add(Map.of("role", "user", "content", sttText));

  CtiWebSocketHandler는 historyMap(메모리)에 대화 이력을 들고 있었지만, CallHandler는 Redis(CallSession)에 들고 있습니다. HTTP는 매 요청이 독립적인 연결이라 메모리에 들고 있을 "세션 객체"가 없기 때문입니다 — 그래서 callId를 키로 Redis에서 꺼내 와야 이전
  대화를 기억합니다.

  private CallSession getOrCreateSession(String callId) {
      Object stored = redisTemplate.opsForValue().get(SESSION_PREFIX + callId);
      if (stored instanceof CallSession session) {
          return session;          // 이전 통화 이력 있음
      }
      return CallSession.builder()...build();   // 첫 발화, 새로 생성
  }

  ③ LLM + ④ TTS

  String llmResponse = llmService.chat(toLlmMessages(session.getMessages()), callId);
  session.getMessages().add(Map.of("role", "assistant", "content", llmResponse));
  saveSession(callId, session);   // Redis에 TTL 1시간으로 다시 저장

  byte[] audio = ttsService.synthesize(llmResponse, callId);

  toLlmMessages()는 Redis에 저장하기 좋은 Map<String,String> 형태를 LlmService.Message(LLM 인터페이스가 원하는 타입)로 변환하는 어댑터 메서드입니다.

  ⑤ CallRecord 저장

  callRecordRepository.save(CallRecord.builder()
          .callId(callId)
          .sttText(sttText)
          .llmResponse(llmResponse)
          .sttElapsedMs(sttElapsed)
          .llmElapsedMs(llmElapsed)
          .ttsElapsedMs(ttsElapsed)
          .totalElapsedMs(totalElapsed)
          .build());

  JPA(MariaDB)에 통화 1건의 텍스트와 단계별 처리 시간을 영구 기록합니다. CtiWebSocketHandler 쪽엔 이 저장 로직이 없습니다 — WebSocket 경로는 통계 기록을 안 합니다(이것도 둘의 실질적 차이점 중 하나).

  ---
  CtiWebSocketHandler vs CallHandler 한눈에 비교

  ┌──────────────────────┬─────────────────────────────┬──────────────────────────┐
  │                      │     CallHandler (HTTP)      │ CtiWebSocketHandler (WS) │
  ├──────────────────────┼─────────────────────────────┼──────────────────────────┤
  │ 음성 입력            │ 파일 전체 한 번             │ 250ms 청크 여러 번       │
  ├──────────────────────┼─────────────────────────────┼──────────────────────────┤
  │ STT 호출 방식        │ .block() 동기 대기          │ .subscribe() 비동기 콜백 │
  ├──────────────────────┼─────────────────────────────┼──────────────────────────┤
  │ 대화 이력 저장소     │ Redis (CallSession)         │ 메모리 (historyMap)      │
  ├──────────────────────┼─────────────────────────────┼──────────────────────────┤
  │ 처리 시간 측정       │ ✅ STT/LLM/TTS/전체 다 로그 │ ❌ 없음                  │
  ├──────────────────────┼─────────────────────────────┼──────────────────────────┤
  │ DB 저장 (CallRecord) │ ✅                          │ ❌                       │
  ├──────────────────────┼─────────────────────────────┼──────────────────────────┤
  │ 용도                 │ 배치/테스트 호출            │ 실시간 CTI 연동          │
  └──────────────────────┴─────────────────────────────┴──────────────────────────┘

  핵심 한 줄
  ▎ CallController는 입출구, CallHandler는 STT→LLM→TTS를 순서대로 "기다리면서" 처리하고 Redis 세션 + DB 기록까지 남기는 동기형 오케스트레이터입니다.

>> "HTTP는 매 요청이 독립적인 연결이라 메모리에 들고 있을 "세션 객체"가 없기 때문입니다 — 그래서 callId를 키로 Redis에서 꺼내 와야 이전 대화를 기억합니다." 이 말은 HTTP 여건상 단 건으로 끝나므로 메모리에 들고 있을 수 없어서 Redis 사용한다. 맞어?
● 맞습니다. 정확히 짚었습니다.

  조금 더 정확하게 풀면:

  WebSocket = 연결 1개가 전화 1건 내내 살아있음
  연결 수립 → 발화1 → 발화2 → 발화3 → ... → 연결 종료
     (이 전체 기간 동안 동일한 session 객체, 동일한 historyMap 엔트리가 메모리에 계속 존재)
  그래서 historyMap.get(session.getId())로 방금 전 대화를 그냥 메모리에서 꺼내면 됩니다.

  HTTP = 요청 1번 = 완전히 독립된 거래 1건
  발화1: 요청 도착 → process() 실행 → 응답 전송 → 끝 (이 요청을 처리하던 객체/스레드는 여기서 임무 종료)
  발화2: 새 요청 도착 → (발화1을 처리했던 그 어떤 메모리 객체와도 연결고리가 없음)
  process(audioData, callId)가 리턴하는 순간 그 호출과 관련된 모든 로컬 상태는 사라집니다. 발화2가 왔을 때 "방금 그 전화"라는 걸 식별할 수 있는 건 오직 callId 문자열뿐이고, 그 문자열로 찾아갈 수 있는 메모리 객체가 애초에 없습니다.

  그래서 "단 건으로 끝난다"는 표현이 정확합니다 — 매 요청이 그 자체로 시작과 끝이 있는 트랜잭션이라, 다음 요청과 이어줄 살아있는 객체가 서버 메모리 안에 없는 겁니다. 그 끊어진 연결고리를 Redis가 대신 이어줍니다: callId를 키로 써서 "방금 그 전화의 대화
  이력"을 외부에 보관해두고, 다음 요청이 오면 그 키로 다시 꺼내오는 방식입니다.

  (덧붙이면: 서버를 여러 대로 늘렸을 때 발화1과 발화2가 서로 다른 서버 인스턴스로 갈 수도 있는데, 그래도 Redis는 공유 저장소라서 문제없이 이어집니다. 메모리(historyMap)였다면 이 경우 무조건 깨집니다.)

>> "toLlmMessages()는 Redis에 저장하기 좋은 Map<String,String> 형태를 LlmService.Message(LLM 인터페이스가 원하는 타입)로 변환하는 어댑터 메서드입니다." 이해 안 돼. 좀 더 쉽게 설명.
● toLlmMessages() — 더 쉽게

  핵심: 같은 내용인데 "포장 모양"이 다른 두 가지 형태가 있어서, 모양을 바꿔주는 일만 합니다.

  ---
  두 형태를 나란히 놓고 보면

  // CallSession 안에 저장된 형태 — Map
  {"role": "user", "content": "배송 언제 와요?"}
  //   ↑ Map<String, String>. 그냥 키-값 묶음.

  // LlmService.chat()이 요구하는 형태 — Message
  new LlmService.Message("user", "배송 언제 와요?")
  //   ↑ Message 라는 전용 클래스(record). 필드 2개짜리 박스.

  들어있는 정보는 똑같습니다 (role, content). 그런데 자바에서는 타입이 다르면 그냥 안 들어갑니다.

  llmService.chat(session.getMessages(), callId);
  //               └─ List<Map<String,String>>
  //
  // 그런데 chat()의 실제 시그니처는:
  String chat(List<LlmService.Message> messages, String callId);
  //               └─ List<Message> 만 받음 → Map을 넣으면 컴파일 에러 💥

  택배를 보낼 때 상자 크기가 안 맞으면 그냥 안 들어가는 것과 같습니다. 내용물(주소, 물건)은 같아도 상자 모양이 다르면 옮겨 담아야 합니다.

  ---
  toLlmMessages()가 하는 일 = 옮겨 담기

  private List<LlmService.Message> toLlmMessages(List<Map<String, String>> messages) {
      return messages.stream()
              .map(m -> new LlmService.Message(m.get("role"), m.get("content")))
              //         └─ Map에서 값 2개 꺼내서 → Message 상자에 다시 담기
              .collect(Collectors.toList());
  }

  ┌───────────────────────────────────┬──────────────────────────────────────┬─────────────────────────────┐
  │          입력 (Map 1개)           │                 변환                 │     출력 (Message 1개)      │
  ├───────────────────────────────────┼──────────────────────────────────────┼─────────────────────────────┤
  │ {"role":"user", "content":"안녕"} │ m.get("role"), m.get("content") 꺼냄 │ new Message("user", "안녕") │
  └───────────────────────────────────┴──────────────────────────────────────┴─────────────────────────────┘

  리스트 전체를 하나씩 이 변환을 거쳐서, List<Map> → List<Message>로 바꿔주는 것뿐입니다.

  ---
  왜 애초에 두 형태로 나뉘었나

  - CallSession.messages는 session.getMessages().add(Map.of("role", "user", "content", sttText)) 식으로 Map으로 계속 쌓이게 만들어 놨습니다 (Redis에 저장하는 객체라서 단순한 형태로).
  - LlmService.chat() 인터페이스는 처음 설계할 때부터 Message 타입을 받게 정해져 있습니다 (CtiWebSocketHandler의 historyMap도 이미 List<Message>로 만들어서 들고 있어서, 거긴 변환이 필요 없습니다).

  이 둘이 서로 다른 이유로 서로 다른 모양을 갖게 됐고, CallHandler에서 둘을 연결하는 지점에서만 변환이 필요해진 겁니다.

  ---
  한 줄 정리
  ▎ Map과 Message는 내용은 같고 포장만 다른 같은 데이터. toLlmMessages()는 Map 상자를 열어서 Message 상자에 옮겨 담는, 딱 그 일만 하는 변환기입니다.

>> CallRecord.builder() 와 callRecordRepository.save(... 마지막 .build() 역할 및 의미.
● CallRecord.builder() ~ .build() — 빌더 패턴

  한 줄 요약

  .builder()로 빈 상자를 열고 → 필드들을 하나씩 채운 뒤 → .build()로 "조립 완료!" 눌러서 진짜 CallRecord 객체를 만들어낸다.

  ---
  단계별로 쪼개보면

  CallRecord.builder()              // ① 빈 조립 상자 생성 (아직 CallRecord 아님!)
          .callId(callId)            // ② callId 값 끼워넣고, 상자 자신을 반환
          .sttText(sttText)          // ③ sttText 값 끼워넣고, 상자 자신을 반환
          .llmResponse(llmResponse)  // ④ ...
          .sttElapsedMs(sttElapsed)
          .llmElapsedMs(llmElapsed)
          .ttsElapsedMs(ttsElapsed)
          .totalElapsedMs(totalElapsed)
          .build();                  // ⑤ 지금까지 채운 값으로 진짜 CallRecord 완성!

  ┌──────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │     단계     │                                                실제로 일어나는 일                                                │
  ├──────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ ① builder()  │ CallRecord 가 아니라 CallRecordBuilder 라는 임시 조립용 객체를 만든다                                            │
  ├──────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ ②~④ .xxx(값) │ 그 임시 객체에 필드 값을 하나씩 채우고, 매번 자기 자신(builder)을 다시 반환해서 .으로 계속 이어붙일 수 있게 한다 │
  ├──────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ ⑤ .build()   │ 지금까지 채워진 값들을 모아서 진짜 CallRecord 인스턴스를 생성한다. 이게 없으면 끝까지 임시 객체 상태로 남는다    │
  └──────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  .build()를 안 부르면 → CallRecordBuilder 객체만 있고 CallRecord는 존재하지 않는다. 컴파일도 안 된다 (save()는 CallRecord를 원하는데 CallRecordBuilder를 주려고 하면 타입 에러).

  ---
  왜 굳이 new CallRecord(...) 안 쓰고 빌더를 쓰나

  CallRecord는 필드가 9개입니다 (id, callId, sttText, llmResponse, sttElapsedMs, llmElapsedMs, ttsElapsedMs, totalElapsedMs, createdAt).

  // 생성자로 쓰면 — 순서 외워야 하고, 안 쓰는 값도 다 적어야 함
  new CallRecord(null, callId, sttText, llmResponse, sttElapsed, llmElapsed, ttsElapsed, totalElapsed, null);
  //              ↑id    ↑순서 틀리면 끝장   ...                                              ↑createdAt

  // 빌더로 쓰면 — 이름표 붙여서 필요한 것만, 순서 무관하게
  CallRecord.builder()
          .callId(callId)
          .sttText(sttText)
          ...
          .build();

  id와 createdAt은 일부러 빼고 안 채웁니다 — 둘 다 직접 넣는 값이 아니라 자동으로 채워지는 값이기 때문입니다:
  - id: @GeneratedValue(strategy = GenerationType.IDENTITY) → DB가 INSERT 시점에 자동으로 번호 부여
  - createdAt: @CreatedDate → JPA가 저장 시점에 자동으로 현재 시각 채움

  ---
  마지막 save(...)까지 연결하면

  callRecordRepository.save( CallRecord.builder()....build() );
  //                          └──────────────┬──────────────┘
  //                          완성된 CallRecord 객체 1개

  build()로 완성된 CallRecord 객체를 save()에 바로 넘겨서 call_records 테이블에 INSERT 합니다. (CallRecordRepository는 JpaRepository<CallRecord, Long> 상속이라 save()는 따로 구현 안 해도 Spring Data JPA가 기본 제공)

  비유: 빌더는 "조립 설명서대로 부품을 하나씩 끼우다가, 마지막에 완성 버튼(build())을 눌러야 비로소 완제품이 나오는 키트"입니다. 완성 버튼을 누르기 전엔 부품 더미일 뿐, 완제품(CallRecord)이 아닙니다.

>> 그럼, builder() 는 모든 클래스가 가지고 있는거야?
● 아니요. builder()는 자바 기본 기능이 아니라, @Builder라는 Lombok 어노테이션을 붙인 클래스에만 생깁니다.

  실제로 이 프로젝트 29개 클래스 중 @Builder가 붙은 건 딱 2개뿐입니다.

  Searched for 1 pattern (ctrl+o to expand)

● @Builder 있는 클래스 vs 없는 클래스

  ┌────────────────────────────────────────┬──────────┬─────────────────────────────────────┐
  │                 클래스                 │ @Builder │        builder() 사용 가능?         │
  ├────────────────────────────────────────┼──────────┼─────────────────────────────────────┤
  │ CallRecord                             │ ✅       │ CallRecord.builder()... 가능        │
  ├────────────────────────────────────────┼──────────┼─────────────────────────────────────┤
  │ CallSession                            │ ✅       │ CallSession.builder()... 가능       │
  ├────────────────────────────────────────┼──────────┼─────────────────────────────────────┤
  │ CallHandler                            │ ❌       │ CallHandler.builder() → 컴파일 에러 │
  ├────────────────────────────────────────┼──────────┼─────────────────────────────────────┤
  │ CallController, LlmService, 그 외 전부 │ ❌       │ 마찬가지로 없음                     │
  └────────────────────────────────────────┴──────────┴─────────────────────────────────────┘

  ---
  왜 이렇게 갈렸나 — 클래스의 "역할"이 다르기 때문

  - CallRecord, CallSession → 데이터를 담는 객체 (DB row, Redis에 저장될 상태값). 필드가 여러 개고, "이 값들로 객체 하나 조립해줘" 가 자연스러운 용도 → 빌더가 딱 맞음.
  - CallHandler, CallController, LlmService 구현체들 → 동작(로직)을 담는 객체. Spring이 시작할 때 딱 1개만 만들어서 계속 재사용하는 **빈(Bean)**이고, 생성자에 의존성(다른 서비스들)을 주입받기만 하면 됨 → @RequiredArgsConstructor(생성자 자동 생성)로 충분,
  빌더로 조립할 일이 없음.

  // CallHandler — 데이터 조립이 아니라 "의존성 주입"이 목적
  @RequiredArgsConstructor   // ← 이게 생성자를 만들어줌, builder()는 안 만듦
  public class CallHandler {
      private final SttService sttService;   // 생성자로 한 번만 주입받음
      ...
  }

  ---
  정리: builder()는 Lombok이 @Builder 어노테이션을 보고 그 클래스 전용으로 자동 생성해주는 메서드입니다. 어노테이션을 안 붙이면 애초에 그런 메서드가 존재하지 않습니다 — "모든 클래스가 기본으로 가진 기능"이 아니라 "옵션으로 켜야만 생기는 기능"입니다.

```

### Config 3종 — RedisConfig, WebClientConfig, WebSocketConfig 쉽게 설명해줘.
```
● Config 3종 — 한눈에 공통점부터

  @Configuration + @Bean = "이 메서드가 만들어낸 객체를 스프링이 평생 관리하는 공용 객체(Bean)로 등록해줘" 라는 뜻입니다.
  직접 new RedisTemplate() 식으로 여기저기서 만들면 설정이 흩어지니, 한 곳에 모아서 "이렇게 만든 거 하나를 다른 클래스들이 가져다 쓰게 해줘" 라고 선언하는 파일들입니다.

  ---
  1. RedisConfig — "Redis에 저장할 때 변환 규칙"

  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
      RedisTemplate<String, Object> template = new RedisTemplate<>();
      template.setConnectionFactory(factory);
      template.setKeySerializer(new StringRedisSerializer());           // 키 → 그냥 글자
      template.setValueSerializer(new GenericJackson2JsonRedisSerializer()); // 값 → JSON
      return template;
  }

  CallHandler에서 썼던 redisTemplate.opsForValue().set("call:session:abc", session) 가 실제로 Redis에 들어갈 때:

  ┌─────────────────────────┬──────────────────────────────────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────┐
  │          항목           │                            변환 규칙                             │                                            이유                                             │
  ├─────────────────────────┼──────────────────────────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 키 ("call:session:abc") │ StringRedisSerializer → 그냥 평범한 글자 그대로 저장             │ 설정 안 하면 자바 기본 방식으로 저장돼서 redis-cli로 봤을 때 키 이름이 깨진 바이너리로 보임 │
  ├─────────────────────────┼──────────────────────────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────┤
  │ 값 (CallSession 객체)   │ GenericJackson2JsonRedisSerializer → JSON 문자열로 변환해서 저장 │ 사람이 읽을 수 있고, 꺼낼 때 다시 JSON → 객체로 자동 변환됨                                 │
  └─────────────────────────┴──────────────────────────────────────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────┘

  비유: Redis는 "아무거나 다 저장은 해주는데, 어떤 모양으로 포장해서 넣을지는 네가 정해라"는 창고입니다. 이 설정이 그 포장 규칙서입니다.

  ---
  2. WebClientConfig — "외부 API 부를 때 쓰는 도구 2개"

  @Bean
  public WebClient webClient() {
      return WebClient.builder()
              .codecs(config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
              .build();
  }

  @Bean
  @Profile("real")
  public OkHttpClient okHttpClient() {
      return new OkHttpClient.Builder()
              .connectTimeout(10, TimeUnit.SECONDS)
              .readTimeout(0, TimeUnit.MILLISECONDS) // 스트리밍: 타임아웃 없음
              .build();
  }

  ┌──────────────┬────────────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │     Bean     │                              용도                              │                                                  비고                                                  │
  ├──────────────┼────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ WebClient    │ 일반 HTTP 호출 (LLM/TTS 시뮬레이터, Claude API, Google TTS 등) │ 기본 버퍼 크기(256KB 정도)는 음성 데이터엔 너무 작아서 10MB로 늘려놓음 — 안 늘리면 큰 응답에서 에러 남 │
  ├──────────────┼────────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ OkHttpClient │ WebSocket 연결용 (RTZR STT)                                    │ @Profile("real")이라 운영(real) 모드에서만 만들어짐 — sim 모드에선 이 Bean 자체가 존재 안 함           │
  └──────────────┴────────────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  readTimeout(0) = "응답 안 와도 끊지 마라" → RTZR과의 연결은 통화 내내 계속 열려 있는 스트리밍 연결이라서, 일반 HTTP처럼 몇 초 안에 응답 없으면 끊는 방식이면 안 되기 때문입니다.

  ---
  3. WebSocketConfig — "/ws/cti 주소로 오면 누가 받을지 등록"

  @Configuration
  @EnableWebSocket
  @RequiredArgsConstructor
  public class WebSocketConfig implements WebSocketConfigurer {

      private final CtiWebSocketHandler ctiWebSocketHandler;

      @Override
      public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
          registry.addHandler(ctiWebSocketHandler, "/ws/cti")
                  .setAllowedOrigins("*");
      }
  }

  ┌─────────────────────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │                        부분                         │                                                                  의미                                                                   │
  ├─────────────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ @EnableWebSocket                                    │ "이 프로젝트, WebSocket 기능 켜줘" 스위치                                                                                               │
  ├─────────────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ registry.addHandler(ctiWebSocketHandler, "/ws/cti") │ "/ws/cti 로 연결 요청 들어오면 CtiWebSocketHandler가 처리해라" 라는 주소-담당자 매핑표 등록                                             │
  ├─────────────────────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ .setAllowedOrigins("*")                             │ "어떤 출처(도메인/포트)에서 온 연결이든 다 허용" — 프론트엔드(:5173)와 백엔드(:8080) 포트가 다르므로 이게 없으면 브라우저가 연결을 막음 │
  └─────────────────────────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  비유: CallController가 @RequestMapping("/call")으로 HTTP 주소를 등록하는 것과 똑같은 역할인데, WebSocket은 Spring MVC 어노테이션(@PostMapping 등)이 아니라 이런 별도의 설정 클래스로 직접 등록해줘야 합니다. (WebSocket은 Spring MVC와 다른 등록 방식을 쓰기
  때문)

  ---
  정리 — 왜 3개 다 따로 분리했나

  ┌─────────────────┬──────────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────┐
  │     Config      │                    누구를 위한 준비물                    │                          안 만들면?                           │
  ├─────────────────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤
  │ RedisConfig     │ CallHandler, CtiWebSocketHandler 등이 쓰는 RedisTemplate │ Redis 저장 포맷이 기본값(읽기 힘든 바이너리)으로 동작         │
  ├─────────────────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤
  │ WebClientConfig │ LLM/TTS 호출하는 서비스들이 쓰는 HTTP/WS 클라이언트      │ 직접 new WebClient.builder().build()를 여기저기서 반복해야 함 │
  ├─────────────────┼──────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤
  │ WebSocketConfig │ /ws/cti 엔드포인트 자체가 동작하려면 필수                │ 브라우저가 /ws/cti로 연결 시도해도 아무도 안 받아서 연결 실패 │
  └─────────────────┴──────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────┘

  셋 다 "비즈니스 로직이 아니라, 그 로직이 의존하는 외부 도구/프레임워크 기능을 한 군데서 세팅해주는" 역할입니다.

>> 좀 더 쉽게 설명 가능해? 알듯 모를듯. 필요성과 이유 등 뭔가 좀 이해가 덜돼.
● 한 발 물러서서 — Config가 왜 필요한지부터

  스프링은 "내가 만든 클래스"만 자동으로 알아챕니다

  @Service, @Component, @RestController 붙은 클래스
     → 스프링이 앱 시작할 때 자동으로 "어 이거 객체 하나 만들어서 보관해놔야겠다" 알아서 처리

  지금까지 본 CallHandler, CallController, LlmModeRouter 같은 건 내가(우리 프로젝트가) 직접 작성한 클래스라서 스프링이 보고 알아서 객체를 만들어줍니다.

  그런데 RedisTemplate, WebClient, OkHttpClient는 내가 만든 클래스가 아니다

  이건 남이 만든 라이브러리 코드입니다. 그 코드 파일을 열어서 @Service 같은 걸 붙일 수가 없습니다 (내 코드가 아니니까요). 그리고 /ws/cti 라는 주소를 어떤 핸들러가 처리할지 등록하는 절차도, 그냥 클래스 하나 만든다고 자동으로 되는 게 아니라 별도의 등록
  절차가 필요합니다.

  그래서 "이건 내가 대신 만들어서 스프링한테 등록해줄게"라고 손으로 직접 써주는 코드가 Config 클래스입니다.

  ---
  비유: 직원 vs 장비

  ┌─────────────┬────────────────────────────┬────────────────────────────────────────────────────────────────────────┐
  │             │  직원 (@Service 클래스들)  │                    장비 (Redis/WebClient/WebSocket)                    │
  ├─────────────┼────────────────────────────┼────────────────────────────────────────────────────────────────────────┤
  │ 누가 만드나 │ 회사(스프링)가 알아서 채용 │ 외부에서 사온 기계라 매니저가 직접 못 만듦                             │
  ├─────────────┼────────────────────────────┼────────────────────────────────────────────────────────────────────────┤
  │ 등록 방법   │ 자동                       │ 누군가 "이 장비는 이렇게 설치해서 카운터에 둬라" 설치 설명서를 써야 함 │
  ├─────────────┼────────────────────────────┼────────────────────────────────────────────────────────────────────────┤
  │ 그 설명서가 │ —                          │ 바로 Config 클래스                                                     │
  └─────────────┴────────────────────────────┴────────────────────────────────────────────────────────────────────────┘

  RedisConfig, WebClientConfig, WebSocketConfig = 각각 "Redis 장비", "WebClient/OkHttp 장비", "/ws/cti 접수창구"에 대한 설치 설명서입니다.

  ---
  이게 없으면 구체적으로 뭐가 깨지나

  ┌──────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │    Config 없으면     │                                                                                           결과                                                                                           │
  ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ RedisConfig 없음     │ CallHandler가 private final RedisTemplate<String,Object> redisTemplate; 받으려 해도 줄 사람이 없어서 앱 시작 자체가 실패                                                                 │
  ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ WebClientConfig 없음 │ LLM/TTS 호출하는 서비스들이 WebClient 못 받아서 마찬가지로 앱 시작 실패                                                                                                                  │
  ├──────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ WebSocketConfig 없음 │ 앱은 켜지지만, 브라우저가 ws://localhost:8080/ws/cti 로 접속 시도하면 "그런 주소 없음" 으로 연결 거부 (CtiWebSocketHandler 코드는 멀쩡히 있어도 그 주소에 연결을 안 시켜놨으니 무용지물) │
  └──────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  즉 셋 다 "코드는 다 짜놨는데, 그 코드가 실제로 쓰이려면 거쳐야 하는 등록 절차"입니다.

  ---
  그래서 각 Config가 정확히 등록하는 것

  ┌─────────────────┬───────────────────────────────────────────────────────────────────┐
  │     Config      │                      등록하는 것 한 줄 요약                       │
  ├─────────────────┼───────────────────────────────────────────────────────────────────┤
  │ RedisConfig     │ "Redis에 저장할 때, 키는 글자로, 값은 JSON으로 변환해서 넣어라"   │
  ├─────────────────┼───────────────────────────────────────────────────────────────────┤
  │ WebClientConfig │ "외부 API 부를 때 쓸 HTTP 도구, WebSocket 도구를 미리 만들어놔라" │
  ├─────────────────┼───────────────────────────────────────────────────────────────────┤
  │ WebSocketConfig │ "/ws/cti 주소로 연결 오면 CtiWebSocketHandler가 받아라"           │
  └─────────────────┴───────────────────────────────────────────────────────────────────┘

  이 세 개가 만들어낸 결과물(RedisTemplate, WebClient, 등록된 /ws/cti 주소)을 CallHandler 같은 다른 클래스들이 생성자로 받아서(private final RedisTemplate ...) 가져다 쓰는 겁니다 — 이전에 봤던 "생성자 주입" 그 자리에 들어가는 객체가 바로 여기서 만들어진
  것들입니다.

>> 그럼, 이 프로젝트는 외부 라이브러리 생성해서 쓰는 거은 3개(RedisConfig, WebClientConfig, WebSocketConfig) 밖에 없는거네?
● 맞습니다. 정확히 짚으셨습니다 — 이 프로젝트에서 외부 라이브러리/프레임워크 객체를 직접 만들어 등록하는 곳은 이 3개뿐입니다. grep으로 전체 확인해봐도 @Configuration이 붙은 클래스가 딱 3개입니다.

  정확히 세어보면

  ┌─────────────────┬───────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
  │     Config      │          @Bean 개수           │                                                                  등록 방식                                                                  │
  ├─────────────────┼───────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ RedisConfig     │ 1개 (RedisTemplate)           │ @Bean 메서드                                                                                                                                │
  ├─────────────────┼───────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ WebClientConfig │ 2개 (WebClient, OkHttpClient) │ @Bean 메서드                                                                                                                                │
  ├─────────────────┼───────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
  │ WebSocketConfig │ 0개                           │ @Bean이 아니라 WebSocketConfigurer 인터페이스를 구현해서 등록 (방식이 다름 — 직접 만드는 게 아니라 "어디로 연결을 보낼지" 매핑만 해주는 것) │
  └─────────────────┴───────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

  → "객체를 직접 만드는" @Bean은 총 3개(RedisTemplate, WebClient, OkHttpClient)이고, WebSocketConfig는 객체를 만드는 게 아니라 "이미 만들어진 CtiWebSocketHandler를 /ws/cti 주소에 연결해주는" 매핑 등록입니다.

  ---
  참고로 — 비슷해 보이지만 결이 다른 것 하나

  VoicebotApplication.java에 이런 게 있습니다:

  @SpringBootApplication
  @EnableJpaAuditing   // ← CallRecord의 @CreatedDate가 동작하게 하는 스위치
  @EnableScheduling    // ← @Scheduled 메서드(RTZR 토큰 자동 갱신 등)가 동작하게 하는 스위치
  public class VoicebotApplication { ... }

  이건 "객체를 만들어서 등록"하는 게 아니라 스프링 부트에 이미 내장된 기능을 켜는 스위치입니다 (전등 스위치 ON/OFF 같은 것). @Bean으로 새 객체를 만드는 것과는 다른 종류라서, "외부 라이브러리 객체를 만드는 곳"으로 셀 건 아닙니다.

  정리: 라이브러리 객체를 직접 만들어서 등록하는 진짜 "설치 설명서"는 말씀하신 3개가 맞고, VoicebotApplication의 두 어노테이션은 "이미 있는 기능 켜기"라서 결이 다른 별개 항목입니다.

```

### docker 에 대해서 알고 싶어. image & container 관계. 주요 명령어와 의미, 존재 이유 등 근본적인 개념부터 설명. 그리고 container 생성 & 사용 후 삭제가 낫은지 너무 많아져서 지저분한 것 같기도 하고.
```
● 도커의 핵심 개념을 이 프로젝트(docker-compose.yml)에서 실제 쓰는 예시로 묶어서 설명할게요.

  1. Image와 Container의 관계

  Image = 클래스(설계도), Container = 인스턴스(실행 중인 객체) 비유가 제일 정확합니다.

  mariadb:11 (image)          ──run──▶  voicebot-mariadb (container)
  "MariaDB 설치된 파일시스템"            "실제로 떠서 3306 포트 듣고 있는 프로세스"
  읽기 전용, 디스크에 고정              image 위에 '쓰기 가능한 레이어'를 한 장 얹은 것

  - Image: 여러 개의 읽기 전용 레이어(layer)를 쌓아놓은 템플릿. mariadb:11처럼 한 번 받아두면 바뀌지 않습니다.
  - Container: image의 레이어 위에 컨테이너 전용 쓰기 레이어(writable layer)를 하나 추가해서 실행한 상태. 컨테이너 안에서 파일을 쓰거나 지워도 image 자체는 전혀 변하지 않습니다.
  - 그래서 같은 image로 컨테이너를 몇 개든 찍어낼 수 있고, 컨테이너를 지워도 image는 그대로 남습니다.

  docker-compose.yml을 보면 이 관계가 그대로 보입니다.

  mariadb:
    image: mariadb:11          # ← 이게 image (Docker Hub에서 받은 템플릿)
    container_name: voicebot-mariadb   # ← 이게 그 image로 띄운 container의 이름
    volumes:
      - mariadb-data:/var/lib/mysql    # ← 컨테이너가 지워져도 살아남을 데이터

  2. Docker가 존재하는 이유

  가상머신(VM)과 비교하면 가장 빠르게 이해됩니다.

  ┌───────────────────────────┬────────────────────────┬─────────────────────────────────────────────────────┐
  │                           │           VM           │                  Docker Container                   │
  ├───────────────────────────┼────────────────────────┼─────────────────────────────────────────────────────┤
  │ 격리 단위                 │ OS 전체를 하나 더 부팅 │ 프로세스를 OS 커널 수준에서 격리 (namespace/cgroup) │
  ├───────────────────────────┼────────────────────────┼─────────────────────────────────────────────────────┤
  │ 부팅 시간                 │ 수십 초~분             │ 1초 내                                              │
  ├───────────────────────────┼────────────────────────┼─────────────────────────────────────────────────────┤
  │ 용량                      │ 수 GB (OS 포함)        │ 수십~수백 MB (앱 + 의존성만)                        │
  ├───────────────────────────┼────────────────────────┼─────────────────────────────────────────────────────┤
  │ "내 PC에서는 됐는데" 문제 │ 여전히 발생 가능       │ image 하나로 완전히 동일 환경 보장                  │
  └───────────────────────────┴────────────────────────┴─────────────────────────────────────────────────────┘

  이 프로젝트에서 실제로 체감되는 이유:
  - STT/LLM/TTS 시뮬레이터, MariaDB, Redis, MongoDB를 각각 로컬에 직접 설치하면 버전 충돌·포트 충돌·"누구 PC에는 있는데 누구 PC에는 없는" 문제가 생깁니다.
  - docker-compose.yml 하나만 받으면 팀원 전체가 똑같은 mariadb:11, redis:7-alpine 환경을 1초에 복제합니다. docs/dev-environment.md에 나온 devcontainer 철학("내 PC에서는 되는데" 문제 제거)과 같은 맥락입니다.

  3. 주요 명령어

  Image 관련

  docker images              # 로컬에 받아둔 image 목록
  docker pull mariadb:11      # Docker Hub에서 image 다운로드
  docker build -t myapp .     # Dockerfile로 image 직접 만들기
  docker rmi <image>           # image 삭제 (이 image를 쓰는 container가 없어야 함)

  Container 관련

  docker run <image>          # image로 새 container 생성 + 실행
  docker ps                   # 실행 중인 container 목록
  docker ps -a                # 멈춘 것까지 전부 (a = all)
  docker stop <container>     # 정지 (프로세스에 SIGTERM)
  docker start <container>    # 멈춘 container 다시 실행
  docker rm <container>       # container 삭제 (정지된 상태여야 함)
  docker exec -it <container> bash   # 실행 중인 container 내부로 진입
  docker logs -f <container>  # 로그 실시간 확인
  docker ps -a                # 멈춘 것까지 전부 (a = all)
  docker stop <container>     # 정지 (프로세스에 SIGTERM)
  docker start <container>    # 멈춘 container 다시 실행
  docker rm <container>       # container 삭제 (정지된 상태여야 함)
  docker exec -it <container> bash   # 실행 중인 container 내부로 진입
  docker logs -f <container>  # 로그 실시간 확인

  Compose (여러 container를 한 단위로)

  docker compose up -d        # compose 파일 기준으로 전체 기동 (백그라운드)
  docker compose down         # 전체 정지 + container 삭제 (volume은 보존)
  docker compose down -v      # container + volume까지 전부 삭제

  4. 컨테이너, 매번 지우는 게 나을까?

  판단 기준은 하나입니다: 상태(데이터)를 컨테이너 안에 들고 있는가, 밖(volume)에 들고 있는가.

  - mariadb / redis / mongodb처럼 데이터를 가진 컨테이너: 이 프로젝트는 이미 volumes: mariadb-data:/var/lib/mysql 처럼 데이터를 컨테이너 밖 volume에 분리해뒀습니다. 그러니 컨테이너 자체는 지워도 안전합니다 — docker composedown은 컨테이너만 지우고
  volume은 남기니, 다음에 up 하면 데이터가 그대로 복원됩니다. down -v를 써야 volume까지 날아갑니다.
  - 일회성으로 띄워서 테스트만 하는 컨테이너 (예: docker run -it ubuntu bash로 뭔가 잠깐 확인): 이런 건 docker run --rm <image>로 띄우는 게 정답입니다. --rm 옵션을 주면 컨테이너가 멈추는 즉시 자동 삭제되어 애초에 쌓이지 않습니다.

  지저분해지는 전형적인 원인은 "--rm 없이 docker run을 반복"하는 패턴입니다. 이미 멈춘 컨테이너가 docker ps -a에 계속 쌓입니다.

  정리가 필요할 때 쓰는 명령:
  docker container prune      # 멈춘 container 전부 삭제 (volume은 안 건드림)
  docker image prune          # 어떤 container도 쓰지 않는 dangling image 삭제
  docker system prune         # container + network + dangling image 한 번에 (volume은 기본 제외)
  docker system prune -a --volumes   # 진짜 다 지우기 (주의: 운영 중인 volume도 날아감)

  권장 워크플로우: 이 프로젝트처럼 docker-compose.yml로 관리되는 서비스는 매번 지웠다 새로 만들 필요 없이 docker compose down (개발 끝날 때) / up -d (다시 시작할 때)만 반복하면 됩니다. 컨테이너가 어차피 compose 단위로 묶여있어 지저분해질 일이 적고,
  volume 덕분에 데이터도 안전합니다. 정말 쌓인 게 보이면 docker container prune이나 docker system prune 한 번으로 충분합니다.

```

### docker images 결과인데 'vsc-voicebot-js-42468:devpod-' 시작하는 이미지가 왜 이리 많어?
```
>> docker images 결과
❯ docker images 결과인데 'vsc-voicebot-js-42468:devpod-' 시작하는 이미지가 왜 이리 많어?
  vscode ➜ /workspaces/voicebot-js (main) $ docker images
                                                                                                             i Info →   U  In Use
  IMAGE                                                                      ID             DISK USAGE   CONTENT SIZE   EXTRA
  1-oauth2-based-project-app:latest                                          3699735839bd        534MB          157MB    U
  1-oauth2-based-project-frontend:latest                                     b71890033171         92MB         25.9MB    U
  4-ai-habit-platform-ai:latest                                              f5fd4c80a177       1.72GB          364MB    U
  4-ai-habit-platform-api:latest                                             16c3e75973c7       1.55GB          324MB    U
  4-ai-habit-platform-demo:latest                                            ed69130221af        466MB         99.7MB
  debian:bookworm                                                            ed4fcc40bb11        207MB         52.1MB
  docker.elastic.co/beats/filebeat:8.12.2                                    4a91cdd2a829        419MB          108MB
  docker.elastic.co/beats/filebeat:8.13.0                                    b43fdc25fce2        425MB          108MB    U
  docker.elastic.co/elasticsearch/elasticsearch:8.12.2                       81b3bbd42d4d       1.27GB          473MB
  docker.elastic.co/elasticsearch/elasticsearch:8.13.0                       fcf6b29cb00f       1.27GB          475MB    U
  docker.elastic.co/kibana/kibana:8.12.2                                     529459ea3b52       1.79GB          399MB
  docker.elastic.co/kibana/kibana:8.13.0                                     de457abb7ac4        1.8GB          402MB    U
  docker.elastic.co/logstash/logstash:8.12.2                                 ecde323f45ea       1.23GB          430MB
  docker.elastic.co/logstash/logstash:8.13.0                                 0b8667a00f57       1.37GB          495MB    U
  etl-public-data-backend:latest                                             9a9cc10adb59        589MB          141MB
  etl-public-data-frontend:latest                                            93ba7fbe4378        644MB          131MB
  grafana/grafana:10.3.3                                                     8640e5038e83        533MB          105MB    U
  jaegertracing/all-in-one:1.55                                              f6b5d09073f1        105MB         32.3MB    U
  mariadb:11                                                                 7974b628cbb4        484MB          106MB    U
  mariadb:11.2                                                               ff87d49107a1        534MB          122MB    U
  mcr.microsoft.com/devcontainers/base:ubuntu                                3dcb059253b2       1.06GB          275MB    U
  mcr.microsoft.com/devcontainers/java:21-bookworm                           4340df89b1c1       2.12GB          631MB
  mcr.microsoft.com/devcontainers/java:latest                                02e4c51fa0f8       2.35GB          687MB
  mongo:7                                                                    81ed620b4593       1.12GB          281MB    U
  postgres:15                                                                f30e3de0ac9c        654MB          162MB
  postgres:16-alpine                                                         20edbde7749f        389MB          109MB    U
  postgres:16.2                                                              4aea012537ed        636MB          157MB
  prom/prometheus:v2.50.1                                                    bc1794e85c9e        347MB         95.7MB    U
  redis:7-alpine                                                             6ab0b6e73817       58.7MB         17.3MB    U
  testcontainers/ryuk:0.12.0                                                 dd3f023a6ed7       29.2MB         11.7MB
  voicebot-js-call-simulator:latest                                          a03e6896231f        218MB         53.4MB
  voicebot-js-llm-simulator:latest                                           d777938f4d5e        209MB         51.4MB
  voicebot-js-stt-simulator:latest                                           3743fed338fa        209MB         51.4MB
  voicebot-js-tts-simulator:latest                                           8f4845c2b8eb        209MB         51.4MB
  vsc-1-oauth2-based-project-fe7fd:devpod-e84f7d36a860b05de83462dcf512f771   48b81a287e42       4.03GB         1.18GB    U
  vsc-1-think-link-29021:devpod-f3c98f62e03e76b0d660953b4675e0a5             0a6672bd0638       2.84GB          711MB    U
  vsc-2_toyproject-64cad:devpod-0cfa8cbdcf782a66eec63f1aca6982c5             764e41108076       2.82GB          744MB    U
  vsc-3_etl_mvp-ea24f:devpod-b4055cad11b3e2e19b74a1d9676a9180                12cbf9cb8bd5       3.19GB          840MB    U
  vsc-4-ai-habit-platform-2e373:devpod-8e3fa59c058d8112a1f2a764445a6454      a5b24d1c1553       2.59GB          672MB    U
  vsc-voicebot-js-42468:devpod-052288938e8564300d6d24f992e9f6b3              1d1d343754f8       3.51GB         1.01GB
  vsc-voicebot-js-42468:devpod-407f8714d4797a032ed6a994c6fb7be0              e248096d21e4       3.04GB          856MB
  vsc-voicebot-js-42468:devpod-59dc167fbd7b281e4fb2a99c4785fe25              4f00d02912b3       3.86GB         1.06GB
  vsc-voicebot-js-42468:devpod-96d1a0502a35c11fa3258536437eb22b              95a3e911c2b0        3.6GB         1.03GB
  vsc-voicebot-js-42468:devpod-a416506746e871a346e49aab40b6d943              c562aac5719c       3.51GB            1GB
  vsc-voicebot-js-42468:devpod-c85af7ed39eb42bdc4d30789a8bfa811              2f2743208a5a       3.87GB         1.06GB
  vsc-voicebot-js-42468:devpod-e1f39c9a472b9bb3787a2b92d5b6efb5              6391412ee2ca       3.92GB         1.07GB    U
  vscode ➜ /workspaces/voicebot-js (main) $
  
● vsc-voicebot-js-42468:devpod-*로 시작하는 이미지들이 7개나 쌓여있고, 마지막 줄(devpod-e1f39c9a...)에만 U(In Use) 표시가 붙어있네요 — 즉 나머지 6개는 전혀 안 쓰이는데 디스크만 차지하고 있는 상태입니다. 실제로 어떤 컨테이너가 지금 떠 있는지 먼저
  확인해볼게요.

● Bash(docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' && echo "---" && docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' | grep -i devp…)
  ⎿  NAMES               IMAGE                                                           STATUS
     priceless_goodall   vsc-voicebot-js-42468:devpod-e1f39c9a472b9bb3787a2b92d5b6efb5   Up 15 hours
     ---
     … +6 lines (ctrl+o to expand)

● 확인했습니다. priceless_goodall라는 컨테이너 하나만 떠 있고, 그게 쓰는 이미지는 devpod-e1f39c9a472b9bb3787a2b92d5b6efb5(지금 우리가 작업 중인 이 devcontainer) 하나뿐입니다. 나머지 6개의 vsc-voicebot-js-42468:devpod-* 이미지는 컨테이너조차 없는 완전한
  고아 이미지입니다.

  왜 이렇게 쌓였나

  DevPod은 devcontainer를 빌드할 때 devcontainer.json + Dockerfile 내용을 해시(hash)로 계산해서 이미지 태그를 만듭니다.

  devpod-052288938e8...  ← 1차 빌드 (Dockerfile 수정 전)
  devpod-407f8714d47...  ← 2차 빌드 (뭔가 수정 후 rebuild)
  devpod-59dc167fbd7...  ← 3차 빌드
  ...
  devpod-e1f39c9a472...  ← 현재 빌드 (지금 쓰는 것)

  devcontainer.json이나 Dockerfile을 바꾸고 "Rebuild Container" 를 할 때마다 해시값이 달라지므로 DevPod은 매번 새 이미지를 빌드합니다. 문제는 이전 이미지를 자동으로 지워주지 않는다는 것 — 혹시 롤백할 경우를 대비해 그냥 남겨둡니다. 그런데 실제로 롤백하는
  일은 거의 없으니 계속 쌓이는 거죠.

  JDK + Maven + 각종 툴이 들어간 devcontainer 이미지 하나가 3~4GB라서, 안 쓰는 6개만 합쳐도 약 21GB가 그냥 버려지고 있는 셈입니다.

  이게 바로 이전에 말씀하신 "사용 후 삭제 안 하면 지저분해진다"는 우려가 실제로 발생한 사례입니다. 다만 원인이 docker run을 반복한 게 아니라 devcontainer rebuild가 쌓인 것이라는 차이가 있습니다.

  정리 방법

  현재 쓰는 이미지(6391412ee2ca / devpod-e1f39c9a...)만 남기고 나머지를 지우면 됩니다.

  docker rmi 1d1d343754f8 e248096d21e4 4f00d02912b3 95a3e911c2b0 c562aac5719c 2f2743208a5a

  이 6개를 지워도 지금 작업 환경에는 영향이 없습니다(컨테이너가 그 이미지들을 참조하지 않으니까요). 실행해도 괜찮으면 말씀해주세요, 대신 실행해 드릴게요.

  ▎ 참고로 vsc-1-oauth2-based-project-*, vsc-4-ai-habit-platform-* 같은 것들은 다른 프로젝트의 devpod 이미지/컨테이너입니다(전부 Exited 상태). 이건 voicebot-js와 무관하니 그 프로젝트들을 더 쓸 계획이 없을 때만 따로 정리하시면 됩니다.

>> 'docker rmi 1d1d343754f8 e248096d21e4 4f00d02912b3 95a3e911c2b0 c562aac5719c 2f2743208a5a' 하면 지워지는거야?
● 네, 정확히 그 동작을 합니다.

  - docker rmi <IMAGE_ID> = 해당 ID의 이미지를 로컬 디스크에서 삭제
  - 여러 개의 ID를 공백으로 나열하면 한 번에 순서대로 삭제됩니다
  - 지정한 6개 ID(1d1d343754f8, e248096d21e4, 4f00d02912b3, 95a3e911c2b0, c562aac5719c, 2f2743208a5a)만 정확히 지워지고, 나머지(6391412ee2ca 등 다른 이미지)는 그대로 남습니다

  방금 docker ps -a로 확인했을 때 이 6개를 참조하는 컨테이너가 하나도 없었으므로, 삭제 시 다음과 같이 정상적으로 지워질 겁니다.

  Deleted: sha256:1d1d343754f8...
  Deleted: sha256:e248096d21e4...
  ...

  만약 어딘가에서 여전히 참조 중이라면 (그럴 가능성은 없지만) Error response from daemon: conflict: unable to delete ... (must be forced) - image is being used by stopped container ... 같은 에러가 나고 그 항목만 실패합니다. 지금 상태로는 6개 다 깨끗하게
  지워질 거예요.

```

### rmi 의미?
```
● rmi = remove image. docker rmi <image>는 로컬에 받아둔(또는 빌드한) 이미지를 삭제하는 명령입니다.

  비교하면 헷갈리지 않습니다.

  ┌───────────────────────┬───────────┬──────────────────────────────────────────────┐
  │         명령          │   대상    │                     의미                     │
  ├───────────────────────┼───────────┼──────────────────────────────────────────────┤
  │ docker rm <container> │ container │ 컨테이너 삭제                                │
  ├───────────────────────┼───────────┼──────────────────────────────────────────────┤
  │ docker rmi <image>    │ image     │ 이미지 삭제 ( = docker image rm의 줄임 별칭) │
  └───────────────────────┴───────────┴──────────────────────────────────────────────┘

  컨테이너는 이미지 위에 떠 있는 "실행 인스턴스"이므로, 그 이미지를 쓰는 컨테이너가 남아있으면 rmi가 거부됩니다 — 먼저 rm으로 컨테이너를 지운 뒤에야 rmi로 이미지를 지울 수 있습니다 (지금 케이스는 컨테이너가 없으니 바로 rmi 가능).

```

### container names 은 어떻게 결정? 같은 이미지 사용해도 다른 경우가 있는지?
```
● Container 이름은 세 가지 방식 중 하나로 결정됩니다.

  1. --name으로 직접 지정

  docker run --name my-mariadb mariadb:11

  2. Docker Compose의 container_name:

  이 프로젝트 docker-compose.yml이 바로 이 방식입니다.

  mariadb:
    image: mariadb:11
    container_name: voicebot-mariadb   # ← 항상 이 이름으로 고정

  compose down → up을 반복해도 매번 voicebot-mariadb라는 동일한 이름이 재사용됩니다.

  3. 아무것도 안 지정하면 → Docker가 무작위 생성

  지난번 docker ps -a 결과에 나온 이게 바로 이 케이스입니다.

  priceless_goodall      ← vsc-voicebot-js-42468:devpod-e1f39c9a...
  confident_blackwell    ← vsc-2_toyproject-64cad:devpod-0cfa8cbd...
  hopeful_visvesvaraya   ← vsc-1-think-link-29021:devpod-f3c98f62...
  jolly_williamson       ← vsc-4-ai-habit-platform-2e373:devpod-8e3fa59c...

  Docker 내부에 박혀있는 형용사 목록 + 유명 과학자/해커 성씨 목록에서 하나씩 뽑아 형용사_성씨 형태로 조합합니다 (DevPod이 컨테이너를 띄울 때 --name을 지정하지 않아서 이렇게 됩니다).

  같은 이미지인데 이름이 다른 경우?

  네, 매번 다릅니다 — 이름은 이미지와 완전히 무관하게, docker run을 호출할 때마다 새로 뽑힙니다.

  docker run -d redis:7-alpine    # → 예: silly_einstein
  docker run -d redis:7-alpine    # → 예: brave_turing
  docker run -d redis:7-alpine    # → 예: nostalgic_curie

  똑같은 redis:7-alpine 이미지로 3번 실행해도 이름은 매번 랜덤하게 다릅니다. 이름은 컨테이너 인스턴스 식별자일 뿐이고, 이미지는 "어떤 템플릿으로 만들었는지"만 나타내므로 둘은 서로 독립적입니다.

  한 가지 제약: 이름은 호스트 내에서 유일해야 함

  docker run --name web nginx
  docker run --name web nginx   # ❌ Error: Conflict, container name "/web" is already in use

  같은 이름을 다시 쓰려면 기존 컨테이너를 docker rm web으로 지우거나, 다른 이름을 줘야 합니다. (정지된 상태라도 이름이 점유되어 있으면 충돌합니다.)

  정리: container_name을 명시하면 재현 가능하고 알아보기 쉬운 이름을 유지할 수 있어서, 이 프로젝트의 docker-compose.yml처럼 인프라 서비스에는 명시하는 게 좋은 습관입니다. DevPod처럼 임시·내부용 컨테이너는 굳이 안 줘도 되니 랜덤 이름으로 남아있는 겁니다.

```

### 현재 devpod 띄울 때 docker-compose.yml 에 container_name 지정되 있지 않나? 왜 다르게 느끼지?
```
● 좋은 질문이에요. 둘이 완전히 다른 컨테이너라서 그렇습니다. .devcontainer/devcontainer.json을 확인해볼게요.

  Searched for 1 pattern (ctrl+o to expand)

● 핵심은 두 컨테이너가 서로 다른 명령으로, 서로 다른 설정 파일을 보고 만들어진다는 점입니다.

  devcontainer 자체 (priceless_goodall)

  .devcontainer/devcontainer.json을 보면:

  {
    "build": { "dockerfile": "Dockerfile" }
  }

  docker-compose.yml을 전혀 참조하지 않습니다. devpod up을 실행하면 DevPod이 .devcontainer/Dockerfile로 직접 docker build + docker run을 합니다. 이때 runArgs나 mounts는 지정돼 있지만 이름(--name)을 지정하는 옵션이 없습니다. 그래서 Docker가 랜덤
  이름(priceless_goodall)을 붙인 겁니다.

  mariadb/redis/mongodb (voicebot-mariadb 등)

  이건 완전히 별개의 파일 docker-compose.yml에서, 다른 명령으로 띄웁니다.

  docker compose -f docker-compose.yml -f docker-compose.sim.yml up -d

  이 파일 안에는 서비스마다 container_name:이 명시돼 있어서 고정된 이름이 붙습니다.

  정리: 두 개의 독립된 컨트롤 플레인

  devpod up                                    docker compose up -d
     │                                              │
     ▼                                              ▼
  .devcontainer/Dockerfile 빌드                docker-compose.yml 읽음
     │                                              │
     ▼                                              ▼
  docker run (이름 미지정)                     docker run (container_name 지정)
     │                                              │
     ▼                                              ▼
  priceless_goodall                           voicebot-mariadb, voicebot-redis, ...
  (devcontainer 자신 = 지금 이 셸)              (devcontainer 안에서 추가로 띄운 인프라)

  둘이 같은 docker ps 목록에 같이 보이는 이유는, devcontainer.json의 mounts에 있는

  "source=/var/run/docker.sock,target=/var/run/docker.sock,type=bind"

  때문입니다. 이게 호스트의 Docker 소켓을 devcontainer 안으로 그대로 꽂아주는 Docker-out-of-Docker 방식(docs/dev-environment.md 8절에서 다룬 패턴과 같은 계열)이라서, devcontainer 안에서 docker ps를 쳐도 devcontainer 자기 자신과, 그 안에서 추가로 띄운 형제
  컨테이너들이 동일한 호스트 Docker 데몬 위에 나란히 보이는 겁니다. 즉 한 호스트 데몬, 두 개의 서로 다른 "누가 컨테이너를 만들었나" 경로가 합쳐져서 보이는 거라 이름 부여 방식도 따로 갑니다.

```

### Host OS 에서 devpod 로 devcontainer 컨테이너 생성하고 그 개발환경에서 docker compose up 으로 실행해서 작업환경 까지 만들었어. 그러면 이건 DinD(Docker in Docker) 야. 그런데 devcontainer.json 에서 mounts 섹션에서 "source=/var/run/docker.sock,target=/var/run/docker.sock,type=bind" 설정해서 DooD(Docker out of Docker) 되는건가?
```
● 정확히 이해하셨습니다. 겉모양은 DinD처럼 보이지만 실제로는 DooD입니다.

  DinD vs DooD 차이

  DinD — 컨테이너 안에 Docker 데몬이 하나 더 뜨는 구조

  Host OS
  └── Docker 데몬 (host)
      └── devcontainer
          └── Docker 데몬 (inner) ← 별도 데몬
              ├── mariadb
              └── redis

  - 컨테이너 안 컨테이너가 inner 데몬에 귀속
  - host docker ps에 mariadb/redis가 안 보임
  - --privileged 권한 필요 (보안 위험)

  DooD — 소켓만 공유해서 host 데몬을 그대로 씀

  Host OS
  └── Docker 데몬 (host) ← 데몬은 하나뿐
      ├── devcontainer (priceless_goodall)
      │   └── /var/run/docker.sock → host 소켓으로 연결
      ├── voicebot-mariadb   ← devcontainer가 만들었지만
      ├── voicebot-redis        host 데몬이 직접 관리
      └── voicebot-mongodb

  이 프로젝트가 DooD인 증거

  두 가지로 확인됩니다.

  1. devcontainer.json의 설정
  "source=/var/run/docker.sock,target=/var/run/docker.sock,type=bind"
  host의 소켓 파일을 컨테이너 안으로 그대로 꽂은 것 — 새 데몬을 띄우는 게 아닙니다.

  2. 아까 docker ps -a 결과

  devcontainer 안에서 실행했는데 priceless_goodall(devcontainer 자신)과 mariadb/redis가 같은 목록에 나란히 보였습니다. DinD였다면 inner 데몬은 자기 자신(devcontainer)을 볼 수 없고, host의 컨테이너도 볼 수 없습니다.

  정리

  ┌──────────────────────┬────────────────────────────┬───────────────────────────────────────┐
  │                      │            DinD            │          DooD (이 프로젝트)           │
  ├──────────────────────┼────────────────────────────┼───────────────────────────────────────┤
  │ 데몬 개수            │ 2개 (host + inner)         │ 1개 (host만)                          │
  ├──────────────────────┼────────────────────────────┼───────────────────────────────────────┤
  │ 생성된 컨테이너 위치 │ inner 데몬 소속            │ host 데몬 소속 (형제)                 │
  ├──────────────────────┼────────────────────────────┼───────────────────────────────────────┤
  │ host에서 가시성      │ 안 보임                    │ 보임                                  │
  ├──────────────────────┼────────────────────────────┼───────────────────────────────────────┤
  │ 설정 방법            │ --privileged + docker:dind │ .sock bind mount                      │
  ├──────────────────────┼────────────────────────────┼───────────────────────────────────────┤
  │ 보안                 │ 위험                       │ 상대적으로 낫지만 소켓 권한 주의 필요 │
  └──────────────────────┴────────────────────────────┴───────────────────────────────────────┘

  "컨테이너 안에서 컨테이너를 만든다"는 행위는 같아 보여도, 데몬을 공유하느냐 아니냐가 DinD/DooD를 가르는 핵심입니다.

>>  privileged 단어 뜻
● privileged (발음: 프리빌리지드)는 특권을 가진, 특별 권한이 있는 이라는 뜻입니다.

● Linux 권한 체계에서 나온 단어입니다.

privileged = "특권을 가진" / "권한이 부여된"

일반 프로세스(컨테이너 포함)는 보안상 할 수 있는 일이 제한됩니다. --privileged를 주면 그 제한이 풀려서 host OS 커널에 직접 접근하는 수준의 권한을 갖게 됩니다.

DinD에서 --privileged가 필요한 이유 — 컨테이너 안에 새 Docker 데몬을 띄우려면 커널 기능(네트워크 인터페이스 생성, cgroup 제어 등)을 직접 건드려야 하는데, 그게 일반 컨테이너 권한으로는 막혀있기 때문입니다.

일상 비유로는 "관리자 권한으로 실행" (Windows의 UAC, sudo) 과 비슷한 개념입니다.

● docker run --privileged ubuntu

>> cgroup 이란?
● cgroup = control group

  Linux 커널이 프로세스들을 그룹으로 묶어서 자원을 제한·측정·격리하는 기능입니다.

  제어하는 자원

  ┌────────────┬──────────────────────────────┐
  │    자원    │             예시             │
  ├────────────┼──────────────────────────────┤
  │ CPU        │ 이 그룹은 CPU 20%까지만 써라 │
  ├────────────┼──────────────────────────────┤
  │ 메모리     │ 이 그룹은 최대 512MB까지만   │
  ├────────────┼──────────────────────────────┤
  │ 디스크 I/O │ 읽기 속도 100MB/s 제한       │
  ├────────────┼──────────────────────────────┤
  │ 네트워크   │ 대역폭 제한                  │
  └────────────┴──────────────────────────────┘

  Docker와의 관계

  docker run --memory=512m --cpus=1 nginx 같은 옵션이 내부적으로 cgroup을 설정하는 겁니다.

  Docker 데몬
  └── docker run 실행
      └── Linux 커널에 cgroup 생성
          └── 컨테이너 프로세스를 그 cgroup에 배정
              → 메모리/CPU 자동 제한 적용

  DinD에서 왜 cgroup이 문제가 되나

  컨테이너 안에서 새 Docker 데몬을 띄우면, 그 inner 데몬도 자식 컨테이너들을 위해 cgroup을 새로 만들어야 합니다. 그런데 이미 자신이 cgroup 안에 갇혀있는 상태라 — 커널 입장에서는 "cgroup 안에서 또 cgroup을 만들겠다"는
  요청이고, 이게 일반 권한으로는 거부됩니다. --privileged를 줘야 커널이 허용합니다.

  쉽게 말하면 cgroup은 Docker가 "이 컨테이너는 이만큼만 써라"를 강제하는 Linux 커널 장치입니다.

>> namespace 란?
● namespace = Linux 커널이 프로세스에게 "보이는 세계"를 분리해주는 기능

  cgroup이 자원 사용량을 제한한다면, namespace는 무엇이 보이는지를 격리합니다.

  namespace 종류

  ┌───────────┬─────────────────────┬─────────────────────────────────────────────┐
  │ namespace │      격리 대상      │             컨테이너에서의 효과             │
  ├───────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ PID       │ 프로세스 목록       │ 컨테이너 안에서 ps하면 자기 프로세스만 보임 │
  ├───────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ NET       │ 네트워크 인터페이스 │ 컨테이너마다 독립된 IP, 포트 공간           │
  ├───────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ MNT       │ 파일시스템 마운트   │ 컨테이너가 자기 루트(/)를 가짐              │
  ├───────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ UTS       │ hostname            │ 컨테이너마다 다른 hostname 설정 가능        │
  ├───────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ IPC       │ 프로세스 간 통신    │ 컨테이너끼리 메모리 공유 안 됨              │
  ├───────────┼─────────────────────┼─────────────────────────────────────────────┤
  │ USER      │ 사용자/그룹 ID      │ 컨테이너 안 root ≠ host root                │
  └───────────┴─────────────────────┴─────────────────────────────────────────────┘

  비유

  여러 직원이 같은 회사 건물(host OS)에서 일하는데, 각자 **자기 방(namespace)**에 있어서 다른 방이 안 보이는 상태입니다. 실제로는 같은 건물(커널)을 공유하지만, 서로의 존재를 모릅니다.

  Docker와의 관계

  docker run nginx 실행
  └── 커널이 새 namespace 세트 생성
      ├── PID namespace  → 컨테이너 안 프로세스는 PID 1부터 시작
      ├── NET namespace  → eth0, 172.17.0.x IP 할당
      ├── MNT namespace  → nginx 이미지 파일시스템이 /로 보임
      └── UTS namespace  → hostname = 컨테이너 ID

  컨테이너는 VM처럼 보이지만 실제로는 namespace로 격리되고 cgroup으로 제한된 프로세스입니다. 별도의 OS를 부팅하는 게 아니라 host 커널을 그대로 쓰면서 "보이는 것만 분리"하는 거라 가볍고 빠른 겁니다.

  cgroup vs namespace 한 줄 요약

  ┌───────────┬───────────────────────────────┐
  │           │             역할              │
  ├───────────┼───────────────────────────────┤
  │ cgroup    │ 얼마나 쓸 수 있나 (양적 제한) │
  ├───────────┼───────────────────────────────┤
  │ namespace │ 무엇이 보이나 (범위 격리)     │
  └───────────┴───────────────────────────────┘

  이 둘이 합쳐져서 Docker 컨테이너의 격리가 만들어집니다.

>> namespace 은 컨테이너에서 보여지는 세상을 결정해준다는건가? 좀 더 쉽게 말해줘.
● 네, 맞습니다.

  "컨테이너가 자기만의 세상을 가진 것처럼 착각하게 만드는 기술" 입니다.

  ---
  실제로는 host OS 위에서 그냥 프로세스 하나가 돌고 있는 건데, 그 프로세스한테 커널이 이렇게 말하는 겁니다.

  ▎ "너는 이것만 볼 수 있어. 나머지는 없는 거야."

  ---
  구체적으로

  컨테이너 안에서 ps 치면 프로세스가 몇 개 안 나옵니다.

  # 컨테이너 안
  $ ps
  PID 1  nginx
  PID 2  nginx worker

  근데 host에서 ps치면 수백 개가 나옵니다. 컨테이너 안 nginx도 거기 섞여서 보입니다.

  실제로는 같은 프로세스인데, 컨테이너 안에서는 "나 혼자다" 라고 보이는 겁니다. namespace가 시야를 차단했기 때문입니다.

  ---
  일상 비유

  놀이공원 포토존에서 배경 그림 앞에 서서 사진 찍는 것과 비슷합니다.

  - 실제로는 놀이공원 한 구석에 서 있는 것
  - 사진에는 에펠탑 앞에 있는 것처럼 보임

  컨테이너도 실제로는 host OS 위의 프로세스인데, namespace 덕분에 "나는 독립된 서버다"라고 느끼는 겁니다.

>> 그러면 DinD 는 문제가 맞네. DooD 가 맞는 방향이네?
● 개발 환경에서는 맞습니다. 다만 DooD도 완벽하진 않아서 한 줄 추가합니다.

  DinD의 문제

  - --privileged 필요 → 커널에 무제한 접근 → 보안 위험
  - 데몬이 두 개 → 이미지도 두 벌, 컨테이너도 두 벌 → 복잡하고 무거움
  - 이미 namespace 안에 갇힌 상태에서 또 namespace를 만들려니 충돌 발생

  DooD의 장점

  - 데몬이 host 하나뿐 → 단순
  - 이미지 공유 → 재다운로드 불필요
  - --privileged 불필요

  DooD의 주의점

  /var/run/docker.sock에 접근 가능하다는 것 자체가 사실상 host root 권한입니다.

  docker.sock 접근 가능
      └── docker run --privileged -v /:/host ubuntu bash
          └── host 파일시스템 전체 접근 가능

  그래서 DooD도 신뢰할 수 있는 환경(개인 개발 PC, 팀 내부 서버)에서는 괜찮지만, 외부 코드가 실행되는 공개 CI 환경에서는 위험합니다.

  정리

  ┌────────────────┬─────────────────┬─────────────────────────────────┐
  │                │      DinD       │              DooD               │
  ├────────────────┼─────────────────┼─────────────────────────────────┤
  │ 개발 환경      │ ❌ 복잡, 무거움 │ ✅ 이 프로젝트처럼              │
  ├────────────────┼─────────────────┼─────────────────────────────────┤
  │ 공개 CI        │ ❌              │ ❌ (Kaniko 같은 별도 도구 사용) │
  ├────────────────┼─────────────────┼─────────────────────────────────┤
  │ 완전 격리 필요 │ ✅              │ ❌                              │
  └────────────────┴─────────────────┴─────────────────────────────────┘

  이 프로젝트처럼 개발자 본인 PC에서 devcontainer 쓰는 상황은 DooD가 정석입니다.

```
###  ".devcontainer/Dockerfile로 직접 docker build + docker run을 합니다. 이때 runArgs나 mounts는 지정돼 있지만 이름(--name)을 지정하는 옵션이 없습니다. 그래서 Docker가 랜덤 이름(priceless_goodall)을 붙인 겁니다." 내용 보면 runArgs나 mounts는 지정되어 있다는데 맞어? 안 보이는데
```
● 확인해볼게요, 파일을 다시 정확히 읽어서 줄 번호로 보여드리겠습니다.

  Read 1 file (ctrl+o to expand)

● 네, 6~12번째 줄에 정확히 있습니다.

  6   "runArgs": [
  7     "--network=voicebot-net"
  8   ],
  9   "mounts": [
  10    "source=/var/run/docker.sock,target=/var/run/docker.sock,type=bind",
  11    "source=${localEnv:HOME}/.ssh,target=/home/vscode/.ssh,type=bind,readonly"
  12  ],

  - runArgs: 컨테이너를 voicebot-net이라는 네트워크에 연결 (그래야 같은 네트워크의 mariadb/redis/mongodb 컨테이너와 통신 가능)
  - mounts: Docker 소켓 + SSH 키를 호스트에서 바인드 마운트

  제가 말한 요점은 "이 항목들은 있는데, 그중에 컨테이너 이름을 지정하는 항목(--name 또는 runArgs에 --name=xxx 형태로 넣는 것)은 없다"는 것이었습니다. runArgs 배열에 --network=voicebot-net만 들어있을 뿐, --name=voicebot-dev-container 같은 옵션은 빠져있죠.
  그래서 컨테이너 이름 결정에는 영향을 주지 않고, Docker가 알아서 랜덤 이름을 붙인 겁니다.

  혹시 다른 부분이 안 보인다고 느끼신 거라면, 어느 줄을 보고 그렇게 느꼈는지 짚어주시면 더 정확히 답변드릴게요.

```

### 그건 .devcontainer/devcontainer.json 이지 않어? Dockerfile 라며?
```
● 맞습니다, 제가 부정확하게 말했어요. runArgs/mounts는 Dockerfile이 아니라 devcontainer.json에 있는 필드입니다. 둘의 역할이 다릅니다.

  ┌─────────────────────────────────┬────────────────────────────────────────────────────────────────────┬──────────────────────┐
  │              파일               │                                역할                                │ 대응되는 Docker 명령 │
  ├─────────────────────────────────┼────────────────────────────────────────────────────────────────────┼──────────────────────┤
  │ .devcontainer/Dockerfile        │ 이미지를 어떻게 빌드할지 (FROM, RUN apt-get ...)                   │ docker build         │
  ├─────────────────────────────────┼────────────────────────────────────────────────────────────────────┼──────────────────────┤
  │ .devcontainer/devcontainer.json │ 빌드된 이미지를 어떻게 실행할지 (runArgs, mounts, forwardPorts...) │ docker run           │
  └─────────────────────────────────┴────────────────────────────────────────────────────────────────────┴──────────────────────┘

  DevPod이 하는 일은 이 둘을 순서대로 합친 것입니다.

  devcontainer.json 읽음
     │
     ├─ "build.dockerfile": "Dockerfile" 확인
     │       ▼
     │   docker build -f .devcontainer/Dockerfile .
     │       ▼
     │   이미지 생성 (vsc-voicebot-js-42468:devpod-<hash>)
     │
     └─ 그 이미지로 컨테이너 실행
             ▼
         docker run [devcontainer.json의 runArgs, mounts 적용] <빌드된 이미지>
             ▼
         priceless_goodall (이름 미지정 → 랜덤)

  즉 runArgs/mounts는 Dockerfile 내용과는 무관하고, 빌드가 끝난 이미지를 docker run할 때 어떤 옵션을 붙일지를 devcontainer.json이 지정하는 것입니다. 그 옵션 목록에 --name이 없어서 이름이 랜덤하게 붙은 거고요.

  정정하자면, 제가 "Dockerfile로 docker build + docker run을 합니다. 이때 runArgs나 mounts는 지정돼 있지만"이라고 한 문장이 마치 Dockerfile 안에 runArgs/mounts가 있는 것처럼 읽혔을 텐데, 그건 잘못된 표현이었습니다 — 정확히는 devcontainer.json이
  build(Dockerfile 참조)와 run(runArgs/mounts) 두 단계를 모두 정의하는 것이고, Dockerfile 자체에는 그런 필드가 없습니다.

```

### devcontainer.json 에 --name 지정한다면?
```
● runArgs는 docker run에 그대로 전달되는 옵션 배열이므로, --name도 똑같이 넣을 수 있습니다.

  "runArgs": [
    "--network=voicebot-net",
    "--name=voicebot-devcontainer"
  ]

  동작

  지금까지 컨테이너 이름이 priceless_goodall처럼 랜덤하게 나온 건 어디서도 --name을 지정하지 않았기 때문이었죠. 위처럼 runArgs에 추가하면, 다음에 Rebuild Container를 할 때 DevPod이 실행하는 docker run 명령에 --name=voicebot-devcontainer가 그대로 포함되어
  컨테이너 이름이 고정됩니다.

  주의할 점

  1. 이미지 태그는 안 바뀜 — --name은 컨테이너 이름만 고정하고, 이미지 태그(vsc-voicebot-js-42468:devpod-<hash>)는 여전히 DevPod이 빌드 내용 해시로 자동 생성합니다. 즉 지난번 얘기한 "이미지가 rebuild마다 쌓이는 문제"는 이걸로 해결되지 않습니다.
  2. 이름은 호스트에서 유일해야 함 — rebuild할 때 기존 컨테이너를 안 지우고 새로 띄우려 하면 Conflict: container name "/voicebot-devcontainer" is already in use 에러가 날 수 있습니다. 보통 DevPod이 rebuild 시 이전 컨테이너를 먼저 정리하지만, 혹시 충돌하면
  docker rm <old-container>로 지운 뒤 다시 시도하면 됩니다.
  3. 적용 시점 — devcontainer.json만 수정한다고 즉시 바뀌지 않고, Rebuild Container를 한 번 해야 새 설정으로 컨테이너가 다시 만들어집니다.

  실용적 효과는 docker ps 했을 때 priceless_goodall 대신 voicebot-devcontainer로 바로 알아볼 수 있다는 것 — 디버깅/정리할 때 편해집니다.

```

### devcontainer.json에 추가 방법과  rebuild 방법 알려줘. 내가 할게.
```
● devpod CLI가 컨테이너 안에도 설치돼 있네요(Docker 소켓을 공유해서 호스트와 같은 데몬을 보고 있습니다). 정확한 rebuild 플래그를 --help로 확인해볼게요.

● Bash(devpod up --help 2>&1 | head -60)

     Usage:
     … +41 lines (ctrl+o to expand)

● 확인했습니다. --recreate 플래그가 정확히 이 용도입니다 ("If true will remove any existing containers and recreate them").


● Bash(devpod up --help 2>&1 | head -60)
  ⎿  Starts a new workspace

     Usage:
     … +41 lines (ctrl+o to expand)

● 확인했습니다. --recreate 플래그가 정확히 이 용도입니다 ("If true will remove any existing containers and recreate them").

  1. devcontainer.json 수정


● 확인했습니다. --recreate 플래그가 정확히 이 용도입니다 ("If true will remove any existing containers and recreate them").

  1. devcontainer.json 수정

  .devcontainer/devcontainer.json의 runArgs 배열에 한 줄 추가:

  "runArgs": [
    "--network=voicebot-net",
    "--name=voicebot-devcontainer"
  ],

  2. Rebuild 실행 — 반드시 호스트(컨테이너 밖)에서

  ⚠️ 중요: 지금 이 셸은 그 컨테이너 안에서 돌고 있습니다. rebuild는 지금 떠 있는 컨테이너를 지우고 새로 만드는 작업이라, 이 컨테이너 안에서 실행하면 명령이 자기 발밑을 지우다가 끊겨버립니다. 호스트 PC(Mac/Windows 터미널,
  devpod CLI가 설치된 그 쪽)에서 실행하세요.

  # 프로젝트 루트 (호스트 기준 경로)에서
  devpod up . --ide intellij --recreate

  - --recreate: 기존 컨테이너를 지우고 devcontainer.json을 다시 읽어서 새로 생성 (이미지가 이미 있으면 재빌드 없이 새 컨테이너만 생성, Dockerfile이 바뀌었으면 이미지도 다시 빌드)
  - --ide intellij 대신 VS Code로 연결한다면 --ide vscode

  3. 확인

  rebuild 후 호스트에서:

  docker ps --format 'table {{.Names}}\t{{.Image}}'

  voicebot-devcontainer라는 이름으로 보이면 성공입니다.

  참고

  - --recreate는 컨테이너만 지우고 다시 만듭니다. devpod이 관리하는 워크스페이스 메타데이터(소스, 설정)는 유지됩니다.
  - 더 강하게 --reset을 쓰면 소스까지 지우고 재생성하니, 지금 목적(이름만 바꾸기)에는 --recreate가 맞습니다.
  - 기존에 떠 있던 priceless_goodall 컨테이너는 --recreate 과정에서 자동으로 정리됩니다. 혹시 안 지워지고 남아있으면 호스트에서 docker rm priceless_goodall로 정리하면 됩니다.

```

### redis 는 volumn 없던데. 특성상 그때마다 메모리 기억해서 필요없는건가?

```
❯ 그래서 현 프로젝트는 docker 종료시 컨테이너를 지워도 상관 없다. 즉, docker compose down 해도 상관 없다. 맞어?

● 정확히 짚으셨는데, 확인하고 답할게요.

  Searched for 1 pattern (ctrl+o to expand)

● 맞습니다. 두 가지 다 정확하게 짚으셨어요.

  Redis에 volume이 없는 이유

  Redis는 기본적으로 메모리에만 저장합니다. 영속화(persistence) 옵션(appendonly yes 등)을 켜면 디스크에 쓸 수도 있지만, 이 프로젝트에서는 그 설정을 주지 않았습니다.

  이게 의도적인 선택인 이유 — CLAUDE.md에 나온 Redis 용도를 보면:

  ▎ callId는 Redis에 세션 상태를 저장하는 키로 사용된다.

  콜 세션은 전화 통화 한 건의 생명주기와 일치하는 휘발성 데이터입니다. 통화가 끝나면 의미 없고, 서버를 재시작해도 진행 중인 통화가 있을 리 없으니 데이터가 날아가도 무방합니다. volume을 붙일 이유가 없는 거죠.

  docker compose down에 대한 결론

  각 서비스를 정리하면:

  ┌─────────────────────────────┬─────────────────┬────────────────────────┐
  │           서비스            │     volume      │ compose down 시 데이터 │
  ├─────────────────────────────┼─────────────────┼────────────────────────┤
  │ MariaDB                     │ mariadb-data ✅ │ 보존                   │
  ├─────────────────────────────┼─────────────────┼────────────────────────┤
  │ MongoDB                     │ mongodb-data ✅ │ 보존                   │
  ├─────────────────────────────┼─────────────────┼────────────────────────┤
  │ Redis                       │ 없음            │ 날아감 (의도적)        │
  ├─────────────────────────────┼─────────────────┼────────────────────────┤
  │ STT/LLM/TTS/Call 시뮬레이터 │ 없음            │ 날아감 (원래 무상태)   │
  └─────────────────────────────┴─────────────────┴────────────────────────┘

  docker compose down은 컨테이너만 삭제하고 named volume(mariadb-data, mongodb-data)은 건드리지 않으므로 안전합니다. 다음에 compose up하면 DB 데이터는 그대로 복원됩니다.

  주의할 것은 딱 하나 — docker compose down -v를 쓰면 volume까지 지워져서 MariaDB/MongoDB 데이터가 날아갑니다. 일반 down과 down -v는 다른 명령이라고 기억해두시면 됩니다.

```

### 아래 내용은 정리한다는건가?

```
>> 명령어
vscode ➜ /workspaces/voicebot-js (main) $ docker image prune -a
WARNING! This will remove all images without at least one container associated to them.
Are you sure you want to continue? [y/N] ^C
vscode ➜ /workspaces/voicebot-js (main) $

● Ctrl+C로 취소하셔서 잘하셨습니다. 너무 많이 지웁니다.

  -a의 의미
  vscode ➜ /workspaces/voicebot-js (main) $

● Ctrl+C로 취소하셔서 잘하셨습니다. 너무 많이 지웁니다.

  -a의 의미

  docker image prune       # dangling 이미지만 삭제 (태그가 <none>:<none>인 것)
  docker image prune -a    # 컨테이너가 하나도 없는 이미지 전부 삭제

  -a는 "컨테이너에 연결된 이미지는 빼고 나머지 전부"라는 뜻입니다. 지금 compose down 상태이면 mariadb, redis, mongo, 시뮬레이터 컨테이너가 모두 없으니, 그 이미지들까지 전부 삭제 대상이 됩니다.
  지워졌을 것들 (일부)

  redis:7-alpine        ← 필요한 것
  mongo:7               ← 필요한 것
  voicebot-js-*-simulator:latest   ← 빌드하는 데 시간 걸리는 것
  elasticsearch:8.13.0  ← 1.27GB 짜리...
  kibana, logstash...

  지금 목적에 맞는 명령은

  앞서 이야기한 6개의 고아 devpod 이미지만 정확히 지우는 거였죠:

  docker rmi 1d1d343754f8 e248096d21e4 4f00d02912b3 95a3e911c2b0 c562aac5719c 2f2743208a5a

```

### frontend/nohup.out 왜 생긴거니?
```
>> 명령어
❯ vscode ➜ /workspaces/voicebot-js (main) $ git status
  현재 브랜치 main
  브랜치가 'origin/main'보다 1개 커밋만큼 앞에 있습니다.
    (로컬에 있는 커밋을 제출하려면 "git push"를 사용하십시오)

  추적하지 않는 파일:
    (커밋할 사항에 포함하려면 "git add <파일>..."을 사용하십시오)
      docs/LLM-MODE-ANALYSIS.md
      frontend/nohup.out

  커밋할 사항을 추가하지 않았지만 추적하지 않는 파일이 있습니다 (추적하려면 "git
  add"를 사용하십시오)

● frontend/ 디렉토리에서 아래처럼 nohup으로 Vite를 백그라운드 실행했을 때 생긴 로그 파일입니다.

  # 이렇게 실행하면 nohup.out이 현재 디렉토리에 자동 생성됨
  cd frontend
  nohup npm run dev &

  nohup은 터미널을 닫아도 프로세스가 죽지 않도록 백그라운드로 실행하는 명령인데, 출력을 터미널에 못 쓰니까 자동으로 현재 디렉토리에 nohup.out 파일로 저장합니다. docs/FRONTEND.md에 Spring Boot 백그라운드 실행 예시로 nohup을
  쓰는 패턴이 나와있는데, 그걸 참고해서 Vite도 같은 방식으로 실행하신 것 같습니다.

  .gitignore에 추가해두면 앞으로 git status에 안 나타납니다.

  echo "frontend/nohup.out" >> .gitignore

  ---
  그리고 git status 말씀하신 건 — push가 이미 완료됐습니다. 제가 push하는 사이에 status를 보신 거라 "1개 커밋 앞에" 로 보인 것이고, 지금은 origin/main과 동기화된 상태입니다.

```

