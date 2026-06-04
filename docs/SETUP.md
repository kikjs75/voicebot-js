# 개발환경 세팅 가이드

처음 한 번만 하면 됩니다.

---

## 1. 사전 준비

- DevPod 설치: https://devpod.sh
- Docker Desktop 실행 중인지 확인

---

## 2. DevPod으로 컨테이너 시작

```bash
# 프로젝트 루트에서
devpod up . --ide intellij
```

IntelliJ가 자동으로 컨테이너에 연결되어 열립니다.

---

## 3. IntelliJ 최초 설정

컨테이너 안에서 IntelliJ가 열리면 아래 항목을 한 번만 설정합니다.

### Java SDK 지정
`File` → `Project Structure` → `SDK` → `Java 21` 선택

### Maven 경로 확인
`Settings` → `Build, Execution, Deployment` → `Build Tools` → `Maven`
Maven home: `/usr/share/maven` (컨테이너 안에 설치된 경로)

### Spring Boot 실행 설정
`Run` → `Edit Configurations` → `+` → `Spring Boot`
- Main class: `com.voicebot.VoicebotApplication`
- VM Options: `-Dspring.profiles.active=sim`

### 코드 스타일
`Settings` → `Editor` → `Code Style` → `Java`
원하는 스타일로 설정 (기본값 사용해도 무방)

---

## 4. 환경변수 설정

```bash
cp .env.example .env
```

`.env` 파일을 열어 필요한 API 키를 입력합니다.
시뮬레이터 모드(`sim`)로만 개발할 경우 API 키는 비워도 됩니다.

---

## 5. 인프라 + 시뮬레이터 시작

```bash
# 인프라(MariaDB, Redis) + 시뮬레이터 4종 한번에 시작
docker compose -f docker-compose.yml -f docker-compose.sim.yml up -d
```

---

## 6. 앱 실행

IntelliJ에서 위에서 만든 Spring Boot Run Configuration으로 실행하거나:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=sim
```

`http://localhost:8080` 에서 확인합니다.

---

## 실제 외부 서비스로 전환 시

`.env` 에 API 키 입력 후 profile만 변경합니다.

```bash
SPRING_PROFILES_ACTIVE=real ./mvnw spring-boot:run
```

코드 변경 없음.

---

## GitHub Push 인증 설정 (devcontainer)

`git push`는 GitHub 서버에 코드를 올리는 행위다.
GitHub은 "이 사람이 진짜 이 저장소 주인인가"를 확인해야 하며, 방법은 두 가지다.

| | HTTPS + 토큰 | SSH 키 |
|---|---|---|
| 증명 방식 | 아는 값 (토큰) | 가진 것 (개인키) |
| 설정 난이도 | 쉬움 | 복잡 |
| 만료 | 있음 (설정에 따라) | 없음 |
| devcontainer | 컨테이너마다 재설정 필요 | 호스트 키 공유 시 한 번만 |

---

### 방법 A: HTTPS + Personal Access Token (간단)

토큰 = 특정 권한만 가진 임시 비밀번호. URL에 포함하면 git이 `.git/config`에 저장해두고
이후 `git push` 시 자동으로 사용한다.

**토큰 발급**
1. https://github.com/settings/tokens → **Generate new token (classic)**
2. `repo` 권한 체크 후 생성 (만료 없이 쓰려면 **No expiration** 선택)

**Remote URL에 토큰 포함**

```bash
git remote set-url origin https://<사용자명>:<토큰>@github.com/kikjs75/voicebot-js.git
```

이후 `git push`만 하면 된다. 토큰이 만료되면 새 토큰으로 위 명령을 다시 실행한다.

> **주의:** `.git/config`에 토큰이 평문으로 저장된다. 이 파일을 공유하거나 복사할 때 토큰도 함께 넘어가므로 주의한다.

---

### 방법 B: SSH 키 (권장 — 만료 없음)

수학적으로 연결된 열쇠 두 개(개인키/공개키)를 사용한다.
공개키는 GitHub에 걸어두고, 개인키는 내 PC에만 보관한다.
push 시 비밀값을 전송하지 않고 서명으로만 증명하므로 더 안전하다.

**1. SSH 키 생성**

```bash
ssh-keygen -t ed25519 -C "kikjs75@gmail.com"
# 파일 위치, passphrase 모두 Enter (기본값)
```

생성 결과:
```
~/.ssh/id_ed25519      ← 개인키 (절대 공유 금지)
~/.ssh/id_ed25519.pub  ← 공개키 (GitHub에 등록)
```

**2. 공개키 확인**

```bash
cat ~/.ssh/id_ed25519.pub
```

**3. GitHub에 공개키 등록**

https://github.com/settings/keys → **New SSH key** → 공개키 붙여넣기

**4. Remote URL을 SSH로 변경**

```bash
git remote set-url origin git@github.com:kikjs75/voicebot-js.git
```

**5. 확인**

```bash
ssh -T git@github.com
# Hi kikjs75! You've successfully authenticated...
```

**devcontainer 재시작 문제 해결**

SSH 키는 `~/.ssh/`에 저장되는데, 컨테이너가 재생성되면 사라진다.
호스트 OS의 키를 컨테이너와 공유하면 한 번만 설정하면 된다.

`.devcontainer/devcontainer.json`에 추가:

```json
"mounts": [
  "source=${localEnv:HOME}/.ssh,target=/home/vscode/.ssh,type=bind,readonly"
]
```

호스트 OS(Mac/Windows)에서 키를 한 번만 만들어두면 컨테이너가 재생성돼도 자동으로 사용된다.

---

## MCP 연결과 GitHub 인증

MCP(Claude Code 등)에서 GitHub에 접근할 때는 **HTTPS + 토큰만 가능**하다.
SSH 키는 사용할 수 없다.

이유는 두 방식이 동작하는 계층이 다르기 때문이다.

```
git push/pull    →  git 전송 프로토콜  →  SSH 또는 HTTPS+토큰 둘 다 가능
MCP / GitHub API →  HTTP 웹 요청       →  토큰만 가능
```

SSH 키는 전용 터널을 여는 열쇠라 git 전송에서만 쓸 수 있고,
MCP나 GitHub API는 웹 요청 방식이라 토큰(PAT)이 유일한 인증 수단이다.

---

## 커밋 메시지 규칙 (Conventional Commits)

이 프로젝트는 커밋 메시지에 **Conventional Commits** 규칙을 사용한다.

```
feat(llm): 콜센터 시스템 프롬프트 추가
│    │      └─ 실제 설명
│    └─ 범위: 어느 부분을 수정했는지
└─ prefix: 변경의 성격
```

| prefix | 의미 |
|---|---|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `chore` | 기능 변경 없는 잡일 (설정, 빌드 등) |
| `docs` | 문서 수정 |
| `refactor` | 동작 변경 없는 코드 구조 개선 |
| `test` | 테스트 추가/수정 |

**Co-Authored-By** 줄은 Claude와 함께 작성한 커밋임을 표시한다.

```
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```
