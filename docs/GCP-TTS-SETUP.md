# GCP Cloud Text-to-Speech 설정 가이드

Google Cloud TTS 서비스 계정 JSON 키 발급 절차.  
프로젝트: `voicebot-js-dev`

---

## 사전 확인

- GCP 프로젝트: **voicebot-js-dev** (상단 프로젝트 선택기에서 확인)
- 계정 역할: **소유자(Owner)** 이상 필요

> 상단에 다른 프로젝트(예: thinklink-dev)가 표시되면 클릭 → 목록에서 `voicebot-js-dev` 선택 후 진행.

---

## Step 1 — Cloud Text-to-Speech API 활성화

```
GCP 콘솔 → APIs & Services → 라이브러리
→ 검색창에 "text-to-speech" 입력
→ Cloud Text-to-Speech API 선택
→ [사용] 버튼 클릭
```

완료 확인:
- 상태: **사용 설정됨**
- 서비스: `texttospeech.googleapis.com`

---

## Step 2 — 서비스 계정 생성

```
GCP 콘솔 → APIs & Services → 사용자 인증 정보
→ [+ 사용자 인증 정보 만들기]
→ 서비스 계정 선택
```

입력 내용:

| 항목 | 값 |
|---|---|
| 서비스 계정 이름 | `tts-service-account` |
| 서비스 계정 ID | `tts-service-account` (자동) |
| 설명 | Google Cloud TTS API 호출용 (선택) |

생성 후 이메일:
```
tts-service-account@voicebot-js-dev.iam.gserviceaccount.com
```

**역할 설정**

"역할 선택" 드롭다운에서 검색:
```
texttospeech
```
→ `Cloud Text-to-Speech 사용자` 선택

> ⚠️ 역할이 검색되지 않는 경우:  
> 역할 없이 [완료] 후 아래 IAM에서 직접 부여.

```
GCP 콘솔 → IAM 및 관리자 → IAM
→ [+ 액세스 권한 부여]
→ 새 주 구성원: tts-service-account@voicebot-js-dev.iam.gserviceaccount.com
→ 역할: Cloud Text-to-Speech 사용자
→ [저장]
```

---

## Step 3 — JSON 키 발급

```
사용자 인증 정보 → 서비스 계정 목록 → tts-service-account 클릭
→ [키] 탭
→ [키 추가] → [새 키 만들기]
→ 키 유형: JSON
→ [만들기]
```

→ `.json` 파일이 자동 다운로드됨.

> ⚠️ **JSON 파일은 딱 한 번만 다운로드 가능.** 분실 시 새 키를 다시 발급해야 함.  
> ⚠️ **절대 Git에 커밋 금지.** `.gitignore`에 `*.json` 또는 키 파일 경로 추가.

---

## Step 4 — Spring Boot 프로젝트에 적용

### 키 파일 배치

```bash
# 예: 프로젝트 외부 안전한 경로에 배치
mkdir -p /etc/secrets
cp ~/Downloads/voicebot-js-dev-xxxx.json /etc/secrets/gcp-tts-key.json
chmod 600 /etc/secrets/gcp-tts-key.json
```

### 환경변수 설정 (.env)

```
GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/gcp-tts-key.json
```

### Spring Boot 실행

```bash
SPRING_PROFILES_ACTIVE=real-google ./mvnw spring-boot:run
```

---

## Step 5 — 동작 확인 (curl)

```bash
# Access Token 발급 (gcloud CLI 설치 시)
TOKEN=$(gcloud auth print-access-token)

# TTS 호출 테스트
curl -s -X POST \
  "https://texttospeech.googleapis.com/v1/text:synthesize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "input": {"text": "안녕하세요, 테스트입니다."},
    "voice": {"languageCode": "ko-KR", "name": "ko-KR-Neural2-A"},
    "audioConfig": {"audioEncoding": "LINEAR16", "sampleRateHertz": 8000}
  }' | python3 -c "
import sys, json, base64
data = json.load(sys.stdin)
open('/tmp/test.wav', 'wb').write(base64.b64decode(data['audioContent']))
print('저장 완료: /tmp/test.wav')
"
```

---

## 주의사항

| 항목 | 내용 |
|---|---|
| 인증 방식 | 서비스 계정 OAuth2만 가능 (API 키 방식 미지원) |
| 토큰 만료 | 1시간 — `credentials.refreshIfExpired()` 자동 갱신 |
| 1회 요청 최대 | 5,000바이트 (텍스트 기준) |
| 무료 티어 | Neural2 기준 월 100만자 |
| 요금 시작 | 무료 초과분부터 Neural2 $16/100만자 |

---

## 관련 문서

- [GCP TTS REST 레퍼런스](https://cloud.google.com/text-to-speech/docs/reference/rest/v1/text/synthesize)
- [지원 음성 목록 (한국어)](https://cloud.google.com/text-to-speech/docs/voices?hl=ko)
- [서비스 계정 인증 가이드](https://cloud.google.com/docs/authentication/getting-started)
- [요금 정책](https://cloud.google.com/text-to-speech/pricing)
