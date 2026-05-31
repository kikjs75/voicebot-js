# 시뮬레이터 가이드

## 시뮬레이터 구성

| 서비스 | 포트 | 역할 |
|---|---|---|
| stt-simulator | 8081 | 오디오 → 텍스트 변환 모킹 |
| llm-simulator | 8082 | 규칙 기반 응답 생성 모킹 |
| tts-simulator | 8083 | 텍스트 → 오디오 변환 모킹 |
| call-simulator | 8084/8085 | 콜 발신/수신 UI |

## 실행

```bash
# 전체 (인프라 + 시뮬레이터)
docker compose -f docker-compose.yml -f docker-compose.sim.yml up -d

# 인프라만
docker compose up postgres redis -d

# 시뮬레이터만
docker compose -f docker-compose.sim.yml up -d
```

## STT 시뮬레이터 API

```
POST /recognize
Header: X-Call-Id: {callId}
Body: binary (audio bytes)

Response: 200 OK
Body: "안녕하세요, 문의사항이 있습니다."
```

시나리오 파일 (`simulators/stt/scenarios/*.txt`) 에 텍스트를 작성하면
순서대로 응답합니다.

## LLM 시뮬레이터 API

```
POST /chat
Header: X-Call-Id: {callId}
Body: { "messages": [{"role": "user", "content": "..."}] }

Response: 200 OK
Body: "네, 무엇을 도와드릴까요?"
```

시나리오 파일 (`simulators/llm/scenarios/*.json`) 에 키워드-응답 매핑을 정의합니다.

## TTS 시뮬레이터 API

```
POST /synthesize
Header: X-Call-Id: {callId}
Body: { "text": "안녕하세요" }

Response: 200 OK
Content-Type: audio/mpeg
Body: binary (mp3 bytes)
```

텍스트를 그대로 파일명으로 사용해 미리 준비된 wav를 반환하거나,
없으면 무음 mp3를 반환합니다.

## Call 시뮬레이터

웹 UI: http://localhost:8085

- 콜 발신: 전화번호 입력 → Spring Boot `/call/incoming` 호출
- 시나리오 선택: 미리 정의된 대화 시나리오 자동 재생
- 콜 이력: 처리된 콜 목록 조회
