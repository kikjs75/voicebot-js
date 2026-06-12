# LLM 다중 모드 설계 문서

## 개요

기존 Claude API 직접 응답 방식을 유지하면서, 의도 분류 + MongoDB Playbook 기반 응답 방식을 추가한다.
설정 파일 하나로 3가지 모드를 전환할 수 있다.

---

## 배경 및 목적

| 항목 | 기존 방식 | 개선 목적 |
|---|---|---|
| 응답 생성 | Claude API가 매번 자유롭게 생성 | 정형화된 답변은 Playbook으로 일관성 확보 |
| 응답 속도 | Claude 응답시간 의존 | Playbook 조회 시 응답시간 단축 |
| 응답 관리 | 프롬프트 수정으로만 제어 | MongoDB에서 의도별 답변 직접 관리 |
| 확장성 | 새로운 답변 패턴 추가 어려움 | Playbook document 추가만으로 확장 |

---

## 3가지 LLM 모드

### ANTHROPIC 모드 (기존 방식 유지)

```
STT → userText → Claude API (응답 직접 생성) → TTS
```

- Claude API가 메시지 전체를 받아 자유롭게 응답 생성
- 기존 `ClaudeApiLlmService.chat()` 그대로 사용
- MongoDB 미사용

**적합한 상황**: 자유 형식 대화, Playbook 미구축 초기 단계

---

### INTERNAL 모드 (Playbook 전용)

```
STT → userText
           ↓
     Claude API (의도 분류만 — 경량 호출)
     → { intent: "배송문의", confidence: 0.95 }
           ↓
     MongoDB Playbook 조회
     ↙                  ↘
  hit                   miss (or confidence 낮음)
  ↓                          ↓
Playbook 응답            fallback 문구
("배송 관련 안내...")    ("죄송합니다. 상담원을...")
           ↓
          TTS
```

- Claude API는 의도 분류 역할만 담당 (짧은 호출, 비용 절감)
- 응답은 100% MongoDB Playbook에서 제공
- Playbook 미매핑 의도는 설정된 fallback 문구 반환

**적합한 상황**: 정형화된 콜센터 FAQ, 빠른 응답이 필요한 환경

---

### HYBRID 모드 (혼합)

```
STT → userText
           ↓
     Claude API (의도 분류)
     → { intent: "배송문의", confidence: 0.95 }
           ↓
     MongoDB Playbook 조회
     ↙                       ↘
  hit (confidence ≥ 임계값)    miss (or confidence < 임계값)
  ↓                                 ↓
Playbook 응답                  Claude API (응답 직접 생성)
(빠름, 일관성)                 (기존 방식 fallback)
           ↓
          TTS
```

- MongoDB에 매핑된 의도 → Playbook 응답 (빠름, 일관성)
- 미매핑 또는 낮은 신뢰도 → 기존 Claude 직접 응답으로 자동 전환
- 두 방식이 의도 매핑 여부에 따라 자동으로 번갈아 동작

**적합한 상황**: Playbook을 점진적으로 구축하면서 운영하는 경우

---

## 설정 파일

### application.yml

```yaml
voicebot:
  llm:
    mode: HYBRID                    # ANTHROPIC | INTERNAL | HYBRID

    hybrid:
      confidence-threshold: 0.7     # 이하면 Claude 직접 응답으로 fallback

    internal:
      confidence-threshold: 0.7     # 이하면 fallback-response 반환
      fallback-response: "죄송합니다. 잠시 후 상담원을 연결해드리겠습니다."
```

### 환경변수로 전환

```bash
# ANTHROPIC 모드
VOICEBOT_LLM_MODE=ANTHROPIC ./mvnw spring-boot:run

# INTERNAL 모드
VOICEBOT_LLM_MODE=INTERNAL ./mvnw spring-boot:run

# HYBRID 모드
VOICEBOT_LLM_MODE=HYBRID ./mvnw spring-boot:run
```

---

## MongoDB Playbook 구조

### Collection: `intent_playbook`

```json
{
  "_id": "배송문의",
  "intent": "배송문의",
  "response": "배송 관련 안내드립니다.\n- 일반 배송: 결제 완료 후 2~3 영업일 소요\n- 특급 배송: 1 영업일 이내 도착\n- 배송 조회는 주문번호를 말씀해주세요.",
  "action": "provide_info",
  "escalate": false,
  "confidenceThreshold": 0.7
}
```

### 의도 목록 (초기 Playbook)

| intent | action | escalate |
|---|---|---|
| `인사` | `provide_info` | false |
| `배송문의` | `provide_info` | false |
| `반품환불` | `provide_info` | false |
| `교환` | `provide_info` | false |
| `결제` | `provide_info` | false |
| `회원` | `provide_info` | false |
| `주문조회` | `request_order_number` | false |
| `상담원연결` | `escalate` | true |
| `종료` | `end_call` | false |
| `기타` | `fallback` | false |

---

## Claude API 의도 분류 프롬프트

```
[System]
당신은 콜센터 상담 AI입니다.
고객 발화를 분석하여 반드시 아래 JSON 형식으로만 응답하세요.
다른 텍스트는 포함하지 마세요.

intent는 반드시 다음 중 하나여야 합니다:
인사 | 배송문의 | 반품환불 | 교환 | 결제 | 회원 | 주문조회 | 상담원연결 | 종료 | 기타

{
  "intent": "배송문의",
  "confidence": 0.95
}

[User]
{userText}
```

---

## 소스 구조

### 신규 추가 파일

```
src/main/java/com/voicebot/
├── service/llm/
│   ├── strategy/
│   │   ├── LlmStrategy.java           ← 전략 인터페이스
│   │   ├── AnthropicStrategy.java     ← ANTHROPIC 모드
│   │   ├── InternalStrategy.java      ← INTERNAL 모드
│   │   └── HybridStrategy.java        ← HYBRID 모드
│   ├── LlmModeRouter.java             ← 모드 라우터 (설정값으로 전략 선택)
│   └── PlaybookService.java           ← MongoDB 조회 서비스
├── domain/
│   ├── IntentResult.java              ← { intent, confidence } DTO
│   └── IntentPlaybook.java            ← MongoDB Document
└── repository/
    └── IntentPlaybookRepository.java  ← Spring Data MongoDB Repository
```

### 기존 파일 변경 범위

| 파일 | 변경 내용 |
|---|---|
| `CallHandler.java` | `LlmModeRouter` 주입으로 교체 |
| `ClaudeApiLlmService.java` | `classifyIntent()` 메서드 추가 (기존 `chat()` 유지) |
| `pom.xml` | `spring-data-mongodb` 의존성 추가 |
| `docker-compose.yml` | MongoDB 컨테이너 추가 |
| `application.yml` | `voicebot.llm.mode` 설정 추가 |

### 변경하지 않는 파일

- `LlmService.java` (인터페이스)
- `SimulatorLlmService.java`
- STT / TTS 관련 전체
- `WebSocketConfig.java`, `CtiWebSocketHandler.java`

---

## 인프라 변경

### docker-compose.yml 추가

```yaml
mongodb:
  image: mongo:7
  ports:
    - "27017:27017"
  volumes:
    - mongo_data:/data/db
  environment:
    MONGO_INITDB_DATABASE: voicebot

volumes:
  mongo_data:
```

### pom.xml 추가

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

---

## 성능 로그 (기존 패턴 유지)

```
[LLM-MODE]   callId=abc mode=HYBRID
[INTENT]     callId=abc intent=배송문의 confidence=0.95 elapsed=320ms
[PLAYBOOK]   callId=abc hit=true elapsed=5ms
[LLM-PERF]   callId=abc elapsed=325ms   ← 기존 태그 유지
```

---

## 구현 순서 (검토 후 진행)

1. `pom.xml` — MongoDB 의존성 추가
2. `docker-compose.yml` — MongoDB 컨테이너 추가
3. `IntentResult.java` — DTO
4. `IntentPlaybook.java` — MongoDB Document
5. `IntentPlaybookRepository.java` — Repository
6. `PlaybookService.java` — MongoDB 조회 로직
7. `ClaudeApiLlmService.java` — `classifyIntent()` 추가
8. `LlmStrategy.java` + 3가지 구현체
9. `LlmModeRouter.java` — 설정값으로 전략 선택
10. `CallHandler.java` — LlmModeRouter 주입
11. `application.yml` — 모드 설정 추가
12. Playbook 초기 데이터 투입

---

## 구현 후 수정이 필요한 가이드 문서

이 설계를 구현하면 아래 6개 문서의 해당 항목을 함께 갱신해야 한다.

---

### 1. CLAUDE.md

| 항목 | 수정 내용 |
|---|---|
| 기술 스택 표 | `MongoDB 7` 행 추가 |
| 패키지 구조 | `service/llm/strategy/`, `LlmModeRouter.java`, `PlaybookService.java`, `domain/IntentResult.java`, `domain/IntentPlaybook.java`, `repository/IntentPlaybookRepository.java` 추가 |
| 환경변수 | `VOICEBOT_LLM_MODE=HYBRID` 항목 추가 |

---

### 2. ARCHITECTURE.md

| 항목 | 수정 내용 |
|---|---|
| 전체 구성도 | 내부 서비스 Docker 블록에 `MongoDB :27017` 추가 |
| HTTP 콜 처리 흐름 | `2. LlmService.chat()` → `2. LlmModeRouter → (ANTHROPIC/INTERNAL/HYBRID 분기)` 로 교체 |
| WebSocket 콜 처리 흐름 | `LlmService → TtsService` 사이에 LlmModeRouter 분기 추가 |
| Profile 구조 표 아래 | LLM 모드는 Profile과 별개 축임을 명시하는 설명 추가 (`real` profile에서 ANTHROPIC/INTERNAL/HYBRID 중 선택) |

---

### 3. SIMULATOR.md

| 항목 | 수정 내용 |
|---|---|
| 실행 — 인프라만 | `docker compose up postgres redis -d` → `docker compose up mariadb redis mongodb -d` |
| 신규 섹션 추가 | **MongoDB Playbook 초기 데이터** — mongosh로 `intent_playbook` 컬렉션에 초기 document 투입 명령 |

---

### 4. SETUP.md

| 항목 | 수정 내용 |
|---|---|
| 5. 인프라 + 시뮬레이터 시작 | `docker-compose.yml`에 MongoDB 포함됐으므로 별도 설명 불필요. 기동 명령은 동일 |
| 신규 단계 추가 | **LLM 모드 설정** 단계 추가 — `application.yml`의 `voicebot.llm.mode` 설명 + 환경변수로 전환하는 방법 |
| 신규 단계 추가 | **Playbook 초기 데이터 투입** 단계 추가 — MongoDB에 의도별 document 삽입 절차 |

---

### 5. SOURCE-ANALYSIS.md

| 항목 | 수정 내용 |
|---|---|
| 4. LLM — ClaudeApiLlmService | `classifyIntent()` 메서드 추가 — 의도 분류 전용 경량 Claude 호출 설명 |
| 신규 섹션 추가 | **LlmModeRouter** — `application.yml`의 `voicebot.llm.mode` 값을 읽어 전략을 선택하는 라우터. 전략 패턴 설명 |
| 신규 섹션 추가 | **LlmStrategy / AnthropicStrategy / InternalStrategy / HybridStrategy** — 각 전략의 처리 흐름 다이어그램 |
| 신규 섹션 추가 | **PlaybookService** — MongoDB `intent_playbook` 컬렉션 조회 로직 |
| 10. 주요 로그 태그 표 | `[LLM-MODE]`, `[INTENT]`, `[PLAYBOOK]` 태그 3개 추가 |

---

### 6. TESTING-GUIDE.md

| 항목 | 수정 내용 |
|---|---|
| 1. 환경 시작 > real profile | MongoDB 컨테이너 상태 확인 명령 추가 (`docker ps`로 `voicebot-mongodb` 확인) |
| 3. 로그 확인 | PERF 로그 예시 출력에 `[LLM-MODE]`, `[INTENT]`, `[PLAYBOOK]` 태그 추가 |
| 3. DB 조회 | **MongoDB Playbook 조회** 항목 추가 — `mongosh`로 intent_playbook 컬렉션 조회 명령 |
| 9. 다음 개선 예정 항목 표 | `LLM 모드 전환 (ANTHROPIC → HYBRID)` 항목 추가 — Playbook 구축 후 INTERNAL/HYBRID 전환 절차

---

## 질문
### 의도목록에 action 항목 의미와 실제 값인 provide_info, request_order_number, escalate, end_call, fallback 의미와 로직과 어떻게 연결되는지 알고 싶어.
```
● action 필드의 역할

  action은 의도가 파악된 후 시스템이 취할 행동을 정의한다.

  Playbook 조회 결과에서 response 텍스트만 쓰는 게 아니라, 그 다음에 무엇을 할지를 action이 결정한다.

  의도 분류 → Playbook 조회 → action 확인 → 분기

  ---
  각 action 값의 의미

  provide_info

  가장 단순한 경우. 조회된 response를 그대로 TTS로 보내고 끝.

  배송문의 → Playbook 조회 → response 텍스트 → TTS → 통화 계속

  ---
  request_order_number

  응답 전에 추가 정보가 필요한 경우. 주문 조회는 주문번호 없이는 답변 불가.

  주문조회 → Playbook 조회
           → "주문번호를 말씀해주세요" TTS 출력
           → 다음 STT 입력 대기 (주문번호 수집)
           → 수집 완료 → 실제 조회 로직 실행

  provide_info와 달리 대화가 한 턴 더 필요하다는 신호.

  ---
  escalate

  상담원 연결. 시스템이 직접 답변하지 않고 사람에게 넘긴다.

  상담원연결 → Playbook 조회
             → "잠시만요, 상담원 연결합니다" TTS 출력
             → CTI 시스템에 상담원 연결 신호 전송
             → 통화 핸드오프

  escalate: true 필드와 쌍으로 존재하는 이유는 action과 별도로 빠르게 boolean 체크할 수 있게 하기 위함.

  ---
  end_call

  통화 종료.

  종료 → Playbook 조회
       → "감사합니다. 이용해 주셔서 감사합니다" TTS 출력
       → WebSocket 연결 종료 신호
       → CallSession 종료 처리

  ---
  fallback

  어떤 의도에도 해당 안 될 때의 안전망. 기타 의도에 매핑.

  기타 → Playbook 조회
       → application.yml의 fallback-response 반환
         ("죄송합니다. 잠시 후 상담원을 연결해드리겠습니다.")
       → HYBRID 모드라면 Claude 직접 응답으로 전환

  ---
  코드에서 어떻게 연결되나

  설계 기준으로 InternalStrategy / HybridStrategy 안에서 이렇게 분기한다:

  // PlaybookService가 조회한 결과
  IntentPlaybook playbook = playbookService.find(intent);

  switch (playbook.getAction()) {
      case "provide_info"          -> return playbook.getResponse();
      case "request_order_number"  -> return requestOrderNumber(callId);
      case "escalate"              -> return escalateToAgent(callId);
      case "end_call"              -> return endCall(callId);
      case "fallback"              -> return fallbackResponse;  // yml 설정값
  }

  ---
  한눈에 정리

  action                 다음 동작
  ─────────────────────────────────────────────────────
  provide_info         → response 텍스트 → TTS → 계속
  request_order_number → 추가 입력 요청 → 다음 턴 대기
  escalate             → 상담원 연결 신호 → 핸드오프
  end_call             → 종료 멘트 → 통화 끊기
  fallback             → 기본 문구 (또는 Claude fallback)

  response 필드가 뭐라고 말할지라면, action은 말한 다음 뭘 할지다.

```

### LLM-MODE-DESIGN.md 내용대로 변경하면 cpp-ws-server 와는 관계 없지?
```
● 맞습니다. cpp-ws-server는 영향 없습니다.

  이유는 두 서버의 역할 경계가 명확하기 때문입니다.

  ---
  cpp-ws-server가 하는 일

  브라우저/CTI → cpp-ws-server (C++)
                      ↓ HTTP 호출
                Spring Boot :8080
                      ↓
                STT → LLM → TTS

  SpringLlmService.cpp를 보면 cpp-ws-server는 Spring Boot의 엔드포인트를 그냥 호출할 뿐이다.

  ---
  LLM-MODE-DESIGN.md 변경 범위

  변경은 전부 Spring Boot 내부에서만 일어난다.

  Spring Boot 내부
  ├── CallHandler.java       ← LlmModeRouter로 교체
  ├── ClaudeApiLlmService    ← classifyIntent() 추가
  ├── strategy/              ← 신규 (ANTHROPIC / INTERNAL / HYBRID)
  ├── PlaybookService        ← 신규 (MongoDB 조회)
  └── application.yml        ← 모드 설정 추가

  cpp-ws-server 입장에서는 "Spring Boot에 HTTP 요청 보내고 응답 받는다"는 사실이 전혀 바뀌지 않는다.

  ---
  한 줄 요약

  ▎ LLM 모드 변경은 Spring Boot 내부 라우팅 로직이고,
  ▎ cpp-ws-server는 그 결과만 받는 외부 호출자라 무관하다.

```

### cpp-ws-server 애서 LLM 호출도 Spring-Boot 의 RestAPI 호출하는 것이 실제 처리는 Sping-Boot 에서 하는거지?
```
● 네, 정확합니다.

  실제 흐름

  cpp-ws-server (C++)
        │
        │  POST http://localhost:8080/llm/chat   ← HTTP 호출만 함
        │  Body: { "text": "배송 언제 와요?" }
        ↓
  Spring Boot :8080
        │
        ├── 의도 분류 (Claude API)
        ├── Playbook 조회 (MongoDB)
        ├── 응답 생성
        └── response 반환
        │
        ↓
  cpp-ws-server
        │  응답 받아서 브라우저로 전달
        ↓
  브라우저/CTI

  ---
  cpp-ws-server 코드 관점

  SpringLlmService.cpp는 이게 전부다:

  // cpp-ws-server가 하는 일의 전부
  std::string SpringLlmService::chat(const std::string& text) {
      // HTTP POST 날리고
      // 응답 문자열 받아서 반환
      // 내부 로직은 1도 모름
  }

  LLM이 ANTHROPIC 모드인지, HYBRID 모드인지, MongoDB를 쓰는지 — cpp-ws-server는 알 필요도 없고 알 수도 없다.

  ---
  한 줄 요약

  ▎ cpp-ws-server = 네트워크 중계자
  ▎ Spring Boot = 실제 두뇌
```

### 
```
```

### 
```
```

### 
```
```

### 
```
```

### 
```
```




