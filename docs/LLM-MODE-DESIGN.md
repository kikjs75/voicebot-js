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
