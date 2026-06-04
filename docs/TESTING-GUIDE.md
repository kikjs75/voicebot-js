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

# 백그라운드 (로그 파일에 저장)
nohup env SPRING_PROFILES_ACTIVE=real mvn spring-boot:run > app-real.log 2>&1 &
echo "PID: $!"
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

### Vite 프론트엔드 기동 (CTI WebSocket 테스트 시)

Spring Boot가 실행 중인 상태에서 **별도 터미널**로 실행한다.

```bash
cd /workspaces/voicebot-js/frontend
npm run dev
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

## 5. 소스 분석 포인트

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
  │    wss://openapi.vito.ai/v1/transcribe:streaming?sample_rate=16000&...
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

## 6. logback으로 로그 파일 자동 저장

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

> **현재 미적용** — 당장 필요한 경우 위 파일을 추가하면 된다. 적용 시 `.gitignore`에 `logs/` 추가 필요.

---

## 7. 자주 확인하는 것

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

## 8. 다음 개선 예정 항목

| 항목 | 현재 | 목표 | 방법 |
|---|---|---|---|
| LLM 응답시간 | 4,849ms | < 3,000ms | max_tokens 축소 + 짧은 응답 system 프롬프트 |
| TTS 응답시간 | 3,226ms | < 1,000ms | LLM 응답 단축 시 자동 개선 |
| 전체 응답시간 | 8,532ms | < 5,000ms | 위 두 항목 개선 시 달성 |

개선 대상 파일:
- `src/main/java/com/voicebot/service/llm/ClaudeApiLlmService.java` — `max_tokens`, `system` 프롬프트
