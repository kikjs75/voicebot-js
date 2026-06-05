# 개발환경 구축 가이드

Spring Boot 음성봇 프로젝트 기준으로 devcontainer, DevPod, IDE 선택까지 정리한 문서입니다.

---

## 1. 개발환경 구조 이해

### 로컬 실행 (단순)

```
로컬 PC
├── IntelliJ / VSCode  (IDE)
│       ↓ 직접 실행
│   Spring :8080      (로컬 JDK)
│       ↕
└── Docker Compose
        ├── redis      :6379
        ├── kafka      :9092
        ├── stt-sim    :8082
        ├── tts-sim    :8084
        ├── cti-sim    :8085
        └── crm-sim    :8087
```

Spring만 로컬에서 IDE로 실행하고, 나머지 인프라는 Docker로 띄우는 방식.
디버깅이 가장 편하고, 입문 단계에서 권장.

---

### devcontainer (컨테이너 안에서 개발)

```
로컬 PC
├── VSCode / IntelliJ  (UI만 로컬에서 실행)
│       ↕ Remote 연결
└── Docker 컨테이너
        ├── JDK 21, Maven
        ├── 소스코드 (bind mount)
        └── 실행 환경 전체
```

- IDE UI: 로컬에서 실행
- 실행 환경 + 소스: 컨테이너 안
- 소스는 **로컬에 있고 컨테이너에 마운트**됨 (동일한 파일)

#### 소스 위치 방식 2가지

| 방식 | 설명 | 용도 |
|------|------|------|
| **bind mount** | 로컬 소스를 컨테이너에 마운트 | 개발 (수정 즉시 반영) |
| **git clone** | 컨테이너 안에서 git clone | 배포 / CI-CD |

```yaml
# bind mount 예시 (.devcontainer/devcontainer.json)
{
  "mounts": [
    "source=${localWorkspaceFolder},target=/workspace,type=bind"
  ]
}
```

---

## 2. devcontainer

### 개념

`.devcontainer/devcontainer.json` 설정 파일 기반으로
**컨테이너 안에 개발 환경 전체를 정의**하는 표준 스펙.
VSCode, IntelliJ, GitHub Codespaces 등에서 지원.

### 장점

- 팀 전체가 동일한 환경 보장 ("내 PC에서는 되는데" 문제 제거)
- JDK, Maven, 플러그인까지 코드로 관리
- GitHub Codespaces와 연동 가능 (클라우드 개발)

### 단점

- 처음 설정이 복잡
- 컨테이너 빌드 시간 소요
- 혼자 개발할 때는 오히려 번거로움

### 기본 구조

```
.devcontainer/
├── devcontainer.json   ← 핵심 설정
├── Dockerfile          ← 컨테이너 이미지 정의 (선택)
└── DEV-ENVIRONMENT.md  ← 이 문서
```

### devcontainer.json 예시

```json
{
  "name": "voice-bot-dev",
  "image": "eclipse-temurin:21",
  "features": {
    "ghcr.io/devcontainers/features/java:1": {
      "version": "21",
      "installMaven": "true"
    },
    "ghcr.io/devcontainers/features/docker-in-docker:2": {}
  },
  "mounts": [
    "source=${localWorkspaceFolder},target=/workspace,type=bind"
  ],
  "workspaceFolder": "/workspace",
  "postCreateCommand": "mvn dependency:resolve",
  "forwardPorts": [8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087],
  "customizations": {
    "vscode": {
      "extensions": [
        "vmware.vscode-spring-boot",
        "vscjava.vscode-java-pack",
        "vscjava.vscode-maven"
      ]
    }
  }
}
```

---

## 3. DevPod

### 개념

devcontainer 스펙을 기반으로 **어디서든 개발 환경을 실행**할 수 있게 해주는 오픈소스 도구.
로컬 Docker, AWS, GCP, Kubernetes 등 다양한 Provider를 지원.

### devcontainer vs DevPod 차이

| | devcontainer | DevPod |
|--|-------------|--------|
| 실행 위치 | 로컬 Docker | 로컬 / 클라우드 / Kubernetes |
| IDE 연동 | VSCode, IntelliJ | VSCode, IntelliJ, JetBrains Gateway |
| 설정 파일 | devcontainer.json | devcontainer.json (동일하게 사용) |
| 용도 | 로컬 컨테이너 개발 | 멀티 Provider 개발 환경 |

### DevPod 실행 예시

```bash
# 로컬 Docker Provider로 실행
devpod up . --ide vscode

# IntelliJ로 실행
devpod up . --ide intellij

# AWS EC2에서 실행
devpod up . --provider aws --ide vscode
```

---

## 4. IDE 별 비교

### VSCode vs IntelliJ (Spring Boot 기준)

| 항목 | VSCode | IntelliJ |
|------|--------|----------|
| Spring 자동완성 | 보통 | 매우 강력 |
| Bean 의존관계 시각화 | ❌ | ✅ |
| application.yml 자동완성 | 약함 | 완벽 |
| 리팩토링 | 기본적 | 강력 |
| 디버깅 | 가능 | 매우 편함 |
| 메모리 사용 | 가벼움 | 무거움 |
| devcontainer 지원 | ✅ 공식 지원 | ✅ 지원 (Gateway) |
| 가격 | 무료 | Ultimate 유료 |

### IntelliJ 에디션

| 에디션 | 가격 | Spring 지원 |
|--------|------|------------|
| Community | 무료 | 기본 |
| Ultimate | 유료 | 완전 지원 |
| 학생 / 오픈소스 | 무료 | Ultimate 동일 |

---

## 5. IDE 선택 전략

### 처음 배울 때 → IntelliJ Community 또는 Ultimate

```
이유:
- Spring Boot 자동완성, Bean 추적, yml 자동완성
- 학습 효율이 VSCode 대비 높음
- @Autowired 연결관계 한눈에 파악 가능
- 처음부터 올바른 패턴 익히기 좋음
```

### 어느 정도 익숙해진 후 → devcontainer + DevPod 고려

```
이유:
- 팀 합류 또는 멀티 환경 필요 시
- 클라우드 개발 환경 필요 시
- CI/CD 파이프라인 연동 시
```

### VSCode는 언제?

```
- Node.js, React, Python 등 다른 스택과 혼용 시
- 가벼운 환경이 필요할 때
- devcontainer 설정 작업 자체를 편집할 때
```

---

## 6. 음성봇 프로젝트 권장 구성

### 개발 단계

```bash
# 1. 인프라 + 시뮬레이터 Docker로 실행
docker compose up -d

# 2. Spring은 IntelliJ에서 직접 실행
# Run > VoiceBotApplication
```

### 통합 테스트 단계

```bash
# Spring 포함 전체 Docker Compose
docker compose --profile full up
```

### 운영 단계

```
- 시뮬레이터 제거
- 실제 STT / TTS 외부 API 연동 (Clova, Google 등)
- Kubernetes 또는 ECS로 컨테이너 오케스트레이션
```

---

## 8. Dockerfile에서 외부 저장소 패키지 설치하는 방법

apt 기본 저장소에 없는 패키지(Docker, GitHub CLI 등)는 **4단계 패턴**으로 설치한다.
GitHub CLI(`gh`) 설치를 예시로 설명한다.

```dockerfile
# ① GPG 키 등록
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
    | gpg --dearmor -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
# ② apt 저장소 등록
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] \
       https://cli.github.com/packages stable main" \
       > /etc/apt/sources.list.d/github-cli.list \
# ③ 설치
    && apt-get update && apt-get install -y gh \
# ④ 캐시 청소
    && apt-get clean && rm -rf /var/lib/apt/lists/*
```

---

### 단계별 설명

#### ① GPG 키 등록 — "GitHub 도장 원본 보관"

```bash
curl -fsSL URL | gpg --dearmor -o /usr/share/keyrings/...gpg
```

| 부분 | 역할 |
|---|---|
| `curl -fsSL URL` | GitHub 서버에서 키 파일 다운로드 |
| `gpg --dearmor` | 텍스트 형식 → 바이너리 형식 변환 (apt가 읽을 수 있는 형식) |
| `-o /usr/share/keyrings/...` | 변환한 키를 apt 키 보관함에 저장 |

이 키는 나중에 패키지 설치 시 **위변조 검증 기준**으로 쓰인다.

#### ② apt 저장소 등록 — "패키지 받을 주소 + 검증할 키 지정"

```bash
echo "deb [arch=... signed-by=...키경로] https://cli.github.com/packages stable main" \
> /etc/apt/sources.list.d/github-cli.list
```

| 부분 | 역할 |
|---|---|
| `deb` | apt 저장소 형식 |
| `arch=$(dpkg --print-architecture)` | 내 CPU 아키텍처 자동 감지 (amd64, arm64 등) |
| `signed-by=...` | ①에서 등록한 키로 서명 검증해라 |
| `https://cli.github.com/packages` | 패키지 받을 URL |
| `stable main` | 안정화 버전 |

`/etc/apt/sources.list.d/` 는 apt의 **즐겨찾기 쇼핑몰 목록**. 이 파일을 추가하면 `apt-get install gh` 시 해당 URL로 접근한다.

#### ③ 설치 — "패키지 다운로드 + 서명 자동 검증"

```bash
apt-get update && apt-get install -y gh
```

`apt-get update`로 저장소 목록을 갱신한 뒤 설치한다. 이때 apt가 자동으로 패키지 서명을 검증한다.

| 상황 | 결과 |
|---|---|
| GitHub가 만든 진짜 패키지 | 서명 일치 ✅ → 설치 허용 |
| 누군가 중간에 변조한 패키지 | 서명 불일치 ❌ → 설치 거부 |
| 가짜 GitHub 사이트의 패키지 | 서명 없음 ❌ → 설치 거부 |

#### ④ 캐시 청소 — "설치 후 찌꺼기 제거"

```bash
apt-get clean && rm -rf /var/lib/apt/lists/*
```

설치 과정에서 생긴 임시 파일을 삭제한다. **Docker 이미지 용량을 줄이기 위해** 설치 직후 반드시 실행한다.

---

### 전체 흐름 요약

```
① GitHub 도장 원본 저장  (GPG 키 등록)
        ↓
② "여기서 받아라 + 이 키로 검증해라" 등록  (저장소 등록)
        ↓
③ 패키지 받기 + 도장 자동 검증  (apt-get install)
        ↓
④ 찌꺼기 버리기  (캐시 청소)
```

①②가 사전 준비, ③이 실제 설치+검증, ④가 뒷정리.
Docker CLI, Node.js 등 apt 기본 저장소에 없는 패키지는 모두 이 패턴을 따른다.

---

## 7. 포트 구성 참고

| 서비스 | 포트 | 비고 |
|--------|------|------|
| Spring | 8080 | 메인 오케스트레이터 |
| 미디어서버 시뮬 | 8081 | WebSocket |
| STT 시뮬 | 8082 | WebSocket |
| LLM 시뮬 | 8083 | REST + SSE |
| TTS 시뮬 | 8084 | REST |
| CTI 시뮬 (WS) | 8085 | WebSocket 이벤트 |
| CTI 시뮬 (REST) | 8086 | REST 제어 API |
| CRM 시뮬 | 8087 | REST |
| Redis | 6379 | 세션 / TTS 캐시 |
| Kafka | 9092 | 메시지 브로커 |
