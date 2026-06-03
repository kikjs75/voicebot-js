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

## GitHub Push 설정 (devcontainer)

devcontainer 안에서는 SSH 키가 없어 `git push`가 실패한다.
HTTPS + Personal Access Token 방식으로 인증한다.

### 토큰 발급
1. https://github.com/settings/tokens → **Generate new token (classic)**
2. `repo` 권한 체크 후 생성

### Remote URL에 토큰 포함

```bash
git remote set-url origin https://<GitHub_사용자명>:<토큰>@github.com/<org>/<repo>.git
```

예시:
```bash
git remote set-url origin https://kikjs75:<토큰>@github.com/kikjs75/voicebot-js.git
```

이후 `git push`가 인증 없이 동작한다.
토큰은 `.git/config`에 저장되므로 컨테이너 재시작 후에도 유지된다.
