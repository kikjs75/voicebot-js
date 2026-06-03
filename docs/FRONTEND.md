# 프론트엔드 개발 가이드

## 개요

`frontend/` 는 CTI WebSocket 테스트를 위한 React 프로젝트다.
Spring Boot(`src/`)와 독립적으로 실행되며 Maven 빌드 대상이 아니다.

---

## 디렉토리 구조

```
voicebot-js/
├── src/                        ← Spring Boot (Maven 빌드 대상)
├── frontend/                   ← React + Vite (독립 실행)
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── App.jsx
│       └── CtiSimulator.jsx    ← reference/voicebot-demo에서 복사
└── reference/voicebot-demo/
    └── CtiSimulator.jsx        ← 참고 원본 (수정하지 않음)
```

---

## 최초 세팅

```bash
# 프로젝트 루트에서
cd /workspaces/voicebot-js
npm create vite@latest frontend -- --template react
cd frontend
npm install
```

`reference/voicebot-demo/CtiSimulator.jsx` 를 `frontend/src/` 에 복사한다.

```bash
cp reference/voicebot-demo/CtiSimulator.jsx frontend/src/CtiSimulator.jsx
```

`frontend/src/App.jsx` 에서 컴포넌트를 연결한다.

```jsx
import CtiSimulator from './CtiSimulator'

export default function App() {
  return <CtiSimulator />
}
```

---

## 실행

```bash
cd frontend
npm run dev
```

브라우저: `http://localhost:5173`

Spring Boot가 먼저 실행 중이어야 WebSocket 연결이 된다.

```bash
# Spring Boot 백그라운드 실행
nohup mvn spring-boot:run -Dspring-boot.run.profiles=sim > app.log 2>&1 & echo "PID: $!" && tail -f app.log
```

---

## 포트

| 서비스 | 포트 |
|---|---|
| Spring Boot | 8080 |
| React (Vite) | 5173 |
| WebSocket 연결 대상 | `ws://localhost:8080/ws/cti` |

---

## Host OS 접근 — devcontainer 포트 포워딩

devcontainer 안에서 실행 중인 서비스를 Host OS 브라우저에서 접근하려면
`.devcontainer/devcontainer.json`에 포트 포워딩 설정이 필요하다.

```json
"forwardPorts": [8080, 5173]
```

설정 후 **devcontainer 재시작** 필요. 재시작 후 Spring Boot와 Vite를 다시 기동해야 한다.

재기동 절차 → @docs/TESTING-GUIDE.md (4. CTI WebSocket 수동 테스트 → devcontainer 재시작 후 서비스 재기동 절차)

---

## .gitignore

프로젝트 루트 `.gitignore` 에 아래 항목이 있는지 확인한다.

```
frontend/node_modules/
frontend/dist/
```

---

## WebSocket 구현 설계

CTI WebSocket 백엔드 설계 → @docs/CTI-WEBSOCKET.md
