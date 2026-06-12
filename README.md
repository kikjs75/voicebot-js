# voicebot-js

콜센터 자동화를 위한 음성봇 백엔드 서버. STT → LLM → TTS 파이프라인.

---

## 문서 목차

### 시작하기

| 문서 | 내용 |
|---|---|
| [SETUP.md](docs/SETUP.md) | 개발환경 세팅 (최초 1회 — DevPod, IntelliJ, .env 설정) |
| [dev-environment.md](docs/dev-environment.md) | devcontainer / DevPod / IDE 선택 가이드 |

---

### 아키텍처 & 설계

| 문서 | 내용 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 전체 구성도, 콜 처리 흐름, Profile 구조 |
| [CTI-WEBSOCKET.md](docs/CTI-WEBSOCKET.md) | WebSocket(`/ws/cti`) 설계 — Sinks 브리지, 세션 생명주기 |
| [LLM-MODE-DESIGN.md](docs/LLM-MODE-DESIGN.md) | LLM 3가지 모드 설계 (ANTHROPIC / INTERNAL / HYBRID) + MongoDB Playbook |
| [CPP-WEBSOCKET-PLAN.md](docs/CPP-WEBSOCKET-PLAN.md) | C++ WebSocket 서버 구현 계획 |
| [TTS-AUDIO-PLAYBACK-PLAN.md](docs/TTS-AUDIO-PLAYBACK-PLAN.md) | TTS 오디오 브라우저 재생 구현 계획 |
| [PLAN.md](docs/PLAN.md) | 전체 구현 계획 및 단계별 진행 상태 |

---

### 개발 가이드

| 문서 | 내용 |
|---|---|
| [SOURCE-ANALYSIS.md](docs/SOURCE-ANALYSIS.md) | 소스 핵심 흐름 분석 (CallHandler, STT/LLM/TTS 구현체, WebSocket) |
| [SIMULATOR.md](docs/SIMULATOR.md) | 시뮬레이터 4종 실행 및 API 스펙 |
| [FRONTEND.md](docs/FRONTEND.md) | React 프론트엔드(`frontend/`) 세팅 및 실행 |
| [EXTERNAL-API.md](docs/EXTERNAL-API.md) | 외부 API 인증 방식 — RTZR STT, Claude, Google TTS |
| [GCP-TTS-SETUP.md](docs/GCP-TTS-SETUP.md) | GCP 서비스 계정 키 발급 절차 |

---

### 테스트

| 문서 | 내용 |
|---|---|
| [TESTING-GUIDE.md](docs/TESTING-GUIDE.md) | 환경 시작, 수동 테스트, 로그 확인, CTI WebSocket 테스트 절차 |
| [CPP-WS-TESTING-GUIDE.md](docs/CPP-WS-TESTING-GUIDE.md) | C++ WebSocket 서버 빌드 및 테스트 |
| [E2E-TEST-REAL.md](docs/E2E-TEST-REAL.md) | E2E 테스트 결과 — real profile |
| [E2E-TEST-SIM.md](docs/E2E-TEST-SIM.md) | E2E 테스트 결과 — sim profile |

---

### C++ 서버

| 문서 | 내용 |
|---|---|
| [CPP-WS-SERVER-ANALYSIS.md](docs/CPP-WS-SERVER-ANALYSIS.md) | cpp-ws-server 소스 분석 (Java 비유 포함) |
| [CPP-WEBSOCKET-PLAN.md](docs/CPP-WEBSOCKET-PLAN.md) | C++ WebSocket 서버 구현 계획 |
| [CPP-WS-TESTING-GUIDE.md](docs/CPP-WS-TESTING-GUIDE.md) | C++ WebSocket 서버 빌드 및 테스트 |

---

### 검토 & 결정

| 문서 | 내용 |
|---|---|
| [TTS-VENDOR-REVIEW.md](docs/TTS-VENDOR-REVIEW.md) | TTS 업체 비교 검토 및 선정 근거 |
| [BACKLOG.md](docs/BACKLOG.md) | 개선사항 / 버그 / 기술부채 목록 |

---

### 학습 노트

| 문서 | 내용 |
|---|---|
| [C++.md](docs/C++.md) | C++ 문법 및 단축키 학습 노트 |
| [C++ Boost.Asio.md](docs/C++%20Boost.Asio.md) | Boost.Asio 학습 노트 |
