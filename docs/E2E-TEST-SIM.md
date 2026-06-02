# E2E 테스트 결과 — sim profile

**테스트 일자**: 2026-06-02  
**Profile**: `sim`  
**환경**: devcontainer + Docker Compose (voicebot-net)

---

## 사전 준비

### 인프라 + 시뮬레이터 기동

```bash
# 네트워크 생성 (최초 1회)
docker network create voicebot-net

# MariaDB + Redis 기동
docker compose -f docker-compose.yml up mariadb redis -d

# 시뮬레이터 4종 빌드 + 기동
docker compose -f docker-compose.yml -f docker-compose.sim.yml \
  up stt-simulator llm-simulator tts-simulator call-simulator -d --build
```

### 시뮬레이터 헬스체크

```bash
curl -s http://stt-simulator:8081/health
curl -s http://llm-simulator:8082/health
curl -s http://tts-simulator:8083/health
```

결과:
```
{"status":"ok","service":"stt-simulator"}
{"status":"ok","service":"llm-simulator"}
{"status":"ok","service":"tts-simulator"}
```

### Spring Boot 기동

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=sim
```

기동 확인 로그:
```
Started VoicebotApplication in 1.672 seconds
```

---

## 검증 1 — 기본 콜 흐름

### 명령어

```bash
# 더미 PCM 오디오 생성 (100ms @ 8kHz 16bit = 1,600 bytes)
dd if=/dev/urandom of=/tmp/dummy.pcm bs=1600 count=1

# /call/incoming 호출
curl -s -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: E2E-TEST-001" \
  --data-binary @/tmp/dummy.pcm \
  -o /tmp/response.wav \
  -w "HTTP %{http_code} | response: %{size_download} bytes\n"
```

### 결과

```
HTTP 200 | response: 124 bytes
```

✅ **통과** — 200 OK, TTS 오디오(WAV) 반환

---

## 검증 2 — PERF 로그

### 명령어

```bash
grep -E "PERF" /tmp/spring-boot.log
```

### 결과

```
[STT-PERF] callId=E2E-TEST-001 elapsed=180ms
[LLM-PERF] callId=E2E-TEST-001 elapsed=44ms
[TTS-PERF] callId=E2E-TEST-001 elapsed=11ms
[CALL-PERF] callId=E2E-TEST-001 elapsed=347ms
```

| 단계 | 목표 | 실측 | 판정 |
|---|---|---|---|
| STT | < 1,000ms | 180ms | ✅ |
| LLM | < 3,000ms | 44ms | ✅ |
| TTS | < 1,000ms | 11ms | ✅ |
| 전체 | < 5,000ms | 347ms | ✅ |

✅ **통과** — 모든 단계 목표치 이내

---

## 검증 3 — DB 저장 (CallRecord)

### 명령어

```bash
docker exec voicebot-mariadb mariadb -u voicebot -pvoicebot1234 voicebot \
  -e "SELECT call_id, stt_text, llm_response, \
      stt_elapsed_ms, llm_elapsed_ms, tts_elapsed_ms, total_elapsed_ms \
      FROM call_records;"
```

### 결과

| 컬럼 | 값 |
|---|---|
| call_id | E2E-TEST-001 |
| stt_text | 안녕하세요, 요금 문의드리려고요. |
| llm_response | 요금 관련 문의시 고객센터 홈페이지(www.example.com)에서 상세 청구 내역을 확인하실 수 있습니다. 이번 달 청구서에 대해 구체적으로 어떤 부분이 궁금하신가요? |
| stt_elapsed_ms | 180 |
| llm_elapsed_ms | 44 |
| tts_elapsed_ms | 11 |
| total_elapsed_ms | 347 |

✅ **통과** — CallRecord 정상 저장 확인

---

## 검증 4 — Redis 세션

### 명령어

```bash
# 세션 키 목록
docker exec voicebot-redis redis-cli KEYS "call:session:*"

# 세션 내용 조회
docker exec voicebot-redis redis-cli GET "call:session:E2E-TEST-001"
```

### 결과

```
call:session:E2E-TEST-001
```

```json
{
  "@class": "com.voicebot.call.CallSession",
  "callId": "E2E-TEST-001",
  "messages": [
    { "role": "user", "content": "안녕하세요, 요금 문의드리려고요." },
    { "role": "assistant", "content": "요금 관련 문의시 ..." }
  ],
  "state": "ACTIVE",
  "startedAt": ...
}
```

✅ **통과** — Redis에 콜 세션(대화 이력) 정상 저장 확인

---

## 검증 5 — 에러 케이스 (STT 시뮬레이터 다운)

### 명령어

```bash
# STT 시뮬레이터 강제 중지
docker stop voicebot-stt-sim

# 콜 요청
curl -s -X POST http://localhost:8080/call/incoming \
  -H "Content-Type: application/octet-stream" \
  -H "X-Call-Id: E2E-ERROR-001" \
  --data-binary @/tmp/dummy.pcm \
  -w "\nHTTP %{http_code}\n"

# 시뮬레이터 재시작
docker start voicebot-stt-sim
```

### 결과

```json
{"timestamp":"2026-06-02T06:28:21.185+00:00","status":500,"error":"Internal Server Error","path":"/call/incoming"}
HTTP 500
```

✅ **통과** — STT 장애 시 500 반환 확인

---

## 최종 결과

| 항목 | 결과 |
|---|---|
| 기본 콜 흐름 | ✅ 통과 |
| PERF 로그 | ✅ 통과 |
| DB 저장 | ✅ 통과 |
| Redis 세션 | ✅ 통과 |
| 에러 케이스 | ✅ 통과 |

**5/5 통과** — Phase 3 완료
