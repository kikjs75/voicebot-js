# 테스트 및 소스 분석 가이드

직접 테스트하고 소스를 분석할 때 참고하는 문서.

---

## 1. 환경 시작

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
SPRING_PROFILES_ACTIVE=real mvn spring-boot:run
```

기동 확인 로그 (real profile):

```bash
# 백그라운드 실행 시 로그 확인
tail -f /tmp/spring-real.log | grep -E "Started|RTZR.*토큰|TTS-GOOGLE|ERROR"
```

```
[STT-RTZR] 토큰 발급 완료 expire_at=...
[TTS-GOOGLE] 서비스 계정 자격증명 로드 완료
Started VoicebotApplication in 2.x seconds
```

---

## 2. 수동 테스트

### 테스트용 한국어 음성 생성 (공통)

sim/real 모두 동일한 방법으로 테스트 음성을 생성한다.  
> sim profile은 STT 시뮬레이터가 오디오 내용을 무시하고 시나리오 파일에서 텍스트를 반환하므로 아무 음성이나 가능하지만, real profile은 RTZR이 실제 음성을 인식해야 하므로 반드시 실제 한국어 음성이 필요하다.

```bash
# python3-cryptography 설치 (최초 1회)
apt-get install -y python3-cryptography

# Google TTS로 한국어 음성 생성 (8kHz LINEAR16 PCM)
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
    'audioConfig': {'audioEncoding': 'LINEAR16', 'sampleRateHertz': 8000}
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
  -H "X-Call-Id: SESSION-TEST-001" \
  --data-binary @/tmp/korean-test.pcm -o /dev/null -s -w "%{http_code}\n"

# 두 번째 발화 (같은 callId — 이전 대화 이력 포함)
curl -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: SESSION-TEST-001" \
  --data-binary @/tmp/korean-test.pcm -o /dev/null -s -w "%{http_code}\n"

# Redis에서 대화 이력 확인
docker exec voicebot-redis redis-cli GET "call:session:SESSION-TEST-001"
```

---

## 3. 로그 확인

### PERF 로그 실시간 확인

```bash
# Spring Boot 실행 중인 터미널에서 직접 확인하거나
# 백그라운드 실행 시:
tail -f /tmp/spring-real.log | grep -E "PERF|STT-RTZR.*final|ERROR"
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

## 4. 소스 분석 포인트

### 핵심 파일 읽는 순서

```
1. src/main/java/com/voicebot/call/CallController.java   ← HTTP 진입점
2. src/main/java/com/voicebot/call/CallHandler.java      ← 파이프라인 오케스트레이션
3. src/main/java/com/voicebot/call/CallSession.java      ← 세션 모델
4. src/main/java/com/voicebot/service/stt/SttService.java          ← STT 인터페이스
5. src/main/java/com/voicebot/service/stt/RtzrWebSocketSttService.java  ← STT 구현
6. src/main/java/com/voicebot/service/llm/ClaudeApiLlmService.java       ← LLM 구현
7. src/main/java/com/voicebot/service/tts/GoogleCloudTtsService.java     ← TTS 구현
```

### CallHandler — 파이프라인 흐름

```
process(audioData, callId)
  │
  ├─ 1. sttService.recognize(Flux.just(audioData), callId)
  │       .filter(isFinal)            ← final:true 만 통과
  │       .timeout(30초)              ← 무음/무응답 타임아웃
  │       .onErrorReturn("")          ← 타임아웃 시 빈 문자열
  │
  ├─ 2. (빈 결과면) → "죄송합니다, 다시 말씀해 주세요" TTS 즉시 반환
  │
  ├─ 3. Redis에서 세션 로드 (또는 신규 생성)
  │
  ├─ 4. llmService.chat(messages, callId)    ← 대화 이력 포함 LLM 호출
  │
  ├─ 5. Redis에 세션 저장 (TTL 1시간)
  │
  ├─ 6. ttsService.synthesize(llmResponse, callId)
  │
  └─ 7. CallRecord DB 저장 + byte[] 반환
```

### RtzrWebSocketSttService — WebSocket 흐름

```
recognize(audioStream, callId)
  │
  ├─ Flux.create() — OkHttp WebSocket 콜백 → Reactor 브릿지
  │
  ├─ WebSocket 연결
  │    Authorization: Bearer {token}
  │    wss://openapi.vito.ai/v1/transcribe:streaming?sample_rate=8000&...
  │
  ├─ audioStream 구독 → binary frame 전송
  │
  ├─ 오디오 스트림 완료 → "EOS" 전송
  │
  ├─ onMessage: JSON 파싱 → SttResult(text, isFinal) emit
  │    final=false → 중간 결과
  │    final=true  → 확정 결과 → WebSocket close
  │
  └─ onClosed → Flux complete
```

### 설정 파일 구조

```
application.yml          ← 공통 설정 (DB, Redis, 로그레벨)
application-sim.yml      ← sim profile (DB/Redis/STT/LLM/TTS → 시뮬레이터)
application-real.yml     ← real profile (RTZR + Claude + Google TTS)
```

---

## 5. 자주 확인하는 것

### RTZR 토큰 상태

```bash
# 토큰 발급 시간 및 만료 로그
grep "RTZR.*토큰" /tmp/spring-real.log
# [STT-RTZR] 토큰 발급 완료 expire_at=1780421420
# expire_at = Unix timestamp (6시간 후 만료)
```

### Google TTS 자격증명

```bash
# 자격증명 파일 존재 확인
ls -la env/

# 자격증명 로드 로그
grep "TTS-GOOGLE" /tmp/spring-real.log
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

## 6. 다음 개선 예정 항목

| 항목 | 현재 | 목표 | 방법 |
|---|---|---|---|
| LLM 응답시간 | 4,849ms | < 3,000ms | max_tokens 축소 + 짧은 응답 system 프롬프트 |
| TTS 응답시간 | 3,226ms | < 1,000ms | LLM 응답 단축 시 자동 개선 |
| 전체 응답시간 | 8,532ms | < 5,000ms | 위 두 항목 개선 시 달성 |

개선 대상 파일:
- `src/main/java/com/voicebot/service/llm/ClaudeApiLlmService.java` — `max_tokens`, `system` 프롬프트
