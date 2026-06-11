# cpp-ws-server

C++ Boost.Asio 기반 WebSocket 서버. RTZR STT → Spring LLM/TTS 파이프라인을 처리한다.

## 의존성

| 라이브러리 | 용도 |
|---|---|
| Boost.Asio | 비동기 네트워크 |
| OpenSSL | TLS |
| libcurl | HTTP 클라이언트 (Spring 연동) |
| nlohmann_json | JSON 파싱 |
| spdlog | 로깅 (FetchContent 자동 다운로드) |

### macOS

```bash
brew install boost openssl curl nlohmann-json cmake
```

### Ubuntu / devcontainer

```bash
apt-get install -y libboost-all-dev libssl-dev libcurl4-openssl-dev nlohmann-json3-dev cmake
```

---

## 빌드

```bash
cd cpp-ws-server
cmake -B build
cmake --build build -j$(nproc)
```

빌드 산출물: `build/voicebot-cpp`

---

## 환경변수 설정

```bash
cp cpp-ws-server/.env.example cpp-ws-server/.env
```

`.env`를 열어 `RTZR_CLIENT_ID`, `RTZR_CLIENT_SECRET` 값을 입력한다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `PORT` | `9090` | WebSocket 서버 포트 |
| `SPRING_URL` | `http://localhost:8080` | Spring Boot 서버 주소 |
| `RTZR_CLIENT_ID` | (필수) | RTZR 인증 ID |
| `RTZR_CLIENT_SECRET` | (필수) | RTZR 인증 Secret |
| `RTZR_SAMPLE_RATE` | `16000` | STT 샘플레이트 (Hz) |

---

## 실행

Spring Boot가 먼저 실행 중이어야 한다.

```bash
source cpp-ws-server/.env
./cpp-ws-server/build/voicebot-cpp
```

서버 기동 확인:

```
[MAIN] 서버 시작 port=9090 spring=http://localhost:8080
```

---

## 로그

실행 디렉토리 기준 `logs/` 에 날짜별 롤링 파일로 저장된다.

```
logs/voicebot-2024-01-15.log
```
