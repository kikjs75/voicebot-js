# 외부 API 설정 가이드

real profile 구현(Phase 4)에 필요한 외부 API 인증 방식 및 요청·응답 스펙 정리.

---

## 1. RTZR STT (WebSocket — 기본, `real` profile)

### 개요

리턴제로(ReturnZero)의 실시간 스트리밍 STT. 한국어 콜센터 특화.

- 개발자 포털: https://developers.rtzr.ai
- 요금: 1,000원/시간 (T1, 월 1,000시간 이하)

### NCP 설정 경로

```
https://developers.rtzr.ai/signup → 회원가입
→ 콘솔(https://developers.rtzr.ai/console)에서 client_id / client_secret 발급
```

### Step 1 — 인증 토큰 발급

토큰 유효기간 **6시간** (`expire_at` Unix timestamp 포함). 만료 전 자동 갱신 필요.

```
POST https://openapi.vito.ai/v1/authenticate
Content-Type: application/x-www-form-urlencoded

client_id={RTZR_CLIENT_ID}&client_secret={RTZR_CLIENT_SECRET}
```

응답:

```json
{ "access_token": "eyJ...", "expire_at": 1690377931 }
```

> 구현 포인트: `expire_at` 기준으로 만료 5분 전에 재발급하는 스케줄러 필요 (`@Scheduled`).

에러 코드:

| 코드 | 의미 |
|---|---|
| 400 (H0001) | 잘못된 파라미터 |
| 401 (H0002) | 인증 실패 |
| 500 (E500) | 서버 오류 |

### Step 2 — WebSocket 연결

```
wss://openapi.vito.ai/v1/transcribe:streaming
  ?sample_rate=8000
  &encoding=LINEAR16
  &domain=CALL
  &use_itn=true
  &use_disfluency_filter=true
  &use_profanity_filter=false
  &use_punctuation=false

Header: Authorization: Bearer {access_token}
```

| 파라미터 | 음성봇 권장값 | 설명 |
|---|---|---|
| `sample_rate` | `8000` | 전화 G.711 8kHz |
| `encoding` | `LINEAR16` | PCM 16bit raw |
| `model_name` | (생략) | 기본값 `sommers_ko` |
| `domain` | `CALL` | **기본값이 CALL** — 전화 환경 최적화, 명시 권장 |
| `use_itn` | `true` | 숫자·날짜 정규화 |
| `use_disfluency_filter` | `true` | 간투어 제거 |
| `use_punctuation` | `false` | TTS 입력에 구두점 불필요 |
| `keywords` | 선택 | `"단어,단어:스코어"` 형식, 최대 100개 |

> ALAW 직접 전송도 가능 (`encoding=ALAW`) → G.711 디코딩 단계 생략 가능. 테스트 후 선택.

> Java 구현체는 **OkHttpClient** 기반 (`WebSocketListener` 상속). Spring WebClient WebSocket은 사용 불가.

### Step 3 — 오디오 전송 및 응답

오디오 청크를 **binary frame**으로 전송. 스트림 종료 시 `"EOS"` **텍스트 메시지** 전송 필수.

응답 JSON:

```json
{
  "seq": 1,
  "start_at": 0,
  "duration": 1500,
  "final": true,
  "alternatives": [
    {
      "text": "안녕하세요",
      "confidence": 0.95,
      "words": [
        { "text": "안녕하세요", "start_at": 0, "duration": 800, "confidence": 0.95 }
      ]
    }
  ]
}
```

| 필드 | 설명 |
|---|---|
| `final: false` | 중간 결과 (실시간 표시용) |
| `final: true` | 확정 결과 → `alternatives[0].text` 를 LLM으로 전달 |

에러 코드:

| 코드 | 의미 |
|---|---|
| 400 (H0001) | 파라미터 오류 |
| 401 (H0002) | 인증 실패 |
| 429 (A0001) | 동시 채널 한도 초과 |
| 500 (E500) | 서버 오류 |

### 동시 채널 제한

| 등급 | 스트리밍 동시 채널 |
|---|---|
| 무료 | 5 |
| Basic (유료) | 20 |
| Enterprise | 협의 |

> MVP 콜센터 동시 통화 수 감안해 유료 전환 시점 결정 필요. 429 수신 시 재시도 로직 고려.

### 요금

- **가입 즉시 600분(10시간) 무료** → 개발·테스트 비용 없음
- T1: **1,000원/시간** (월 0~1,000시간)
- 최소 집계 단위: **10초** (10초 미만도 10초로 과금)

### 환경변수

```
RTZR_CLIENT_ID=
RTZR_CLIENT_SECRET=
```

### 오디오 파이프라인

```
전화 수신 (G.711, 8kHz)
  → PCM 8kHz 16bit (LINEAR16)
  → binary frame 전송 → "EOS" 전송으로 종료
  ← final:true 수신 → SttResult(text, isFinal=true)
```

---

## 2. CLOVA Speech STT (gRPC — 선택, `real-grpc` profile)

### 개요

NAVER Cloud Platform CLOVA Speech 실시간 스트리밍. gRPC 전용.

### NCP 설정 경로

```
콘솔 → Services → AI Services → CLOVA Speech
→ [이용 신청] → Basic 플랜 이상
→ [스트리밍 인식 도메인 생성]
→ 도메인 이름/코드 입력 → [생성]
→ Secret Key 확인
```

> ⚠️ Free 플랜 미지원. Basic 플랜 이상 필요.

### 접속 정보

| 항목 | 값 |
|---|---|
| Host | `clovaspeech-gw.ncloud.com` |
| Port | `50051` |
| 프로토콜 | gRPC (TLS) |
| 인증 | `Authorization: Bearer {secretKey}` |

### 오디오 포맷 요구사항

| 항목 | 값 |
|---|---|
| 포맷 | PCM (headerless raw wave) |
| 샘플링 레이트 | **16kHz** |
| 채널 | 1 (mono) |
| 비트 | 16bit |

> 전화 G.711은 8kHz → **16kHz 업샘플링** 후 전송 필요.

### Config JSON 주요 파라미터

```json
{
  "transcription": { "language": "ko" },
  "keywordBoosting": [{ "keyword": "예약", "weight": 3.0 }],
  "semanticEpd": {}
}
```

### Java gRPC 연결 패턴

```java
ManagedChannel channel = NettyChannelBuilder
    .forTarget("clovaspeech-gw.ncloud.com:50051")
    .useTransportSecurity()
    .build();

NestServiceGrpc.NestServiceStub client = NestServiceGrpc.newStub(channel);
Metadata metadata = new Metadata();
metadata.put(Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
             "Bearer " + secretKey);
client = MetadataUtils.attachHeaders(client, metadata);
```

### pom.xml 추가 의존성

```xml
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-protobuf</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.63.0</version>
</dependency>
```

> proto 파일은 NAVER Cloud 공식 SDK에서 별도 확인 필요.

### 환경변수

```
CLOVA_SPEECH_SECRET_KEY=
```

---

## 3. CLOVA Voice TTS (REST, `real` / `real-grpc` profile 공통)

### NCP 설정 경로

```
콘솔 → Services → AI·NAVER API
→ [Application 등록]
→ 서비스: CLOVA Voice - Premium 체크
→ Web URL: http://localhost:8080
→ [등록] → [인증 정보] → Client ID / Client Secret 확인
```

> ⚠️ 기본료 **월 90,000원** 발생. 호출 없어도 청구됨.

### 엔드포인트

```
POST https://naveropenapi.apigw.ntruss.com/tts-premium/v1/tts
Content-Type: application/x-www-form-urlencoded
X-NCP-APIGW-API-KEY-ID: {client_id}
X-NCP-APIGW-API-KEY:    {client_secret}
```

> ⚠️ Body는 JSON이 아닌 **form-urlencoded** 형식.

### 요청 파라미터

| 파라미터 | 음성봇 권장값 | 설명 |
|---|---|---|
| `speaker` | `nara` | 여성 한국어 목소리 |
| `text` | LLM 응답 | 합성할 텍스트 |
| `volume` | `0` | -5~5 |
| `speed` | `0` | -5~5 |
| `pitch` | `0` | -5~5 |
| `emotion` | `0` | 0~3 |
| `emotion-strength` | `1` | 0~2 |
| `format` | `wav` | 오디오 포맷 |
| `sampling-rate` | `8000` | 전화 G.711 직접 사용 가능 |

### 응답

```
Content-Type: audio/wav
Body: binary (8kHz WAV)
```

### 환경변수

```
CLOVA_VOICE_CLIENT_ID=
CLOVA_VOICE_CLIENT_SECRET=
```

---

## 4. Anthropic Claude API (LLM, `real` / `real-grpc` profile 공통)

### API 키 발급

```
https://console.anthropic.com → API Keys → [Create Key]
```

### 엔드포인트

```
POST https://api.anthropic.com/v1/messages
Content-Type: application/json
x-api-key: {ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

### 요청 형식

```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "messages": [
    { "role": "user",      "content": "안녕하세요" },
    { "role": "assistant", "content": "네, 안녕하세요!" },
    { "role": "user",      "content": "예약하고 싶어요" }
  ]
}
```

### 응답 파싱

```json
{ "content": [{ "type": "text", "text": "네, 예약을 도와드리겠습니다." }] }
```

`response.content[0].text` 추출.

### 환경변수

```
ANTHROPIC_API_KEY=
```

---

## 5. 환경변수 전체 매핑

| 환경변수 | 서비스 | Profile | 발급 경로 |
|---|---|---|---|
| `RTZR_CLIENT_ID` | RTZR STT | `real` | developers.rtzr.ai 콘솔 |
| `RTZR_CLIENT_SECRET` | RTZR STT | `real` | developers.rtzr.ai 콘솔 |
| `CLOVA_SPEECH_SECRET_KEY` | CLOVA Speech STT | `real-grpc` | NCP 콘솔 → CLOVA Speech 도메인 |
| `CLOVA_VOICE_CLIENT_ID` | CLOVA Voice TTS | 공통 | NCP 콘솔 → AI·NAVER API → Application |
| `CLOVA_VOICE_CLIENT_SECRET` | CLOVA Voice TTS | 공통 | NCP 콘솔 → AI·NAVER API → Application |
| `ANTHROPIC_API_KEY` | Claude LLM | 공통 | console.anthropic.com |

---

## 6. 전체 오디오 파이프라인

```
전화 수신 (G.711, 8kHz)
  │
  ├─ [real profile]
  │    → PCM 8kHz 16bit
  │    → RTZR WebSocket 스트리밍
  │    ← final:true 텍스트
  │
  └─ [real-grpc profile]
       → PCM 16kHz 16bit (업샘플링 필요)
       → CLOVA Speech gRPC 스트리밍
       ← 확정 텍스트
  │
  ▼
LLM: Claude API (messages[])
  │
  ▼
TTS: CLOVA Voice REST
  → format=wav, sampling-rate=8000
  ← 8kHz WAV binary
  │
  ▼
전화 송출 (G.711, 8kHz)
```
