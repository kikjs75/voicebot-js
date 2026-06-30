# 인수자를 위한 핵심 개념 설명 (명령어·예시 포함)

## 목차

- [1) Docker Swarm 설명](#1-docker-swarm-설명)
- [2) Redis Pub/Sub vs Sticky Session](#2-redis-pubsub-vs-sticky-session)
- [3) PBX, CTI, PDS, IVR 설명](#3-pbx-cti-pds-ivr-설명)
- [4) IVR 연동 시 DTMF란](#4-ivr-연동-시-dtmf란)
- [5) G.711 → PCM 변환이 필요한 이유](#5-g711--pcm-변환이-필요한-이유)
- [6) SSML 이란](#6-ssml-이란)
- [7) WebSocket 브릿지 vs SIP B2BUA](#7-websocket-브릿지-vs-sip-b2bua)

---

## 1) Docker Swarm 설명

[↑ 목차](#목차)

### 왜 알아야 하는가
지금까지는 `docker compose up`으로 노트북 1대에서 컨테이너 몇 개 띄우고 끝났습니다. 그런데 실제 운영에서는 "전화 1,000통이 동시에 와도 안 끊겨야 한다"는 요구사항이 생깁니다. 서버 1대로는 감당 못 하니 **여러 대를 묶어서 하나처럼 운영**해야 하고, 그 묶는 기술이 Docker Swarm입니다.

### docker-compose와 비교

```
docker-compose (지금까지 쓰던 것)
─────────────────────────────────
내 컴퓨터(노트북) 1대
  └── 컨테이너 여러 개 (Spring Boot, MariaDB, Redis...)

→ 컴퓨터 1대가 죽으면 전부 죽음
→ 트래픽 몰리면 그냥 느려짐 (확장 불가)
```

```
Docker Swarm (운영 환경)
─────────────────────────────────
서버 3대를 하나의 클러스터로 묶음

  Manager 서버  ──┬── Worker 서버 1 (Spring Boot 2개)
   (관제탑)       ├── Worker 서버 2 (Spring Boot 2개)
                  └── Worker 서버 3 (MariaDB, Redis)

→ 서버 1대 죽어도 나머지가 대신 처리 (이중화)
→ 트래픽 몰리면 컨테이너 개수만 늘리면 됨 (확장)
```

### 비유로 이해하기

```
docker-compose = 식당 주방 1개
                 손님 몰리면 그냥 줄 서서 기다림

Docker Swarm   = 같은 메뉴 파는 지점 3개를 본사가 관리
                 한 지점 화재나도 다른 지점이 영업 계속
                 손님 몰리면 지점 추가
```

### 핵심 용어

| 용어 | 의미 |
|---|---|
| Node | Swarm에 참여하는 서버 1대 |
| Manager Node | 전체를 지휘 (어디에 컨테이너 배치할지 결정) |
| Worker Node | 실제로 컨테이너가 돌아가는 서버 |
| Service | "이 이미지를 N개 복제해서 운영해줘"라는 단위 명령 |
| Replica | Service 하나를 몇 개 복제해서 띄울지 |
| Stack | 여러 Service를 묶어서 한 번에 배포하는 단위 (= docker-compose의 Swarm 버전) |

### 주요 명령어

```bash
# 1. Swarm 클러스터 시작 (Manager 서버에서 최초 1회)
docker swarm init --advertise-addr <Manager IP>

# 출력 예시:
# docker swarm join --token SWMTKN-1-xxxxx 192.168.1.10:2377
#   ↑ 이 명령어를 Worker 서버들에서 그대로 실행하면 클러스터에 합류

# 2. Worker 서버에서 클러스터 합류
docker swarm join --token SWMTKN-1-xxxxx 192.168.1.10:2377

# 3. 클러스터에 어떤 노드들이 있는지 확인
docker node ls
# ID         HOSTNAME    STATUS   AVAILABILITY   MANAGER STATUS
# abc123 *   manager1    Ready    Active         Leader
# def456     worker1     Ready    Active
# ghi789     worker2     Ready    Active

# 4. Spring Boot 서비스를 4개 복제해서 배포
docker service create --name voicebot --replicas 4 -p 8080:8080 voicebot-image:latest

# 5. 실행 중인 서비스 목록과 복제 상태 확인
docker service ls
# NAME       REPLICAS   IMAGE
# voicebot   4/4        voicebot-image:latest

# 6. docker-compose.yml과 비슷한 docker-stack.yml로 한 번에 배포
docker stack deploy -c docker-stack.yml voicebot-stack

# 7. 트래픽 몰릴 때 즉시 확장 (재배포 없이 인스턴스 수만 증가)
docker service scale voicebot=8
```

### 구체적 예시 — 장애 시나리오

```
상황: Worker 서버 2에서 돌던 Spring Boot 컨테이너 1개가 다운됨

docker-compose였다면:
  → 그냥 죽은 채로 방치, 사람이 직접 재시작해야 함

Docker Swarm이라면:
  → Manager가 "replicas=4인데 지금 3개밖에 없네" 자동 감지
  → 살아있는 다른 Worker 서버에 자동으로 컨테이너 1개 새로 생성
  → 사람 개입 없이 자동 복구 (Self-healing)
```

**핵심**: docker-compose는 "내 컴퓨터에서 실행"이고, Docker Swarm은 "여러 서버를 하나처럼 운영 + 장애 자동 복구"라는 점이 가장 큰 차이입니다.

---

## 2) Redis Pub/Sub vs Sticky Session

[↑ 목차](#목차)

### 왜 이 둘이 등장했는가
서버를 여러 대로 늘리면(Swarm) 새로운 문제가 생깁니다. **"고객이 통화하면서 말한 내용(대화 이력)을 어느 서버가 기억하고 있나?"** 라는 문제입니다.

```
문제 상황:

고객이 1번째 발화 → 로드밸런서가 서버 A로 보냄 → 서버 A 메모리에 대화 저장
고객이 2번째 발화 → 로드밸런서가 서버 B로 보냄 ← 서버 B는 1번째 발화를 모름!
                                                    → 엉뚱한 답변 ❌
```

이 문제를 푸는 방법이 두 가지이고, **철학이 완전히 다릅니다.**

### 방법 A — Sticky Session (고정 배정)

```
"같은 고객은 항상 같은 서버로만 보내자"

고객 1번째 발화 → 로드밸런서 → 서버 A (고정)
고객 2번째 발화 → 로드밸런서 → 서버 A (계속 같은 곳)
고객 3번째 발화 → 로드밸런서 → 서버 A (계속 같은 곳)

→ 서버 A 메모리에 계속 쌓아두면 됨, 공유할 필요 없음
```

비유: **단골 손님은 항상 같은 직원이 응대**하게 배정. 그 직원이 손님 취향을 계속 기억하면 됨.

#### 주요 설정 (Nginx 기준 예시)

```nginx
upstream voicebot_backend {
    ip_hash;                 # ← 고객 IP 기준으로 항상 같은 서버로 고정
    server 10.0.0.1:8080;
    server 10.0.0.2:8080;
    server 10.0.0.3:8080;
}
```

```
ip_hash 외에도 callId, sessionId 기준으로 고정하는 방식도 있음
→ WebSocket 연결은 보통 "연결 자체가 한 서버에 붙어서 유지"되므로
  사실 별도 설정 없이도 같은 연결이 끊기기 전까진 자동으로 Sticky함
```

```
장점: 구현 단순, 속도 빠름 (메모리 직접 접근)
단점: 서버 A가 죽으면 그 고객의 대화 이력이 통째로 사라짐
      (서버 A에 부하가 몰려도 다른 서버로 분산 안 됨)
```

### 방법 B — Redis Pub/Sub (방송 공유)

```
"누가 받아도 상관없게, 모든 서버가 같은 정보를 보게 하자"

고객 1번째 발화 → 서버 A → Redis에 "이런 대화 있었음" 발행(Publish)
                            → 모든 서버(A,B,C)가 구독(Subscribe) 중이라 다 받음

고객 2번째 발화 → 서버 B로 가도 → Redis에서 대화 이력 조회 가능
```

비유: **손님 정보를 직원 개인 수첩이 아니라 공용 게시판**에 적어둠. 어느 직원이 응대하든 게시판만 보면 다 앎.

#### 주요 명령어 (Redis CLI 기준)

```bash
# 서버 A가 callId=CTI-1234 채널로 메시지 발행
PUBLISH call:CTI-1234 '{"role":"user","content":"환불하고 싶어요"}'

# 서버 B/C는 미리 해당 채널을 구독 중 → 자동으로 메시지 수신
SUBSCRIBE call:CTI-1234

# 실제로는 Pub/Sub보다 "대화 이력 저장/조회"용으로
# 아래처럼 Redis를 단순 저장소로 쓰는 방식이 더 자주 쓰임 (RPUSH + LRANGE)
RPUSH history:CTI-1234 '{"role":"user","content":"환불하고 싶어요"}'
LRANGE history:CTI-1234 0 -1     # 전체 대화 이력 조회

# Java(Spring) 쪽에서는 RedisTemplate으로 처리
# redisTemplate.opsForList().rightPush("history:" + callId, message);
```

```
장점: 서버 죽어도 다른 서버가 즉시 이어받음 (안정적)
단점: 구현 복잡, Redis 호출 비용 발생 (메모리 직접 접근보다 느림)
```

### 비교표

| | Sticky Session | Redis Pub/Sub (또는 Redis 저장) |
|---|---|---|
| 동작 원리 | 로드밸런서가 같은 서버로만 라우팅 | 모든 서버가 Redis로 상태 공유 |
| 구현 난이도 | 쉬움 | 어려움 |
| 서버 장애 시 | 그 고객 대화 끊김 | 다른 서버가 이어받음 |
| 속도 | 빠름 | 약간 느림 (네트워크 호출) |
| 콜센터 적합성 | 단기적으로 충분 | 진짜 이중화하려면 필요 |

### 구체적 예시 — 현재 voicebot-js와 연결

```java
// CtiWebSocketHandler.java — 현재 코드
private final Map<String, List<LlmService.Message>> historyMap = new ConcurrentHashMap<>();
// ↑ 이게 바로 "서버 메모리에만 있는 대화 이력" → Sticky Session이 강제된 구조
```

```
개선 방향:
  음성 스트림(Sinks.Many)  → Redis로 옮길 수 없음 (직렬화 불가) → Sticky Session 유지
  대화 이력(historyMap)    → Redis List/JSON으로 이전 가능 → 장애 시 복구 가능

→ "음성 연결은 Sticky, 대화 이력은 Redis 백업"이 현실적인 절충안
```

---

## 3) PBX, CTI, PDS, IVR 설명

[↑ 목차](#목차)

콜센터에서 전화 한 통이 처리되는 순서대로 등장하는 4가지 시스템입니다.

```
전화가 흘러가는 순서:

고객 전화  →  [PBX]  →  [CTI]  →  음성봇/상담사
                ↑
           [PDS]가 미리 걸어줄 수도 있음 (발신인 경우)

상담 중간에 메뉴 선택이 필요하면 [IVR]이 끼어듦
```

### PBX — Private Branch eXchange (사설 교환기)

```
역할: 전화를 "받아서 어디론가 연결해주는 교환원"

옛날 회사 전화 교환원 → 지금은 PBX 장비/소프트웨어가 자동으로 함

예: 1588-0000으로 전화 오면
    PBX가 "이건 음성봇으로 보내야지" 판단해서 연결
```

**왜 필요한가**: 음성봇 서버가 전화망(PSTN)에 직접 연결될 수 없습니다. 전화 신호를 디지털 데이터로 바꿔주는 중간 장비가 반드시 필요한데, 그게 PBX입니다.

대표 제품: Asterisk, FreeSWITCH(오픈소스), Avaya, Cisco UCM(상용)

### CTI — Computer Telephony Integration (컴퓨터-전화 통합)

```
역할: PBX(전화)와 컴퓨터 시스템(음성봇, 상담사 화면)을 연결하는 "통역사"

PBX 입장: "전화 시작됐어" "전화 끊겼어" (전화 신호)
컴퓨터 입장: 이 신호를 알아들을 수 있는 JSON으로 변환 필요
```

#### 구체적 예시 — 현재 코드의 CTI 이벤트

```json
{ "type": "CTI_EVENT", "event": "CALL_START", "callerNumber": "010-1234-5678", "receiverNumber": "1588-0000" }
{ "type": "CTI_EVENT", "event": "CALL_END" }
```

```java
// CtiWebSocketHandler.java
if ("CTI_EVENT".equals(type)) {
    String ctiEvent = (String) event.get("event");
    if ("CALL_END".equals(ctiEvent)) {
        handleCallEnd(session);
    }
    // HOLD, TRANSFER 등은 아직 처리 안 함
}
```

**왜 필요한가**: 위 JSON이 바로 CTI 이벤트입니다. PBX와 음성봇 사이의 "약속된 신호 체계"가 없으면 둘이 대화가 안 됩니다.

### PDS — Predictive Dialing System (예측 발신 시스템)

```
역할: 상담사/봇이 먼저 거는 게 아니라, 시스템이 알아서 미리 전화를 검

지금 voicebot-js: 고객이 걸면 받는 구조 (수신 전용)
PDS:              우리가 먼저 고객한테 거는 구조 (발신 전용)

예: 카드 연체 안내, 만족도 조사, 마케팅 콜
    → 명단 1만 개를 PDS가 순서대로 자동 발신
    → 받으면 음성봇/상담사 연결
    → 안 받으면 다음 번호로
```

**왜 필요한가**: "고객이 전화를 걸어올 때만" 대응하는 게 아니라 "우리가 먼저 거는" 시나리오(연체 안내, 설문조사)도 콜센터 업무의 큰 비중을 차지합니다. 현재 코드는 이 발신 흐름 자체가 없습니다.

### IVR — Interactive Voice Response (자동 응답 시스템)

```
역할: "1번 누르시면 OOO, 2번 누르시면 OOO" 메뉴 트리

기존 ARS 경험: "상담원 연결은 9번을 눌러주세요" ← 이게 IVR

지금 voicebot-js: 자연어로 대화 (STT→LLM→TTS)
IVR:              버튼(DTMF)으로 메뉴 선택
```

**왜 필요한가**: AI 음성봇이 모든 걸 처리하기엔 비용/정확도 문제가 있습니다. 단순 분기("환불은 1번")는 IVR로 빠르게 처리하고, 복잡한 자연어 상담만 AI 음성봇으로 넘기는 **하이브리드 설계**가 실무에서 일반적입니다.

### 4개 한눈에 비교

| 시스템 | 역할 | 비유 | 방향 |
|---|---|---|---|
| PBX | 전화 받아서 연결 | 회사 정문 안내데스크 | 수신/발신 공통 |
| CTI | 전화 신호 ↔ 컴퓨터 통역 | 안내데스크와 사무실 사이 인터폰 | 수신/발신 공통 |
| PDS | 우리가 먼저 전화 검 | 텔레마케터의 자동 다이얼 | 발신 전용 |
| IVR | 버튼 메뉴 선택 | ARS "1번을 눌러주세요" | 수신 중심 |

---

## 4) IVR 연동 시 DTMF란

[↑ 목차](#목차)

### 왜 필요한가
IVR이 "1번을 눌러주세요"라고 말한 다음, **고객이 실제로 누른 버튼이 1번인지 2번인지 알아내는 기술**이 필요합니다. 그게 DTMF입니다.

### DTMF란

**DTMF = Dual-Tone Multi-Frequency (이중톤 다중주파수)**

```
전화기 버튼을 누르면 "삑" 소리가 나는데,
그 소리가 사실은 2개의 주파수가 합쳐진 소리입니다.

예: "1" 버튼 = 697Hz + 1209Hz 두 음이 동시에 남
    "2" 버튼 = 697Hz + 1336Hz
    "9" 버튼 = 852Hz + 1477Hz

→ 버튼마다 고유한 "화음"이 정해져 있어서
   수신 측이 그 화음 조합을 분석해 어떤 버튼인지 알아냄
```

### 비유로 이해하기

```
DTMF = 피아노 화음 암호

각 버튼 = 정해진 두 음을 동시에 누르는 것
수신기 = 그 화음을 듣고 "아, 이건 1번이구나" 해독
```

### 처리 방식 3가지 — 왜 중요한가

```
방법 1: Inband DTMF (음성 안에 섞어서 전송)
  → 버튼 소리가 일반 음성 스트림에 그대로 섞여 옴
  → STT가 이 "삑삑" 소리를 사람 말로 오인식할 위험 ❌

방법 2: RFC 2833 (Out-of-band, 별도 채널) — 권장
  → 음성과 분리된 별도 RTP 패킷(Payload Type=101)으로 전송
  → "이건 버튼 입력이야"라고 명확히 표시되어 옴 ✅

방법 3: SIP INFO (시그널링 채널로 전송)
  → 음성 채널이 아닌 제어 채널로 전송
```

### 구체적 예시 — RFC 2833 패킷 형태

```
일반 음성 RTP 패킷:
  Payload Type = 0 (G.711)
  Payload = 실제 음성 데이터

DTMF RTP 패킷 (RFC 2833):
  Payload Type = 101 (DTMF 전용)
  Payload = { event: 1, duration: 160ms }   ← "1번 버튼, 160ms 눌림"

→ 음성과 완전히 분리된 별도 패킷이라 STT가 헷갈릴 일이 없음
```

### 코드 레벨 — 향후 추가 시 형태 예시

```java
// 현재 없는 핸들러, IVR 도입 시 추가가 필요한 부분
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // ...
    if ("CTI_EVENT".equals(type) && "DTMF".equals(event.get("event"))) {
        String digit = (String) event.get("digit");   // "1", "2", "9" 등
        ivrService.handleMenuSelection(session, callId, digit);
    }
}
```

**왜 알아야 하는가**: 현재 `CtiWebSocketHandler`에는 DTMF를 처리하는 코드가 전혀 없습니다. IVR 메뉴("1번 환불, 2번 배송")를 만들려면, 고객이 누른 버튼을 받는 이벤트 핸들러를 새로 만들어야 합니다. 이때 CTI 협력사에게 **"DTMF를 RFC 2833 방식으로 분리해서 주실 수 있나요?"**라고 반드시 확인해야 STT 오작동을 피할 수 있습니다.

---

## 5) G.711 → PCM 변환이 필요한 이유

[↑ 목차](#목차)

### 문제의 핵심 — 둘이 쓰는 "언어"가 다름

```
PBX가 주는 음성 형식        RTZR(STT)가 원하는 형식
─────────────────────       ─────────────────────
G.711 코덱                  PCM (압축 안 한 원본)
8,000Hz (1초에 8천 번 측정)  16,000Hz (1초에 1만6천 번 측정)
압축된 형태                 비압축 형태

→ 형식이 안 맞으면 STT가 음성을 못 알아듣거나 잡음으로 인식
```

### 비유로 이해하기

```
G.711  = 압축된 ZIP 파일 (전화망 표준, 용량 작게)
PCM    = 압축 풀린 원본 파일 (STT가 분석하기 쉬운 형태)

8kHz   = 사진을 거칠게 찍음 (전화 음질, 사람 목소리만 담음)
16kHz  = 사진을 정밀하게 찍음 (STT가 정확히 분석하려면 이 정도 필요)

→ ZIP 파일을 그냥 그림판에 열려고 하면 안 열리듯이
  G.711을 그대로 RTZR에 넣으면 제대로 인식 못 함
```

### 지금 코드에서 무슨 일이 일어나는가

```java
// CtiWebSocketHandler.java — 현재 코드
protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    byte[] chunk = message.getPayload().array();
    sink.tryEmitNext(chunk);  // ← 받은 그대로 RTZR로 넘김 (변환 없음)
}
```

지금은 브라우저(CtiSimulator.jsx)가 PCM으로 보내주기 때문에 문제가 안 드러났을 뿐입니다. **실제 PBX가 연결되는 순간, G.711 8kHz 음성이 변환 없이 그대로 STT로 들어가서 인식률이 크게 떨어지거나 아예 실패**합니다.

### 구체적 예시 — 변환 코드 형태

```java
// 향후 추가가 필요한 AudioConverter 예시
public class AudioConverter {

    // ① G.711 μ-law → 16bit PCM (8kHz) — 압축 해제
    public static short[] decodeMuLaw(byte[] muLawBytes) {
        short[] pcm = new short[muLawBytes.length];
        for (int i = 0; i < muLawBytes.length; i++) {
            pcm[i] = muLawToPcm(muLawBytes[i]);
        }
        return pcm;
    }

    // ② 8kHz → 16kHz 업샘플링 — 샘플 사이를 보간값으로 채워 2배로
    public static short[] upsample8to16(short[] pcm8k) {
        short[] pcm16k = new short[pcm8k.length * 2];
        for (int i = 0; i < pcm8k.length - 1; i++) {
            pcm16k[i * 2]     = pcm8k[i];
            pcm16k[i * 2 + 1] = (short) ((pcm8k[i] + pcm8k[i + 1]) / 2);
        }
        return pcm16k;
    }
}

// 적용 위치 — sink에 넣기 전에 변환 한 줄만 추가
chunk = AudioConverter.convertMuLawTo16kPcm(chunk);
sink.tryEmitNext(chunk);
```

### 업샘플링 시각화

```
8kHz 원본:   [10] [30] [20] [40]
                  ↓ 사이에 중간값 삽입
16kHz 결과:  [10] [20] [30] [25] [20] [30] [40] [40]
                   ↑         ↑         ↑
              (10+30)/2  (30+20)/2  (20+40)/2
```

### 왜 미리 알아야 하는가

```
협력사 미팅에서 물어볼 질문 (해결 우선순위 순):

1순위: "PBX에서 16kHz PCM으로 변환해서 주실 수 있나요?"
       → 가능하면 우리가 코드 안 짜도 됨 (가장 편함)

2순위: "중간 미디어 서버가 변환해주나요?"
       → Asterisk/FreeSWITCH가 대신 처리해줄 수도 있음

3순위: 둘 다 안 되면 우리가 직접 변환 코드 작성
       → 위 AudioConverter 같은 로직 필요
```

**핵심**: 이 변환 작업 자체는 코드로 어렵지 않지만, **누가 변환을 책임질지(우리 vs 협력사)를 사전에 정하지 않으면** 실제 연동 테스트에서 "STT가 왜 이렇게 인식을 못 하지?"라는 원인 불명 장애로 한참 시간을 날릴 수 있습니다.

---

## 6) SSML 이란

[↑ 목차](#목차)

### 왜 필요한가
지금 TTS는 텍스트를 그냥 기계적으로 읽습니다. "안녕하세요. 환불 도와드리겠습니다."를 한 호흡에 단조롭게 읽으면, 콜센터 고객 입장에서 로봇처럼 느껴지고 신뢰도가 떨어집니다. **읽는 방식을 세밀하게 제어**하기 위해 SSML을 씁니다.

### SSML이란

**SSML = Speech Synthesis Markup Language (음성 합성 마크업 언어)**

```
일반 텍스트로 줄 때:
  "안녕하세요 환불 문의 맞으신가요"
  → TTS가 알아서 읽음, 우리가 속도/쉬는 타이밍 제어 불가

SSML로 줄 때:
  <speak>
    안녕하세요.
    <break time="500ms"/>          ← 0.5초 쉬기
    <emphasis>환불</emphasis> 문의 맞으신가요?
  </speak>
  → 정확히 어디서 쉬고, 어디를 강조할지 우리가 지정
```

### HTML과 비교하면 이해가 쉬움

```
HTML  = 글자에 굵게(<b>), 색깔(<span>) 등 "보이는" 스타일 입히는 태그
SSML  = 문장에 쉬기, 강조, 속도 등 "들리는" 스타일 입히는 태그

→ 텍스트 → HTML  = 화면에 예쁘게 표시
→ 텍스트 → SSML  = 귀에 자연스럽게 들리게
```

### 콜센터에서 유용한 태그

| 태그 | 효과 | 사용 예 |
|---|---|---|
| `<break time="500ms"/>` | 쉬는 시간 삽입 | 문장 사이 자연스러운 호흡 |
| `<emphasis>` | 강조 | "환불"을 또렷하게 |
| `<say-as interpret-as="digits">` | 숫자를 자리수대로 읽기 | "1588"을 "일오팔팔"로 |
| `<prosody rate="slow">` | 천천히 읽기 | 고령 고객 응대 |

### 구체적 예시 — Google TTS 요청 코드 비교

```json
// 현재 코드 — 일반 text 사용
{
  "input": { "text": "고객님의 주문번호는 1234입니다. 환불 처리하겠습니다." },
  "voice": { "languageCode": "ko-KR", "name": "ko-KR-Neural2-A" },
  "audioConfig": { "audioEncoding": "LINEAR16", "sampleRateHertz": 8000 }
}
```

```json
// SSML 적용 시 — "text" 대신 "ssml" 키 사용
{
  "input": {
    "ssml": "<speak>고객님의 주문번호는 <break time='300ms'/> <say-as interpret-as='digits'>1234</say-as>입니다. <break time='500ms'/> <emphasis>환불</emphasis> 처리하겠습니다.</speak>"
  },
  "voice": { "languageCode": "ko-KR", "name": "ko-KR-Neural2-A" },
  "audioConfig": { "audioEncoding": "LINEAR16", "sampleRateHertz": 8000 }
}
```

```
SSML 없이: "고객님의주문번호는1234입니다환불처리하겠습니다"
           → 숨도 안 쉬고 빠르게 읽혀서 알아듣기 어려움

SSML 적용: "고객님의 주문번호는 [쉬고] 일이삼사입니다 [쉬고]
            [환불 강조] 처리하겠습니다"
           → 사람이 말하듯 자연스러운 리듬 + 숫자 정확히 읽힘
```

**현재 상태**: `GoogleCloudTtsService.java`는 `text` 필드만 쓰고 `ssml` 필드를 안 씁니다. 지금은 단순 안내 정도라 큰 문제가 안 보이지만, 실제 운영에서 "왜 봇이 로봇처럼 들리나요"라는 피드백이 나오면 가장 먼저 적용해볼 수 있는 개선 포인트입니다.

---

## 7) WebSocket 브릿지 vs SIP B2BUA

[↑ 목차](#목차)

### 왜 이 선택이 중요한가
PBX와 우리 음성봇을 연결하는 방식을 정하는 **건축 설계 단계의 결정**입니다. 이걸 잘못 정하면 나중에 구조를 통째로 다시 짜야 할 수 있어서, 협력사 미팅 초반에 반드시 합의해야 하는 항목입니다.

### 방식 A — WebSocket 브릿지 (지금 voicebot-js 방식)

```
PBX  ──SIP/RTP──▶  미디어 서버(중계자)  ──WebSocket──▶  음성봇(Spring Boot)
                   (SIP을 WebSocket으로
                    변환해주는 중간 다리)

→ 음성봇 입장에서는 SIP을 전혀 몰라도 됨
→ 미디어 서버가 "번역"을 대신 해줌
```

비유: **외국 손님(PBX, SIP을 말함)과 우리 직원(음성봇, WebSocket만 앎) 사이에 통역사(미디어 서버)를 세워두는 것.**

```
장점: 음성봇 서버가 SIP/RTP를 몰라도 구현 가능 (현재 코드 구조 그대로 활용)
      웹 개발자 친화적 (WebSocket은 익숙한 기술)
단점: 중간에 미디어 서버라는 컴포넌트가 하나 더 필요 (관리 포인트 증가)
      미디어 서버가 죽으면 그 사이 모든 통화 장애
```

### 방식 B — SIP B2BUA (Back-to-Back User Agent)

```
PBX  ──SIP/RTP──▶  음성봇 서버 자체가 SIP을 직접 처리
                   (B2BUA = "두 개의 SIP 세션을 동시에 들고 중계")

→ 음성봇 서버가 PBX와 직접 SIP으로 통화
→ 중간 통역사 없음, 음성봇이 직접 외국어(SIP) 구사
```

비유: **우리 직원이 직접 외국어(SIP)를 배워서 손님과 직접 대화하는 것.** 통역사가 필요 없음.

### 구체적 예시 — 실제 SIP 메시지 흐름 비교

```
WebSocket 브릿지 방식 (미디어 서버가 SIP 처리):

고객폰 ──INVITE──▶ PBX ──INVITE──▶ 미디어서버 (SIP 응답 책임짐)
                                       │
                                  WebSocket으로 변환
                                       │
                                       ▼
                                  Spring Boot (JSON만 받음)
                                  { "type":"CTI_EVENT", "event":"CALL_START" }

→ Spring Boot는 INVITE/ACK/BYE 같은 SIP 메시지를 전혀 다루지 않음
```

```
SIP B2BUA 방식 (음성봇이 직접 SIP 처리):

고객폰 ──INVITE──▶ PBX ──INVITE──▶ Spring Boot 자체가 응답
                                    (200 OK, ACK 등 직접 처리)
                                       │
                                  RTP로 직접 음성 송수신

→ Spring Boot 안에 SIP 스택(예: jain-sip, mjSip 같은 라이브러리) 내장 필요
```

### 비교표

| | WebSocket 브릿지 | SIP B2BUA |
|---|---|---|
| 구조 | PBX → 미디어 서버 → 음성봇 | PBX → 음성봇 (직접) |
| 음성봇이 SIP을 알아야 하나 | 몰라도 됨 | 알아야 함 |
| 추가 컴포넌트 | 미디어 서버 필요 | 불필요 |
| 구현 난이도 | 낮음 (현재 코드 재사용 가능) | 높음 (SIP 전문 지식 필요) |
| 지연시간 | 한 단계 더 거침 | 더 짧음 |
| 장애 지점 | 미디어 서버도 장애 포인트 | 음성봇 서버 하나로 집중 |

### 현재 voicebot-js와의 연결점

```
현재 구조 (CtiSimulator.jsx 기준):
  브라우저(마이크) → WebSocket → Spring Boot

이건 사실상 "WebSocket 브릿지" 방식의 축소판입니다.
실제 PBX 연동 시에는 브라우저 자리에
Asterisk/FreeSWITCH 같은 미디어 서버가 들어와서
SIP/RTP를 WebSocket으로 변환해주는 역할을 대신 하게 됩니다.

→ 현재 코드 구조를 거의 그대로 유지하면서 SIP Trunk 연동 가능
→ 그래서 실무에서도 신규 음성봇 프로젝트는
  WebSocket 브릿지 방식을 더 많이 선택하는 편입니다
  (B2BUA는 통신 전문 인력이 있는 조직에서 주로 선택)
```

**협력사 미팅에서 던질 질문**: "저희는 WebSocket 브릿지 방식(미디어 서버 경유)을 가정하고 설계했는데, 귀사 PBX가 이 방식을 지원하나요, 아니면 직접 SIP으로 붙어야 하나요?"
