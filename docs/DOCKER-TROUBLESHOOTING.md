# 트러블슈팅 Q&A

devcontainer 환경 및 운영 중 실제로 겪은 문제와 해결 방법을 기록한다.

---

## 목차

- [컨테이너 이름 충돌](#컨테이너-이름-충돌)
- [docker compose down vs stop](#docker-compose-down-vs-stop)
- [docker rm -f](#docker-rm--f)
- [down 후에도 충돌이 반복되는 이유](#down-후에도-충돌이-반복되는-이유)
- [redis·mariadb는 충돌이 안 난 이유](#redismariadb는-충돌이-안-난-이유)
- [devcontainer에서 bind mount 불가 에러](#devcontainer에서-bind-mount-불가-에러)
- [docker exec zsh 에러](#docker-exec-zsh-에러)
- [docker cp 후 초기화 스크립트 실행](#docker-cp-후-초기화-스크립트-실행)
- [/docker-entrypoint-initdb.d 경로란](#docker-entrypoint-initdb.d-경로란)
- [docker inspect --format Go 템플릿 문법](#docker-inspect---format-go-템플릿-문법)
- [Claude 백틱 응답으로 인한 intent 분류 실패](#claude-백틱-응답으로-인한-intent-분류-실패)

---

## 컨테이너 이름 충돌

**에러**
```
Error response from daemon: Conflict. The container name "/voicebot-mongodb" is already in use
```

**원인**

같은 이름의 컨테이너가 이미 존재하는데 중복 생성을 시도했다.
`docker compose down` 없이 컨테이너를 종료하거나, 다른 compose 파일로 만든 컨테이너가 남아있을 때 발생한다.

**해결**
```bash
# 방법 1: compose 전체 정리 후 재시작 (권장)
docker compose down
docker compose up -d

# 방법 2: 해당 컨테이너만 강제 삭제
docker rm -f voicebot-mongodb
docker compose up -d
```

---

## docker compose down vs stop

| 명령 | 컨테이너 | 네트워크 | 볼륨(데이터) | 이미지 |
|---|---|---|---|---|
| `docker compose stop` | 중지 (보존) | 보존 | 보존 | 보존 |
| `docker compose down` | **삭제** | **삭제** | 보존 | 보존 |
| `docker compose down -v` | **삭제** | **삭제** | **삭제** | 보존 |

- `down`만으로는 볼륨(DB 데이터)이 삭제되지 않는다.
- `-v` 옵션을 붙여야 볼륨까지 삭제된다 (DB 초기화 시 사용).

---

## docker rm -f

```
docker rm -f voicebot-mongodb
         │    └─ 삭제할 컨테이너 이름
         └─ force: 실행 중이어도 강제 삭제
```

| | `docker rm -f` | `docker compose down` |
|---|---|---|
| 대상 | 지정한 컨테이너 1개 | compose가 관리하는 전체 |
| 네트워크 정리 | ❌ | ✅ |
| 용도 | 특정 컨테이너만 지울 때 | 전체 환경 정리할 때 |

---

## down 후에도 충돌이 반복되는 이유

`docker compose down`은 **현재 compose 파일이 관리하는 컨테이너만** 삭제한다.

`voicebot-mongodb`가 다른 compose 파일(`docker-compose.sim.yml` 등)이나 수동 명령으로 만들어진 경우,
현재 compose는 그 컨테이너를 모르기 때문에 삭제하지 않는다.

```bash
# 현재 상태 확인
docker ps -a | grep voicebot

# 강제 삭제
docker rm -f voicebot-mongodb
```

---

## redis·mariadb는 충돌이 안 난 이유

`docker compose down` 결과를 보면:

```
✔ Container voicebot-mariadb  Removed   ← 삭제 성공
✔ Container voicebot-redis    Removed   ← 삭제 성공
(voicebot-mongodb는 목록에 없음 → 관리 대상이 아님)
```

`down`이 redis·mariadb는 정상 삭제했으므로 `up` 시 이름 충돌이 없었다.
mongodb만 삭제되지 않아 충돌이 발생했다.

`docker ps -a`에서 `Created` 상태로 보이는 redis·mariadb는 방금 새로 만들어진 컨테이너다.
mongodb 충돌로 전체 up이 실패하면서 같이 시작되지 못한 것이다.

---

## devcontainer에서 bind mount 불가 에러

**에러**
```
Error response from daemon: mounts denied:
The path /workspaces/voicebot-js/mongo-init is not shared from the host and is not known to Docker.
```

**원인**

```
Mac (호스트)
  └── devcontainer (Docker 컨테이너)
        └── /workspaces/voicebot-js/mongo-init  ← 여기 존재
              │
              └── docker compose up 실행
                    │
                    ▼
              Mac의 Docker Daemon
                    ← 이 경로를 모름
```

`docker compose`는 Mac의 Docker Daemon에게 명령을 보내는데,
`mongo-init` 경로는 Mac이 아니라 devcontainer 안에만 존재한다.

**해결**: `docker-compose.yml`에서 해당 마운트를 주석 처리한다.

```yaml
volumes:
  - mongodb-data:/data/db
  # devcontainer(Docker-out-of-Docker) 환경에서는 호스트 경로 마운트 불가
  # - ./mongo-init:/docker-entrypoint-initdb.d:ro
```

---

## docker exec zsh 에러

**에러**
```
OCI runtime exec failed: exec failed: unable to start container process: exec: "zsh": executable file not found in $PATH
```

**원인**

해당 컨테이너 이미지에 `zsh`가 설치되어 있지 않다.
Alpine 기반 이미지(`redis:7-alpine` 등)는 용량을 최소화하기 위해 `sh`만 포함한다.

**해결**
```bash
docker exec -it <container> bash   # bash가 있는 경우
docker exec -it <container> sh     # 항상 있음 (Alpine 포함)
```

---

## docker cp 후 초기화 스크립트 실행

`/docker-entrypoint-initdb.d/` 스크립트는 **최초 컨테이너 생성 시에만 자동 실행**된다.
이미 실행 중인 컨테이너에 파일을 복사해도 자동으로 실행되지 않으므로, `mongosh`로 직접 실행한다.

```bash
# 1. 파일 복사 (devcontainer 안에서 실행 가능)
docker cp ./mongo-init/. voicebot-mongodb:/docker-entrypoint-initdb.d/

# 2. 스크립트 직접 실행
docker exec -it voicebot-mongodb mongosh --file /docker-entrypoint-initdb.d/01-playbook.js
```

성공 시 출력:
```
Playbook 초기 데이터 투입 완료: 10건
```

### docker cp vs bind mount 차이

| | bind mount (`volumes:`) | `docker cp` |
|---|---|---|
| 동작 방식 | Docker Daemon이 직접 호스트 경로에 접근 | 명령 실행 위치에서 파일을 읽어 컨테이너로 전송 |
| devcontainer에서 실행 시 | ❌ 호스트(Mac)가 경로를 모름 | ✅ devcontainer 안의 파일을 읽어서 전송 |

`docker cp`는 "내가 있는 곳의 파일을 컨테이너로 복사"하는 방식이므로 devcontainer 안에서 실행해도 된다.

---

## /docker-entrypoint-initdb.d 경로란

호스트 파일시스템에는 존재하지 않는다. **Docker 컨테이너 이미지 안에 약속된 경로**다.

```
호스트 (devcontainer)              컨테이너 (MongoDB 이미지 내부)
./mongo-init/          ──mount──▶  /docker-entrypoint-initdb.d/
  └── init.js                          └── init.js
```

MongoDB 공식 이미지의 entrypoint 스크립트가 이 경로를 스캔해서
`.js` / `.sh` 파일을 최초 기동 시 자동 실행한다.
이미 데이터가 있으면 건너뛴다 (최초 1회만 실행).

---

## docker inspect --format Go 템플릿 문법

`docker inspect`의 `--format` 옵션은 Go 템플릿 문법을 사용한다.

```bash
MONGOIP=$(docker inspect voicebot-mongodb --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
```

### range / end

```
{{range .NetworkSettings.Networks}}
    반복할 대상 (map 또는 배열)
{{.IPAddress}}
    현재 항목의 IPAddress 필드
{{end}}
    반복 종료
```

`Networks`가 여러 네트워크를 가질 수 있는 map이라 단순히 `.Networks.IPAddress`로 접근할 수 없고, `range`로 순회해야 한다.

### JSON에서 `{}` vs `[]`

| | `{}` 객체 (Map) | `[]` 배열 (List) |
|---|---|---|
| 접근 방식 | 키 (`"voicebot-net"`) | 인덱스 (`0`, `1`) |
| Java 대응 | `Map<String, Object>` | `List<Object>` |
| 순서 | 없음 | 있음 |

```json
// {} — 키로 접근
"Networks": {
  "voicebot-net": { "IPAddress": "172.18.0.2" },
  "bridge":       { "IPAddress": "172.17.0.3" }
}

// [] — 인덱스로 접근
"Ports": [
  { "HostPort": "27017" },
  { "HostPort": "8080" }
]
```

### Go 템플릿 순회 비교

```
# {} map — 키($k)와 값($v)
{{range $k, $v := .Networks}}
  {{$k}}: {{$v.IPAddress}}
{{end}}
→ voicebot-net: 172.18.0.2
→ bridge: 172.17.0.3

# [] 배열 — 인덱스($i)와 값($v)
{{range $i, $v := .Ports}}
  {{$i}}: {{$v.HostPort}}
{{end}}
→ 0: 27017
→ 1: 8080
```

### 특정 요소만 꺼낼 때

```
# [] 배열 — 첫 번째 요소
{{(index .Ports 0).HostPort}}

# {} map — 특정 키
{{(index .Networks "voicebot-net").IPAddress}}
```

`range`는 `{}`이든 `[]`이든 동일하게 쓰고, `$k`/`$i`에 키 또는 인덱스가 들어오는 차이다.

---

## Claude 백틱 응답으로 인한 intent 분류 실패

### 증상

TC-06 "아무 말이나 합니다 이상한 말" 테스트 시 아래 로그 발생.

```
[INTENT] callId=CPP 분류 실패 → 기타 반환:
Unexpected character ('`' (code 96)): expected a valid value
```

이후 `confidence=0.0`으로 기타 분류 → Playbook hit=false → Claude fallback 진입.

### 원인

Claude가 JSON을 마크다운 코드블록으로 감싸서 반환했다.

```
# 기대값
{"intent": "기타", "confidence": 0.3}

# 실제 Claude 응답
```json
{"intent": "기타", "confidence": 0.3}
```
```

**왜 그렇게 오는가**: Claude는 채팅 UI 환경에서 학습됐다. 그 환경에서는 코드를 백틱으로 감싸는 게 표준이라 이 패턴이 강하게 학습됐다. 특히 입력이 애매할수록 더 신중하게 응답하려다 마크다운 형식으로 빠지는 경향이 있다.

**왜 항상 그런 건 아닌가**: Claude 응답이 확률적이기 때문이다. 같은 프롬프트도 실행마다 다르게 반환된다.

### 해결

`ClaudeApiLlmService.java`에 `stripCodeBlock()` 메서드를 추가해 파싱 전 백틱을 제거한다.

```java
private String stripCodeBlock(String text) {
    if (text == null) return "";
    return text.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("(?s)```\\s*", "").trim();
}
```

`classifyIntent()`와 `chat()` 모두 Claude 응답을 파싱 전에 `stripCodeBlock()`을 통과시킨다.

### 분류 실패 로그에 원문 추가

파싱 실패 시 Claude가 실제로 뭘 보냈는지 알 수 없었다. catch 블록에 `rawText`를 로그에 추가.

```java
String rawText = null;
try {
    ...
    rawText = (String) first.get("text");   // 원문 저장
    String text = stripCodeBlock(rawText);  // 백틱 제거 후 파싱
    ...
} catch (Exception e) {
    log.warn("[INTENT] callId={} 분류 실패 → 기타 반환: {} | raw={}", callId, e.getMessage(), rawText);
}
```

이제 실패 시 로그에 Claude 원문이 함께 찍힌다.

```
[INTENT] callId=CPP 분류 실패 → 기타 반환: Unexpected character... | raw=```json\n{"intent":"기타"...}\n```
```

### 재현 방법 (테스트용)

INTENT_SYSTEM_PROMPT에 백틱 예시를 추가하면 Claude가 거의 100% 백틱으로 반환한다.

```java
private static final String INTENT_SYSTEM_PROMPT = """
        고객 발화를 분석하여 아래처럼 JSON 코드블록으로 응답하세요.
        
        ```json
        {"intent": "배송문의", "confidence": 0.95}
        ```
        """;
```

테스트 후 원래 프롬프트로 복원할 것.
