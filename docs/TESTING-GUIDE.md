# 테스트 및 소스 분석 가이드

직접 테스트하고 소스를 분석할 때 참고하는 문서.

---

## 1. 환경 시작

### vscode 계정 전환
- vscode 계정 전환해야지 파일 소유권 오염, Git 작업 오염 안 된다.
- 그러나 apt-get 같은 시스템 명령만 sudo 붙여서 실행.

```bash
su - vscode
```

### sim profile (시뮬레이터 — API 키 불필요)

```bash
# 1) 인프라 + 시뮬레이터 기동
docker compose -f docker-compose.yml -f docker-compose.sim.yml up -d

# 2) 컨테이너 상태 확인
docker ps --format "table {{.Names}}\t{{.Status}}"

# 3) 시뮬레이터 헬스체크
curl http://stt-simulator:8081/health
curl http://llm-simulator:8082/health
curl http://tts-simulator:8083/health

# 4) Spring Boot 기동
mvn spring-boot:run -Dspring-boot.run.profiles=sim
```

### real profile (실제 외부 API)

```bash
# 1) 인프라만 기동 (시뮬레이터 불필요)
docker compose up mariadb redis -d

# 2) .env 파일 확인
cat .env  # RTZR, ANTHROPIC, GOOGLE 키 설정 여부 확인

# 3) Spring Boot 기동
set -a && source .env && set +a

# 포그라운드 (로그 터미널에 직접 출력)
SPRING_PROFILES_ACTIVE=real mvn spring-boot:run

# 백그라운드 (logback이 logs/app.log 에 자동 저장)
nohup env SPRING_PROFILES_ACTIVE=real mvn spring-boot:run > /dev/null 2>&1 &
echo "PID: $!"
until grep -q "Started VoicebotApplication" logs/app.log; do sleep 2; done
echo "Spring Boot 기동 완료"
```

기동 확인 로그 (real profile):

```bash
# 백그라운드 실행 시 로그 확인
tail -f logs/app.log | grep -E "Started|RTZR.*토큰|TTS-GOOGLE|ERROR"
```

```
[STT-RTZR] 토큰 발급 완료 expire_at=...
[TTS-GOOGLE] 서비스 계정 자격증명 로드 완료
Started VoicebotApplication in 2.x seconds
```

### 실행 상태 확인

```bash
# Spring Boot
lsof -ti:8080 && echo "실행중" || echo "중지됨"

# Vite
lsof -ti:5173 && echo "실행중" || echo "중지됨"
```

### Spring Boot 재시작 (코드 변경 후)

```bash
# 실행 중인 프로세스 종료
kill $(lsof -ti:8080)

# 재시작
cd /workspaces/voicebot-js
set -a && source .env && set +a
SPRING_PROFILES_ACTIVE=real mvn spring-boot:run
```

백그라운드로 실행 중이었다면:

```bash
kill $(lsof -ti:8080)
nohup env SPRING_PROFILES_ACTIVE=real mvn spring-boot:run > /dev/null 2>&1 &
until grep -q "Started VoicebotApplication" logs/app.log; do sleep 2; done
echo "재시작 완료"
```

---

### Vite 프론트엔드 기동 (CTI WebSocket 테스트 시)

Spring Boot가 실행 중인 상태에서 실행한다.

```bash
# 포그라운드 (별도 터미널)
cd /workspaces/voicebot-js/frontend
npm run dev

# 백그라운드
cd /workspaces/voicebot-js/frontend
nohup npm run dev > /tmp/vite.log 2>&1 &
until grep -q "Local:" /tmp/vite.log; do sleep 1; done
echo "Vite 기동 완료 → http://localhost:5173"
```

브라우저: `http://localhost:5173`

> Host OS 브라우저에서 접근하려면 `.devcontainer/devcontainer.json`에 `"forwardPorts": [8080, 5173]` 설정이 필요하다.

---

## 2. 수동 테스트

### 테스트용 한국어 음성 생성 (공통)

sim/real 모두 동일한 방법으로 테스트 음성을 생성한다.  
> sim profile은 STT 시뮬레이터가 오디오 내용을 무시하고 시나리오 파일에서 텍스트를 반환하므로 아무 음성이나 가능하지만, real profile은 RTZR이 실제 음성을 인식해야 하므로 반드시 실제 한국어 음성이 필요하다.

```bash
# python3-cryptography 설치 (최초 1회)
sudo apt-get install -y python3-cryptography

# Google TTS로 한국어 음성 생성 (16kHz LINEAR16 PCM)
set -a && source .env && set +a
python3 << 'EOF'
import urllib.request, urllib.parse, json, base64, os, time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.backends import default_backend

creds_path = os.environ['GOOGLE_APPLICATION_CREDENTIALS']
with open(creds_path) as f:
    creds = json.load(f)

private_key = serialization.load_pem_private_key(creds['private_key'].encode(), password=None, backend=default_backend())
now = int(time.time())
header  = base64.urlsafe_b64encode(json.dumps({'alg':'RS256','typ':'JWT'}).encode()).rstrip(b'=')
payload = base64.urlsafe_b64encode(json.dumps({
    'iss': creds['client_email'],
    'scope': 'https://www.googleapis.com/auth/cloud-platform',
    'aud': 'https://oauth2.googleapis.com/token',
    'exp': now + 3600, 'iat': now
}).encode()).rstrip(b'=')
signing_input = header + b'.' + payload
signature = base64.urlsafe_b64encode(
    private_key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
).rstrip(b'=')
jwt = (signing_input + b'.' + signature).decode()

data = urllib.parse.urlencode({'grant_type':'urn:ietf:params:oauth:grant-type:jwt-bearer','assertion':jwt}).encode()
with urllib.request.urlopen(urllib.request.Request('https://oauth2.googleapis.com/token', data=data)) as r:
    token = json.load(r)['access_token']

body = json.dumps({
    'input': {'text': '안녕하세요. 무엇을 도와드릴까요?'},
    'voice': {'languageCode': 'ko-KR', 'name': 'ko-KR-Neural2-A'},
    'audioConfig': {'audioEncoding': 'LINEAR16', 'sampleRateHertz': 16000}
}).encode()
req = urllib.request.Request(
    'https://texttospeech.googleapis.com/v1/text:synthesize', data=body,
    headers={'Authorization': f'Bearer {token}', 'Content-Type': 'application/json'}
)
with urllib.request.urlopen(req) as r:
    audio = base64.b64decode(json.load(r)['audioContent'])
    open('/tmp/korean-test.pcm', 'wb').write(audio)
    print(f'생성 완료: {len(audio)} bytes')
EOF
```

---

### sim profile 테스트

```bash
curl -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: TEST-SIM-001" \
  --data-binary @/tmp/korean-test.pcm \
  -o /tmp/response.wav \
  -w "HTTP %{http_code} | %{size_download} bytes\n"
```

### real profile 테스트

```bash
curl -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: TEST-REAL-001" \
  --data-binary @/tmp/korean-test.pcm \
  -o /tmp/response.pcm \
  -w "HTTP %{http_code} | %{size_download} bytes\n" \
  --max-time 60
```

### 대화 이어가기 (세션 테스트)

같은 `callId`로 여러 번 호출하면 대화 이력이 Redis에 누적된다.

```bash
# 첫 번째 발화
curl -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: TEST-SIM-001" \
  --data-binary @/tmp/korean-test.pcm -o /dev/null -s -w "%{http_code}\n"

# 두 번째 발화 (같은 callId — 이전 대화 이력 포함)
curl -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: TEST-SIM-001" \
  --data-binary @/tmp/korean-test.pcm -o /dev/null -s -w "%{http_code}\n"

# Redis에서 대화 이력 확인
docker exec voicebot-redis redis-cli GET "call:session:TEST-SIM-001"
```

---

## 3. 로그 확인

### PERF 로그 실시간 확인

```bash
# Spring Boot 실행 중인 터미널에서 직접 확인하거나
# 백그라운드 실행 시:
tail -f app-real.log | grep -E "PERF|STT-RTZR.*final|ERROR"
```

예시 출력:
```
[STT-PERF]  callId=TEST-001 elapsed=344ms  text="안녕하세요"
[LLM-PERF]  callId=TEST-001 elapsed=4849ms
[TTS-PERF]  callId=TEST-001 elapsed=3226ms
[CALL-PERF] callId=TEST-001 elapsed=8532ms
```

### DB 조회

```bash
# 전체 콜 레코드
docker exec voicebot-mariadb mariadb -u voicebot -pvoicebot1234 voicebot \
  -e "SELECT call_id, stt_text, LEFT(llm_response,50), total_elapsed_ms, created_at FROM call_records ORDER BY id DESC LIMIT 10;"

# 특정 callId
docker exec voicebot-mariadb mariadb -u voicebot -pvoicebot1234 voicebot \
  -e "SELECT * FROM call_records WHERE call_id='TEST-001'\G"
```

### Redis 세션 조회

```bash
# 세션 키 목록
docker exec voicebot-redis redis-cli KEYS "call:session:*"

# 세션 내용 확인
docker exec voicebot-redis redis-cli GET "call:session:TEST-001"

# 세션 삭제 (테스트 초기화)
docker exec voicebot-redis redis-cli DEL "call:session:TEST-001"
```

---

## 4. CTI WebSocket 수동 테스트 (담당자)

### 사전 조건 — devcontainer 포트 포워딩

Host OS 브라우저에서 devcontainer 안 서비스에 접근하려면 포트 포워딩이 필요하다.
`.devcontainer/devcontainer.json`에 아래 설정이 되어 있어야 한다.

```json
"forwardPorts": [8080, 5173]
```

설정 변경 후 devcontainer를 **재시작**해야 적용된다.

---

### devcontainer 재시작 후 서비스 재기동 절차

devcontainer 재시작 시 Spring Boot와 Vite 개발 서버가 모두 종료되므로 다시 기동해야 한다.

```bash
# 1) vscode 계정으로 전환
su - vscode
cd /workspaces/voicebot-js

# 2) Docker 컨테이너 상태 확인 (자동 재시작됨)
docker ps --format "table {{.Names}}\t{{.Status}}"

# 3) Spring Boot 기동 (real profile)
set -a && source .env && set +a
nohup env SPRING_PROFILES_ACTIVE=real mvn spring-boot:run > app-real.log 2>&1 &
echo "PID: $!"

# 기동 확인
until grep -q "Started VoicebotApplication" app-real.log; do sleep 2; done
echo "Spring Boot 기동 완료"

# 4) Vite 프론트엔드 기동
cd frontend
nohup npm run dev > /tmp/vite.log 2>&1 &
until grep -q "Local:" /tmp/vite.log; do sleep 1; done
echo "Vite 기동 완료 → http://localhost:5173"
```

---

### 테스트 절차

**1. Host OS 브라우저에서 `http://localhost:5173` 접속**

**2. 우측 상단 WS 상태 확인**
- 🟢 `WS 연결됨` → 정상 진행
- 🔴 `WS 미연결` → Spring Boot 기동 여부 확인 (`lsof -ti:8080`)

**3. 통화 정보 입력 후 `📞 전화 걸기`**
- 발신번호 / 수신번호 입력 (기본값 사용 가능)
- 브라우저 마이크 권한 요청 팝업 → **허용**
- 상태: `대기중` → `통화중` 변경 확인

**4. 마이크에 대고 한국어로 말하기**
```
예시 발화:
  "안녕하세요, 요금 문의드리려고요"
  "인터넷이 연결이 안 돼요"
  "해지 신청하고 싶어요"
```

**5. 우측 실시간 로그 패널에서 파이프라인 결과 확인**

| 아이콘 | 항목 | 확인 내용 |
|---|---|---|
| 🎤 STT | 음성 인식 결과 | 발화 내용이 정확히 텍스트로 변환됐는지 |
| 🧠 LLM | Claude 응답 | 의도에 맞는 응답이 생성됐는지 |
| 🔊 TTS | 음성 출력 텍스트 | TTS로 변환될 텍스트 내용 |

**6. `📵 끊기` 버튼으로 통화 종료**

---

### 서버 없이 UI만 확인 (선택)

전화 걸기 후 `⚡ UI 더미 테스트` 버튼 클릭 →
서버 연결 없이 가상 파이프라인 동작 확인 (STT/LLM/TTS 고정값으로 표시)

---

### 테스트 중 로그 모니터링

```bash
# Spring Boot CTI 파이프라인 로그
tail -f /workspaces/voicebot-js/app-real.log | grep -E "\[CTI\]|\[CTI-LLM\]|\[CTI-TTS\]|ERROR"
```

예시 출력:
```
[CTI] 연결됨 sessionId=... callId=CTI-XXXXXXXX
[CTI] 이벤트 수신 type=CTI_EVENT callId=CTI-XXXXXXXX
[CTI] STT 최종 callId=CTI-XXXXXXXX text="안녕하세요 요금 문의드리려고요"
[CTI-LLM-PERF] callId=CTI-XXXXXXXX elapsed=3241ms
[CTI-TTS-PERF] callId=CTI-XXXXXXXX elapsed=891ms
```

---

## 5. LLM Mode + MongoDB + 마이크 E2E 테스트 (real profile)

LLM 다중 모드(ANTHROPIC / INTERNAL / HYBRID)와 MongoDB Playbook이 정상 동작하는지
마이크 음성으로 직접 확인하는 절차다.

---

### 배경 — devcontainer에서 MongoDB에 접근하는 방법

devcontainer 안에서 `docker run`으로 실행한 컨테이너는 **sibling 컨테이너**다.
`-p 27017:27017`로 포트를 매핑해도 devcontainer의 `localhost:27017`에는 연결되지 않는다.
호스트 OS(Mac) 쪽에 매핑되기 때문이다.

해결 방법: MongoDB 컨테이너의 **Docker 네트워크 IP**를 직접 사용한다.

```
devcontainer(localhost)
    │
    ├─ localhost:27017  ← 아무것도 없음 ❌
    │
    └─ voicebot-net 네트워크
           └─ voicebot-mongodb  172.20.0.5:27017  ✅
```

---

### 1단계: MongoDB 컨테이너 준비

```bash
# MongoDB 컨테이너가 실행 중인지 확인
docker ps | grep mongodb

# 없으면 기동 (voicebot-net 네트워크에 연결)
docker run -d \
  --name voicebot-mongodb \
  --network voicebot-net \
  -p 27017:27017 \
  mongo:7

# MongoDB IP 확인 (보통 172.20.0.5)
docker inspect voicebot-mongodb --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
```

---

### 2단계: Playbook 데이터 투입

```bash
docker exec -i voicebot-mongodb mongosh voicebot << 'EOF'
db.intent_playbook.drop();
db.intent_playbook.insertMany([
  { _id: "인사",      intent: "인사",      response: "안녕하세요! 무엇을 도와드릴까요?",                                              action: "provide_info",        escalate: false, confidenceThreshold: 0.7 },
  { _id: "배송문의",  intent: "배송문의",  response: "일반 배송은 2~3 영업일, 특급 배송은 1 영업일 이내 도착합니다. 배송 조회는 주문번호를 말씀해주세요.", action: "provide_info",        escalate: false, confidenceThreshold: 0.7 },
  { _id: "반품환불",  intent: "반품환불",  response: "반품과 환불은 수령 후 7일 이내 신청 가능합니다. 주문번호와 사유를 알려주시면 처리해드리겠습니다.", action: "provide_info",        escalate: false, confidenceThreshold: 0.7 },
  { _id: "교환",      intent: "교환",      response: "교환은 수령 후 7일 이내, 상품 하자 또는 오배송 시 가능합니다.",                 action: "provide_info",        escalate: false, confidenceThreshold: 0.7 },
  { _id: "결제",      intent: "결제",      response: "신용카드, 계좌이체, 무통장입금이 가능합니다. 주문번호를 알려주시면 확인해드리겠습니다.", action: "provide_info",        escalate: false, confidenceThreshold: 0.7 },
  { _id: "회원",      intent: "회원",      response: "회원정보 변경, 탈퇴, 비밀번호 재설정은 마이페이지에서 처리하실 수 있습니다.",    action: "provide_info",        escalate: false, confidenceThreshold: 0.7 },
  { _id: "주문조회",  intent: "주문조회",  response: "주문번호를 말씀해주시면 주문 상태를 확인해드리겠습니다.",                        action: "request_order_number", escalate: false, confidenceThreshold: 0.7 },
  { _id: "상담원연결", intent: "상담원연결", response: "상담원에게 연결해드리겠습니다. 잠시만 기다려주세요.",                          action: "escalate",            escalate: true,  confidenceThreshold: 0.7 },
  { _id: "종료",      intent: "종료",      response: "이용해 주셔서 감사합니다. 좋은 하루 되세요.",                                  action: "end_call",            escalate: false, confidenceThreshold: 0.7 },
  { _id: "기타",      intent: "기타",      response: "죄송합니다. 잠시 후 상담원을 연결해드리겠습니다.",                              action: "fallback",            escalate: false, confidenceThreshold: 0.7 }
]);
print("Playbook 건수: " + db.intent_playbook.countDocuments());
EOF
```

데이터 확인:
```bash
docker exec -i voicebot-mongodb mongosh voicebot --quiet \
  --eval 'db.intent_playbook.find({},{intent:1,action:1,_id:0}).forEach(d=>print(JSON.stringify(d)))'
```

---

### 3단계: Spring Boot 기동 (real profile + HYBRID 모드)

```bash
cd /workspaces/voicebot-js

# MongoDB IP 변수로 저장 (직접 확인 후 사용)
MONGO_IP=$(docker inspect voicebot-mongodb --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
echo "MongoDB IP: $MONGO_IP"

# 환경변수 로드 + Spring Boot 기동
set -a && source .env && set +a

MONGODB_URI=mongodb://${MONGO_IP}:27017/voicebot \
VOICEBOT_LLM_MODE=HYBRID \
nohup mvn spring-boot:run -Dspring-boot.run.profiles=real > /tmp/real-app.log 2>&1 &

echo "Spring Boot PID: $!"

# 기동 완료 대기
until grep -q "Started VoicebotApplication\|APPLICATION FAILED" /tmp/real-app.log; do sleep 2; done
grep -E "Started|ERROR|Mongo" /tmp/real-app.log | tail -5
```

정상 기동 시 로그:
```
MongoClient ... created with settings ... hosts=[172.20.0.5:27017]
Started VoicebotApplication in 2.x seconds
```

MongoDB 연결 오류 시:
```
Exception opening socket ... ConnectException: 연결이 거부됨
```
→ `MONGO_IP`를 다시 확인하고 재기동.

---

### 4단계: Vite 프론트엔드 기동

```bash
cd /workspaces/voicebot-js/frontend
nohup npm run dev > /tmp/vite.log 2>&1 &
until grep -q "Local:" /tmp/vite.log; do sleep 1; done
echo "Vite 기동 완료 → http://localhost:5173"
```

---

### 5단계: 마이크로 테스트

**Host OS 브라우저에서 `http://localhost:5173` 접속**

> devcontainer 포트 포워딩(`"forwardPorts": [8080, 5173]`)이 설정되어 있어야 한다.

**테스트 순서:**

1. `📞 전화 걸기` 클릭 → 마이크 권한 **허용**
2. 아래 발화 목록대로 마이크에 대고 말한다
3. 화면 우측 로그 패널과 터미널 로그에서 결과를 확인한다
4. `📵 끊기`로 통화 종료 후 다음 케이스 진행

---

### 테스트 케이스 목록

| # | 발화 예시 | 기대 intent | 기대 action | 예상 응답 경로 |
|---|---|---|---|---|
| TC-01 | "안녕하세요" | 인사 | provide_info | Playbook 응답 |
| TC-02 | "배송이 언제 오나요?" | 배송문의 | provide_info | Playbook 응답 |
| TC-03 | "반품하고 싶어요" | 반품환불 | provide_info | Playbook 응답 |
| TC-04 | "주문한 거 어디까지 왔어요?" | 주문조회 | request_order_number | Playbook 응답 |
| TC-05 | "상담원 연결해주세요" | 상담원연결 | escalate | Playbook 응답 |
| TC-06 | "아무 말이나 합니다 이상한 말" | 기타 | fallback | Playbook 응답 or Claude fallback |

---

### 6단계: 로그로 결과 확인

**터미널 1 — 실시간 파이프라인 모니터링:**
```bash
tail -f /tmp/real-app.log | grep -E "\[LLM-MODE\]|\[INTENT\]|\[PLAYBOOK\]|\[LLM-PERF\]|ERROR"
```

**TC-02 (배송문의) 정상 출력 예시:**
```
[LLM-MODE]  callId=CTI-xxxxxxxx mode=HYBRID
[INTENT]    callId=CTI-xxxxxxxx intent=배송문의 confidence=0.95 elapsed=1400ms
[PLAYBOOK]  callId=CTI-xxxxxxxx intent=배송문의 hit=true elapsed=3ms
[PLAYBOOK]  callId=CTI-xxxxxxxx hit=true action=provide_info → Playbook 응답
[LLM-PERF]  callId=CTI-xxxxxxxx elapsed=1403ms
```

**TC-05 (상담원연결) 정상 출력 예시:**
```
[LLM-MODE]  callId=CTI-xxxxxxxx mode=HYBRID
[INTENT]    callId=CTI-xxxxxxxx intent=상담원연결 confidence=0.99 elapsed=1200ms
[PLAYBOOK]  callId=CTI-xxxxxxxx intent=상담원연결 hit=true elapsed=2ms
[PLAYBOOK]  callId=CTI-xxxxxxxx hit=true action=escalate → Playbook 응답
```

**Claude fallback 출력 예시 (confidence 낮을 때):**
```
[INTENT]    callId=CTI-xxxxxxxx intent=기타 confidence=0.45 elapsed=1300ms
[PLAYBOOK]  callId=CTI-xxxxxxxx hit=false confidence=0.45 → Claude fallback
[LLM-PERF]  callId=CTI-xxxxxxxx elapsed=4200ms
```

---

### 7단계: Playbook 데이터 검증

MongoDB에서 intent별 hit 여부를 직접 확인:

```bash
# 전체 Playbook 목록
docker exec -i voicebot-mongodb mongosh voicebot --quiet \
  --eval 'db.intent_playbook.find({},{intent:1,response:1,action:1,escalate:1,_id:0}).forEach(d=>printjson(d))'

# 특정 intent 조회
docker exec -i voicebot-mongodb mongosh voicebot --quiet \
  --eval 'printjson(db.intent_playbook.findOne({intent:"배송문의"}))'
```

---

### 8단계: LLM 모드 전환 테스트

Spring Boot를 재기동하지 않고 `VOICEBOT_LLM_MODE` 환경변수만 바꿔서 모드별 동작을 비교할 수 있다.

```bash
# ANTHROPIC 모드 — 항상 Claude가 직접 응답 (Playbook 무시)
VOICEBOT_LLM_MODE=ANTHROPIC ...

# INTERNAL 모드 — Playbook 응답만 사용, 없으면 fallback 문자열
VOICEBOT_LLM_MODE=INTERNAL ...

# HYBRID 모드 (기본) — Playbook hit 시 Playbook, 아니면 Claude
VOICEBOT_LLM_MODE=HYBRID ...
```

모드별 로그 비교:

| 모드 | INTENT 로그 | PLAYBOOK 로그 | Claude 호출 |
|---|---|---|---|
| ANTHROPIC | 없음 | 없음 | 항상 |
| INTERNAL | 있음 | 있음 | 없음 (fallback 문자열) |
| HYBRID | 있음 | 있음 | Playbook miss 시만 |

---

### 빠른 재기동 스크립트

코드 수정 후 반복 테스트할 때 사용:

```bash
# Spring Boot 재기동 (기존 프로세스 종료 후 재시작)
kill $(lsof -ti:8080) 2>/dev/null

MONGO_IP=$(docker inspect voicebot-mongodb --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
set -a && source .env && set +a

MONGODB_URI=mongodb://${MONGO_IP}:27017/voicebot \
VOICEBOT_LLM_MODE=HYBRID \
nohup mvn spring-boot:run -Dspring-boot.run.profiles=real > /tmp/real-app.log 2>&1 &

until grep -q "Started VoicebotApplication\|APPLICATION FAILED" /tmp/real-app.log; do sleep 2; done
echo "재기동 완료"
```

---

## 6. 소스 분석

→ **[SOURCE-ANALYSIS.md](SOURCE-ANALYSIS.md)** 참조

---

## 7. logback으로 로그 파일 자동 저장

현재는 `nohup ... > app-real.log` 처럼 셸 리다이렉트로 로그를 남기고 있다.
**logback 설정**을 추가하면 Spring Boot가 자동으로 파일에 로그를 남기므로 리다이렉트 없이 `mvn spring-boot:run`만 실행해도 된다.

### 설정 방법

`src/main/resources/logback-spring.xml` 파일을 생성한다:

```xml
<configuration>
  <!-- 콘솔 출력 -->
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- 파일 출력 (날짜별 롤링) -->
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory> <!-- 7일치만 보관 -->
    </rollingPolicy>
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="CONSOLE" />
    <appender-ref ref="FILE" />
  </root>
</configuration>
```

### 각 항목 설명

**CONSOLE / FILE — appender**

로그를 어디에 출력할지 정의하는 단위다. CONSOLE은 터미널, FILE은 파일에 출력한다.
두 appender를 `<root>`에 모두 등록하면 동시에 출력된다.

**`<encoder>`**

logback은 로그를 내부적으로 객체로 들고 있다. `<encoder>`는 그 객체를 실제 텍스트로 변환하는 방법을 정의한다. `<pattern>`은 변환 형식이고, `<encoder>`는 그것을 담는 그릇이다.

**`<pattern>` 주요 항목**

| 패턴 | 출력 예시 | 설명 |
|---|---|---|
| `%d{HH:mm:ss.SSS}` | `14:23:01.123` | 시각 |
| `[%thread]` | `[main]` | 스레드명 |
| `%-5level` | `INFO ` | 레벨을 5자리로 왼쪽 정렬 (짧으면 공백 채움) |
| `%logger{36}` | `c.v.s.stt.RtzrWebSocket` | 클래스명을 36자 이내로 축약 |
| `%msg%n` | `[STT-RTZR] 연결됨` + 줄바꿈 | 실제 메시지 |

**파일 경로**

경로를 `logs/app.log`로 지정하면 `mvn spring-boot:run`을 실행한 디렉토리(프로젝트 루트) 기준으로 `logs/` 디렉토리가 생성된다. 디렉토리가 없어도 logback이 자동 생성한다.

```
/workspaces/voicebot-js/
└── logs/
    ├── app.log              ← 오늘 로그 (현재 기록 중)
    └── app.2026-06-03.log   ← 자정에 롤링된 이전 날 로그
```

**`<root level="INFO">`**

INFO 이상(INFO/WARN/ERROR)만 기록하고 DEBUG는 무시한다.
단, `application.yml`의 `logging.level` 설정이 우선하므로:

```yaml
logging:
  level:
    com.voicebot: DEBUG   # ← 이 패키지는 logback root 설정을 덮어씀
```

**소스 변경 필요 여부**

없다. `src/main/resources/logback-spring.xml` 파일만 추가하면 Spring Boot가 자동 감지한다. `pom.xml` 의존성 추가도 불필요하다 (Spring Boot Starter에 logback 내장).

설정 후에는 리다이렉트 없이 실행해도 `logs/app.log`에 자동으로 기록된다:

```bash
SPRING_PROFILES_ACTIVE=real mvn spring-boot:run
# → logs/app.log 에 자동 저장
```

> **적용 완료** — `src/main/resources/logback-spring.xml` 파일이 존재하며 현재 활성화되어 있다. `.gitignore`에 `logs/` 추가 필요.

---

## 8. 자주 확인하는 것

### RTZR 토큰 상태

```bash
# 토큰 발급 시간 및 만료 로그
grep "RTZR.*토큰" app-real.log
# [STT-RTZR] 토큰 발급 완료 expire_at=1780421420
# expire_at = Unix timestamp (6시간 후 만료)
```

### Google TTS 자격증명

```bash
# 자격증명 파일 존재 확인
ls -la env/

# 자격증명 로드 로그
grep "TTS-GOOGLE" app-real.log
```

### 시뮬레이터 시나리오 확인

```bash
# STT 시뮬레이터 시나리오 목록
curl http://stt-simulator:8081/scenarios

# LLM 시뮬레이터 응답 직접 테스트
curl -X POST http://llm-simulator:8082/chat \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"안녕"}]}'
```

---

## 9. 환경변수 관리

### 현재 방식 — `set -a && source .env && set +a`

```bash
set -a && source .env && set +a
mvn spring-boot:run
```

보안 때문이 아니라 **환경변수를 자식 프로세스에 전달하기 위한 기술적 이유**다.

`set -a` 없이 `source .env`만 하면 현재 셸에만 적용되고, `mvn`(자식 프로세스)에는 전달되지 않는다.

**보안 수준**

| 보호 | 여부 |
|---|---|
| git 커밋 차단 | ✅ `.gitignore`에 포함 |
| 파일시스템 접근 차단 | ❌ 파일을 볼 수 있는 누구나 읽을 수 있음 |

개발 환경에서는 이 정도면 충분하다. 진짜 보안이 필요한 건 배포 환경이다.

---

### 개발 환경 대안 — direnv

디렉토리에 들어가면 자동으로 `.env`를 로드하고, 나가면 자동 해제한다.
매번 `set -a && source .env`를 입력할 필요가 없다.

```bash
# 설치
sudo apt-get install -y direnv

# ~/.bashrc에 hook 추가 (최초 1회)
echo 'eval "$(direnv hook bash)"' >> ~/.bashrc
source ~/.bashrc

# 프로젝트 루트에 .envrc 파일 생성
echo 'dotenv' > /workspaces/voicebot-js/.envrc

# 신뢰 허용 (최초 1회)
direnv allow
```

이후 디렉토리 진입 시 자동 적용된다.

```bash
cd /workspaces/voicebot-js
# direnv: loading .env  ← 자동 로드
mvn spring-boot:run     # 그냥 실행 가능
```

---

### 배포 환경 — 플랫폼이 주입

배포 시에는 `.env` 파일 자체를 서버에 올리지 않는다.
비밀값은 코드/파일이 아닌 플랫폼이 주입하는 것이 원칙이다.

| 환경 | 방법 |
|---|---|
| Docker | `docker run --env-file .env` 또는 `-e KEY=VALUE` |
| Kubernetes | `Secret` 오브젝트로 주입 |
| AWS | Parameter Store / Secrets Manager |
| GitHub Actions | Repository Secrets |

---

## 10. 다음 개선 예정 항목

| 항목 | 현재 | 목표 | 방법 |
|---|---|---|---|
| LLM 응답시간 | 4,849ms | < 3,000ms | max_tokens 축소 + 짧은 응답 system 프롬프트 |
| TTS 응답시간 | 3,226ms | < 1,000ms | LLM 응답 단축 시 자동 개선 |
| 전체 응답시간 | 8,532ms | < 5,000ms | 위 두 항목 개선 시 달성 |

개선 대상 파일:
- `src/main/java/com/voicebot/service/llm/ClaudeApiLlmService.java` — `max_tokens`, `system` 프롬프트
