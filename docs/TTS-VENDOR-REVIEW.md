# TTS 업체 검토 및 선정

**검토 기준**: 가격 > 음질 > Latency > API 편의성  
**대상 언어**: 한국어(필수), 영어  
**규모**: PoC/소규모 (월 수만 글자)

---

## 업체 비교표

| 업체 | 100만자 요금 | 무료 티어 | 한국어 품질 | Latency | REST API |
|---|---|---|---|---|---|
| **Google Cloud TTS** | Neural2 $16 / Standard $4 | 월 100만자 무료 (Neural2) | ⭐⭐⭐⭐ | 보통 | ✅ |
| **CLOVA Voice** | 기본 100만자 포함 (월 기본료) | 월 100만자 포함 | ⭐⭐⭐⭐⭐ | 빠름 (국내) | ✅ |
| **OpenAI TTS** | Standard $15 / HD $30 | 없음 | ⭐⭐⭐ | 보통 | ✅ |
| **ElevenLabs** | 구독 $6~(30K자) + 초과 $0.30/1K | 없음 | ⭐⭐⭐⭐ | 빠름 (~75ms) | ✅ |

---

## 업체별 상세 분석

### 1. Google Cloud TTS — **PoC 1순위**

**요금**
| 모델 | 100만자 요금 | 월 무료 티어 |
|---|---|---|
| Standard | $4 | 400만자 |
| WaveNet | $4 | 100만자 |
| Neural2 | $16 | **100만자** ← PoC 추천 |
| Chirp 3 HD | $30 | 100만자 |
| Studio | $160 | 없음 |

> PoC 기준 월 수만 글자 → **Neural2 무료 티어 내에서 완전 무료 운영 가능**

**한국어 음성 목록**
```
ko-KR-Standard-A/B (여성) / C/D (남성)
ko-KR-Wavenet-A/B  (여성) / C/D (남성)
ko-KR-Neural2-A    (여성) ← PoC 추천
ko-KR-Neural2-B    (남성) ← PoC 추천
```

**인증 방식 주의**

> ⚠️ Google Cloud TTS는 **API 키 방식이 동작하지 않는다**.  
> **서비스 계정(OAuth2) 방식만** 지원. → `google-auth-library-java` 의존성 필요.

**장점**
- PoC 비용 $0 (Neural2 월 100만자 무료)
- 한국어 + 영어 동시 고품질 지원
- REST API, WAV/MP3 반환 → 현재 아키텍처와 호환
- `sampleRateHertz: 8000` 지원 → 전화 G.711 직접 사용 가능
- `credentials.refreshIfExpired()` 한 줄로 토큰 자동 갱신 (별도 스케줄러 불필요)

**단점**
- 응답이 **base64 인코딩 JSON** → 디코딩 처리 필요
- 한국어 품질이 CLOVA 대비 살짝 부자연스러울 수 있음
- GCP 서비스 계정 JSON 키 파일 발급 및 관리 필요

> 서비스 계정 발급 절차: **[docs/GCP-TTS-SETUP.md](./GCP-TTS-SETUP.md)**

---

### 2. CLOVA Voice — **한국어 품질 최우선 시**

**장점**
- 한국어 품질 최고 수준 (네이버 자체 데이터)
- 국내 서버 → 낮은 Latency
- 감정 표현 파라미터 (콜센터 봇에 유리)
- `sampling-rate=8000` 지원

**단점**
- 월 기본료 **90,000원** (호출 없어도 과금)
- 영어 품질은 Google/OpenAI 대비 아쉬움
- 이미 Phase 4에서 `ClovaVoiceTtsService` 구현 완료

---

### 3. OpenAI TTS — **비추천 (가격 우선 기준)**

- 무료 티어 없음, PoC부터 비용 발생
- LLM도 OpenAI 사용 시 단일 키 관리 장점

---

## 선택 가이드

| 상황 | 선택 |
|---|---|
| PoC 비용 $0으로 시작 | **Google Cloud TTS** (Neural2 ko-KR) |
| 한국어 품질 최우선 | CLOVA Voice |
| 둘 다 테스트 | Google로 시작 → 품질 비교 후 CLOVA 전환 |

---

## 아키텍처 연동 포인트

```
LLM 응답 텍스트
  ↓
TtsService.synthesize(text, callId)
  │
  ├─ [real profile]         ClovaVoiceTtsService   → form-urlencoded POST
  ├─ [real-google profile]  GoogleCloudTtsService  → JSON POST + base64 디코딩
  └─ [sim profile]          SimulatorTtsService    → REST mock
  ↓
byte[] (8kHz WAV)
  ↓
CallController 응답
```

> TTS 교체는 `SPRING_PROFILES_ACTIVE` 변경 한 줄로 끝. 코드 변경 없음.

---

## 결론

**PoC 단계**: Google Cloud TTS (Neural2, 무료)로 시작  
**운영 전환 시**: CLOVA Voice와 A/B 품질 비교 후 결정
