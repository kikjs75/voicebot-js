# C++ WebSocket 서버 테스트 가이드

C++ WebSocket 서버(`cpp-ws-server/`)를 빌드하고 실행하는 절차.
Java 버전과 병행 운영하며 포트(8080 vs 9090)로 구분한다.

전체 설계 → @docs/CPP-WEBSOCKET-PLAN.md

---

## 포트 구성

| 서비스 | 포트 | 비고 |
|---|---|---|
| Spring Boot (Java) | 8080 | LLM/TTS REST 제공, WebSocket Java 버전 |
| C++ WebSocket 서버 | 9090 | WebSocket C++ 버전 |
| React (Vite) | 5173 | 프론트엔드 |

> `.devcontainer/devcontainer.json`에 `"forwardPorts": [8080, 5173, 9090]` 설정 완료.

---

## 1. C++ 서버 빌드

```bash
cd /workspaces/voicebot-js/cpp-ws-server

# 1단계: 빌드 디렉토리 생성 및 cmake 구성 (최초 1회)
cmake -B build

# 2단계: 컴파일
cmake --build build

# 실행 파일 확인
ls build/voicebot-cpp
```

### 두 단계로 나누는 이유

**1단계 `cmake -B build` — 준비**

`CMakeLists.txt`(설계도)를 읽고 실제 컴파일에 필요한 모든 것을 준비한다.

- 라이브러리 설치 여부 확인 (`find_package`)
- 어떤 파일을 어떤 순서로 컴파일할지 계획 수립
- `build/` 폴더에 Makefile(공사 지시서) 생성

라이브러리 미설치 같은 문제가 이 단계에서 발견된다.

**2단계 `cmake --build build` — 컴파일**

1단계에서 만들어진 Makefile을 보고 실제로 `.cpp` 파일들을 컴파일한다.

- `.cpp` → `.o` (중간 파일) 변환
- `.o` 파일들을 합쳐서 `voicebot-cpp` 실행 파일 생성

**1단계는 최초 1회만 실행하면 된다.** 코드 수정 후 재빌드할 때는 2단계만 실행한다.
cmake가 변경된 `.cpp` 파일만 골라서 다시 컴파일하므로 전체를 처음부터 다시 하지 않아 빠르다.

1단계를 다시 해야 하는 경우: 라이브러리 추가/제거, `CMakeLists.txt` 수정 등 설계 자체가 바뀔 때.

### `--build` 와 `build` 구분

```bash
cmake --build build
        ↑         ↑
     옵션        폴더명
```

- `--build` — cmake에게 "컴파일 모드로 동작해라"는 옵션. 없으면 컴파일하지 않는다.
- `build` — 1단계에서 준비물을 넣어둔 폴더 이름. 폴더명을 자유롭게 바꿀 수 있다.

### 코드 수정 후 재빌드

```bash
# cpp-ws-server/ 안에서
cmake --build build

# 다른 디렉토리에서 실행할 때는 절대 경로 사용
cmake --build /workspaces/voicebot-js/cpp-ws-server/build
```

절대 경로는 현재 위치에 상관없이 항상 같은 결과가 보장된다.

---

## 2. 실행 순서

C++ 서버는 LLM/TTS를 Spring Boot에 위임하므로 **Spring Boot가 먼저 실행 중이어야 한다.**

```
① Docker 인프라 기동
② Spring Boot 기동 (real profile)
③ C++ 서버 기동
④ Frontend 기동 (C++ 버전으로 전환)
```

---

### ① Docker 인프라 기동

```bash
# MariaDB + Redis (시뮬레이터 불필요 — real profile 사용)
docker compose up mariadb redis -d

# 상태 확인
docker ps --format "table {{.Names}}\t{{.Status}}"
```

---

### ② Spring Boot 기동 (real profile)

C++ 서버가 `POST /api/cti/llm/chat`, `POST /api/cti/tts/synthesize`를 호출하므로
Spring Boot는 반드시 **real profile**로 실행해야 한다.

```bash
cd /workspaces/voicebot-js
set -a && source .env && set +a

# 포그라운드
SPRING_PROFILES_ACTIVE=real mvn spring-boot:run

# 백그라운드 (logback이 logs/app.log 에 자동 저장)
nohup env SPRING_PROFILES_ACTIVE=real mvn spring-boot:run > /dev/null 2>&1 &
echo "PID: $!"
until grep -q "Started VoicebotApplication" logs/app.log; do sleep 2; done
echo "Spring Boot 기동 완료"
```

기동 확인:

```bash
lsof -ti:8080 && echo "실행중" || echo "중지됨"
```

---

### ③ C++ 서버 기동

```bash
cd /workspaces/voicebot-js
set -a && source .env && set +a

# 포그라운드 (로그 터미널에 직접 출력)
PORT=9090 \
SPRING_URL=http://localhost:8080 \
RTZR_CLIENT_ID=$RTZR_CLIENT_ID \
RTZR_CLIENT_SECRET=$RTZR_CLIENT_SECRET \
./cpp-ws-server/build/voicebot-cpp

# 백그라운드
nohup env \
  PORT=9090 \
  SPRING_URL=http://localhost:8080 \
  RTZR_CLIENT_ID=$RTZR_CLIENT_ID \
  RTZR_CLIENT_SECRET=$RTZR_CLIENT_SECRET \
  ./cpp-ws-server/build/voicebot-cpp > /tmp/cpp-ws.log 2>&1 &
echo "PID: $!"
```

기동 확인 로그:

```
[STT-RTZR] 토큰 발급 완료 expire_at=...
[MAIN] 서버 시작 port=9090 spring=http://localhost:8080
```

기동 확인 명령:

```bash
lsof -ti:9090 && echo "실행중" || echo "중지됨"
```

---

### ④ Frontend 기동 — C++ 버전으로 전환

환경변수 `VITE_WS_URL`만 바꾸면 Java ↔ C++ 전환이 된다.

```bash
cd /workspaces/voicebot-js/frontend

# C++ 버전 (포트 9090)
VITE_WS_URL=ws://localhost:9090/ws/cti npm run dev

# 백그라운드
VITE_WS_URL=ws://localhost:9090/ws/cti nohup npm run dev > /tmp/vite.log 2>&1 &
until grep -q "Local:" /tmp/vite.log; do sleep 1; done
echo "Vite 기동 완료 → http://localhost:5173"
```

브라우저: `http://localhost:5173`

---

## 3. Java ↔ C++ 전환

코드 변경 없이 Vite 실행 시 환경변수만 바꾼다.

```bash
# Java 버전 (기본값)
npm run dev
# 또는
VITE_WS_URL=ws://localhost:8080/ws/cti npm run dev

# C++ 버전
VITE_WS_URL=ws://localhost:9090/ws/cti npm run dev
```

---

## 4. 수동 테스트

### wscat 설치 (최초 1회)

```bash
sudo npm install -g wscat
```

### WebSocket 연결 테스트

```bash
# C++ 서버 연결
wscat -c ws://localhost:9090/ws/cti

# 연결 후 CALL_START 이벤트 전송
{"type":"CTI_EVENT","event":"CALL_START","callerNumber":"010-1234-5678","receiverNumber":"1588-0000"}

# 통화 종료
{"type":"CTI_EVENT","event":"CALL_END"}
```

### 브라우저 테스트 절차

1. `http://localhost:5173` 접속
2. 우측 상단 WS 상태 `🟢 WS 연결됨` 확인
3. 통화 정보 입력 후 `📞 전화 걸기`
4. 마이크 권한 허용
5. 한국어로 말하기
6. 우측 로그 패널에서 결과 확인

| 아이콘 | 항목 | 확인 내용 |
|---|---|---|
| 🎤 STT | 음성 인식 결과 | RTZR C++ 직접 연결 결과 |
| 🧠 LLM | Claude 응답 | Spring `/api/cti/llm/chat` 경유 |
| 🔊 TTS | 음성 출력 텍스트 | Spring `/api/cti/tts/synthesize` 경유 |

---

## 5. 로그 확인

### C++ 서버 로그

```bash
# 백그라운드 실행 시
tail -f /tmp/cpp-ws.log

# 파이프라인 흐름만 필터링
tail -f /tmp/cpp-ws.log | grep -E "\[CTI\]|\[STT-RTZR\]|ERROR"
```

정상 흐름 예시:

```
[STT-RTZR] 연결됨 callId=CTI-S1
[CTI] 연결됨 sessionId=S1 callId=CTI-S1
[CTI] STT 최종 callId=CTI-S1 text="안녕하세요 요금 문의드리려고요"
[STT-RTZR] 연결됨 callId=CTI-S1   ← 다음 발화 재연결
[CTI] 다음 발화 대기 callId=CTI-S1
```

### Spring Boot 로그 (LLM/TTS REST 수신 확인)

```bash
tail -f /workspaces/voicebot-js/logs/app.log | grep -E "\[CTI-REST\]|ERROR"
```

```
[CTI-REST] LLM 요청 messages=3
[CTI-REST] TTS 요청 text=무엇을 도와드릴까요?
```

---

## 6. 서버 종료

```bash
# C++ 서버
kill $(lsof -ti:9090)

# Spring Boot
kill $(lsof -ti:8080)

# Vite
kill $(lsof -ti:5173)
```

---

## 7. 로그 시각 불일치 문제 (타임존)

### 증상

로그 시각이 현재 한국 시각보다 9시간 느리게 표시된다.

```
실제 한국 시각:  2026-06-05 06:28:37 KST
로그에 찍힌 시각: 2026-06-04 21:28:37   ← 9시간 차이
```

### 원인

Dockerfile에 `ENV TZ=Asia/Seoul`이 있지만, 이것은 Docker 이미지의 환경변수만 설정한다.
실제 시스템 타임존 파일(`/etc/localtime`, `/etc/timezone`)은 UTC 그대로여서
devcontainer 셸 세션과 JVM 모두 UTC 기준으로 동작한다.

```bash
# 확인 명령
cat /etc/timezone       # Etc/UTC  ← UTC로 고정되어 있음
echo $TZ                # (비어있음) ← ENV가 셸에 전달 안 됨
```

### 수정 내용 (적용 완료)

**① Dockerfile — 시스템 타임존 파일 교체** (`.devcontainer/Dockerfile`)

```dockerfile
RUN apt-get install -y locales tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
    && echo "Asia/Seoul" > /etc/timezone
```

`ENV TZ=Asia/Seoul`만으로는 부족하고, 시스템 파일 자체를 KST로 교체해야 모든 프로세스에 적용된다.

**② logback 패턴 — 타임존 명시** (`src/main/resources/logback-spring.xml`)

```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS, Asia/Seoul} ...</pattern>
```

시스템 설정과 무관하게 logback이 항상 KST로 출력하도록 이중 보장한다.

### 적용 방법

Dockerfile 수정은 **devcontainer 재빌드** 후 적용된다.

```
VSCode: Ctrl+Shift+P → "Dev Containers: Rebuild Container"
```

logback 패턴 수정은 **Spring Boot 재시작**만으로 적용된다.

### 재빌드 후 확인 방법

```bash
# 1. 시스템 타임존
date
# 기대값: 2026. 06. 05. (금) 06:xx:xx KST

cat /etc/timezone
# 기대값: Asia/Seoul

# 2. TZ 환경변수
echo $TZ
# 기대값: Asia/Seoul

# 3. JVM 타임존
java -XshowSettings:all -version 2>&1 | grep timezone
# 기대값: user.timezone = Asia/Seoul

# 4. Spring Boot 로그 시각
tail -3 /workspaces/voicebot-js/logs/app.log
# 기대값: 2026-06-05 06:xx:xx.xxx [main] INFO ...
#                    ↑ KST 시각
```

---

## 8. 자주 발생하는 오류

| 오류 | 원인 | 해결 |
|---|---|---|
| `RTZR 토큰 발급 실패` | `RTZR_CLIENT_ID` / `RTZR_CLIENT_SECRET` 미설정 | `.env` 확인 후 재실행 |
| `Connection refused` (9090) | C++ 서버 미실행 | `lsof -ti:9090` 확인 |
| LLM/TTS 응답 없음 | Spring Boot 미실행 또는 sim profile | Spring을 real profile로 재기동 |
| `cmake: not found` | C++ 빌드 환경 미설치 | devcontainer 재빌드 |
| `find_package` 오류 | cmake 버전 문제 | `cmake --version` 확인 (3.16 이상 필요) |
