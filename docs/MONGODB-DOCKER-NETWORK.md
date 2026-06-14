# MongoDB × Docker 네트워크

TESTING-GUIDE.md §5/§6 "배경" 섹션에서 나온 개념을 정리한 문서.

---

## 목차

- [sibling 컨테이너란](#sibling-컨테이너란)
- [localhost:27017이 안 되는 이유](#localhost27017이-안-되는-이유)
- [해결 방법 A — Docker 네트워크 IP 직접 사용](#해결-방법-a--docker-네트워크-ip-직접-사용)
- [해결 방법 B — docker network connect](#해결-방법-b--docker-network-connect)
- [올바른 해결책 — docker-compose에 포함](#올바른-해결책--docker-compose에-포함)
- [Named volume — 데이터 영구 보존](#named-volume--데이터-영구-보존)
- [mongo-init — 초기 데이터 자동 투입](#mongo-init--초기-데이터-자동-투입)

---

## sibling 컨테이너란

[↑ 목차](#목차)

devcontainer는 **Docker 소켓(`/var/run/docker.sock`)을 호스트 OS에서 빌려서** docker 명령을 실행한다.
devcontainer 안에서 `docker run`을 쳐도 실제로 컨테이너를 만드는 주체는 **호스트 OS(Mac)의 Docker 데몬**이다.

```
호스트 OS (Mac)
└── Docker 데몬
    ├── devcontainer              ← 내가 작업하는 공간
    │     │
    │     └─ docker run mongo      ← 소켓을 타고 Mac Docker 데몬으로 올라감
    │
    └── voicebot-mongodb           ← Mac Docker 데몬이 직접 생성
```

결과물인 MongoDB 컨테이너가 devcontainer 안에 생기는 게 아니라,
**Mac Docker 데몬 아래에 나란히(side by side)** 생긴다. 이 "나란히"가 sibling이다.

---

## localhost:27017이 안 되는 이유

[↑ 목차](#목차)

`-p 27017:27017`은 "호스트 OS:컨테이너" 매핑이다.
컨테이너의 주인이 Mac Docker 데몬이므로 포트가 **Mac** 쪽에 열린다.

```
Mac localhost:27017          →  voicebot-mongodb:27017  ✅ (Mac 브라우저에서 접근 가능)
devcontainer localhost:27017  →  아무것도 없음            ❌
```

devcontainer 입장에서 MongoDB는 같은 Docker 네트워크 위의 이웃 컨테이너일 뿐이라,
IP나 네트워크 이름 해석으로 찾아야 한다.

---

## 해결 방법 A — Docker 네트워크 IP 직접 사용

[↑ 목차](#목차)

Docker 브리지 네트워크의 IP는 같은 Docker 호스트 위라면 네트워크가 달라도 라우팅된다.

```bash
docker inspect voicebot-mongodb --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
# → 172.20.0.5

MONGODB_URI=mongodb://172.20.0.5:27017/voicebot
```

**장점**: 추가 설정 없이 바로 동작.  
**단점**: 재기동 시 IP가 바뀔 수 있어 매번 확인 필요.

---

## 해결 방법 B — docker network connect

[↑ 목차](#목차)

같은 Docker 네트워크에 있으면 컨테이너명으로 접근할 수 있다.
devcontainer를 `voicebot-net`에 직접 연결하면 이름 해석이 된다.

```bash
# devcontainer 컨테이너명 확인
docker ps | grep devpod

# voicebot-net에 연결
docker network connect voicebot-net <devcontainer명>
```

이후 `voicebot-mongodb:27017`으로 접근 가능.

**단점**: devcontainer가 재생성(devpod 재시작 등)되면 연결이 사라진다. 매번 재실행 필요.

---

## 올바른 해결책 — docker-compose에 포함

[↑ 목차](#목차)

MongoDB를 `docker-compose.yml`에 추가하면:

- `docker compose up -d` 하나로 기동 + `voicebot-net` 자동 연결
- 컨테이너명(`voicebot-mongodb`)이 항상 voicebot-net DNS에 등록됨

이 프로젝트에서는 이미 적용되어 있다.

```yaml
# docker-compose.yml (발췌)
mongodb:
  image: mongo:7
  container_name: voicebot-mongodb
  volumes:
    - mongodb-data:/data/db
    - ./mongo-init:/docker-entrypoint-initdb.d:ro

# default 네트워크가 voicebot-net으로 지정되어 있어 별도 networks: 선언 불필요
networks:
  default:
    name: voicebot-net
```

devcontainer 자체는 voicebot-net에 속하지 않으므로,
Spring Boot(`mvn spring-boot:run`) 기동 시에는 IP 조회(해결 방법 A) 또는
devcontainer를 네트워크에 연결(해결 방법 B)한 뒤 컨테이너명을 사용한다.

---

## Named volume — 데이터 영구 보존

[↑ 목차](#목차)

```yaml
volumes:
  mongodb-data:  # named volume
```

| 명령 | 컨테이너 | 볼륨 | 데이터 |
|---|---|---|---|
| `docker compose stop` | 중지 | 유지 | 유지 |
| `docker compose down` | 삭제 | 유지 | 유지 |
| `docker compose down -v` | 삭제 | 삭제 | 사라짐 |

`docker compose down -v`를 쓸 때만 데이터가 사라진다.
재기동 시 `mongo-init` 스크립트가 다시 실행되어 초기 데이터가 재투입된다.

---

## mongo-init — 초기 데이터 자동 투입

[↑ 목차](#목차)

`/docker-entrypoint-initdb.d/` 경로에 마운트된 `.js` 파일은
**볼륨이 비어 있는 최초 기동 시 한 번만** 자동 실행된다.

```
mongo-init/
└── 01-playbook.js  ← intent_playbook 컬렉션 초기 데이터 10건
```

볼륨에 이미 데이터가 있으면 스크립트는 건너뛴다.
데이터를 초기화하려면 `docker compose down -v` 후 재기동한다.

### /docker-entrypoint-initdb.d 는 누가 만드나

사용자가 만드는 게 아니라 **MongoDB 공식 Docker 이미지가 미리 약속해둔 경로**다.
이미지 안의 entrypoint 스크립트가 이 폴더를 스캔해서 파일을 자동 실행한다.

```
컨테이너 시작
    ↓
MongoDB entrypoint 스크립트 실행
    ↓
/docker-entrypoint-initdb.d/ 폴더 스캔
    ↓
.js 파일 → mongosh로 자동 실행
.sh 파일 → bash로 자동 실행
```

"이 경로에 파일을 넣으면 내가 실행해줄게"라고 MongoDB 이미지가 약속한 것이고,
사용자는 그 경로에 파일을 마운트하기만 하면 된다.

DB 계열 이미지들이 관례적으로 동일한 경로를 사용한다.

| 이미지 | 초기화 경로 |
|---|---|
| mongo | `/docker-entrypoint-initdb.d/` |
| mysql / mariadb | `/docker-entrypoint-initdb.d/` |
| postgres | `/docker-entrypoint-initdb.d/` |

### ./mongo-init:/docker-entrypoint-initdb.d:ro 의미

```
./mongo-init  :  /docker-entrypoint-initdb.d  :  ro
     ①                      ②                    ③
```

- **①** `./mongo-init` — 호스트(내 PC)의 경로. 프로젝트 루트의 `mongo-init/` 폴더
- **②** `/docker-entrypoint-initdb.d` — 컨테이너 안의 경로. MongoDB가 자동 실행하는 약속된 폴더
- **③** `ro` — read-only. 컨테이너가 이 폴더에 쓰기를 못 하게 막아 스크립트 보호

```
내 PC의 mongo-init/01-playbook.js
        ↕ (마운트 — 같은 파일)
컨테이너 /docker-entrypoint-initdb.d/01-playbook.js
        ↓ (MongoDB가 최초 기동 시 자동 실행)
voicebot DB에 intent_playbook 10건 삽입
```
