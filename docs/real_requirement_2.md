# 실제 AICC 요구사항 분석 — voicebot-js 갭 분석

## 목차

- [요구사항 개요](#요구사항-개요)
- [요구사항 1 — Docker Swarm 이중화 및 채널 구성](#요구사항-1--docker-swarm-이중화-및-채널-구성)
- [요구사항 2 — PBX, CTI, PDS, IVR 연동](#요구사항-2--pbx-cti-pds-ivr-연동)
- [요구사항 3 — 기존 콜봇(SOE) 및 STT/TTS 연동](#요구사항-3--기존-콜봇soe-및-stttts-연동)
- [요구사항 4 — SIP Trunk 연계 방식 구축](#요구사항-4--sip-trunk-연계-방식-구축)
- [우선순위별 구현 로드맵](#우선순위별-구현-로드맵)
- [핵심 갭 요약](#핵심-갭-요약)
- [용어 정리](#용어-정리)

---

## 요구사항 개요

[↑ 목차](#목차)

실제 AICC(AI Contact Center) 프로젝트에서 요구하는 4가지 핵심 항목:

1. Docker Swarm 기반 이중화 구성 및 채널 구성
2. PBX, CTI, PDS, IVR 연동
3. 기존 콜봇(SOE) 및 STT/TTS 연동
4. SIP Trunk 연계 방식 구축

현재 voicebot-js는 STT→LLM→TTS 파이프라인 핵심 로직이 구현되어 있으나,
실제 운영 환경을 위한 위 4가지 항목과는 상당한 갭이 존재한다.

---

## 요구사항 1 — Docker Swarm 이중화 및 채널 구성

[↑ 목차](#목차)

### 현재 상태

```
voicebot-js 현재 구조:
  docker-compose.yml         ← 단일 노드, 단순 컨테이너 기동
  docker-compose.sim.yml     ← 시뮬레이터 추가
  Spring Boot :8080 1개
  MariaDB 1개
  Redis 1개
```

### 실제 운영에서 요구하는 것

```
Docker Swarm 운영 구조:

Manager Node (제어)
   │
   ├── Worker Node 1  (Spring Boot replica × 2)
   ├── Worker Node 2  (Spring Boot replica × 2)
   └── Worker Node 3  (MariaDB, Redis Sentinel)

채널 구성 = 동시 통화 처리 수
  채널 200 = Spring Boot 인스턴스가 WebSocket 200개를 동시에 처리해야 함
```

### 부족한 것 (코드 레벨)

| 항목 | 현재 | 필요한 것 |
|---|---|---|
| WebSocket 세션 공유 | `ConcurrentHashMap` (인스턴스 메모리) | Redis Pub/Sub 또는 Sticky Session |
| `sinkMap`, `historyMap` | 로컬 메모리 | 다중 인스턴스 불가 → Redis로 이전 필요 |
| 배포 파일 | `docker-compose.yml` | `docker-stack.yml` (Swarm 전용) |
| 헬스체크 | 없음 | `/actuator/health` + Swarm 헬스체크 |
| TTS 캐시 | 없음 | Redis에 고정 문구 캐싱 필요 |

### 핵심 문제

`CtiWebSocketHandler`의 `sinkMap`, `historyMap`, `callIdMap`, `disposableMap`이
모두 인스턴스 메모리에 있어서 **Swarm 이중화 시 세션 소실** 발생.

```java
// CtiWebSocketHandler.java — 이중화 불가 구조
private final Map<String, Sinks.Many<byte[]>> sinkMap = new ConcurrentHashMap<>();
private final Map<String, List<LlmService.Message>> historyMap = new ConcurrentHashMap<>();
private final Map<String, String> callIdMap = new ConcurrentHashMap<>();
private final Map<String, Disposable> disposableMap = new ConcurrentHashMap<>();
// ↑ 인스턴스 로컬 메모리 → 다른 인스턴스에서 조회 불가
```

**해결 방향**: Sinks/Flux는 직렬화 불가이므로 인메모리 유지 필수.
대신 `historyMap`(대화 이력)은 Redis JSON으로 이전하고,
동일 callId 요청이 항상 동일 인스턴스로 라우팅되도록 **Sticky Session** 적용.

---

## 요구사항 2 — PBX, CTI, PDS, IVR 연동

[↑ 목차](#목차)

### 현재 CTI 이벤트 처리 현황

```java
// CtiWebSocketHandler.java — 현재 지원하는 CTI 이벤트
if ("CTI_EVENT".equals(type)) {
    String ctiEvent = (String) event.get("event");
    if ("CALL_END".equals(ctiEvent)) {
        handleCallEnd(session);  // 종료만 처리
    }
    // HOLD, TRANSFER, CONFERENCE, CALLBACK, DTMF → 없음
}
```

### PBX 연동 갭

```
현재 연결 경로:
  브라우저(CtiSimulator.jsx) → WebSocket → Spring Boot

실제 운영 연결 경로:
  고객 전화기
       │ PSTN
       ▼
  PBX (교환기)
       │ SIP Trunk
       ▼
  미디어 서버 (Asterisk / FreeSWITCH / Kamailio)
       │ WebSocket 또는 HTTP (변환 후)
       ▼
  Spring Boot :8080

PBX가 보내는 오디오 포맷: G.711 μ-law 8kHz
현재 코드: PCM 그대로 받음 → AudioConverter 클래스 없음
```

### CTI 이벤트 — 현재 누락된 것

```
HOLD          → 음성봇 일시정지, 보류음 재생
RESUME        → 보류 해제, 대화 재개
TRANSFER      → 상담사에게 넘김 (에스컬레이션)
CONFERENCE    → 3자 통화 (음성봇 + 상담사 + 고객)
CALLBACK      → 재연결 예약
DTMF          → 고객이 누른 버튼 번호 처리
```

### PDS(Predictive Dialing System) — 완전히 없음

```
PDS = 발신 콜센터에서 쓰는 자동 다이얼러
현재 voicebot-js: 수신 전용 (POST /call/incoming, /ws/cti)

PDS 연동 시 필요한 것:
  - 발신 트리거 수신 API
  - 캠페인 ID, 고객 번호, 스크립트 ID 매핑
  - 발신 결과 콜백 처리 (응답/부재/거절)
  - 현재 코드에 해당 엔드포인트 없음
```

### IVR — 없음

```
IVR = 메뉴 트리 ("1번 누르시면 환불, 2번 누르시면 배송")

현재: STT → LLM → TTS (자연어 처리만)

IVR 필요 시:
  - DTMF 이벤트 처리 핸들러
  - 메뉴 트리 상태 관리 (Redis)
  - 각 메뉴별 TTS 스크립트 미리 합성
  - PlaybookService에 IVR 분기 로직 추가 필요
```

---

## 요구사항 3 — 기존 콜봇(SOE) 및 STT/TTS 연동

[↑ 목차](#목차)

### SOE란?

**SOE = System of Engagement**

```
System of Record (SoR)     ← DB, CRM 등 데이터 저장 (고객이 직접 안 봄)
System of Engagement (SoE) ← 고객과 실제 대화하는 채널 (고객이 직접 쓰는 접점)
```

AICC 문맥에서 "기존 콜봇(SOE)"는 **현재 운영 중인 레거시 음성봇 시스템**을 의미한다.
규칙 기반, 트리 구조 시나리오로 동작하며 새 AI 봇으로 교체하거나 병행 운영한다.

> **Engage** 핵심 뉘앙스: 일방적으로 정보를 주는 게 아니라 서로 주고받으며 연결되는 것.
> 그래서 고객과 실시간 대화하는 음성봇이 "System of Engagement"에 해당.

### SOE 외부 연동 — 없음

```java
// LlmModeRouter.java — 현재 분기
// ANTHROPIC  → Claude API
// INTERNAL   → PlaybookService (규칙 기반) ← SOE와 유사하나 내부 구현
// HYBRID     → confidence 기준으로 분기

// 실제 운영 시 SOE 연동 필요한 것:
//   - SOE REST API 클라이언트 (외부 HTTP 호출)
//   - SOE 세션 ID ↔ callId 매핑
//   - SOE 응답과 LLM 응답 통합 처리
//   - 에스컬레이션 판단 로직 (SOE 실패 → LLM → 상담사)
```

현재 `HybridStrategy.java`는 내부 PlaybookService ↔ Claude API 간 분기이고,
**외부 SOE 시스템과의 HTTP 연동 코드가 없다.**

### STT/TTS 현재 연동 후 남은 갭

```
RtzrWebSocketSttService 현재 이슈:
  - G.711 → PCM 변환 코드 없음 (PBX 연결 시 문제)
  - 화자분리(Speaker Diarization) 없음 (상담사+고객 구분)
  - VAD(Voice Activity Detection) 처리 없음

GoogleCloudTtsService 현재 이슈:
  - SSML 미사용 (자연스러운 발음 제어 불가)
  - TTS 캐싱 없음 (고정 문구 반복 합성 → 비용 낭비)
  - PBX 음성 주입 방식 미결정 (RTP push vs URL pull)
```

---

## 요구사항 4 — SIP Trunk 연계 방식 구축

[↑ 목차](#목차)

### 현재 상태 — SIP 코드 전혀 없음

```
현재 연결 경로:
  브라우저/CtiSimulator.jsx → WebSocket → Spring Boot

실제 운영 연결 경로:
  고객 전화기
       │ PSTN
       ▼
  PBX (교환기)
       │ SIP Trunk
       ▼
  미디어 서버 (Asterisk / FreeSWITCH / Kamailio)
       │ WebSocket 또는 HTTP (변환 후)
       ▼
  Spring Boot :8080
```

### SIP Trunk 연계를 위한 협의 포인트

| 협의 항목 | 내용 | voicebot-js 대응 |
|---|---|---|
| 미디어 서버 종류 | Asterisk, FreeSWITCH, Kamailio | 없음 |
| 오디오 브릿지 방식 | WebSocket 브릿지 vs SIP B2BUA | WebSocket만 |
| 코덱 협상 | G.711 / G.729 / Opus | AudioConverter 없음 |
| TTS 음성 주입 | URL pull vs RTP push | 방식 미결정 |
| SRTP | 암호화된 음성 전송 | 없음 |

### CTI 협력사 미팅 체크리스트

```
□ SIP 트렁크 방식인가, WebSocket 브릿지 방식인가?
□ 코덱: G.711μ / G.711a / G.729 / Opus 중 무엇?
□ 샘플레이트: 8kHz / 16kHz?
□ DTMF 처리: RFC 2833 / SIP INFO / Inband?
□ TTS 음성 주입 방식: RTP push / URL pull?
□ 미디어 서버 별도 존재하는가? (B2BUA 구조?)
□ NAT 환경인가? (STUN/TURN 필요 여부)
□ 암호화: SRTP / SDES / DTLS?
```

---

## 우선순위별 구현 로드맵

[↑ 목차](#목차)

### Phase 1 — 단일 인스턴스 안정화 (즉시)

```
1. AudioConverter 클래스 추가 (G.711 → PCM 16kHz)
   → CtiWebSocketHandler.handleBinaryMessage()에 적용

2. HOLD / TRANSFER / DTMF CTI 이벤트 처리 추가
   → CtiWebSocketHandler.handleTextMessage() 확장

3. TTS 고정 문구 Redis 캐싱
   → "잠시만 기다려 주세요", "감사합니다" 사전 합성

4. /actuator/health 엔드포인트 활성화
   → pom.xml에 spring-boot-starter-actuator 추가
```

### Phase 2 — 이중화 준비 (Swarm 전)

```
5. WebSocket 세션 상태를 Redis로 이전
   sinkMap      → 인메모리 유지 (Flux/Sink는 직렬화 불가)
   historyMap   → Redis (callId 기반 JSON 직렬화)
   → 핵심: 동일 callId는 반드시 동일 인스턴스로 라우팅 (Sticky Session)

6. docker-stack.yml 작성
   → replicas, update_config, placement 설정
```

### Phase 3 — 미디어 서버 연동 (SIP Trunk)

```
7. 미디어 서버(Asterisk/FreeSWITCH) 시뮬레이터 추가
   → docker-compose.sim.yml에 추가

8. SIP Trunk → WebSocket 브릿지 구현
   → 현재 CtiSimulator.jsx가 하는 역할을 미디어 서버가 대체
```

---

## 핵심 갭 요약

[↑ 목차](#목차)

| 요구사항 | 완성도 | 핵심 부족 항목 |
|---|---|---|
| Docker Swarm 이중화 | 20% | `sinkMap`/`historyMap` 단일 인스턴스 한계, Sticky Session 미설계 |
| PBX/CTI/PDS/IVR | 30% | G.711 변환 없음, HOLD/TRANSFER 없음, PDS/IVR 전혀 없음 |
| SOE/STT/TTS 연동 | 60% | SOE 외부 연동 없음, SSML 미사용, TTS 캐싱 없음 |
| SIP Trunk | 5% | SIP 코드 없음, 미디어 서버 연동 없음 |

**가장 먼저 해결해야 할 것**:
- G.711 AudioConverter 구현 (PBX 연결 최소 요건)
- CTI 이벤트 확장 (HOLD/TRANSFER/DTMF)

이 두 가지가 실제 PBX와 연결되는 최소 요건이고,
나머지는 이 기반 위에서 순차적으로 구현 가능하다.

---

## 용어 정리

[↑ 목차](#목차)

| 용어 | 원문 | 의미 |
|---|---|---|
| AICC | AI Contact Center | AI 기반 콜센터 |
| PBX | Private Branch eXchange | 사설 전화 교환기 |
| CTI | Computer Telephony Integration | 컴퓨터-전화 통합 시스템 |
| PDS | Predictive Dialing System | 자동 발신 다이얼러 |
| IVR | Interactive Voice Response | 자동 응답 시스템 (메뉴 트리) |
| SOE | System of Engagement | 고객 접점 시스템 (레거시 콜봇 통칭) |
| SoR | System of Record | 데이터 저장 시스템 (CRM, DB) |
| SIP | Session Initiation Protocol | 전화 연결/종료 신호 프로토콜 |
| RTP | Real-time Transport Protocol | 실시간 음성 전송 프로토콜 |
| SRTP | Secure RTP | 암호화된 음성 전송 |
| G.711 | - | 유선전화 표준 코덱 (8kHz, 64Kbps) |
| VAD | Voice Activity Detection | 발화 구간 감지 |
| DTMF | Dual-Tone Multi-Frequency | 전화기 버튼 입력 신호 |
| B2BUA | Back-to-Back User Agent | SIP 양방향 중계 서버 |
| Sticky Session | - | 동일 클라이언트를 동일 서버로 라우팅 |
