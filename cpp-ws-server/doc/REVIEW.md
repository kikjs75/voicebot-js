# std::getenv()
```
cpp-ws-server는 .env를 자동으로 읽지 않습니다. std::getenv()는 OS 환경변수를 직접 읽기 때문에, 실행 전에 쉘에서 export하거나 Docker --env-file로 주입해야 합니다.
현재 상황: cpp-ws-server를 ./main 으로 그냥 실행하면 기본값(PORT=9090, SPRING_URL=http://localhost:8080)으로 동작하고, RTZR_CLIENT_ID/SECRET은 빈 값이 됩니다.
```

# 
```
```

# libcurl 사용 흐름을 단계별로 설명
```
  ---
  curl_easy_init ~ perform 전체 흐름

  CURL* curl = curl_easy_init();   // ① curl 핸들 생성
  curl 세션을 하나 만드는 겁니다. 이후 모든 설정은 이 핸들에 붙입니다.

  ---
  curl_easy_setopt(curl, CURLOPT_URL, url.c_str());          // ② 어디에
  curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());  // ③ 뭘 보낼지
  curl_easy_setopt는 그냥 설정 함수입니다. (핸들, 옵션키, 값) 형태로 원하는 만큼 호출해서 설정을 쌓습니다.

  ---
  curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curlWrite);  // ④ 응답이 오면 이 함수를 호출해라
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);      // ⑤ 그 함수에 이 포인터를 넘겨라
  
  이 두 줄의 관계가 핵심입니다.

  curl_easy_perform() 실행 중...

  서버가 응답 데이터를 보냄
          │
          ▼
  curl 내부: "WRITEFUNCTION에 등록된 함수 호출해야지"
          │
          ▼
  curlWrite(ptr, size, nmemb, ④+⑤의 연결고리)
                                ↑
                   CURLOPT_WRITEDATA에 넣은 &response가
                   그대로 마지막 인자 s 로 들어옴

  즉, CURLOPT_WRITEDATA에 넣은 값이 콜백 함수의 마지막 인자로 자동 전달됩니다.

  ---
  std::string* s 는 어디서 지정되나

  std::string response;                                       // 여기서 만들고
  curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);      // 여기서 주소를 등록

  static size_t curlWrite(void* ptr, size_t size, size_t nmemb, std::string* s)
  //                                                             ↑
  //                               curl이 &response를 void*로 받아서 이 자리에 꽂아줌
  {
      s->append(...);  // s == &response, 즉 같은 메모리
  }

  libcurl은 CURLOPT_WRITEDATA를 void*로 저장해두고, 콜백 호출 시 그대로 마지막 인자에 넘깁니다. 타입 캐스팅은 프로그래머가 맞춰줘야 합니다.

  ---
  전체 그림

  ① curl_easy_init()
          │ 핸들 생성
          ▼
  ② ~ ⑤ curl_easy_setopt() × N
          │ URL, POST 바디, 콜백 함수, 콜백에 넘길 버퍼 등록
          ▼
  ⑥ curl_easy_perform()
          │ 실제 HTTP 요청 실행
          │
          │ 응답 데이터 수신할 때마다
          └──→ curlWrite(ptr, size, nmemb, &response)
                    │
                    └──→ response.append(...)  ← 조각조각 누적
          │
          ▼ perform 리턴
  ⑦ curl_easy_cleanup()   // 핸들 해제
          │
          ▼
  response 에 전체 응답 바디가 완성됨
```

# curl_easy_perform 은 어떻게 동작해?
```
⏺ curl_easy_perform은 블로킹 함수입니다. 호출하면 HTTP 요청~응답 완료까지 기다렸다가 리턴합니다.

  ---
  내부 동작 순서

  curl_easy_perform(curl)
          │
          ▼
  ① TCP 연결 (3-way handshake)
          │  서버 IP 조회(DNS) → SYN → SYN-ACK → ACK
          ▼
  ② HTTP 요청 전송
          │  POST /api/cti/llm/chat HTTP/1.1
          │  Content-Type: application/json
          │  {"role":"user", "content":"..."}
          ▼
  ③ 응답 수신 (여기서 콜백 반복 호출)
          │
          │  [첫 번째 패킷 도착]
          │      curlWrite(ptr=데이터조각1, ..., s=&response)
          │      response.append(조각1)
          │
          │  [두 번째 패킷 도착]
          │      curlWrite(ptr=데이터조각2, ..., s=&response)
          │      response.append(조각2)
          │
          │  [수신 완료]
          ▼
  ④ 리턴 (CURLE_OK or 에러코드)

  ---
  블로킹이란

  // 이 줄에서 멈춤 ──────────────────────────────┐
  CURLcode res = curl_easy_perform(curl);        │  네트워크 왕복 시간만큼 대기
  // 여기서 재개 ──────────────────────────────────┘

  // perform이 리턴한 후에야 response가 완성됨
  if (res != CURLE_OK) { ... }
  return response;  // 여기 도달할 때 response는 완전한 상태

  응답이 올 때까지 이 스레드는 아무것도 못 합니다. 그래서 현재 코드에서 LLM 응답이 느리면 그 시간만큼 스레드가 block됩니다.

  ---
  콜백이 여러 번 불리는 이유
  
  서버가 응답을 한 번에 보내지 않고 TCP 패킷으로 쪼개서 보내기 때문입니다.

  서버 응답 JSON: {"intent":"문의","response":"무엇을 도와드릴까요?"}
                   ↓ 네트워크에서 쪼개짐
  패킷1: {"intent":"문의
  패킷2: ","response":"무엇을
  패킷3:  도와드릴까요?"}

  → curlWrite 3번 호출 → response에 3번 append → 완성

  append로 누적하는 이유가 바로 이것입니다.

```

# `curl_easy_perform` 말고 비동기로 HTTP 요청하는 방법은?
```
⏺ libcurl에서 비동기 HTTP 요청하는 방법은 크게 세 가지입니다.

  ---
  1. curl_multi (libcurl 내장)
  
  curl_easy의 멀티 버전입니다. 여러 요청을 동시에 관리합니다.

  CURLM* multi = curl_multi_init();

  CURL* curl = curl_easy_init();
  // ... setopt 설정 ...
  curl_multi_add_handle(multi, curl);

  // 이벤트 루프
  int running;
  do {
      curl_multi_perform(multi, &running);  // 논블로킹
      // 다른 작업 가능
  } while (running > 0);

  curl_multi_cleanup(multi);

  직접 이벤트 루프를 돌려야 해서 코드가 복잡해집니다.

  ---
  2. std::thread로 별도 스레드에서 curl_easy_perform
  
  가장 단순한 방법입니다.

  std::string response;
  std::future<std::string> fut = std::async(std::launch::async, [&]() {
      CURL* curl = curl_easy_init();
      // ... setopt ...
      curl_easy_perform(curl);
      curl_easy_cleanup(curl);
      return response;
  });

  // 다른 작업 수행...

  std::string result = fut.get();  // 완료 시 결과 수령

  curl_easy_perform 자체는 블로킹이지만 다른 스레드에서 돌아가므로 호출 스레드는 자유롭습니다.

  ---
  3. Boost.Asio + Beast (현재 프로젝트에 적합)
  
  이 프로젝트는 이미 Boost를 쓰고 있으니 Boost.Beast로 HTTP를 직접 비동기 처리할 수 있습니다.

  // async_write → async_read 체인으로 콜백 없이 처리
  boost::asio::io_context ioc;
  tcp::resolver resolver(ioc);
  beast::tcp_stream stream(ioc);

  // co_await 방식 (C++20 코루틴)
  auto results = co_await resolver.async_resolve(host, port);
  co_await stream.async_connect(results);
  co_await http::async_write(stream, req);
  co_await http::async_read(stream, buffer, res);

  콜백 중첩 없이 동기 코드처럼 읽히면서 비동기로 동작합니다.

  ---
  비교
  
  ┌─────────────────────────┬────────┬────────────────┬──────────────────────────────────┐
  │          방법           │ 복잡도 │ 현재 코드 변경 │               특징               │
  ├─────────────────────────┼────────┼────────────────┼──────────────────────────────────┤
  │ curl_multi              │ 높음   │ 많음           │ curl 그대로 유지                 │
  ├─────────────────────────┼────────┼────────────────┼──────────────────────────────────┤
  │ std::thread + curl_easy │ 낮음   │ 최소           │ 가장 빠른 적용                   │
  ├─────────────────────────┼────────┼────────────────┼──────────────────────────────────┤
  │ Boost.Beast             │ 중간   │ 중간           │ Boost 이미 있어 추가 의존성 없음 │
  └─────────────────────────┴────────┴────────────────┴──────────────────────────────────┘

  현재 코드 구조에서 빠르게 비동기가 필요하다면 std::async 가 가장 현실적입니다. 장기적으로는 Boost.Beast로 통일하는 게 깔끔합니다.

```

# "타입 캐스팅은 프로그래머가 맞춰줘야 합니다." 의미
```
ptr은 void*로 넘어옵니다. void*는 "타입 정보 없는 메모리 주소"라 그대로는 append에 못 씁니다.

  static size_t curlWrite(void* ptr, ...)
  //                      ↑
  //            curl이 void*로 넘겨줌 (타입 정보 없음)
  {
      s->append(static_cast<char*>(ptr), size * nmemb);
      //         ↑
      //  "이 주소를 char 배열로 해석해라" 고 프로그래머가 명시
  }

  curl 입장에서는 어떤 데이터가 올지 모르기 때문에 void*로 넘깁니다. 받는 쪽에서 "나는 텍스트(HTTP 응답)를 받을 거니까 char*로 쓰겠다"고 직접 캐스팅하는 겁니다.

  만약 바이너리 데이터를 받는다면 SpringTtsService.cpp처럼 std::vector<uint8_t>*로 캐스팅합니다.

  // SpringTtsService.cpp - 음성 바이너리 수신
  static size_t curlWriteBytes(void* ptr, size_t size, size_t nmemb,
                                std::vector<uint8_t>* buf) {
      auto* data = static_cast<uint8_t*>(ptr);  // char* 대신 uint8_t*
      buf->insert(buf->end(), data, data + size * nmemb);
      return size * nmemb;
  }
  
  같은 void*지만 용도에 따라 캐스팅 타입이 달라지는 겁니다.

```

# 참조 소스 설명
```
>> 참조
try {
    auto j = json::parse(response);
    std::lock_guard<std::mutex> lock(tokenMutex_);
    accessToken_ = j["access_token"].get<std::string>();
    expireAt_    = j["expire_at"].get<long>();
    LOG_INFO("[STT-RTZR] 토큰 발급 완료 expire_at={}", expireAt_);
} catch (const std::exception& e) {
    LOG_ERROR("[STT-RTZR] 토큰 파싱 오류: {}", e.what());
}

⏺ 사용 라이브러리

  ┌───────────────────────────────┬──────────────────────────────────┐
  │             코드              │            라이브러리            │
  ├───────────────────────────────┼──────────────────────────────────┤
  │ json::parse, j["key"].get<>() │ nlohmann/json                    │
  ├───────────────────────────────┼──────────────────────────────────┤
  │ std::lock_guard, std::mutex   │ C++ 표준 라이브러리 (<mutex>)    │
  ├───────────────────────────────┼──────────────────────────────────┤
  │ LOG_INFO, LOG_ERROR           │ spdlog (프로젝트 내 매크로 래핑) │
  └───────────────────────────────┴──────────────────────────────────┘

  ---
  한 줄씩 설명

  auto j = json::parse(response);

  // response = '{"access_token":"eyJhbG...","expire_at":1718000000}'
  //                 ↑ curl_easy_perform 이후 채워진 문자열

  auto j = json::parse(response);
  // 문자열 → nlohmann::json 객체로 변환
  // j는 내부적으로 key-value 맵처럼 동작

  파싱 실패 시 (응답이 JSON 형식이 아닐 때) json::parse_error 예외를 던집니다. 아래 catch가 잡습니다.

  ---
  std::lock_guard<std::mutex> lock(tokenMutex_);

  std::lock_guard<std::mutex> lock(tokenMutex_);

  tokenMutex_는 RtzrTokenManager 멤버 변수입니다.

  스레드A (토큰 갱신 중)       스레드B (토큰 읽기 요청)
          │                           │
    lock 획득 ──────────────────→ 대기...
    accessToken_ 쓰기                 │
    lock 해제 ───────────────────→ lock 획득
                                  accessToken_ 읽기

  lock_guard는 RAII 방식입니다. 블록을 벗어나면 자동으로 unlock됩니다. 직접 unlock()을 호출할 필요가 없습니다.

  왜 필요하냐면, RtzrTokenManager는 5분마다 백그라운드 스레드에서 토큰을 갱신하고(refreshToken), 동시에 다른 스레드에서 getAccessToken()으로 토큰을 읽습니다. 뮤텍스 없이 동시 접근하면 데이터가 깨집니다.

  ---
  j["access_token"].get<std::string>()

  accessToken_ = j["access_token"].get<std::string>();
  expireAt_    = j["expire_at"].get<long>();

  JSON 응답
  {
    "access_token": "eyJhbGciOiJSUzI1NiJ9...",   → std::string으로 추출
    "expire_at": 1718000000                        → long으로 추출
  }

  j["키"]로 값을 꺼내고, .get<타입>()으로 C++ 타입으로 변환합니다. 키가 없거나 타입이 맞지 않으면 예외를 던집니다.

  ---
  catch (const std::exception& e)

  catch (const std::exception& e) {
      LOG_ERROR("[STT-RTZR] 토큰 파싱 오류: {}", e.what());
  }

  try 블록에서 발생할 수 있는 예외:
  - json::parse_error — response가 JSON이 아닐 때
  - json::type_error — 키는 있는데 타입이 다를 때
  - std::out_of_range — 키 자체가 없을 때 
  
  셋 다 std::exception을 상속하므로 하나의 catch로 다 잡습니다. e.what()이 에러 메시지를 반환합니다.

  ---
  전체 흐름
  
  response (HTTP 응답 문자열)
          │
          ▼
  json::parse(response)     ← 실패 시 → catch → LOG_ERROR
          │
          ▼
  tokenMutex_ 잠금          ← 다른 스레드 대기
          │
          ▼
  accessToken_ = "eyJ..."   ← 멤버 변수에 저장
  expireAt_    = 1718...    ← 멤버 변수에 저장
          │
          ▼
  tokenMutex_ 자동 해제     ← lock_guard 소멸자
          │
          ▼
  LOG_INFO 출력
  
>> j["키"] 에서 j 어색하네. j 어떤 의미이고 없으면? 
⏺ j는 그냥 변수 이름입니다. json의 앞글자를 딴 관례적인 축약입니다.

  using json = nlohmann::json;  // 파일 상단에 있는 별칭

  auto j = json::parse(response);  // j 대신 아무 이름이나 가능

  아래처럼 바꿔도 완전히 동일합니다.

  auto parsed     = json::parse(response);
  auto tokenJson  = json::parse(response);
  auto result     = json::parse(response);

  ---
  ---
  j 없이 한 줄로 쓰면?

  // j 있는 버전
  auto j = json::parse(response);
  accessToken_ = j["access_token"].get<std::string>();
  expireAt_    = j["expire_at"].get<long>();

  // j 없는 버전
  accessToken_ = json::parse(response)["access_token"].get<std::string>();
  expireAt_    = json::parse(response)["expire_at"].get<long>();

  동작은 같지만 json::parse(response)를 두 번 호출하게 됩니다. 파싱을 두 번 하니 낭비이고, 읽기도 불편합니다.

  j에 한 번 저장해두면 파싱은 한 번만 하고 여러 키를 꺼낼 수 있습니다. 그래서 관례적으로 j를 씁니다.

>> `json::parse` 실패하면 어떻게 돼? 
⏺ json::parse_error 예외를 던집니다.

  try {
      auto j = json::parse(response);   // ← 여기서 예외 발생
      // 이 아래 코드는 실행 안 됨
      accessToken_ = j["access_token"].get<std::string>();
      expireAt_    = j["expire_at"].get<long>();
  } catch (const std::exception& e) {
      LOG_ERROR("[STT-RTZR] 토큰 파싱 오류: {}", e.what());
      // 여기서 잡힘
  }

  예외가 발생하면 try 블록 나머지는 전부 건너뛰고 바로 catch로 점프합니다. 그래서 accessToken_과 expireAt_은 갱신되지 않고 이전 값이 그대로 유지됩니다.

  ---
  json::parse_error가 발생하는 상황

  json::parse("not json");          // 완전히 다른 형식
  json::parse("");                  // 빈 문자열
  json::parse("{broken: json}");    // 키에 따옴표 없음
  json::parse("{'key': 'value'}"); // 작은따옴표 (JSON은 큰따옴표만 허용)

  실제로는 RTZR 인증 서버가 오류를 반환할 때 발생합니다.

  // 정상
  {"access_token":"eyJ...","expire_at":1718000000}

  // 인증 실패 시 서버가 이런 형식으로 반환할 수도 있음
  {"error":"invalid_client","message":"클라이언트 인증 실패"}
  → parse는 성공하지만 j["access_token"] 에서 예외 발생

  두 번째 경우는 parse는 통과하지만 .get<std::string>()에서 std::out_of_range가 발생합니다. 이것도 std::exception을 상속하므로 같은 catch에서 잡힙니다.

```

# 참조 소스 설명
```
>> 참조 소스
void RtzrTokenManager::startScheduler() {
    running_ = true;
    schedulerThread_ = std::thread([this]() {
        while (running_) {
            std::this_thread::sleep_for(std::chrono::minutes(5));
            if (!running_) break;
            long expire;
            {
                std::lock_guard<std::mutex> lock(tokenMutex_);
                expire = expireAt_;
            }
            if (expire - std::time(nullptr) < 600) {
                refreshToken();
            }
        }
    });
}

⏺ 전체 역할

  토큰 만료 10분 전에 자동으로 갱신하는 백그라운드 스케줄러입니다.

  ---
  running_ 의미
  
  백그라운드 스레드를 살릴지 죽일지 제어하는 플래그입니다.

  // 생성자에서
  running_ = true;
  startScheduler();   // 스레드 시작

  // 소멸자에서
  running_ = false;   // 스레드에게 "그만해" 신호
  schedulerThread_.join();  // 스레드 종료 대기

  running_이 false가 되면 while 조건이 거짓이 되어 루프를 빠져나옵니다.

  ---
  sleep_for 하는 이유
  
  5분마다 한 번씩만 체크하면 되기 때문입니다.

  sleep 없이 루프를 돌면:
  while (running_) {
      // 1초에 수백만 번 체크
      // CPU 100% 점유
      // 아무 의미 없는 반복
  }

  sleep_for(5분) 있으면:
  while (running_) {
      5분 대기    ← CPU 사용 0%
      만료 체크   ← 딱 한 번
      필요하면 갱신
  }

  토큰 만료를 분 단위로 체크하면 충분하니 5분 간격이 적절합니다.

  ---
  한 줄씩 흐름

  running_ = true;
  schedulerThread_ = std::thread([this]() {   // 백그라운드 스레드 시작

  [this] 캡처로 멤버 변수(running_, expireAt_, tokenMutex_)에 접근합니다.

      while (running_) {
          std::this_thread::sleep_for(std::chrono::minutes(5));

  5분 대기. 이 동안 스레드는 잠들어 있습니다.

          if (!running_) break;

  sleep 도중 소멸자가 running_ = false로 바꿀 수 있습니다. 잠에서 깨어난 직후 다시 확인하고 종료 신호면 즉시 빠져나옵니다.

          long expire;
          {
              std::lock_guard<std::mutex> lock(tokenMutex_);
              expire = expireAt_;
          }  // ← 여기서 lock 자동 해제

  expireAt_은 refreshToken()도 접근하는 공유 변수입니다. 읽는 것도 뮤텍스로 보호해야 합니다. 읽고 나서 바로 {}블록을 닫아 lock을 빨리 해제합니다.

          if (expire - std::time(nullptr) < 600) {
              refreshToken();
          }

  expire          = 토큰 만료 시각 (Unix timestamp)
  std::time(nullptr) = 지금 시각 (Unix timestamp)

  expire - 지금 = 토큰이 몇 초 남았나

  < 600 = 10분(600초) 미만이면 갱신
  
  ---
  전체 타임라인

  t=0       startScheduler() 호출
             │
             ▼
  t=0~5분   sleep (잠)
             │
             ▼
  t=5분     만료까지 10분 이상 남음 → 갱신 안 함
             │
             ▼
  t=5~10분  sleep (잠)
             │
             ▼
  t=10분    만료까지 10분 이상 남음 → 갱신 안 함
             │  ...반복...
             ▼
  t=N분     만료까지 9분 남음 → refreshToken() 호출
             │
             ▼
             새 토큰 발급, expireAt_ 갱신
```

# 다음 질문 답변
```
>> 질문
1) 모든 sleep 함수가 CPU 사용 0% 되는지?                                                                                                                                                                                
2) sleep 함수는 무엇이 있고 sleep_for 는 다른 sleep 과 어떤 차이가 있는지?                                                                                                                                              

⏺ 1) 모든 sleep 함수가 CPU 0%인지?

  네, 모든 sleep은 CPU 사용 0%입니다.

  sleep 호출 시 OS가 스레드를 **대기 상태(waiting)**로 바꿉니다.

  Running  → Waiting (sleep 호출)
  Waiting  → Ready   (시간 만료, OS가 깨움)
  Ready    → Running (CPU 스케줄링)

  대기 상태에서는 CPU 스케줄링 대상 자체에서 제외됩니다. 시간이 되면 OS가 직접 깨워줍니다. 프로그램이 능동적으로 뭔가를 하지 않습니다.

  ---
  2) sleep 함수 종류와 차이

  C 계열 (구식)

  #include <unistd.h>
  sleep(5);           // 초 단위, POSIX (Linux/Mac 전용)
  usleep(5000000);    // 마이크로초 단위, POSIX (Linux/Mac 전용)

  정밀도가 낮고 플랫폼 종속적입니다. C++에서는 쓰지 않는 게 좋습니다.

  ---
  C++ 표준 (현대적)

  #include <thread>
  #include <chrono>

  std::this_thread::sleep_for(std::chrono::seconds(5));       // 5초
  std::this_thread::sleep_for(std::chrono::minutes(5));       // 5분
  std::this_thread::sleep_for(std::chrono::milliseconds(500));// 0.5초

  std::this_thread::sleep_until(timepoint);  // 특정 시각까지 대기

  ---
  sleep_for vs sleep_until 차이

  // sleep_for: "지금부터 5분 동안 자"
  std::this_thread::sleep_for(std::chrono::minutes(5));

  // sleep_until: "지금부터 5분 동안 자"
  auto wakeTime = std::chrono::system_clock::now() + std::chrono::minutes(5);
  std::this_thread::sleep_until(wakeTime);

  sleep_for:
    지금 ──────── 5분 ──────→ 깨어남
    (상대적, 기준: 지금)

  sleep_until:
    지금          목표시각
     │               │
     └───────────────┘ 깨어남
    (절대적, 기준: 특정 시각)

  대부분의 경우 sleep_for로 충분합니다. sleep_until은 정확한 시각에 실행해야 할 때 씁니다. (예: 매일 오전 3시 배치 작업)

  ---
  sleep_for가 구식 sleep보다 나은 이유

  ┌───────────┬────────────────┬──────────────────────┐
  │           │    sleep()     │     sleep_for()      │
  ├───────────┼────────────────┼──────────────────────┤
  │ 플랫폼    │ Linux/Mac만    │ Windows 포함 전체    │
  ├───────────┼────────────────┼──────────────────────┤
  │ 단위      │ 초만 가능      │ ms, us, ns 전부 가능 │
  ├───────────┼────────────────┼──────────────────────┤
  │ 표준      │ POSIX          │ C++11 표준           │
  ├───────────┼────────────────┼──────────────────────┤
  │ 타입 안전 │ ❌  숫자 그대로 │ ✅  chrono 타입       │
  └───────────┴────────────────┴──────────────────────┘

  sleep(5 * 60);      // 300이 뭔지 단번에 안 보임
  sleep_for(chrono::minutes(5));  // 의도가 명확  
  
  auto wakeTime = std::chrono::system_clock::now() + std::chrono::minutes(5);
  std::this_thread::sleep_until(wakeTime);

  이건 "지금으로부터 5분 후" 입니다. sleep_for(5분)과 결과가 같습니다.

  ---
  진짜 차이는 이렇게 쓸 때 드러납니다.

  // sleep_for: 매번 "지금부터" 5분
  // 처리 시간이 걸리면 실행 간격이 벌어짐

  auto start = steady_clock::now();
  while (true) {
      doWork();  // 1분 걸림
      sleep_for(minutes(5));  // 1분 + 5분 = 6분 간격
  }

  // sleep_until: 다음 실행 시각을 고정해서 유지
  // 처리 시간이 걸려도 간격이 일정함

  auto next = steady_clock::now();
  while (true) {
      doWork();  // 1분 걸려도
      next += minutes(5);
      sleep_until(next);  // 정확히 5분 간격 유지
  }

  오전 9시에 실행하려면 이렇게 씁니다.

  // 내일 오전 9시까지 대기 (이건 실제로 복잡해서 보통 cron으로 처리)
  std::tm t = {};
  t.tm_hour = 9;
  auto wakeTime = std::chrono::system_clock::from_time_t(std::mktime(&t));
  std::this_thread::sleep_until(wakeTime);  
```

# 아래 소스 보면 소멸자인데 왜 join이지?
```
>> 참조 소스
  RtzrTokenManager::~RtzrTokenManager() {                                                                                                                                                                                 
      running_ = false;                                                                                                                                                                                                   
      if (schedulerThread_.joinable()) schedulerThread_.join();                                                                                                                                                           
  }                                                                                                                                                                                                                       

⏺ join()은 스레드가 완전히 끝날 때까지 기다리는 함수입니다.

  ---
  join() 없으면 어떻게 되나

  메인 스레드                    백그라운드 스레드
       │                               │
       │  RtzrTokenManager 소멸        │  sleep_for(5분) 중...
       │  running_ = false             │
       │  소멸자 리턴 ──────────────→  │  (아직 살아있음)
       │                               │
       ▼                               │  잠에서 깨어남
    메모리 해제                        │  tokenMutex_ 접근
    tokenMutex_ 없어짐                 │  💥 이미 해제된 메모리 접근
    accessToken_ 없어짐                │     → 크래시

  소멸자가 리턴하는 순간 멤버 변수가 전부 해제됩니다. 그런데 백그라운드 스레드는 아직 그 멤버 변수를 쓰고 있습니다.

  ---
  join() 있으면

  메인 스레드                    백그라운드 스레드
       │                               │
       │  running_ = false             │  sleep_for(5분) 중...
       │  join() 호출 ── 대기 시작      │
       │  (여기서 멈춤)                 │
       │                               │  잠에서 깨어남
       │                               │  if (!running_) break ← false 확인
       │                               │  루프 종료
       │  ← 대기 끝 ────────────────── │  스레드 완전 종료
       │  소멸자 리턴
       ▼
    메모리 해제 (안전)

  스레드가 완전히 끝난 것을 확인한 후에 메모리를 해제하니 안전합니다.

  ---
  joinable() 체크하는 이유

  if (schedulerThread_.joinable()) schedulerThread_.join();

  startScheduler()가 한 번도 호출되지 않았거나, 이미 join()된 스레드에 다시 join()을 호출하면 예외가 발생합니다. joinable()로 먼저 확인하는 겁니다.

  std::thread t;          // 스레드 없음
  t.joinable();           // false → join() 호출하면 안 됨

  std::thread t(func);    // 스레드 있음
  t.joinable();           // true → join() 가능
  t.join();               // 대기
  t.joinable();           // false → 이미 join됨
```

# boost::beast; 어떤 역할 하는지?
```
⏺ Boost.Beast 역할

  HTTP와 WebSocket 통신을 저수준에서 직접 다루는 라이브러리입니다.

  Boost.Asio(네트워크 I/O)위에서 동작합니다.

  ┌─────────────────────────┐
  │      내 코드             │
  ├─────────────────────────┤
  │    Boost.Beast           │  ← HTTP/WebSocket 프로토콜 처리
  ├─────────────────────────┤
  │    Boost.Asio            │  ← TCP 소켓, 비동기 I/O
  ├─────────────────────────┤
  │    OS 네트워크 스택       │  ← 실제 패킷 송수신
  └─────────────────────────┘

  ---
  libcurl vs Boost.Beast
  
  현재 프로젝트는 libcurl을 쓰고 있는데, 둘의 차이는 이렇습니다.

  libcurl
    - "POST /api/llm/chat 해줘" → 알아서 다 해줌
    - 편하지만 내부 동작 제어 어려움
    - 동기(블로킹) 방식이 기본

  Boost.Beast
    - TCP 연결, HTTP 요청 구성, 응답 파싱을 직접 코딩
    - 번거롭지만 세밀하게 제어 가능
    - 비동기 방식이 자연스러움

  ---
  주요 기능

  HTTP 클라이언트/서버

  // HTTP 요청 만들기
  http::request<http::string_body> req{http::verb::post, "/api/chat", 11};
  req.set(http::field::content_type, "application/json");
  req.body() = R"({"role":"user","content":"안녕"})";

  // 전송
  http::write(stream, req);
  
  // 응답 받기
  http::response<http::string_body> res;
  http::read(stream, buffer, res);

  std::cout << res.body();  // 응답 바디

  ---
  WebSocket 클라이언트/서버

  // WebSocket 연결
  websocket::stream<tcp::socket> ws(ioc);
  ws.handshake("localhost", "/ws/cti");

  // 메시지 전송
  ws.write(net::buffer("hello"));
  
  // 메시지 수신
  beast::flat_buffer buf;
  ws.read(buf);

  현재 프로젝트의 RTZR STT가 WebSocket 기반인데, RtzrWebSocketSttService가 이 역할을 합니다.

  ---
  Boost.Asio와의 관계

  Boost.Asio  =  도로 (TCP 소켓, 비동기 I/O 인프라)
  Boost.Beast =  자동차 (HTTP/WebSocket 프로토콜을 Asio 위에서 구현)

  Beast 없이 Asio만으로 HTTP를 구현하려면 헤더 파싱, 청크 인코딩 등을 전부 직접 만들어야 합니다. Beast가 그 부분을 담당합니다.
```

# 참조 소스에서 타입이 uint8_t 이유는 네트워크 통신의 단위가 8비트이여서?
```
>> 참조 소스
virtual void sendChunk(const std::vector<uint8_t>& chunk) = 0;

virtual void sendChunk(const std::vector<uint8_t>& chunk) = 0; 에서 
```

# 참조소스 에서 explicit 의미?
```
>> 참조소스
explicit RtzrWebSocketSttService(std::shared_ptr<RtzrTokenManager> tokenMgr);

⏺ explicit는 암묵적 변환을 금지하는 키워드입니다.

  ---
  explicit 없을 때

  // explicit 없는 생성자
  RtzrWebSocketSttService(std::shared_ptr<RtzrTokenManager> tokenMgr);

  // 이런 실수가 가능해짐
  std::shared_ptr<RtzrTokenManager> mgr = std::make_shared<RtzrTokenManager>(...); // 정상적인 변수 생성.

  RtzrWebSocketSttService svc = mgr;  // 여기서 문제: 암묵적 변환 → 컴파일 통과

  =로 대입했는데 컴파일러가 자동으로 생성자를 호출합니다. 의도하지 않은 동작인데 에러가 안 납니다.

  ---
  explicit 있을 때

  explicit RtzrWebSocketSttService(std::shared_ptr<RtzrTokenManager> tokenMgr);

  RtzrWebSocketSttService svc = mgr;   // ❌ 컴파일 에러
  RtzrWebSocketSttService svc(mgr);    // ✅ 명시적 생성만 허용
  auto svc = RtzrWebSocketSttService(mgr);  // ✅ 이것도 가능

  ---
  왜 붙이는게 좋은가
  
  인자가 하나인 생성자는 암묵적 변환이 자동으로 일어납니다.

  void process(RtzrWebSocketSttService svc) { ... }

  process(mgr);   // mgr이 자동으로 RtzrWebSocketSttService로 변환됨
                  // 생성자 호출인지 모르고 지나치기 쉬움

  // explicit 있으면
  process(mgr);                          // ❌  컴파일 에러
  process(RtzrWebSocketSttService(mgr)); // ✅  의도가 명확

  인자가 하나인 생성자에는 explicit을 붙이는 것이 C++ 관례입니다. 의도치 않은 암묵적 변환을 막아 버그를 예방합니다.
  
>> RtzrWebSocketSttService svc(mgr); 에서 svc(mgr) 이 생성자 호출 하는 명시적인 코딩이라는거지?
  RtzrWebSocketSttService svc(mgr);
  //                      ↑   ↑
  //                 변수명   생성자 인자

  C++에서 객체를 만드는 방법 두 가지입니다.

  // 명시적 생성 (explicit 있어도 가능)
  RtzrWebSocketSttService svc(mgr);          // 괄호 초기화
  RtzrWebSocketSttService svc{mgr};          // 중괄호 초기화
  auto svc = RtzrWebSocketSttService(mgr);   // 명시적 변환

  // 암묵적 생성 (explicit 있으면 컴파일 에러)
  RtzrWebSocketSttService svc = mgr;         // ❌ 암묵적 변환

  svc(mgr) 형태는 "이 인자로 생성자를 호출하겠다"는 의도가 코드에 명확히 드러납니다. 그래서 explicit이 있어도 허용됩니다.  
```

# 참조 소스 는 자식 클래스에서 꼭 구현하라는 뜻인가?
```
>> 참조 소스
~RtzrWebSocketSttService() override; 에서 override;

⏺ 아닙니다. override와 "꼭 구현하라"는 다른 개념입니다.

  ---
  override 의미
  
  부모 클래스의 가상 함수를 재정의하고 있다고 컴파일러에게 명시하는 키워드입니다.

  // 부모 클래스 (SttService)
  class SttService {
  public:
      virtual ~SttService() {}  // 가상 소멸자
  };

  // 자식 클래스 (RtzrWebSocketSttService)
  class RtzrWebSocketSttService : public SttService {
  public:
      ~RtzrWebSocketSttService() override;  // 부모의 가상 소멸자를 재정의
  };

  ---
  override 없으면 어떻게 되나
  
  동작은 같지만 실수를 못 잡습니다.

  // 부모
  virtual void recognize(const std::string& audio);

  // 자식 - 오타
  void recognise(const std::string& audio);         // override 없음 → 컴파일 통과
                                                     // 재정의가 아닌 새 함수가 됨
  void recognise(const std::string& audio) override; // ❌ 컴파일 에러 → 즉시 발견

  override는 "이게 부모 함수 재정의가 맞는지 확인해줘"라고 컴파일러에게 요청하는 겁니다.

  ---
  "꼭 구현하라"는 = 0 (순수 가상 함수)

  // 부모
  virtual void recognize() = 0;   // 순수 가상 함수 → 자식이 반드시 구현해야 함

  // 자식이 구현 안 하면 컴파일 에러

  ---
  정리

  ┌──────────┬─────────────────────────────────────────────────┐
  │  키워드  │                      의미                       │
  ├──────────┼─────────────────────────────────────────────────┤
  │ override │ 부모 가상 함수를 재정의 중임을 명시 (실수 방지) │
  ├──────────┼─────────────────────────────────────────────────┤
  │ = 0      │ 자식이 반드시 구현해야 함                       │
  └──────────┴─────────────────────────────────────────────────┘

```

# 참조 소스에서 default 뭐야? (virtual 설명 포함)
```
>> 참조 소스
virtual ~SttService() = default; 

⏺ = default는 컴파일러가 기본 구현을 자동 생성하도록 맡기는 키워드입니다.

  ---
  왜 가상 소멸자에 쓰나

  class SttService {
  public:
      virtual ~SttService() = default;
  };

  두 가지 목적을 동시에 해결합니다.

  virtual ~SttService();          // 선언만 하면 .cpp에 구현 필요
  virtual ~SttService() {}        // 직접 빈 구현 작성
  virtual ~SttService() = default; // 컴파일러에게 빈 구현 생성 위임 (가장 깔끔)

  셋 다 동작은 같습니다. = default가 가장 간결합니다.

  ---
  virtual이 없으면 어떻게 되나

  // 부모에 virtual 없음
  class SttService {
  public:
      ~SttService() { }
  };

  SttService* svc = new RtzrWebSocketSttService();
  delete svc;
  //     ↑
  //  SttService 소멸자만 호출
  //  RtzrWebSocketSttService 소멸자 호출 안 됨 → 메모리 누수

  // 부모에 virtual 있음
  virtual ~SttService() = default;
  
  delete svc;
  //  RtzrWebSocketSttService 소멸자 먼저 호출
  //  SttService 소멸자 호출
  //  정상 해제

  ---
  정리
  
  virtual   → 자식 소멸자까지 올바르게 호출되도록 보장
  = default → 빈 구현을 컴파일러에게 위임 (직접 작성 불필요)

  인터페이스 클래스(SttService)처럼 직접 구현할 내용이 없을 때 virtual ~SttService() = default가 관례입니다.

```

# 참조 소스에서 주석 부문 해석해줘.
```
>> 참조 소스
bool onMessage(const std::string& text);  // returns false if final (stop reading)

⏺ "최종 결과가 오면 false를 반환한다 (읽기 중단)" 입니다.

  ---
  동작 방식
  
  RTZR STT는 음성 인식 결과를 두 종류로 보냅니다.

  중간 결과 (isFinal = false): "안녕..."        → onMessage 반환값: true  (계속 읽어)
  중간 결과 (isFinal = false): "안녕하세..."    → onMessage 반환값: true  (계속 읽어)
  최종 결과 (isFinal = true):  "안녕하세요"     → onMessage 반환값: false (그만 읽어)

  ---
  왜 false가 "중단"인가
  
  호출하는 쪽에서 반환값으로 루프를 제어합니다.

  while (onMessage(text)) {
      // true면 계속 메시지 읽기
  }
  // false 반환 → while 조건 거짓 → 루프 종료

  true = 계속, false = 중단 패턴입니다. 이런 설계를 early exit 라고 합니다. 최종 결과를 받은 순간 더 이상 읽을 필요가 없으니 루프를 끝내는 겁니다.

```

# 참조 소스에서 주석 부분 번역해줘. 그리고 std::deque 특징 설명해줘.

```
// 참조 소스
std::deque<std::vector<uint8_t>> queue_;  // accessed only on ioc_ thread

⏺ 주석 번역

  "ioc_ 스레드에서만 접근" 입니다.

  queue_는 뮤텍스 없이 사용하는데, 항상 같은 스레드(ioc_ 스레드)에서만 접근하기 때문에 스레드 안전합니다. 다른 스레드에서 건드리면 안 된다는 경고입니다.

  ---
  std::deque 특징
  
  Double-Ended Queue — 앞뒤 양쪽에서 삽입/삭제가 가능한 자료구조입니다.

  vector:
  [0][1][2][3][4]
   ↑              ↑
  앞 삽입 느림    뒤 삽입 빠름

  deque:
  [0][1][2][3][4]
   ↑              ↑
  앞 삽입 빠름    뒤 삽입 빠름

  ---
  vector vs deque 비교
  
  ┌──────────────────────┬───────────┬───────────┐
  │                      │  vector   │   deque   │
  ├──────────────────────┼───────────┼───────────┤
  │ 뒤 삽입 (push_back)  │ O(1)      │ O(1)      │
  ├──────────────────────┼───────────┼───────────┤
  │ 앞 삽입 (push_front) │ O(n) 느림 │ O(1) 빠름 │
  ├──────────────────────┼───────────┼───────────┤
  │ 앞 삭제 (pop_front)  │ O(n) 느림 │ O(1) 빠름 │
  ├──────────────────────┼───────────┼───────────┤
  │ 임의 접근 ([i])      │ O(1)      │ O(1)      │
  ├──────────────────────┼───────────┼───────────┤
  │ 메모리               │ 연속      │ 비연속    │
  └──────────────────────┴───────────┴───────────┘
  
  ---
  이 코드에서 deque를 쓰는 이유
  
  음성 청크 큐이기 때문입니다.

  sendChunk()     →  queue_.push_back(chunk)   // 뒤에 추가
  WebSocket 전송  →  queue_.pop_front()         // 앞에서 꺼냄

  push_back  →  [청크1][청크2][청크3]  →  pop_front
                ↑ 먼저 들어온 순서대로 꺼냄 (FIFO)

  앞에서 꺼내고 뒤에서 넣는 FIFO(선입선출) 구조라 vector보다 deque가 적합합니다.
  
>> sendChunk()은 여기서 음성을 보내기 위해서 뒤에서 넣는 것이고 이걸 WebSocket 전송하기 위해서 앞에서부터 빼서 처리한다. 맞어? 
⏺ 네, 정확합니다.

  마이크/파일
      │
      ▼
  sendChunk(chunk)
      │  push_back
      ▼
  [청크1][청크2][청크3][청크4]  ← queue_
      │
      │  pop_front
      ▼
  WebSocket 전송 (RTZR STT 서버로)

  들어온 순서대로 STT 서버에 전송해야 음성이 올바르게 인식되니까 FIFO 구조가 맞습니다. 순서가 뒤섞이면 STT가 엉뚱한 결과를 냅니다.

```

# 참조 소스에서 flat_buffer 는 다른 buffer 와 달러? 왜 flat_buffer 사용하는거야?
```
>> 참조 소스
beast::flat_buffer readBuf_; 

⏺ Boost.Beast의 버퍼 종류

  ┌───────────────┬───────────────────────┬─────────────────┐
  │     버퍼      │      메모리 구조      │      특징       │
  ├───────────────┼───────────────────────┼─────────────────┤
  │ flat_buffer   │ 연속된 단일 블록      │ 단순, 접근 빠름 │
  ├───────────────┼───────────────────────┼─────────────────┤
  │ multi_buffer  │ 여러 블록으로 분산    │ 대용량에 유리   │
  ├───────────────┼───────────────────────┼─────────────────┤
  │ static_buffer │ 고정 크기 스택 메모리 │ 동적 할당 없음  │
  └───────────────┴───────────────────────┴─────────────────┘

  ---
  flat_buffer 구조

  flat_buffer 내부
  ┌──────────────────────────────────┐
  │[읽은 데이터][수신 대기 공간      ]│
  │ ↑           ↑                    │
  │ consume()   prepare()로 확장     │
  └──────────────────────────────────┘
  하나의 연속된 메모리 블록

  multi_buffer 내부
  ┌────────┐ ┌────────┐ ┌────────┐
  │블록 1  │ │블록 2  │ │블록 3  │
  └────────┘ └────────┘ └────────┘
  여러 블록이 링크드 리스트로 연결

  ---
  WebSocket 수신에 flat_buffer를 쓰는 이유
  
  WebSocket 메시지는 대부분 작은 JSON입니다.

  {"type":"STT_FINAL","text":"안녕하세요"}

  이 정도 크기는 단일 블록으로 충분하고, 연속 메모리라 파싱이 빠릅니다.

  beast::flat_buffer readBuf_;

  ws_.async_read(readBuf_, handler);

  // 수신 후 문자열로 변환
  auto text = beast::buffers_to_string(readBuf_.data());
  // flat_buffer는 연속 메모리라 바로 string으로 변환 가능

  multi_buffer는 대용량 파일 전송처럼 메모리를 나눠서 관리해야 할 때 유리하지만, STT 결과 JSON 수신처럼 작은 메시지에는 flat_buffer가 더 단순하고 빠릅니다.

>> `multi_buffer` 는 어떤 상황에서 써? 
⏺ 데이터가 크거나, 크기를 예측할 수 없을 때 씁니다.

  ---
  flat_buffer vs multi_buffer 한계

  flat_buffer:
  [         연속 메모리          ]
   ↑
   크기가 부족하면 전체를 새 메모리로 복사해서 확장
   → 데이터가 클수록 복사 비용 증가

  multi_buffer:
  [블록1] → [블록2] → [블록3]
             ↑
             새 블록만 추가하면 됨
             → 기존 데이터 복사 없음

  ---
  multi_buffer를 쓰는 상황
  
  1. 대용량 파일 전송

  1GB 영상 파일 수신
  → flat_buffer: 1GB 연속 메모리 필요 (불가능할 수 있음)
  → multi_buffer: 4KB 블록씩 나눠서 수신 (메모리 효율적)

  2. 크기를 모르는 스트리밍 데이터

  HTTP chunked transfer encoding
  → 데이터가 얼마나 올지 모름
  → 올 때마다 블록 추가

  3. 음성 스트리밍 수신 (대용량)

  장시간 통화 음성 데이터
  → 계속 쌓이는 데이터를 블록 단위로 관리

  ---
  정리
  
  메시지가 작고 크기 예측 가능  →  flat_buffer  (STT 결과 JSON)
  데이터가 크거나 크기 모름     →  multi_buffer (파일, 스트리밍)

  현재 프로젝트에서 STT 결과는 짧은 JSON이라 flat_buffer로 충분합니다.
```

# eosSignaled_ 과 isFinal 어떤 관계?
```
⏺ 현재 소스에서 확인해보겠습니다.

  Searched for 1 pattern, read 1 file (ctrl+o to expand)

⏺ 둘은 방향이 반대입니다.

  ---
  eosSignaled_
  
  클라이언트 → RTZR 방향 — "음성 보내기 끝났다"는 신호

  // complete() 호출 시 (음성 전송 완료)
  void RtzrWebSocketSttService::complete() {
      eosSignaled_ = true;        // 플래그 세팅
      if (!writing_) doWrite();   // doWrite 트리거
  }

  // doWrite()에서
  if (queue_.empty()) {
      if (eosSignaled_) {
          ws_->async_write("EOS")  // RTZR 서버에 "EOS" 문자열 전송
      }
  }

  ---
  isFinal
  
  RTZR → 클라이언트 방향 — "인식 결과 최종 확정됐다"는 신호

  // onMessage()에서 RTZR 응답 파싱
  bool isFinal = j.value("final", false);  // RTZR이 보낸 JSON에서 꺼냄

  if (isFinal) {
      ws_->async_close(...);  // WebSocket 연결 종료
      return false;           // 읽기 중단
  }
  bool isFinal = j.value("final", false);  // RTZR이 보낸 JSON에서 꺼냄

  if (isFinal) {
      ws_->async_close(...);  // WebSocket 연결 종료
      return false;           // 읽기 중단
  }

  ---
  전체 흐름

  [클라이언트]                        [RTZR 서버]
       │
       │── 음성 청크 전송 ──────────→ 인식 중...
       │── 음성 청크 전송 ──────────→ 인식 중...
       │
       │  complete() 호출
       │  eosSignaled_ = true
       │── "EOS" 전송 ──────────────→ 마지막 인식 처리
       │
       │←── {"final": true, ...} ───  최종 결과 반환
       │
       │  isFinal = true
       │  WebSocket 종료

  ---
  정리
  
  ┌───────────┬───────────────────┬─────────────────────┐
  │           │   eosSignaled_    │       isFinal       │
  ├───────────┼───────────────────┼─────────────────────┤
  │ 방향      │ 클라이언트 → RTZR │ RTZR → 클라이언트   │
  ├───────────┼───────────────────┼─────────────────────┤
  │ 의미      │ 음성 입력 끝남    │ 인식 결과 확정      │
  ├───────────┼───────────────────┼─────────────────────┤
  │ 발생 시점 │ complete() 호출   │ RTZR 응답 JSON 수신 │
  ├───────────┼───────────────────┼─────────────────────┤
  │ 결과      │ "EOS" 전송        │ WebSocket 종료      │
  └───────────┴───────────────────┴─────────────────────┘
```

# writing_ 역할 및 의미
```
⏺ writing_은 현재 WebSocket 전송 중인지 여부를 나타내는 플래그입니다.

  ---
  왜 필요한가
  
  WebSocket async_write는 한 번에 하나만 전송할 수 있습니다. 이전 전송이 끝나기 전에 다음 async_write를 호출하면 크래시가 납니다.

  // 동시에 두 번 호출하면 위험
  ws_->async_write(chunk1, handler);  // 전송 중
  ws_->async_write(chunk2, handler);  // 💥 이전 전송 안 끝났는데 호출

  ---
  writing_ 흐름

  void RtzrWebSocketSttService::doWrite() {
      writing_ = true;                    // ① 전송 시작 → 플래그 ON
      ws_->async_write(chunk,
          [self](beast::error_code ec, size_t) {
              self->writing_ = false;     // ② 전송 완료 → 플래그 OFF
              self->doWrite();            // ③ 다음 청크 전송 시도
          });
  }
  
  ---
  complete()에서 if (!writing_) doWrite()

  void RtzrWebSocketSttService::complete() {
      eosSignaled_ = true;
      if (!writing_) doWrite();
  }
  
  경우 1: writing_ = false (전송 중 아님)
      → doWrite() 직접 호출
      → EOS 즉시 전송

  경우 2: writing_ = true (청크 전송 중)
      → doWrite() 호출 안 함
      → 현재 전송 완료 후 콜백에서 doWrite() 자동 호출
      → 그때 queue가 비어있으면 EOS 전송

  ---
  전체 타임라인
  
  sendChunk(chunk1) → queue:[chunk1]  writing_=false → doWrite() → writing_=true
  sendChunk(chunk2) → queue:[chunk2]  writing_=true  → doWrite() 호출 안 함

  chunk1 전송 완료 → writing_=false → doWrite()
                                      → chunk2 꺼내서 전송 → writing_=true

  complete() 호출  → eosSignaled_=true
                     writing_=true → doWrite() 호출 안 함

  chunk2 전송 완료 → writing_=false → doWrite()
                                      → queue 비어있음
                                      → eosSignaled_=true
                                      → "EOS" 전송

  writing_이 없으면 async_write가 동시에 여러 번 호출돼 크래시가 납니다.

```

# sslCtx_.set_default_verify_paths()
```
>> set_default_verify_paths()
SSL/TLS 연결할 때 서버 인증서가 진짜인지 검증하는 과정이 필요해요.
그 검증에 필요한 "신뢰할 수 있는 CA 인증서 목록" 을 어디서 읽어올지 지정하는 함수예요.

>> 비유
내가 RTZR 서버에 접속 시도
→ RTZR이 인증서 제시
→ "이 인증서 믿을 수 있어?" 확인 필요
→ 신뢰할 CA 목록이 있어야 검증 가능

set_default_verify_paths() = "OS가 기본으로 관리하는 CA 목록 써"

>> macOS 기준
/etc/ssl/cert.pem  또는
/usr/local/etc/openssl/cert.pem  (Homebrew)

이 경로들을 자동으로 탐색해서 CA 번들을 로드해요.

>> 안 하면?
SSL handshake failed: certificate verify failed

RTZR WebSocket(wss://) 연결 자체가 실패해요.

>> 한 줄 요약
OS에 설치된 공인 CA 인증서들로 SSL 검증을 활성화한다.
```

# ssl::context    sslCtx_{ssl::context::tlsv12_client};
```
>> ssl::context::tlsv12_client
SSL 컨텍스트를 "TLS 1.2 클라이언트 모드" 로 생성한다는 의미예요.

>> 각 부분 분해
ssl::context    sslCtx_   {ssl::context::tlsv12_client};
//  ↑타입           ↑변수명      ↑초기화 옵션

부분                  의미
ssl::context        OpenSSL 설정을 담는 객체
tlsv12_client       TLS 1.2, 클라이언트 역할

>> tlsv12_client vs 다른 옵션들
옵션                  의미
tlsv12_client       TLS 1.2 고정, 클라이언트
tlsv13_client       TLS 1.3 고정, 클라이언트
tls_client          TLS 버전 자동 협상, 클라이언트
tlsv12_server       TLS 1.2 고정, 서버

RTZR WebSocket(wss://)에 접속하는 쪽이니까 client가 맞아요.

>> 한 줄 요약
"나는 TLS 1.2로 서버에 접속하는 클라이언트야" 라고 OpenSSL에게 선언하는 것
```

# TLS 1.2 고정, 클라이언트 /  TLS 1.2 고정, 서버 차이점
```
>> 답변: 클라이언트 vs 서버 역할 차이

>> 핵심 차이
클라이언트 = 먼저 연결 요청하는 쪽
서버       = 연결을 받아주는 쪽

>> 우리 코드 기준
cpp-ws-server  →→→  RTZR 서버
    (클라이언트)         (서버)
    tlsv12_client    tlsv12_server
    
>> 실질적 동작 차이
동작           client                   server
인증서 검증      상대방(서버) 인증서 검증      옵션 (클라이언트 인증서 요구 가능)
인증서 제출      보통 안 함                 자신의 인증서 제시
연결 시작       connect() 호출            accept() 대기

>> 만약 잘못 설정하면?
// 잘못된 예
ssl::context sslCtx_{ssl::context::tlsv12_server};  // ❌

// RTZR에 접속 시도하는데 서버 모드로 설정
// → SSL Handshake 실패

>> 한 줄 요약
내가 연결을 거는 쪽이면 client, 연결을 받는 쪽이면 server    

```

# RtzrWebSocketSttService::recognize() 설명해줘. 
```
⏺ recognize는 STT 세션을 초기화하고 비동기 I/O 루프를 새 스레드에서 시작하는 진입점 함수입니다. 단계별로 보면:

  ---
  1단계: 이전 세션 정리 (9~13행)

  if (thread_.joinable()) {
      ioc_.stop();      // Boost.Asio I/O 루프에 종료 신호
      thread_.join();   // 스레드가 완전히 끝날 때까지 대기
      ioc_.restart();   // ioc_ 재사용을 위해 "stopped" 상태 초기화
  }

  같은 객체로 recognize를 재호출(재발화)할 경우, 이전 WebSocket 세션을 먼저 깔끔하게 끊습니다.

  ---
  2단계: 상태 초기화 (15~23행)

  callId_      = callId;
  onResult_    = std::move(onResult);   // STT 결과 콜백
  onError_     = std::move(onError);    // 에러 콜백
  queue_.clear();                       // 전송 대기 오디오 청크 버퍼 비우기
  eosSignaled_ = false;                 // "발화 끝" 신호 플래그 리셋
  writing_     = false;                 // 쓰기 중 플래그 리셋
  connected_   = false;                 // 연결 완료 플래그 리셋
  ws_.reset();                          // WebSocket 객체 파괴 후 null
  readBuf_.consume(readBuf_.size());    // 수신 버퍼 비우기

  멤버 변수들을 새 세션 기준으로 전부 리셋합니다. std::move로 콜백을 받아 복사 비용을 없앱니다.

  ---
  3단계: 비동기 루프 시작 (25~28행)

  thread_ = std::thread([this]() {
      doConnect();   // TCP → SSL → WebSocket 핸드셰이크 비동기 체인 시작
      ioc_.run();    // 모든 비동기 작업이 끝날 때까지 이 스레드를 블로킹
  });

  doConnect()는 비동기 작업들을 등록만 하고 즉시 반환합니다. 실제 실행은 ioc_.run()이 합니다. recognize는 스레드를 띄우자마자 호출자에게 반환되므로, 호출 측 스레드는 블로킹되지 않습니다.

  ---
  전체 흐름 요약

  recognize() 호출 (호출자 스레드)
      │
      ├─ 이전 세션 종료
      ├─ 상태 초기화
      └─ 새 스레드 시작
              │
              ├─ doConnect()       TCP 연결 등록
              └─ ioc_.run()        루프 진입
                      │
                      ├─ doSslHandshake()
                      ├─ doWsHandshake()  Bearer 토큰 첨부
                      ├─ doRead()         STT 결과 수신 루프
                      └─ doWrite()        오디오 청크 전송 루프

  sendChunk() / complete()는 별도 스레드에서 호출되지만, net::post(ioc_, ...)로 작업을 ioc_ 스레드에 위임하기 때문에 스레드 안전합니다.

```

# ws_.reset(); 좀 더 자세히 알려줘.
```
⏺ ws_의 타입은 std::unique_ptr<WsStream>이고, WsStream은 세 겹짜리 래퍼입니다.

  ---
  WsStream 구조 (안에서 바깥으로)

  WsStream = ws::stream<ssl::stream<tcp::socket>>
                 │           │           │
                 │           │           └─ 실제 OS 소켓 (TCP 연결)
                 │           └─ TLS 암호화 레이어
                 └─ WebSocket 프레임 레이어

  ws_.reset()은 이 객체를 파괴합니다. 파괴 순서는 바깥→안 순서로 소멸자가 연쇄 호출됩니다.

  ws::stream 소멸자
      → ssl::stream 소멸자
          → tcp::socket 소멸자 → OS 소켓 fd 닫힘 (close syscall)

  ---
  unique_ptr::reset() 이 하는 일

  std::unique_ptr<WsStream> ws_;  // 현재 상태: 가리키고 있는 객체 있음

  ws_.reset();
  // 1. 현재 가리키는 WsStream 객체의 소멸자 호출 → TCP 소켓 fd 닫힘
  // 2. 내부 포인터를 nullptr로 세팅
  // 결과: ws_ == nullptr

  직접 delete ws_; ws_ = nullptr;와 동일하지만, RAII 방식으로 안전하게 처리합니다.

  ---
  왜 reset()이 필요한가

  doConnect()에서 새 세션을 만들 때 이렇게 합니다.

  // RtzrWebSocketSttService.cpp:73
  ws_ = std::make_unique<WsStream>(ioc_, sslCtx_);

  unique_ptr에 새 값을 대입하면 자동으로 이전 객체를 파괴하므로 사실 ws_.reset()을 명시적으로 안 해도 됩니다. 그런데 명시적으로 쓴 이유는 의도를 코드로 표현하기 위해서입니다.

  ws_.reset()  →  "이 시점에서 이전 TCP 소켓을 확실히 닫겠다"

  doConnect()가 호출되기 전까지 ws_가 null 상태로 있어야 sendChunk()나 다른 비동기 핸들러가 죽은 소켓에 접근하는 사고를 막을 수 있습니다.

```

# 
```
```


# RtzrWebSocketSttService::doConnect() 설명해줘.
```
>> 참조 소스
void RtzrWebSocketSttService::doConnect() {
    const std::string host = "openapi.vito.ai";
    const std::string port = "443";

    ws_ = std::make_unique<WsStream>(ioc_, sslCtx_);

    auto resolver = std::make_shared<tcp::resolver>(ioc_);
    resolver->async_resolve(host, port,
        [self = shared_from_this(), resolver, host]
        (beast::error_code ec, tcp::resolver::results_type results) {
            if (ec) { self->onError_(ec.message()); return; }
            net::async_connect(beast::get_lowest_layer(*self->ws_), results,
                [self, host](beast::error_code ec, const tcp::endpoint&) {
                    if (ec) { self->onError_(ec.message()); return; }
                    self->doSslHandshake(host);
                });
        });
}

전체 역할

  doConnect()는 비동기 연결 체인의 첫 번째 단계입니다. 실제로 연결을 완료하지 않고, "DNS 조회 →
  TCP 연결 → SSL → WS 핸드셰이크" 체인의 시작을 등록만 하고 즉시 반환합니다.

  doConnect()는 비동기 연결 체인의 첫 번째 단계입니다. 실제로 연결을 완료하지 않고, "DNS 조회 → TCP 연결 → SSL → WS 핸드셰이크" 체인의 시작을 등록만 하고 즉시 반환합니다.

  ---
  단계별 설명

  ① WsStream 객체 생성

  ws_ = std::make_unique<WsStream>(ioc_, sslCtx_);

  세 겹짜리 소켓 객체를 힙에 생성합니다. 아직 연결은 안 된 상태입니다.

  ws_ → WsStream(ws::stream)
            └─ ssl::stream
                   └─ tcp::socket  ← 소켓 fd 할당만 됨, 연결 없음

  ---
  ② DNS 조회 (async_resolve)

  auto resolver = std::make_shared<tcp::resolver>(ioc_);
  resolver->async_resolve(host, port, [콜백]);

  openapi.vito.ai → IP 주소 변환을 비동기로 요청합니다.
  함수는 여기서 즉시 반환합니다. 결과는 나중에 콜백으로 옵니다.

  resolver를 shared_ptr로 만든 이유는 콜백이 호출될 때까지 객체가 살아있어야 하기 때문입니다. 람다가 resolver를 캡처해서 수명을 연장합니다.

  ---
  ③ TCP 연결 (async_connect)

  net::async_connect(beast::get_lowest_layer(*self->ws_), results, [콜백]);

  DNS 결과(results)의 IP 주소들을 순서대로 시도해서 TCP 연결을 맺습니다.

  beast::get_lowest_layer(*self->ws_)는 세 겹 중 가장 안쪽인 tcp::socket을 꺼냅니다. TCP는 암호화를 모르니까 raw 소켓 수준에서 연결해야 하기 때문입니다.

  ws_->next_layer()           → ssl::stream  (TLS 레이어)
  beast::get_lowest_layer()   → tcp::socket  (실제 소켓)

  ---
  ④ 다음 단계로 넘기기

  self->doSslHandshake(host);

  TCP 연결이 성공하면 SSL 핸드셰이크로 넘깁니다. 이후 체인은 아래와 같습니다.

  doConnect()
      └─ async_resolve 완료
              └─ async_connect 완료
                      └─ doSslHandshake()
                              └─ doWsHandshake()   Bearer 토큰 첨부
                                      └─ doRead() + doWrite() 루프 시작

  ---
  ⑤ self = shared_from_this() 캡처

  [self = shared_from_this(), resolver, host]

  콜백은 ioc_.run() 루프가 실행하는 시점에 호출됩니다. 그 시점에 this가 파괴되어 있을 수 있으므로, shared_ptr로 자기 자신을 캡처해서 수명을 보장합니다.
  
  
```

# "TCP는 암호화를 모르니까 raw 소켓 수준에서 연결해야 하기 때문입니다." 무슨 말이야?
```
⏺ 레이어 구조로 설명할게요.

  WsStream의 세 겹 구조

  ws::stream        ← WebSocket 프레임 (텍스트/바이너리 구분, 마스킹 등)
      └─ ssl::stream    ← TLS 암호화/복호화
             └─ tcp::socket  ← 실제 데이터 송수신 (바이트 읽기/쓰기)

  이 세 레이어는 아래에서 위로 데이터를 올려줍니다.

  [RTZR 서버]
      ↕ 암호화된 바이트
  tcp::socket       ← "그냥 바이트 주고받는 파이프"
      ↕ 복호화된 바이트
  ssl::stream       ← "암호 풀어서 올려줌"
      ↕ WebSocket 메시지
  ws::stream        ← "프레임 포맷으로 해석"

  ---
  TCP 연결이 먼저여야 하는 이유

  TCP 연결(async_connect)은 물리적으로 서버와 파이프를 잇는 작업입니다.

  파이프가 없으면 SSL 핸드셰이크 패킷을 보낼 곳이 없습니다. SSL은 TCP 위에서 동작하기 때문에, 순서가 반드시 이래야 합니다.

  ① TCP 연결   → 파이프 개통
  ② SSL 핸드셰이크  → 파이프 위에서 암호화 협상
  ③ WS 핸드셰이크   → 암호화된 파이프 위에서 프로토콜 업그레이드

  ---
  get_lowest_layer가 필요한 이유

  async_connect는 "IP + 포트로 파이프를 연결"하는 작업이므로, TCP 소켓에게 시켜야 합니다.

  그런데 ws_는 ws::stream(맨 바깥 레이어)을 가리키고 있어서, 안쪽 tcp::socket을 직접 꺼내야 합니다.

  // ws_ 는 ws::stream 을 가리킴
  // TCP 연결은 tcp::socket 에게 시켜야 함
  beast::get_lowest_layer(*ws_)  →  tcp::socket 꺼내기

  "TCP는 암호화를 모른다"는 말은, tcp::socket은 그냥 바이트를 보내고 받을 뿐이고 암호화가 뭔지 모른다는 뜻입니다. 암호화는 그 위의 ssl::stream이 담당합니다. 그래서 TCP 연결 단계에서는 ssl이나 ws
  레이어를 거치지 않고 tcp::socket에 직접 접근한 겁니다.
```

# [self, host](beast::error_code ec, const tcp::endpoint&) { 에서 endpoint 역할 및 의미
```
⏺ tcp::endpoint란

  IP 주소 + 포트 번호를 담는 구조체입니다.

  tcp::endpoint == { IP 주소, 포트 }
  // 예: { "52.78.123.45", 443 }

  ---
  왜 콜백에 넘겨주는가

  async_connect는 results에 여러 IP 후보가 있을 수 있습니다. DNS가 여러 IP를 반환하면 순서대로 시도하다가 첫 번째로 성공한 endpoint를 콜백에 알려줍니다.

  DNS 결과 results = [
      52.78.123.45:443,   ← 1번 시도 → 실패
      13.124.56.78:443,   ← 2번 시도 → 성공 → 이게 endpoint로 넘어옴
  ]

  ---
  이 코드에서의 역할

  [self, host](beast::error_code ec, const tcp::endpoint&) {
      if (ec) { self->onError_(ec.message()); return; }
      self->doSslHandshake(host);
  });

  const tcp::endpoint&로 받고 있지만 변수명이 없습니다. 즉, 어떤 IP로 연결됐는지 신경 쓰지 않겠다는 뜻입니다.

  Boost.Asio가 콜백 시그니처를 (error_code, endpoint) 두 인자로 고정해놓았기 때문에, 쓰지 않더라도 자리를 채워줘야 컴파일됩니다.

  // 이렇게 쓰면 어느 IP로 붙었는지 로그 남길 수 있음
  [self, host](beast::error_code ec, const tcp::endpoint& ep) {
      LOG_INFO("connected to {}:{}", ep.address().to_string(), ep.port());
      self->doSslHandshake(host);
  }

  // 현재 코드처럼 이름 생략 → "신경 안 씀"
  [self, host](beast::error_code ec, const tcp::endpoint&) {
      self->doSslHandshake(host);
  }

  이 코드에서 실제로 필요한 정보는 "성공했냐(ec)"뿐이라서 endpoint는 무시한 겁니다.
```

# recognize() 에서 이전 세션 정리하는데 공용으로 사용하는 ioc 중지하고 재시작하는 이유가 뭐야? ioc 가 recognze 전용인가?
```
>> 참조 소스
   1단계: 이전 세션 정리 (9~13행)

    if (thread_.joinable()) {
        ioc_.stop();      // Boost.Asio I/O 루프에 종료 신호
        thread_.join();   // 스레드가 완전히 끝날 때까지 대기
        ioc_.restart();   // ioc_ 재사용을 위해 "stopped" 상태 초기화
    }
    
⏺ 헤더 파일에서 확인한 멤버 변수 위치를 보면 답이 나옵니다.

  // RtzrWebSocketSttService.h
  class RtzrWebSocketSttService {
  private:
      net::io_context ioc_;   // ← 클래스 멤버
      std::thread     thread_; // ← 클래스 멤버
  };

  ioc_는 이 클래스 인스턴스 전용입니다. 다른 서비스와 공유하지 않습니다.

  ---
  왜 stop → join → restart가 필요한가

  ioc_.run()은 한 번 멈추면 "stopped" 상태로 굳습니다.

  [이전 세션]
  thread_ 안에서 ioc_.run() 실행 중
      ↓
  ioc_.stop() 호출
      ↓
  ioc_.run() 반환 → thread_ 종료
      ↓
  ioc_ 상태 = "stopped" ← 이 상태로 굳어있음

  이 상태에서 restart() 없이 바로 ioc_.run()을 다시 호출하면 즉시 반환해버립니다.

  // restart() 없이 재호출하면
  thread_ = std::thread([this]() {
      doConnect();   // 비동기 작업 등록
      ioc_.run();    // ← stopped 상태라 즉시 반환, 아무것도 안 함
  });

  ioc_.restart()는 "stopped" 플래그만 초기화합니다. 내부 큐 등은 건드리지 않습니다.

  ---
  세 줄의 정확한 역할

  ioc_.stop();      // 실행 중인 ioc_.run()에 "그만" 신호
  thread_.join();   // run()이 완전히 반환될 때까지 대기 (race condition 방지)
  ioc_.restart();   // "stopped" 플래그 초기화 → 다음 run() 정상 동작 보장

  join() 없이 restart()하면 이전 스레드가 아직 ioc_ 안을 실행 중인데 새 스레드도 같은 ioc_를 건드리는 data race가 발생합니다. 그래서 순서가 중요합니다.

```

# doSslHandshake()` 함수도 설명해줘
```
>> 참조 소스
void RtzrWebSocketSttService::doSslHandshake(const std::string& host) {
    ws_->next_layer().async_handshake(ssl::stream_base::client,
        [self = shared_from_this(), host](beast::error_code ec) {
            if (ec) { self->onError_(ec.message()); return; }
            self->doWsHandshake(host);
        });
}

  전체 역할

  TCP 연결이 완료된 후, TLS 암호화 협상을 비동기로 수행합니다.

  ---
  ws_->next_layer()

  ws::stream        ← ws_ 가 가리키는 레이어 (WebSocket)
      └─ ssl::stream    ← next_layer() 로 꺼냄
             └─ tcp::socket

  ws_는 WebSocket 레이어를 가리키고 있습니다. SSL 핸드셰이크는 그 아래 ssl::stream에게 시켜야 하므로 next_layer()로 한 겹 벗겨냅니다.

  doConnect()에서 TCP 연결할 때 get_lowest_layer()로 두 겹을 벗긴 것과 비교하면 이렇습니다.

  get_lowest_layer()  →  tcp::socket  (두 겹 벗김, TCP 연결용)
  next_layer()        →  ssl::stream  (한 겹 벗김, SSL 핸드셰이크용)

  ---
  async_handshake(ssl::stream_base::client, 콜백)

  TLS 핸드셰이크를 비동기로 시작합니다. client는 이 쪽이 연결을 요청하는 쪽임을 명시합니다.

  내부적으로 아래 과정을 서버와 주고받습니다.

  클라이언트                        RTZR 서버
      │── ClientHello ──────────────▶│  "이런 암호화 방식 지원해"
      │◀─ ServerHello ───────────────│  "이걸로 하자"
      │◀─ Certificate ───────────────│  "내 인증서야"
      │   (인증서 검증)               │
      │── ClientKeyExchange ────────▶│
      │── Finished ─────────────────▶│
      │◀─ Finished ───────────────────│
      │       ↓
      │  암호화 채널 완성

  이 과정이 전부 비동기로 진행되고, 완료되면 콜백이 호출됩니다.

  ---
  완료 후

  if (ec) { self->onError_(ec.message()); return; }
  self->doWsHandshake(host);

  SSL 핸드셰이크가 성공하면 다음 단계인 doWsHandshake()로 넘깁니다.

  doConnect()          TCP 파이프 개통
      └─ doSslHandshake()   암호화 채널 협상  ← 지금 여기
              └─ doWsHandshake()   WebSocket 업그레이드 + Bearer 토큰 첨부
                      └─ doRead() + doWrite()

```

# doWsHandshake 설명해줘.
```
>> 참조 소스
void RtzrWebSocketSttService::doWsHandshake(const std::string& host) {
    const std::string sampleRate = getEnvOr("RTZR_SAMPLE_RATE", "16000");
    const std::string path =
        "/v1/transcribe:streaming"
        "?sample_rate=" + sampleRate +
        "&encoding=LINEAR16"
        "&use_itn=true"
        "&use_disfluency_filter=true"
        "&use_profanity_filter=false"
        "&use_punctuation=false";

    std::string token = tokenMgr_->getAccessToken();
    ws_->set_option(ws::stream_base::decorator(
        [token](ws::request_type& req) {
            req.set(http::field::authorization, "Bearer " + token);
            req.set(http::field::user_agent, "voicebot-cpp/1.0");
        }));

    ws_->async_handshake(host, path,
        [self = shared_from_this()](beast::error_code ec) {
            if (ec) { self->onError_(ec.message()); return; }
            LOG_INFO("[STT-RTZR] 연결됨 callId={}", self->callId_);
            self->connected_ = true;
            self->doRead();
            // Flush any chunks that arrived before connection
            if (!self->queue_.empty() || self->eosSignaled_)
                self->doWrite();
        });
}

>> 단어 뜻
- disfluency (디스플루언시, /dɪsˈfluːənsi/): 말을 유창하게 하지 못하는 현상, 말이 끊기거나, 머뭇거리거나, 같은 말을 반복하는 것
- profanity (프로패너티, /prəˈfænəti/): 욕설, 비속어
- punctuation (펑크추에이션, /ˌpʌŋk.tʃuˈeɪ.ʃən/): 문장 부호, 구도점 사용법
- ITN: 일반 영어 단어가 아니라, 음성 인식(STT) 분야에서 사용하는 약어
   - Inverse Text Normalization: 음성 인식 결과를 사람이 읽기 쉬운 형태로 변환하는 과정
   - TN과 ITN의 차이
      - TN (Text Normalization)	| 텍스트 → 발음 형태 | 123 → one hundred twenty-three
      - ITN (Inverse Text Normalization)    | 발음 형태 → 일반 텍스트    | one hundred twenty-three → 123
- inverse (인버스, /ɪnˈvɜːrs/): 반대의, 역의, 거꾸로 된
- decorator (데커레이터, /ˈdekəreɪtər/): 장식하는 사람, 장식 도구, 장식물
    - 프로그래밍에서 Decorator(데코레이터) 패턴은 기존 객체나 함수에 기능을 추가하는 기법을 말합니다.
        - 핵심 아이디어: 원래 기능은 그대로 두고, 바깥에서 기능을 덧붙인다

  전체 역할

  SSL 채널 위에서 HTTP Upgrade 요청을 보내 WebSocket 연결을 확립합니다. RTZR STT 서버에 인증 토큰과 오디오 설정을 함께 전달하는 단계입니다.

  ---
  ① URL 경로 조립

  const std::string path =
      "/v1/transcribe:streaming"
      "?sample_rate=16000"
      "&encoding=LINEAR16"
      "&use_itn=true"
      "&use_disfluency_filter=true"
      "&use_profanity_filter=false"
      "&use_punctuation=false";

  쿼리 파라미터로 RTZR에게 오디오 포맷과 후처리 옵션을 지정합니다.

  ┌───────────────────────┬──────────┬─────────────────────────────────┐
  │       파라미터        │    값    │              의미               │
  ├───────────────────────┼──────────┼─────────────────────────────────┤
  │ sample_rate           │ 16000    │ 16kHz 샘플레이트                │
  ├───────────────────────┼──────────┼─────────────────────────────────┤
  │ encoding              │ LINEAR16 │ 16bit PCM                       │
  ├───────────────────────┼──────────┼─────────────────────────────────┤
  │ use_itn               │ true     │ "삼만원" → "30,000원" 숫자 변환 │
  ├───────────────────────┼──────────┼─────────────────────────────────┤
  │ use_disfluency_filter │ true     │ "음~", "어~" 같은 간투사 제거   │
  ├───────────────────────┼──────────┼─────────────────────────────────┤
  │ use_profanity_filter  │ false    │ 욕설 필터 끔                    │
  ├───────────────────────┼──────────┼─────────────────────────────────┤
  │ use_punctuation       │ false    │ 문장부호 자동 삽입 끔           │
  └───────────────────────┴──────────┴─────────────────────────────────┘

  ---
  ② Bearer 토큰 첨부

  ws_->set_option(ws::stream_base::decorator(
      [token](ws::request_type& req) {
          req.set(http::field::authorization, "Bearer " + token);
          req.set(http::field::user_agent, "voicebot-cpp/1.0");
      }));

  WebSocket 핸드셰이크는 내부적으로 HTTP Upgrade 요청을 보냅니다. decorator는 그 HTTP 요청 헤더를 전송 직전에 가로채서 수정하는 훅입니다.

  실제로 전송되는 HTTP 요청은 이렇습니다.

  GET /v1/transcribe:streaming?sample_rate=16000&... HTTP/1.1
  Host: openapi.vito.ai
  Upgrade: websocket
  Connection: Upgrade
  Authorization: Bearer eyJhbGci...   ← 여기 삽입
  User-Agent: voicebot-cpp/1.0        ← 여기 삽입

  ---
  ③ WebSocket 핸드셰이크

  ws_->async_handshake(host, path, [콜백]);

  서버와 주고받는 내용입니다.

  클라이언트                         RTZR 서버
      │── GET /v1/transcribe:streaming ──▶│  HTTP Upgrade 요청
      │◀─ 101 Switching Protocols ────────│  "WebSocket으로 업그레이드 수락"
      │                                   │
      │  이후부터 HTTP가 아닌 WebSocket 프레임으로 통신

  ---
  ④ 연결 완료 후 처리

  self->connected_ = true;
  self->doRead();
  if (!self->queue_.empty() || self->eosSignaled_)
      self->doWrite();

  connected_ = true로 세팅하는 순간부터 sendChunk()가 큐에 쌓은 오디오 청크를 실제로 전송하기 시작합니다.

  doRead()를 먼저 시작하는 이유는, 오디오를 보내는 도중에도 RTZR이 중간 결과(STT_INTERIM)를 언제든 보낼 수 있기 때문입니다.

  연결 완료
      ├─ doRead()   → STT 결과 수신 루프 시작 (항상)
      └─ doWrite()  → 연결 전에 쌓인 청크가 있으면 즉시 전송 시작

  ---
  전체 체인 완성

  doConnect()          TCP 파이프 개통
      └─ doSslHandshake()   TLS 암호화 채널 협상
              └─ doWsHandshake()   WebSocket 업그레이드 + 토큰/설정 전달  ← 지금 여기
                      ├─ doRead()   STT 결과 수신 루프
                      └─ doWrite()  오디오 청크 전송 루프
                      
>> 아래 조건 의미 설명해줘.
  if (!self->queue_.empty() || self->eosSignaled_)
        self->doWrite();

⏺ 왜 이 조건이 필요한가

  sendChunk()와 complete()는 WebSocket 연결이 완료되기 전에도 호출될 수 있습니다.

  recognize() 호출
      │
      ├─ 새 스레드 시작 (TCP → SSL → WS 핸드셰이크 진행 중...)
      │
      │  [핸드셰이크 완료까지 수백 ms 걸림]
      │
      ├─ sendChunk() 호출  → connected_=false 라서 queue_에만 쌓음
      ├─ sendChunk() 호출  → queue_에만 쌓음
      ├─ complete() 호출   → eosSignaled_=true 만 세팅
      │
      └─ doWsHandshake() 완료 → connected_=true

  sendChunk()의 코드를 보면 이 상황이 명확합니다.

  void RtzrWebSocketSttService::sendChunk(...) {
      net::post(ioc_, [self, c]() {
          if (!self->connected_) {
              self->queue_.push_back(c);  // 연결 전이면 큐에만 쌓고 끝
              return;
          }
          self->queue_.push_back(c);
          if (!self->writing_) self->doWrite();  // 연결 후면 즉시 전송
      });
  }

  ---
  조건 두 가지 의미

  if (!self->queue_.empty() || self->eosSignaled_)
      self->doWrite();

  ┌─────────────────┬────────────────────────────────────────────────────────┐
  │      조건       │                          의미                          │
  ├─────────────────┼────────────────────────────────────────────────────────┤
  │ !queue_.empty() │ 연결 전에 쌓인 오디오 청크가 있다 → 지금 전송 시작     │
  ├─────────────────┼────────────────────────────────────────────────────────┤
  │ eosSignaled_    │ 연결 전에 이미 complete()가 호출됐다 → EOS 프레임 전송 │
  └─────────────────┴────────────────────────────────────────────────────────┘

  두 조건이 ||인 이유는 극단적인 경우를 처리하기 위해서입니다.

  // 케이스 1: 청크만 쌓인 경우
  queue_ = [chunk1, chunk2]
  eosSignaled_ = false
  → !queue_.empty() = true → doWrite() 호출

  // 케이스 2: EOS만 온 경우 (청크 없이 바로 종료)
  queue_ = []
  eosSignaled_ = true
  → eosSignaled_ = true → doWrite() 호출 → EOS 프레임 전송

  // 케이스 3: 둘 다 없는 경우 (아직 아무것도 안 옴)
  queue_ = []
  eosSignaled_ = false
  → 조건 false → doWrite() 호출 안 함 (sendChunk가 나중에 직접 호출)

  케이스 3에서 doWrite()를 호출하지 않아도 되는 이유는, 이후 sendChunk()가 호출될 때 connected_=true 상태이므로 그 안에서 직접 doWrite()를 호출하기 때문입니다.
```

# `doRead()` 설명해줘
```
void RtzrWebSocketSttService::doRead() {
    ws_->async_read(readBuf_,
        [self = shared_from_this()](beast::error_code ec, size_t) {
            if (ec) {
                if (ec != ws::error::closed && ec != net::error::eof)
                    LOG_ERROR("[STT-RTZR] 읽기 오류 callId={} {}", self->callId_, ec.message());
                return;
            }
            auto text = beast::buffers_to_string(self->readBuf_.data());
            self->readBuf_.consume(self->readBuf_.size());
            bool cont = self->onMessage(text);
            if (cont) self->doRead();
        });
}

  전체 역할

  RTZR 서버가 보내는 STT 결과를 재귀 방식으로 계속 수신하는 루프입니다.

  ---
  ① async_read

  ws_->async_read(readBuf_, [콜백]);

  WebSocket 프레임 한 개가 도착할 때까지 비동기로 대기합니다. 함수는 즉시 반환하고, 데이터가 오면 콜백이 호출됩니다.

  readBuf_는 beast::flat_buffer 타입으로 클래스 멤버입니다. 수신 데이터가 여기에 누적됩니다.

  ---
  ② 에러 처리

  if (ec) {
      if (ec != ws::error::closed && ec != net::error::eof)
          LOG_ERROR(...);
      return;
  }

  에러가 발생하면 루프를 종료합니다. 두 가지 에러는 조용히 무시합니다.

  ┌───────────────────┬───────────────────────────────────────┬────────────────────────────┐
  │       에러        │               발생 시점               │            이유            │
  ├───────────────────┼───────────────────────────────────────┼────────────────────────────┤
  │ ws::error::closed │ 정상 종료 시 서버가 Close 프레임 전송 │ 정상 흐름                  │
  ├───────────────────┼───────────────────────────────────────┼────────────────────────────┤
  │ net::error::eof   │ TCP 연결이 끊김                       │ 세션 종료 후 예상되는 상황 │
  └───────────────────┴───────────────────────────────────────┴────────────────────────────┘

  이 두 경우는 에러가 아니라 세션이 끝났다는 신호이므로 로그 없이 return합니다.

  ---
  ③ 버퍼 처리

  auto text = beast::buffers_to_string(self->readBuf_.data());
  self->readBuf_.consume(self->readBuf_.size());

  readBuf_.data()로 수신된 데이터를 문자열로 변환하고, consume()으로 버퍼를 비웁니다.

  consume()을 하지 않으면 다음 async_read 호출 시 이전 데이터가 남아있어 JSON 파싱이 깨집니다.

  ---
  ④ 재귀 루프

  bool cont = self->onMessage(text);
  if (cont) self->doRead();

  onMessage()가 true를 반환하면 doRead()를 다시 호출합니다. 이것이 루프의 핵심입니다.

  doRead()
      └─ 데이터 수신 대기
              └─ 도착 → onMessage()
                      ├─ cont=true  → doRead() 재호출 (중간 결과, 계속 수신)
                      └─ cont=false → 루프 종료 (최종 결과 or 에러)

  onMessage()가 false를 반환하는 경우는 두 가지입니다.

  // onMessage() 안에서
  if (j.contains("error")) {
      onError_(...);
      return false;   // 에러 → 루프 종료
  }
  if (isFinal) {
      ws_->async_close(...);
      return false;   // 최종 결과 → 루프 종료
  }
  return true;        // 중간 결과 → 계속 수신

  ---
  전체 수신 흐름

  doRead() 등록
      │
      └─ RTZR 응답 도착
              │
              ├─ {"final": false, "alternatives": [...]}  중간 결과
              │       → onMessage() = true → doRead() 재호출
              │
              ├─ {"final": true,  "alternatives": [...]}  최종 결과
              │       → onMessage() = false → WS close → 루프 종료
              │
              └─ {"error": "..."}
                      → onMessage() = false → 루프 종료
                      
>> if (ec != ws::error::closed && ec != net::error::eof) => ec 는 여러 에러를 갖을 수 있어? 조건이 AND 이여서 물어보는거야.
⏺ 좋은 질문입니다. ec는 하나의 에러 코드만 담습니다. 그런데 AND 조건인 이유는 이렇습니다.

  ---
  ec는 한 번에 하나

  beast::error_code ec;  // 딱 하나의 에러 값만 담는 구조체

  콜백이 호출될 때 ec에는 단 하나의 에러 코드가 들어있습니다.

  ---
  AND 조건의 의미

  if (ec != ws::error::closed && ec != net::error::eof)
      LOG_ERROR(...);

  이건 "ec가 여러 값을 동시에 갖는다"는 게 아니라, "ec가 이것도 아니고 저것도 아닐 때" 로그를 찍겠다는 뜻입니다.

  자연어로 풀면 이렇습니다.

  ec가 closed 가 아니고
  ec가 eof    도 아니면
  → 예상 못한 진짜 에러 → 로그 찍기

  ec가 closed 이면  → 정상 종료 → 로그 안 찍기
  ec가 eof    이면  → 정상 종료 → 로그 안 찍기

  ---
  OR로 바꾸면 의미가 달라짐

  // AND: closed도 아니고 eof도 아닌 경우에만 로그 (둘 다 무시)
  if (ec != ws::error::closed && ec != net::error::eof)

  // OR로 바꾸면: closed이거나 eof이면 로그 (둘 중 하나만 무시)
  if (ec != ws::error::closed || ec != net::error::eof)

  OR로 쓰면 항상 true가 되어 모든 에러를 로그에 찍는 잘못된 조건이 됩니다. 두 값을 동시에 제외하려면 반드시 AND를 써야 합니다.
  
>> ❯ "OR로 바꾸면 의미가 달라짐" 좀 더 쉽게 설명해줄 수 있어?

⏺ ec에 들어올 수 있는 값 예시

  ec = ws::error::closed   (정상 종료)
  ec = net::error::eof     (정상 종료)
  ec = net::error::timeout (진짜 에러)

  ---
  AND 조건 (현재 코드)

  if (ec != ws::error::closed && ec != net::error::eof)

  각 경우를 대입해보면:

  ec = closed   →  (false && true)  = false  → 로그 안 찍음 ✅
  ec = eof      →  (true  && false) = false  → 로그 안 찍음 ✅
  ec = timeout  →  (true  && true)  = true   → 로그 찍음   ✅

  ---
  OR 조건으로 바꾸면

  if (ec != ws::error::closed || ec != net::error::eof)

  각 경우를 대입해보면:

  ec = closed   →  (false || true)  = true   → 로그 찍음 ❌
  ec = eof      →  (true  || false) = true   → 로그 찍음 ❌
  ec = timeout  →  (true  || true)  = true   → 로그 찍음 ✅

  ec는 하나의 값만 가지므로, closed일 때 eof는 아니고, eof일 때 closed는 아닙니다. 그래서 OR를 쓰면 항상 한 쪽은 true가 되어 어떤 에러가 와도 항상 로그를 찍게 됩니다.

```

# `doWrite()` 설명해줘
```
>> 참조 소스
void RtzrWebSocketSttService::doWrite() {
    if (queue_.empty()) {
        if (eosSignaled_) {
            // EOS: send text frame "EOS"
            writing_ = true;
            ws_->text(true);
            ws_->async_write(net::buffer(std::string("EOS")),
                [self = shared_from_this()](beast::error_code ec, size_t) {
                    self->ws_->text(false);
                    self->writing_ = false;
                    if (ec) self->onError_(ec.message());
                    LOG_INFO("[STT-RTZR] EOS 전송 callId={}", self->callId_);
                });
        }
        return;
    }

    writing_ = true;
    auto chunk = std::move(queue_.front());
    queue_.pop_front();

    ws_->binary(true);
    ws_->async_write(net::buffer(chunk),
        [self = shared_from_this(), chunk](beast::error_code ec, size_t) {
            self->writing_ = false;
            if (ec) { self->onError_(ec.message()); return; }
            self->doWrite();
        });
}

  ---
  전체 역할

  큐에 쌓인 오디오 청크를 하나씩 순서대로 RTZR 서버에 전송합니다. 큐가 비면 EOS 프레임을 보내거나 대기합니다.

  ---
  ① 큐가 비어있는 경우

  if (queue_.empty()) {
      if (eosSignaled_) {
          // EOS 전송
      }
      return;
  }

  큐가 비었을 때 두 가지 분기입니다.

  queue_ 비어있음
      ├─ eosSignaled_ = true  → EOS 프레임 전송 후 종료
      └─ eosSignaled_ = false → 그냥 return (sendChunk가 나중에 doWrite 호출)

  ---
  ② EOS 전송

  ws_->text(true);
  ws_->async_write(net::buffer(std::string("EOS")), [콜백]);

  text(true)로 텍스트 프레임으로 전환 후 문자열 "EOS"를 전송합니다.

  RTZR 서버는 "EOS" 텍스트 프레임을 받으면 "오디오 입력이 끝났다"고 인식하고 최종 결과를 반환합니다.

  콜백에서 ws_->text(false)로 다시 바이너리 모드로 복원합니다.

  오디오 청크 전송  →  binary 프레임
  EOS 전송         →  text 프레임  ("EOS")

  ---
  ③ 오디오 청크 전송

  writing_ = true;
  auto chunk = std::move(queue_.front());
  queue_.pop_front();

  ws_->binary(true);
  ws_->async_write(net::buffer(chunk), [콜백]);

  큐 앞에서 청크 하나를 꺼내 바이너리 프레임으로 전송합니다.

  std::move(queue_.front())는 복사 없이 데이터를 가져옵니다.

  ---
  ④ 재귀 루프

  [self = shared_from_this(), chunk](beast::error_code ec, size_t) {
      self->writing_ = false;
      if (ec) { self->onError_(ec.message()); return; }
      self->doWrite();  // 다음 청크 전송
  }

  전송 완료 후 doWrite()를 재호출해서 큐가 빌 때까지 반복합니다.

  doWrite()
      └─ chunk 전송 완료
              └─ doWrite()
                      └─ chunk 전송 완료
                              └─ doWrite()
                                      └─ queue_ 비어있음
                                              └─ eosSignaled_=true → EOS 전송

  ---
  ⑤ writing_ 플래그 역할

  writing_ = true;   // 전송 시작 전
  // ...async_write 완료 후
  writing_ = false;  // 전송 완료

  async_write는 동시에 두 번 호출하면 안 됩니다. writing_이 true인 동안은 sendChunk()가 큐에만 쌓고 doWrite()를 호출하지 않습니다. 현재 전송이 끝나면 재귀 호출로 자연스럽게 다음 청크를 처리합니다.

>> flat_buffer.consume() 대해서 좀 더 설명해줘.  
⏺ flat_buffer 내부 구조
                                                                                                                                                                         
  flat_buffer는 내부적으로 두 영역으로 나뉩니다.

  [  읽을 수 있는 데이터  |  빈 공간(쓰기 가능)  ]
   ↑                    ↑
   시작                 consume 기준점

  ---
  consume() 역할
  
  "이만큼 읽었으니 버퍼에서 제거해라" 는 뜻입니다.

  // 데이터 수신 후
  auto text = beast::buffers_to_string(readBuf_.data());  // 데이터 읽기
  readBuf_.consume(readBuf_.size());                       // 읽은 만큼 제거

  ---
  consume() 없으면 어떻게 되나
  
  1번째 메시지 수신:
  [{"final":false,"text":"안녕"}         |          ]
   ↑ 읽음, 하지만 consume 안 함

  2번째 메시지 수신:
  [{"final":false,"text":"안녕"}{"final":true,"text":"안녕하세요"}|  ]
   ↑ 이전 데이터가 남아서 누적됨 → 파싱 오류

  consume() 호출 후:
  [{"final":false,"text":"안녕"}         |          ]
   읽고 consume →
  [                                      |          ]
   버퍼 비워짐

  2번째 메시지 수신:
  [{"final":true,"text":"안녕하세요"}    |          ]
   깨끗하게 새 메시지만 있음

  ---
  소스에서 쓰이는 두 곳

  // 1. recognize() - 세션 초기화 시 이전 데이터 제거
  readBuf_.consume(readBuf_.size());

  // 2. doRead() 콜백 - 메시지 읽은 직후 제거
  auto text = beast::buffers_to_string(self->readBuf_.data());
  self->readBuf_.consume(self->readBuf_.size());  // ← 읽었으니 바로 비움
  bool cont = self->onMessage(text);

  ---
  정리

  data()     → 버퍼에 있는 데이터 접근 (읽기만, 제거 안 함)
  consume(n) → 앞에서 n바이트 제거 (실제로 소비 처리)

  data() 후 consume() 안 하면 → 다음 수신 데이터가 뒤에 누적됨
  data() 후 consume() 하면   → 버퍼 초기화, 다음 메시지 깨끗하게 수신
  
>> doWrite() 호출하는 곳을 알려줘. 
⏺ 총 4곳에서 호출됩니다.

  ---
  1. sendChunk() — 58번 줄

  // 청크가 들어올 때마다
  self->queue_.push_back(std::move(c));
  if (!self->writing_) self->doWrite();  // 전송 중 아니면 바로 전송 시작
  새 음성 청크가 들어왔는데 전송 중이 아니면 즉시 전송을 시작합니다.

  ---
  2. complete() — 65번 줄
  
  // 음성 입력 종료 시
  self->eosSignaled_ = true;
  if (self->connected_ && !self->writing_) self->doWrite();  // EOS 전송
  음성 끝 신호가 왔을 때 전송 중이 아니면 EOS를 보냅니다.

  ---
  3. doWsHandshake() 콜백 — 122번 줄

  // WebSocket 연결 완료 직후
  self->connected_ = true;
  self->doRead();
  if (!self->queue_.empty() || self->eosSignaled_)
      self->doWrite();  // 연결 전에 쌓인 청크 flush
  연결되기 전에 sendChunk()로 쌓인 청크들을 연결 완료 후 한꺼번에 비웁니다.

  ---
  4. doWrite() 자기 자신 — 167번 줄

  // 한 청크 전송 완료 콜백에서
  self->writing_ = false;
  self->doWrite();  // 다음 청크 전송 (재귀 호출)
  청크 하나 전송이 끝나면 스스로를 호출해서 다음 청크를 연속으로 처리합니다.

  ---
  전체 흐름

  sendChunk()       → doWrite() ─┐
  complete()        → doWrite()  │ queue 빌 때까지
  doWsHandshake()   → doWrite()  │ 재귀 반복
                      doWrite() ←┘
                      (자기 자신)

>> `doWrite()` 재귀 호출이 끝나는 조건은? 
⏺ 종료 조건은 두 가지입니다.

  ---
  종료 조건 1: queue가 비고 eosSignaled_ = false

  if (queue_.empty()) {
      if (eosSignaled_) {
          // EOS 전송
      }
      return;  // ← 여기서 그냥 리턴 (재귀 없음)
  }

  아직 음성 입력이 끝나지 않은 상태에서 queue가 비면 그냥 리턴합니다. 다음 sendChunk()가 오면 다시 doWrite()를 호출합니다.

  ---
  종료 조건 2: queue가 비고 eosSignaled_ = true → EOS 전송

  if (queue_.empty()) {
      if (eosSignaled_) {
          ws_->async_write("EOS",
              [self](beast::error_code ec, size_t) {
                  self->writing_ = false;
                  // doWrite() 호출 없음 ← 재귀 종료
              });
      }
      return;
  }

  EOS 전송 후 콜백에서 doWrite()를 호출하지 않습니다. 여기서 완전히 끝납니다.

  ---
  전체 흐름

  doWrite()
      │
      ├── queue 비어있음?
      │       │
      │       ├── eosSignaled_ = false → return (대기)
      │       │
      │       └── eosSignaled_ = true  → "EOS" 전송 → 종료
      │
      └── queue에 청크 있음
              │
              └── 청크 전송 → 완료 콜백 → doWrite() 재귀
                                           (다음 청크 처리)
                                           
>> eosSignaled_ = false → return (대기) => 왜 대기한다고해? 이 시점은 비동기 콜백 등록이  안 된 상태. 그러면 요청이 들어와도 처리가 안 되는거 아니야?                                                            
⏺ 맞습니다. 제가 "대기"라고 표현한 게 틀렸습니다.

  이 시점은 단순히 함수가 리턴하는 겁니다. 아무 콜백도 등록 안 합니다.

  ---
  그런데 왜 처리가 되는가
  
  sendChunk()가 직접 doWrite()를 호출하기 때문입니다.

  void RtzrWebSocketSttService::sendChunk(const std::vector<uint8_t>& chunk) {
      net::post(ioc_, [self = shared_from_this(), c = chunk]() mutable {
          self->queue_.push_back(std::move(c));
          if (!self->writing_) self->doWrite();  // ← 여기서 직접 호출
      });
  }

  queue 비어서 doWrite() 리턴
          │
          │  (아무것도 안 함)
          │
  sendChunk(새 청크) 호출
          │
          ├── queue에 청크 추가
          └── writing_ = false → doWrite() 직접 호출 → 전송 재개

  ---
  정리
  
  doWrite() 리턴  =  "지금 보낼 게 없으니 끝낸다"
                     다음 sendChunk()가 오면 그때 다시 doWrite() 시작

  "대기"가 아니라 완전 종료입니다. 다음 진입은 sendChunk()가 책임집니다. 지적해주셔서 감사합니다.
  
>> auto chunk = std::move(queue_.front()); => queue_ 에서 꺼낸 chunk 에 대한 소유권을 갖겠다는거지? 이유?                                                                                                        
⏺ 네, 맞습니다.

  ---
  std::move 없이 복사하면

  auto chunk = queue_.front();  // 복사
  queue_.pop_front();

  queue_: [chunk1 데이터][chunk2][chunk3]
                  ↓ 복사 (메모리 통째로 복사)
  chunk:  [chunk1 데이터 사본]

  // 음성 데이터는 수천 바이트 → 복사 비용 발생

  ---
  std::move로 소유권 이전하면

  auto chunk = std::move(queue_.front());  // 이동
  queue_.pop_front();
  
  queue_: [chunk1 포인터] → 데이터
                  ↓ 포인터만 이전 (데이터 복사 없음)
  chunk:  [chunk1 포인터] → 데이터

  // queue_.front()는 텅 빈 껍데기만 남음
  // pop_front()로 껍데기 제거

  ---
  왜 소유권을 가져야 하나

  ws_->async_write(net::buffer(chunk),
      [self = shared_from_this(), chunk](beast::error_code ec, size_t) {
          //                      ↑
          //             콜백 람다가 chunk를 캡처
          //             async_write가 완료될 때까지 chunk가 살아있어야 함
          self->writing_ = false;
          self->doWrite();
      });
  
  async_write는 비동기입니다. 함수가 리턴해도 전송은 나중에 완료됩니다. 그 사이에 chunk 데이터가 살아있어야 합니다.

  async_write 호출
      │
      │  doWrite() 리턴 ← 여기서 chunk가 사라지면 안 됨
      │
      │  ... 전송 중 ...
      │
      └── 콜백 호출 ← 여기까지 chunk가 살아있어야 함

  콜백 람다가 chunk를 캡처해서 소유하고 있으므로 전송 완료까지 데이터가 안전하게 유지됩니다.

  ---
  정리
  
  std::move 이유 1: 복사 비용 제거 (음성 데이터 크기만큼 절약)
  std::move 이유 2: 소유권을 람다 캡처로 넘겨 비동기 전송 중 데이터 보호 => 잘못된 이유.
  
>> std::move 하지 않아도 캡처에서 명시하면 존재하지 않어?                                                                                                                                                        
⏺ 좋은 질문입니다. 확인해보겠습니다.

  // std::move 없이
  auto chunk = queue_.front();        // 복사
  queue_.pop_front();

  ws_->async_write(net::buffer(chunk),
      [self, chunk](beast::error_code ec, size_t) {  // 람다가 chunk 캡처
          self->writing_ = false;
          self->doWrite();
      });
  
  네, 이렇게 해도 chunk는 람다 캡처에 의해 살아있습니다. 비동기 전송 중 데이터 보호는 됩니다.

  ---
  그러면 std::move의 이유는 하나
  
  복사 비용 제거만이 목적입니다.

  auto chunk = queue_.front();         // ① queue에서 복사  (1번 복사)
  [self, chunk](...)                   // ② 람다 캡처 시 복사 (2번 복사)

  auto chunk = std::move(queue_.front()); // ① 이동 (복사 없음)
  [self, chunk](...)                      // ② 람다 캡처 시 복사 (1번 복사)

  std::move로 queue에서 꺼낼 때 복사 1번을 줄인 겁니다.

  ---
  더 줄이려면

  auto chunk = std::move(queue_.front());
  queue_.pop_front();

  ws_->async_write(net::buffer(chunk),
      [self, chunk = std::move(chunk)](...) {  // 캡처도 이동

  이렇게 하면 복사가 완전히 없어집니다. 현재 소스는 캡처에서 복사가 한 번 일어납니다.
  
>> 2번의 std::move 해도 소유권이 클백에 있으니까 살아 있는게 맞지?                                                                                                                                               
⏺ 네, 맞습니다.

  auto chunk = std::move(queue_.front());  // queue → chunk 로 소유권 이전
  queue_.pop_front();

  ws_->async_write(net::buffer(chunk),
      [self, chunk = std::move(chunk)](...) {  // chunk → 람다 캡처로 소유권 이전
          // 여기까지 chunk 살아있음
      });
  // 이 시점에 chunk는 텅 빈 껍데기 (소유권이 람다로 넘어감)

  소유권 이전 흐름입니다.

  queue_  →(std::move)→  chunk  →(std::move 캡처)→  람다
    ①                     ②                          ③
  데이터                 임시 보관              최종 소유자
                      (람다로 넘기고 끝)      (async_write 완료까지)

  람다가 소유권을 갖고 있으므로 async_write 완료 콜백이 호출될 때까지 데이터는 안전합니다. 복사도 한 번도 없습니다.
  
>> ws_->async_write(net::buffer(chunk), 애서 net::buffer(chunk) 의미 설명해줘.  
⏺ net::buffer(chunk)는 "이 메모리 영역을 전송 버퍼로 사용해라" 는 뜻입니다.

  ---
  net::buffer 역할
  
  chunk(실제 데이터)를 async_write가 이해할 수 있는 버퍼 뷰로 감쌉니다.

  std::vector<uint8_t> chunk = {0x01, 0x02, 0x03, ...};  // 실제 데이터

  net::buffer(chunk)
  // chunk의 시작 주소와 크기를 담은 뷰
  // 데이터를 복사하지 않음
  // 그냥 "여기서부터 이만큼 읽어라" 는 포인터+크기 정보

  ---
  복사 없는 뷰
  
  chunk (vector)
  ┌──────────────────────────┐
  │ 0x01 0x02 0x03 0x04 ...  │  ← 실제 데이터
  └──────────────────────────┘
           ↑
  net::buffer(chunk)
  ┌─────────────────┐
  │ ptr: 시작 주소  │  ← 포인터만 가리킴
  │ size: 바이트 수 │     데이터 복사 없음
  └─────────────────┘

  ---
  async_write가 net::buffer를 요구하는 이유
  
  async_write는 다양한 타입(vector, string, array 등)의 데이터를 받을 수 있어야 합니다. net::buffer로 감싸면 타입에 상관없이 동일한 인터페이스로 처리할 수 있습니다.

  // 전부 net::buffer로 감싸서 전달 가능
  net::buffer(std::vector<uint8_t>{...})
  net::buffer(std::string("EOS"))
  net::buffer(std::array<char, 256>{...})

  ---
  정리

  net::buffer(chunk)  =  chunk의 주소 + 크기를 담은 뷰
                          데이터 복사 없음
                          async_write가 이 정보로 직접 전송

  그래서 chunk가 람다 캡처로 살아있어야 합니다. net::buffer는 복사 없이 chunk를 가리키기만 하므로, 전송 완료 전에 chunk가 사라지면 허상 포인터가 됩니다.


>> net::buffer(chunk) 으로 타입 전환이네. 맞어? 
⏺ 네, 맞습니다.

  std::vector<uint8_t> chunk  →  net::buffer(chunk)

  정확히는 타입 변환보다 래핑(wrapping) 이라고 표현합니다.

  std::vector<uint8_t>          net::const_buffer
  ┌─────────────────────┐       ┌───────────────────┐
  │ data: 0x01 0x02 ... │  →    │ ptr:  0x7fff...   │
  │ size: 1024          │       │ size: 1024        │
  │ capacity: ...       │       └───────────────────┘
  └─────────────────────┘       (주소+크기만 담은 뷰)
     실제 데이터 소유               데이터 소유 안 함

  vector가 가진 여러 정보 중에서 async_write에 필요한 주소+크기 만 뽑아서 net::buffer 타입으로 감싸는 겁니다. 데이터는 복사 안 합니다.

  static_cast처럼 타입 자체를 바꾸는 게 아니라, async_write가 읽을 수 있는 형태로 포장하는 개념입니다.
  
>> writing_ = true; => writing_ 타입에 atomic 필요 없는지?
⏺ writing_은 bool입니다. atomic 필요 없습니다.

  ---
  이유
  
  writing_에 접근하는 코드가 전부 ioc_ 스레드에서만 실행되기 때문입니다.

  void RtzrWebSocketSttService::sendChunk(...) {
      net::post(ioc_, [self]() {        // ioc_ 스레드로 전달
          if (!self->writing_)          // ioc_ 스레드에서 접근
              self->doWrite();
      });
  }
  
  void RtzrWebSocketSttService::complete() {
      net::post(ioc_, [self]() {        // ioc_ 스레드로 전달
          if (!self->writing_)          // ioc_ 스레드에서 접근
              self->doWrite();
      });
  }

  net::post(ioc_, ...)가 핵심입니다. 외부에서 호출해도 실제 실행은 ioc_ 스레드로 넘겨서 처리합니다.

  외부 스레드                    ioc_ 스레드
       │                              │
       │  sendChunk() 호출            │
       │  net::post(ioc_, 람다) ───→  │  람다 실행
       │                              │  writing_ 접근 (여기서만)
       │  complete() 호출             │
       │  net::post(ioc_, 람다) ───→  │  람다 실행
       │                              │  writing_ 접근 (여기서만)

  writing_, queue_, eosSignaled_ 모두 항상 ioc_ 스레드에서만 접근하므로 atomic 없이 안전합니다. 주석 // accessed only on ioc_ thread가 바로 이 의미입니다.

  ---
  atomic이 필요한 경우
  
  여러 스레드에서 직접 접근할 때입니다.

  // 스레드A, 스레드B가 동시에 접근
  bool writing_ = false;          // 위험
  std::atomic<bool> writing_;     // 안전

  현재 구조는 net::post로 항상 같은 스레드로 직렬화하므로 atomic 대신 설계로 안전을 보장합니다.


```

# sendChunk, complete 언제 호출 되는지 알려줘.
```
⏺ 호출 위치는 모두 WsServer.cpp의 WsSession에 있습니다.

  ---
  sendChunk — 브라우저에서 바이너리 프레임이 올 때

  // WsServer.cpp:66-70
  if (isBinary) {
      if (self->stt_) {
          auto* p = reinterpret_cast<const uint8_t*>(payload.data());
          self->stt_->sendChunk({p, p + payload.size()});
      }
  }

  브라우저(CTI 시뮬레이터)가 마이크 음성을 바이너리 WebSocket 프레임으로 보낼 때마다 호출됩니다.

  브라우저 마이크
      │ 250ms마다 오디오 청크 전송 (binary frame)
      ▼
  WsSession::doRead()
      └─ isBinary = true
              └─ stt_->sendChunk(chunk)   ← 여기서 호출

  ---
  complete — 두 가지 상황에서 호출

  ① 브라우저 WebSocket 연결이 끊길 때 (전화 끊김)

  // WsServer.cpp:54-58
  if (ec == ws::error::closed || ec == net::error::eof) {
      self->cancelled_ = true;
      if (self->stt_) self->stt_->complete();  // ← 여기서 호출
      return;
  }

  브라우저가 탭을 닫거나 전화가 끊기면 WebSocket 연결이 종료되고, 그 시점에 호출됩니다.

  ② CTI_EVENT CALL_END 메시지를 받을 때

  // WsServer.cpp:85-87
  if (type == "CTI_EVENT" && j.value("event", "") == "CALL_END") {
      if (stt_) stt_->complete();  // ← 여기서 호출
  }

  브라우저가 발화 종료를 명시적으로 알릴 때 호출됩니다.

  ---
  전체 흐름

  브라우저
      │
      ├─ binary frame (음성 청크)  →  sendChunk()  →  RTZR로 전송
      ├─ binary frame (음성 청크)  →  sendChunk()  →  RTZR로 전송
      ├─ binary frame (음성 청크)  →  sendChunk()  →  RTZR로 전송
      │
      └─ CALL_END 이벤트 또는 연결 끊김
                 │
                 └─ complete()  →  EOS 프레임 전송  →  RTZR 최종 결과 반환

  sendChunk는 발화 중 계속 호출되고, complete는 발화가 끝났을 때 딱 한 번 호출됩니다.
```

# `onMessage()` 설명해줘
```
bool RtzrWebSocketSttService::onMessage(const std::string& text) {
    try {
        auto j = json::parse(text);

        if (j.contains("error")) {
            onError_(j["error"].get<std::string>());
            return false;
        }

        bool isFinal = j.value("final", false);
        auto alts = j.value("alternatives", json::array());
        if (!alts.empty()) {
            std::string recognized = alts[0].value("text", std::string());
            LOG_INFO("[STT-RTZR] callId={} final={} text={}", callId_, isFinal, recognized);
            onResult_({recognized, isFinal});

            if (isFinal) {
                ws_->async_close(ws::close_code::normal,
                    [self = shared_from_this()](beast::error_code) {});
                return false;
            }
        }
    } catch (const std::exception& e) {
        LOG_ERROR("[STT-RTZR] 파싱 오류: {}", e.what());
    }
    return true;
}

  ---
  전체 역할

  RTZR 서버가 보낸 JSON 메시지를 파싱해서 STT 결과를 콜백으로 전달합니다. 반환값으로 doRead() 루프를 계속할지 종료할지 결정합니다.

  ---
  ① JSON 파싱

  auto j = json::parse(text);

  RTZR이 보내는 메시지 형식은 두 가지입니다.

  // 중간 결과
  { "final": false, "alternatives": [{ "text": "안녕하" }] }

  // 최종 결과
  { "final": true,  "alternatives": [{ "text": "안녕하세요" }] }

  // 에러
  { "error": "invalid audio format" }

  ---
  ② 에러 처리

  if (j.contains("error")) {
      onError_(j["error"].get<std::string>());
      return false;
  }

  RTZR이 에러를 보내면 onError_ 콜백을 호출하고 false를 반환해서 루프를 종료합니다.

  ---
  ③ 결과 추출

  bool isFinal = j.value("final", false);
  auto alts = j.value("alternatives", json::array());
  if (!alts.empty()) {
      std::string recognized = alts[0].value("text", std::string());

  alternatives는 인식 후보 목록입니다. RTZR은 여러 후보를 줄 수 있지만 alts[0]으로 가장 확률 높은 첫 번째 결과만 사용합니다.

  j.value("final", false)의 두 번째 인자는 키가 없을 때의 기본값입니다. final 키가 없으면 false로 취급합니다.

  ---
  ④ 콜백 호출

  onResult_({recognized, isFinal});

  SttResult 구조체로 묶어서 호출자에게 전달합니다.

  onResult_는 WsServer.cpp에서 이렇게 등록된 람다입니다.

  // WsServer.cpp:95-98
  [self = shared_from_this()](SttResult r) {
      if (!r.isFinal) return;   // 중간 결과는 무시
      net::post(self->strand_,
          [self, text = r.text]() { self->handleFinalStt(text); });
  }

  중간 결과(isFinal=false)는 무시하고, 최종 결과(isFinal=true)만 LLM으로 넘깁니다.

  ---
  ⑤ 최종 결과 시 WS 닫기

  if (isFinal) {
      ws_->async_close(ws::close_code::normal,
          [self = shared_from_this()](beast::error_code) {});
      return false;
  }

  최종 결과가 오면 RTZR과의 WebSocket을 정상 종료하고 false를 반환해서 doRead() 루프를 멈춥니다.

  ---
  반환값 정리

  return true   중간 결과 → doRead() 재호출 (계속 수신)
  return false  최종 결과 또는 에러 → doRead() 종료

  ---
  전체 흐름

  RTZR 서버
      │
      ├─ {"final": false, ...}  →  onResult_() 호출 (중간)  →  return true  →  doRead() 재호출
      ├─ {"final": false, ...}  →  onResult_() 호출 (중간)  →  return true  →  doRead() 재호출
      └─ {"final": true,  ...}  →  onResult_() 호출 (최종)  →  WS close     →  return false  →  루프 종료
```

# isFinal is True 일 때 콜백이 아무러 동작이 없는데 맞어?
```
⏺ 아닙니다. async_close 이전에 이미 콜백이 호출됩니다.

  // ③ 콜백 호출 (isFinal=true 포함, 항상 호출됨)
  onResult_({recognized, isFinal});

  // ④ isFinal일 때 추가로 WS 닫기
  if (isFinal) {
      ws_->async_close(ws::close_code::normal,
          [self = shared_from_this()](beast::error_code) {});
      return false;
  }

  순서를 보면:

  onResult_({recognized, isFinal=true})  ← 콜백 먼저 호출
          │
          └─ WsServer.cpp 의 람다 실행
                  if (!r.isFinal) return;   // false니까 통과
                  handleFinalStt(text)      // LLM → TTS 파이프라인 시작

  ws_->async_close(...)   ← 그 다음 RTZR WebSocket 종료

  async_close의 콜백 [](beast::error_code) {}이 아무것도 안 하는 것처럼 보여서 헷갈리셨을 것 같은데, 이건 닫기 완료 후 할 일이 없어서 비워둔 겁니다. STT 결과 전달은 그 위의 onResult_에서 이미
  끝났습니다.
```

# SpringLlmService::chat() 에서 curl_slist 관련 부분 설명해줘.
```
>> 참조 소스
      struct curl_slist* headers = nullptr;
      headers = curl_slist_append(headers, "Content-Type: application/json");
  ...
      curl_slist_free_all(headers);
  ...
  
⏺ curl_slist란

  libcurl에서 HTTP 헤더를 전달하기 위한 단방향 연결 리스트입니다.

  curl_slist
  ┌─────────────────┐     ┌─────────────────┐
  │ "Content-Type:  │ ──▶ │ (다음 헤더)      │ ──▶ nullptr
  │  application/   │     │                 │
  │  json"          │     │                 │
  └─────────────────┘     └─────────────────┘
        node 1                  node 2

  각 노드가 헤더 문자열 하나를 담고 다음 노드를 가리키는 구조입니다.

  ---
  세 줄의 역할

  ① 리스트 초기화
  struct curl_slist* headers = nullptr;
  빈 리스트를 만듭니다. nullptr은 "아직 아무 헤더도 없다"는 뜻입니다.

  ② 헤더 추가
  headers = curl_slist_append(headers, "Content-Type: application/json");
  노드를 새로 할당하고 문자열을 복사해서 리스트에 연결합니다. 반환값이 새 리스트의 헤드이므로 반드시 headers에 다시 받아야 합니다.

  헤더를 더 추가하려면 이렇게 이어서 씁니다.
  headers = curl_slist_append(headers, "Content-Type: application/json");
  headers = curl_slist_append(headers, "X-Call-Id: abc123");
  headers = curl_slist_append(headers, "Authorization: Bearer token");

  ③ curl에 전달
  curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
  리스트의 헤드 포인터를 curl에 넘깁니다. curl이 내부적으로 리스트를 순회하며 모든 헤더를 HTTP 요청에 포함합니다.

  ④ 해제
  curl_slist_free_all(headers);
  curl_slist_append가 할당한 모든 노드를 한 번에 해제합니다. free_all인 이유는 노드가 여러 개일 수 있어서 한 번에 전부 해제하기 때문입니다.

  ---
  왜 직접 해제해야 하나

  curl_slist는 C 라이브러리라 RAII가 없습니다. new/delete 대신 curl_slist_append/curl_slist_free_all 쌍으로 수동 관리해야 합니다.

  curl_slist_append()   ← 힙 할당 (new 역할)
  curl_slist_free_all() ← 힙 해제 (delete 역할)

  이 코드에서 curl_easy_perform() 이후 바로 해제하는 이유는, perform()이 완료되는 시점에 curl이 헤더를 이미 다 사용했기 때문입니다.

```

# SpringLlmService::chat() 사용처 알려줘.
```
⏺ WsServer.cpp:123의 handleFinalStt()에서 호출됩니다.

  // WsServer.cpp:120-135
  std::thread([self = shared_from_this(), hist]() mutable {
      auto llmRaw = self->llm_->chat(hist, self->callId_);  // ← 여기
      ...
      auto audioBytes = self->tts_->synthesize(response, self->callId_);
      ...
  }).detach();

  ---
  호출 맥락

  STT 최종 결과가 나온 직후, 별도 스레드에서 LLM → TTS 순서로 호출됩니다.

  RTZR → onMessage() isFinal=true
      └─ onResult_() 콜백
              └─ handleFinalStt(text)
                      └─ std::thread 생성 (별도 스레드)
                              ├─ llm_->chat()       ← SpringLlmService::chat() 실행
                              └─ tts_->synthesize()

  별도 스레드에서 실행하는 이유는 curl_easy_perform()이 응답이 올 때까지 블로킹하기 때문입니다. 메인 ioc_ 스레드를 막으면 다른 WebSocket 세션 전체가 멈추므로, LLM/TTS 같은 무거운 HTTP 요청은 별도
  스레드로 분리합니다.

```

# 이 프로젝트는 ioc 가 여러개 있어?
```
⏺ ioc는 두 개입니다. 역할이 서로 다릅니다.

  ---
  ioc가 두 개인 이유

  ① main.cpp의 ioc — 서버 전용

  net::io_context ioc;                          // main에서 생성
  WsServer(ioc, port, ...)                      // WsServer에 참조로 전달
  ioc.run();                                    // 메인 스레드에서 실행

  브라우저 WebSocket 연결 수락, CTI 이벤트 수신, 결과 전송 등 서버 전체를 담당합니다. 모든 WsSession이 이 하나의 ioc를 공유합니다.

  ---
  ② RtzrWebSocketSttService의 ioc_ — STT 전용

  // RtzrWebSocketSttService.h
  net::io_context ioc_;  // 클래스 멤버, 인스턴스마다 독립

  RTZR 서버와의 WebSocket 연결(TCP → SSL → WS)만 담당합니다. recognize()에서 별도 스레드에서 실행됩니다.

  thread_ = std::thread([this]() {
      doConnect();
      ioc_.run();   // 별도 스레드에서 실행
  });

  ---
  전체 구조

  메인 스레드
      ioc (서버용)
          ├─ WsSession A
          │       └─ 브라우저 ↔ 서버 통신
          ├─ WsSession B
          └─ WsSession C

  STT 스레드 (세션마다 하나)
      ioc_ (STT 전용, RtzrWebSocketSttService 멤버)
          └─ RTZR 서버 ↔ C++ 서버 통신

  둘을 분리한 이유는 RTZR과의 연결이 끊기거나 재시작(ioc_.stop() → restart())될 때 서버 전체 ioc에 영향을 주지 않기 위해서입니다.

```

# STT 스레드 (세션마다 하나) => 여기서 세션은 WsSession 말하는거야?
```
⏺ 예, 맞습니다.

  WsSession 하나(= 브라우저 연결 1개 = 전화 통화 1건)가 RtzrWebSocketSttService를 하나 생성합니다.

  // WsServer.cpp:93
  void startStt() {
      if (!stt_) stt_ = std::make_shared<RtzrWebSocketSttService>(tokenMgr_);
      stt_->recognize(...);
  }

  그리고 RtzrWebSocketSttService마다 ioc_와 thread_가 멤버로 있으므로, 브라우저 연결이 3개면 이렇게 됩니다.

  메인 스레드       ioc (서버용)
                      ├─ WsSession A  →  stt_ (RtzrWebSocketSttService)
                      ├─ WsSession B  →  stt_ (RtzrWebSocketSttService)
                      └─ WsSession C  →  stt_ (RtzrWebSocketSttService)

  STT 스레드 A      ioc_ (A 전용)  →  RTZR 연결
  STT 스레드 B      ioc_ (B 전용)  →  RTZR 연결
  STT 스레드 C      ioc_ (C 전용)  →  RTZR 연결

  WsSession 1개당 STT 스레드 1개입니다.
```

# RtzrWebSocketSttService.recognize() 내용을 보면 발화가 끝나고 연결 유지하다가 발화가 다시 시작되면 연결종료&재연결 하고서 다시 접속해서 STT 하는 것 같은데 맞어?
```
⏺ 맞습니다. 정확히 파악하셨습니다.

  흐름을 보면:

  ① WsSession 연결됨
          └─ startStt()  →  recognize()  →  RTZR 연결

  ② 브라우저가 오디오 전송
          └─ sendChunk() 반복

  ③ 발화 종료 (CALL_END or 연결 끊김)
          └─ complete()  →  EOS 전송  →  RTZR 최종 결과 반환

  ④ onMessage() isFinal=true
          └─ ws_->async_close()  →  RTZR 연결 종료

  ⑤ handleFinalStt()  →  LLM  →  TTS  →  브라우저로 응답 전송

  ⑥ 다음 발화 대기
          └─ startStt()  →  recognize()
                  └─ recognize() 내부:
                          if (thread_.joinable()) {
                              ioc_.stop()    // 이전 스레드 종료
                              thread_.join()
                              ioc_.restart()
                          }
                          // 상태 초기화
                          // 새 스레드로 RTZR 재연결

  recognize()의 이전 세션 정리 코드가 바로 이 재연결을 위한 겁니다. 발화마다 RTZR WebSocket을 새로 연결하는 구조입니다.

```

# io_context 가 요청 간의 총돌을 막는 역할로 알고 있는데 RtzrWebSocketSttService 에서 세션당 1개 이면 굳이 필요가 있는건지? 존재 이유?
```
⏺ 좋은 질문입니다. io_context의 역할을 충돌 방지로만 알고 계신 것 같은데, 그건 strand의 역할입니다.

  ---
  io_context의 실제 역할

  io_context는 비동기 I/O 엔진 자체입니다.

  async_resolve()   ─┐
  async_connect()   ─┤  등록
  async_read()      ─┤  ──▶  io_context  ──▶  OS (epoll/kqueue)
  async_write()     ─┘                            │
                                                  │ I/O 완료 이벤트
                                      콜백 실행 ◀──┘

  async_* 함수들은 "나중에 이 콜백 실행해줘"를 io_context에 등록만 합니다. ioc.run()이 OS에 물어보며 완료된 I/O를 콜백으로 실행합니다. io_context 없이는 비동기 동작 자체가 불가능합니다.

  ---
  strand vs io_context 차이

  ┌────────┬────────────────────────┬───────────────────────────────────────────────┐
  │        │       io_context       │                    strand                     │
  ├────────┼────────────────────────┼───────────────────────────────────────────────┤
  │ 역할   │ 비동기 I/O 이벤트 루프 │ 콜백 간 실행 순서 보장                        │
  ├────────┼────────────────────────┼───────────────────────────────────────────────┤
  │ 없으면 │ 비동기 동작 자체 불가  │ 여러 스레드에서 같은 ioc 쓸 때 race condition │
  └────────┴────────────────────────┴───────────────────────────────────────────────┘

  WsServer.cpp를 보면 둘 다 씁니다.

  // WsServer.cpp
  net::io_context& ioc_;                               // 이벤트 루프
  net::strand<net::io_context::executor_type> strand_; // 충돌 방지

  서버 ioc는 여러 WsSession이 공유하므로 strand로 충돌을 막습니다. RtzrWebSocketSttService는 세션 전용이라 strand가 없어도 됩니다.

  ---
  세션 전용이어도 io_context가 필요한 이유

  // RtzrWebSocketSttService.cpp
  thread_ = std::thread([this]() {
      doConnect();
      ioc_.run();  // 이 스레드에서 이벤트 루프 실행
  });

  STT 전용 스레드에서 이벤트 루프를 독립적으로 돌리기 위해 필요합니다. 만약 서버 ioc를 그대로 쓰면:

  서버 ioc.run() 실행 중
      └─ RTZR 재연결 시 ioc_.stop() → ioc_.restart()
              └─ 서버 전체가 멈춤 ← 다른 WsSession 전부 영향

  세션 전용 ioc_를 따로 두기 때문에 RTZR 재연결이 서버 전체에 영향을 주지 않습니다.
```

# STT 는 꼭 발화가 끝나고 다시 시작 시 RTZR 재연결을 꼭 해야하는거야? 기존 연결로는 안 되는거야?
```
⏺ 이건 코드 설계 문제와 RTZR 프로토콜 문제 두 가지로 나눠서 봐야 합니다.

  ---
  RTZR 프로토콜 관점

  RTZR /v1/transcribe:streaming은 1 세션 = 1 발화 구조로 설계되어 있습니다.

  연결
   │
   ├─ 오디오 청크 전송 (binary)
   ├─ 오디오 청크 전송 (binary)
   ├─ EOS 전송 (text "EOS")
   │
   └─ 최종 결과 수신 → 세션 종료

  EOS를 보내면 RTZR이 최종 결과를 반환하고 세션을 닫습니다. 이후 같은 연결로 새 오디오를 보내도 RTZR이 받아주지 않습니다. 이건 RTZR이 정한 규칙입니다.

  ---
  코드에서도 명시적으로 닫음

  // onMessage() - isFinal=true 시
  if (isFinal) {
      ws_->async_close(ws::close_code::normal, ...);  // 연결 종료
      return false;
  }

  RTZR이 최종 결과를 주는 시점에 클라이언트 쪽에서도 명시적으로 연결을 끊습니다.

  ---
  재연결이 불가피한 이유 요약

  발화 1 종료
      └─ EOS 전송 → RTZR 최종 결과 → WebSocket 닫힘 (프로토콜 규칙)

  발화 2 시작
      └─ 새 WebSocket 연결 필수 (기존 연결 재사용 불가)

  만약 연결을 유지하면서 다발화를 처리하고 싶다면 RTZR이 그런 API를 제공해야 하는데, 현재는 지원하지 않습니다. 재연결은 설계 선택이 아니라 RTZR 프로토콜의 제약입니다.

```

# SpringTtsService::synthesize() 에서 nlohmann::json(text).dump() 에서 dump() 의미?
```
>> 참조 소스
std::vector<uint8_t> SpringTtsService::synthesize(const std::string& text,
                                                   const std::string& callId) {
    std::string url = springUrl_ + "/api/cti/tts/synthesize";
    std::string body = nlohmann::json(text).dump();
    std::vector<uint8_t> response;

    auto start = std::chrono::steady_clock::now();

    CURL* curl = curl_easy_init();
    if (!curl) throw std::runtime_error("curl_easy_init failed");

    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, body.size());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curlWriteBytes);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);

    CURLcode res = curl_easy_perform(curl);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();
    LOG_INFO("[TTS-PERF] callId={} elapsed={}ms", callId, elapsed);

    if (res != CURLE_OK)
        throw std::runtime_error(std::string("TTS HTTP 오류: ") + curl_easy_strerror(res));

    return response;
}

>> 단어
- synthesize (신서사이즈)
    - 어원: syn- = 함께, thesis = 놓다, 두다
        즉, 원래는 "여러 개를 함께 모아 하나로 만들다"라는 뜻입니다.
    - 주요 뜻
        1. 종합하다
        2. 합성하다
        3. 생성하다 (음성·음악·AI 분야)
        
⏺ 두 단계로 나눠서 설명할게요.

  ---
  nlohmann::json(text)

  C++ 문자열 text를 JSON 값 객체로 변환합니다.

  std::string text = "안녕하세요";
  nlohmann::json j = nlohmann::json(text);
  // j는 JSON string 값 → "안녕하세요"

  ---
  .dump()

  JSON 객체를 직렬화된 JSON 문자열로 변환합니다.

  j.dump()
  // 결과: "\"안녕하세요\""
  //        ↑           ↑
  //     큰따옴표가 포함된 JSON 문자열

  ---
  왜 이렇게 쓰는가

  text를 그냥 body로 보내면 JSON 형식이 아닙니다.

  // 그냥 text 전송 (❌ JSON 아님)
  body = "안녕하세요"

  // nlohmann::json(text).dump() (✅ 유효한 JSON)
  body = "\"안녕하세요\""

  Content-Type: application/json으로 보내는데 body가 JSON 규격이 아니면 Spring 서버가 파싱 오류를 냅니다. 특수문자나 따옴표가 포함된 문자열도 자동으로 이스케이프해줍니다.

  std::string text = "저는 \"홍길동\"입니다";

  // 직접 보내면
  body = "저는 \"홍길동\"입니다"  // ❌ JSON 파싱 오류

  // dump() 사용하면
  body = "\"저는 \\\"홍길동\\\"입니다\""  // ✅ 올바른 JSON 이스케이프
  
>> 둘의 차이점이 뭐야?
>>>> 참조
// 직접 보내면
body = "저는 \"홍길동\"입니다"  // ❌ JSON 파싱 오류

// dump() 사용하면
    body = "\"저는 \\\"홍길동\\\"입니다\""  // ✅ 올바른 JSON 이스케이프
    
⏺ 핵심 차이

  JSON에서 문자열 값은 반드시 큰따옴표로 감싸야 합니다.

  유효한 JSON 문자열:   "안녕하세요"
  유효하지 않은 JSON:    안녕하세요

  ---
  직접 보내면 왜 오류인가

  저는 "홍길동"입니다

  Spring이 이걸 JSON으로 파싱하려 하면:

  큰따옴표가 없음 → "이게 문자열인지 숫자인지 뭔지 모르겠다" → 파싱 오류

  ---
  dump() 사용하면 왜 되는가

  "저는 \"홍길동\"입니다"

  Spring이 파싱하면:

  맨 앞뒤 큰따옴표 → "아, JSON 문자열이구나"
  내부 \" → "이스케이프된 큰따옴표, 실제 내용에 포함"
  → 파싱 결과: 저는 "홍길동"입니다  ✅

  ---
  한 줄 요약

  dump()가 하는 일은 바깥에 큰따옴표를 씌우고, 안의 특수문자를 이스케이프 하는 것입니다.

  입력:  저는 "홍길동"입니다
  출력:  "저는 \"홍길동\"입니다"
          ↑                    ↑
       바깥 큰따옴표        내부 큰따옴표 이스케이프
       
>> "\"저는 \\\"홍길동\\\"입니다\"" 에서 \\\" 뭐야?
⏺ C++ 문자열 리터럴과 실제 전송되는 문자열 두 단계로 보면 이해됩니다.

  ---
  C++ 문자열 리터럴에서 이스케이프

  C++ 소스코드에서 \"는 큰따옴표 문자 하나를 의미합니다.

  "\""   →  실제 문자: "
  "\\"   →  실제 문자: \
  "\\\""  →  실제 문자: \"

  ---
  "\"저는 \\\"홍길동\\\"입니다\"" 분해

  \"        →  "        (앞 큰따옴표)
  저는

  \\        →  \        (백슬래시)
  \"        →  "        (큰따옴표)
  홍길동
  \\        →  \        (백슬래시)
  \"        →  "        (큰따옴표)
  입니다
  \"        →  "        (뒤 큰따옴표)

  ---
  실제 전송되는 문자열

  "저는 \"홍길동\"입니다"

  Spring이 이 JSON을 파싱하면:

  바깥 큰따옴표  →  JSON 문자열임을 표시
  \"            →  실제 큰따옴표 문자
  → 파싱 결과:  저는 "홍길동"입니다

  ---
  정리

  C++ 소스코드             실제 전송 (HTTP body)     Spring 파싱 결과
  "\"저는 \\\"홍길동\\\"입니다\""  →  "저는 \"홍길동\"입니다"  →  저는 "홍길동"입니다
       C++ 이스케이프                   JSON 이스케이프              원본 문자열

  이스케이프가 두 겹인 이유는 C++ 문자열 리터럴과 JSON 문자열 두 곳에서 각각 이스케이프가 필요하기 때문입니다.

```

# 모든 헤더 소스에 #pragma once 뭐야?
```
⏺ #pragma once란

  헤더 파일이 한 번만 포함되도록 보장하는 전처리기 지시문입니다.

  ---
  없으면 어떤 문제가 생기나

  // A.h
  struct Foo { int x; };

  // B.h
  #include "A.h"

  // C.h
  #include "A.h"

  // main.cpp
  #include "B.h"
  #include "C.h"
  // 결과: A.h가 두 번 포함됨
  // → struct Foo 중복 정의 오류

  ---
  #pragma once 가 있으면

  // A.h
  #pragma once
  struct Foo { int x; };

  // main.cpp
  #include "B.h"   // A.h 첫 번째 포함 → 처리
  #include "C.h"   // A.h 두 번째 포함 → 이미 포함됐으니 건너뜀
  // 결과: struct Foo 한 번만 정의 ✅

  ---
  전통적인 방식과 비교

  #pragma once 이전에는 include guard를 직접 썼습니다.

  // 전통 방식
  #ifndef RTZR_WEBSOCKET_STT_SERVICE_H
  #define RTZR_WEBSOCKET_STT_SERVICE_H

  // 헤더 내용

  #endif

  // pragma once 방식
  #pragma once

  // 헤더 내용

  둘은 동일한 역할이지만 #pragma once가 훨씬 간결합니다. 현대 C++ 프로젝트에서는 #pragma once를 표준처럼 씁니다.

```

# CallSession 은 언제 사용돼?
```
⏺ 현재는 사용되지 않습니다.

  CallSession.h에 정의만 있고, 어디서도 #include하거나 인스턴스를 만들지 않습니다.

  ---
  실제로 같은 역할을 WsSession이 직접 담당

  CallSession이 담으려 했던 데이터들을 WsSession 멤버로 직접 갖고 있습니다.

  // WsServer.cpp - WsSession 멤버
  std::string sessionId_;                    // CallSession.sessionId 와 동일
  std::string callId_;                       // CallSession.callId 와 동일
  std::vector<LlmService::Message> history_; // CallSession.history 와 동일

  CallSession은 초기 설계 시 세션 상태를 별도 구조체로 분리하려 했던 흔적으로 보입니다. 이후 WsSession 안으로 통합되면서 사용되지 않게 된 미사용 코드입니다.

```

# SpringStyleDailyFileSink 클래스 설명해줘.
```
>> 단어 뜻
- sink (싱크, /sɪŋk/)
    - 1. 일반 영어: 싱크대, 가라앉다, 빠지다, 떨어지다
    - 3. IT/네트워크/비동기 프로그래밍: 데이터를 받아들이는 종착점, 데이터가 흘러 들어가는 목적지
        - 반대 개념은 source(데이터를 만드는 쪽)입니다.
        - Source  --->  Sink
          (생산)        (소비)
⏺ ---
  spdlog 먼저

  spdlog는 C++ 로깅 라이브러리입니다. 핵심 개념은 logger → sink 구조입니다.

  LOG_INFO("메시지")
      └─ logger
              ├─ consoleSink  →  터미널 출력
              └─ fileSink     →  파일 출력

  logger가 메시지를 받아서 연결된 sink들에게 전달합니다. sink는 "메시지를 실제로 어디에 쓸지"를 담당합니다.

  base_sink<std::mutex>를 상속받으면 커스텀 sink를 직접 만들 수 있습니다. SpringStyleDailyFileSink가 바로 그 커스텀 sink입니다.

  ---
  SpringStyleDailyFileSink 전체 역할

  Spring logback 스타일의 날짜별 로그 파일 관리입니다.

  평소:       cpp-ws.log  (현재 로그 계속 기록)

  날짜 바뀌면:
      cpp-ws.log → cpp-ws.2026-06-11.log  (어제 날짜로 이름 변경)
      cpp-ws.log  (새로 생성, 오늘 로그 기록 시작)

  7일 넘으면:  가장 오래된 파일 삭제

  ---
  멤버 변수

  fs::path      baseFile_;     // 현재 로그 파일 경로 "logs/cpp-ws.log"
  int           maxDays_;      // 보관할 최대 날 수 (기본값 7)
  std::ofstream file_;         // 현재 열려있는 파일 스트림
  std::string   currentDate_;  // 오늘 날짜 문자열 "2026-06-12"

  ---
  멤버 함수

  생성자
  SpringStyleDailyFileSink(fs::path baseFile, int maxDays = 7)
  오늘 날짜를 currentDate_에 저장하고 파일을 엽니다.

  ---
  sink_it_() — 로그 한 줄 쓰기 (spdlog가 자동 호출)
  void sink_it_(const spdlog::details::log_msg& msg)
  LOG_INFO 등이 호출될 때마다 spdlog가 이 함수를 부릅니다.

  오늘 날짜 확인
      ├─ 어제와 같으면 → 그냥 파일에 씀
      └─ 날짜 바뀌었으면 → rotate() 먼저 → 새 파일에 씀

  ---
  flush_() — 버퍼 비우기 (spdlog가 자동 호출)
  void flush_() override { file_.flush(); }
  메모리 버퍼에 쌓인 내용을 실제 디스크에 씁니다.

  ---
  todayStr() — 오늘 날짜 문자열 반환
  static std::string todayStr()  // 반환: "2026-06-12"
  static이라 인스턴스 없이도 호출 가능합니다.

  ---
  openFile() — 파일 열기
  void openFile()
  std::ios::app 모드로 열어서 기존 내용에 이어 씁니다.

  ---
  rotate() — 날짜 바뀔 때 파일 교체
  void rotate()
  1. 현재 파일 닫기
  2. cpp-ws.log → cpp-ws.2026-06-11.log 이름 변경
  3. 오래된 파일 삭제 (cleanOldFiles)
  4. 새 cpp-ws.log 열기

  ---
  cleanOldFiles() — 오래된 로그 삭제
  void cleanOldFiles(dir, stem, ext)
  logs/ 폴더에서 "cpp-ws.YYYY-MM-DD.log" 패턴 파일 수집
      → 날짜순 정렬
      → maxDays_(7) 초과분을 오래된 것부터 삭제
```

# SpringStyleDailyFileSink.sink_it_() 설명해줘.
```
>> 참조 소스
void sink_it_(const spdlog::details::log_msg& msg) override {
      auto today = todayStr();
      if (today != currentDate_) {
          rotate();
          currentDate_ = today;
      }
      spdlog::memory_buf_t buf;
      formatter_->format(msg, buf);
      file_.write(buf.data(), static_cast<std::streamsize>(buf.size()));
  }

  ---
  log_msg란

  spdlog가 로그 한 줄의 정보를 담아서 넘겨주는 구조체입니다.

  log_msg {
      level,      // INFO / WARN / ERROR
      time,       // 발생 시각
      payload,    // 실제 메시지 "callId=abc elapsed=123ms"
      logger_name // "voicebot"
  }

  LOG_INFO("callId={} elapsed={}ms", callId, elapsed) 를 호출하면 spdlog가 이 구조체를 만들어서 sink_it_에 넘깁니다.

  ---
  ① 날짜 변경 감지

  auto today = todayStr();        // "2026-06-12"
  if (today != currentDate_) {    // 어제와 다르면
      rotate();                   // 파일 교체
      currentDate_ = today;       // 날짜 업데이트
  }

  로그가 쓰일 때마다 오늘 날짜를 확인합니다. 자정이 지나면 처음 로그가 들어오는 순간 rotate()가 실행됩니다.

  ---
  ② 포맷 적용

  spdlog::memory_buf_t buf;
  formatter_->format(msg, buf);

  memory_buf_t는 메모리 버퍼입니다. formatter_가 log_msg를 패턴에 맞게 문자열로 변환해서 버퍼에 씁니다.

  initLogger()에서 설정한 패턴이 여기서 적용됩니다.

  fileSink->set_pattern("%Y-%m-%d %H:%M:%S.%e [%-5l] %v");

  log_msg { level=INFO, time=..., payload="callId=abc" }
      ↓ formatter_->format()
  buf = "2026-06-12 14:30:01.123 [INFO ] callId=abc\n"

  ---
  ③ 파일에 쓰기

  file_.write(buf.data(), static_cast<std::streamsize>(buf.size()));

  버퍼의 내용을 cpp-ws.log 파일에 씁니다.

  buf.data()는 버퍼의 시작 포인터, buf.size()는 바이트 수입니다.

  ---
  전체 흐름

  LOG_INFO("[STT-RTZR] callId={}", callId)
      │
      └─ spdlog가 log_msg 생성
              │
              └─ sink_it_(msg) 호출
                      │
                      ├─ 날짜 확인 → 바뀌었으면 rotate()
                      ├─ formatter_->format() → 버퍼에 포맷된 문자열 생성
                      └─ file_.write() → 파일에 기록
```

# SpringStyleDailyFileSink.flush_() 설명해줘.
```
  ---
  버퍼란

  OS는 파일 쓰기 성능을 위해 데이터를 바로 디스크에 쓰지 않고 메모리 버퍼에 임시 보관합니다.

  file_.write("로그 메시지")
      │
      └─ OS 메모리 버퍼  ←  여기까지만 감
              │
              └─ (나중에 한꺼번에) 디스크

  버퍼가 꽉 차거나 프로그램이 종료될 때 디스크에 씁니다.

  ---
  flush()가 하는 일

  버퍼에 쌓인 내용을 즉시 디스크에 강제로 씁니다.

  OS 메모리 버퍼
      │ flush()
      ▼
  디스크 (cpp-ws.log)

  ---
  언제 호출되나

  initLogger()에서 이렇게 설정했습니다.

  logger->flush_on(spdlog::level::info);

  INFO 레벨 이상 로그가 쓰일 때마다 spdlog가 자동으로 flush_()를 호출합니다.

  ---
  flush가 없으면 생기는 문제

  서버 갑자기 크래시
      │
      └─ 버퍼에만 있던 로그 전부 사라짐 ← 디스크에 못 씀

  flush를 자주 하면 디스크 I/O가 늘어나지만, 로그가 유실되지 않습니다. 디버깅이 중요한 서버에서는 이 트레이드오프를 감수하고 flush를 씁니다.

```

# sink_it_, flush_ 은 spdlog 가 자동으로 호춣해주는 것 같아. SpringStyleDailyFileSink 클래스에 또 자동 호출되는 함수가 있나?
```
⏺ SpringStyleDailyFileSink에서 자동 호출되는 함수는 sink_it_과 flush_ 두 개뿐입니다.

  base_sink를 보면 virtual로 선언된 함수가 세 개입니다.

  virtual void sink_it_(...)        = 0;  // 순수 가상 → 반드시 구현
  virtual void flush_()             = 0;  // 순수 가상 → 반드시 구현
  virtual void set_pattern_(...)        ;  // 선택적 오버라이드

  set_pattern_은 오버라이드 가능하지만 SpringStyleDailyFileSink에서 구현하지 않았으므로 부모 기본 구현이 그대로 사용됩니다.

  ---
  호출 흐름

  sink_it_과 flush_는 직접 호출되지 않고, base_sink의 public 함수가 뮤텍스 잠금 후 대신 호출합니다.

  LOG_INFO()
      └─ log()          ← spdlog가 호출 (public, base_sink 구현)
              └─ mutex_ 잠금
                      └─ sink_it_()  ← 우리가 구현한 함수

  flush_on(info) 트리거
      └─ flush()        ← spdlog가 호출 (public, base_sink 구현)
              └─ mutex_ 잠금
                      └─ flush_()    ← 우리가 구현한 함수

  mutex_ 잠금을 base_sink가 대신 처리해주므로, sink_it_과 flush_ 안에서는 스레드 안전을 신경 쓰지 않아도 됩니다.

```

# `initLogger()` 설명해줘
```
>> 참조 소스
inline void initLogger() {
      fs::create_directories("logs");

      auto consoleSink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
      consoleSink->set_pattern("%H:%M:%S.%e [%^%-5l%$] %v");

      auto fileSink = std::make_shared<SpringStyleDailyFileSink>("logs/cpp-ws.log", 7);
      fileSink->set_pattern("%Y-%m-%d %H:%M:%S.%e [%-5l] %v");

      std::vector<spdlog::sink_ptr> sinks{consoleSink, fileSink};
      auto logger = std::make_shared<spdlog::logger>("voicebot", sinks.begin(), sinks.end());
      logger->set_level(spdlog::level::info);
      logger->flush_on(spdlog::level::info);
      spdlog::set_default_logger(logger);
      
  ---
  전체 역할

  서버 시작 시 한 번 호출되어 로거를 초기화합니다. 이후 LOG_INFO, LOG_WARN, LOG_ERROR가 동작합니다.

  ---
  ① logs 디렉토리 생성

  fs::create_directories("logs");

  logs/ 폴더가 없으면 만듭니다. 이미 있으면 무시합니다.

  ---
  ② 콘솔 sink 생성

  auto consoleSink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
  consoleSink->set_pattern("%H:%M:%S.%e [%^%-5l%$] %v");

  터미널에 색상과 함께 출력하는 sink입니다. _mt는 멀티스레드 안전을 의미합니다.

  패턴 결과:
  14:30:01.123 [INFO ] callId=abc elapsed=123ms
               ↑색상↑

  ┌─────────────┬─────────────────────────────────┐
  │    패턴     │              의미               │
  ├─────────────┼─────────────────────────────────┤
  │ %H:%M:%S.%e │ 시:분:초.밀리초                 │
  ├─────────────┼─────────────────────────────────┤
  │ %^ ~ %$     │ 색상 범위 (레벨에 따라 색 다름) │
  ├─────────────┼─────────────────────────────────┤
  │ %-5l        │ 레벨 문자열 왼쪽 정렬 5자리     │
  ├─────────────┼─────────────────────────────────┤
  │ %v          │ 실제 메시지                     │
  └─────────────┴─────────────────────────────────┘

  ---
  ③ 파일 sink 생성

  auto fileSink = std::make_shared<SpringStyleDailyFileSink>("logs/cpp-ws.log", 7);
  fileSink->set_pattern("%Y-%m-%d %H:%M:%S.%e [%-5l] %v");

  날짜별 파일로 저장하는 커스텀 sink입니다. 7일치 보관합니다.

  패턴 결과:
  2026-06-12 14:30:01.123 [INFO ] callId=abc elapsed=123ms

  콘솔 패턴과 다른 점은 날짜(%Y-%m-%d)가 앞에 추가되고 색상 코드(%^%$)가 없습니다. 파일에 색상 코드가 들어가면 텍스트가 깨집니다.

  ---
  ④ logger에 sink 두 개 연결

  std::vector<spdlog::sink_ptr> sinks{consoleSink, fileSink};
  auto logger = std::make_shared<spdlog::logger>("voicebot", sinks.begin(), sinks.end());

  LOG_INFO 한 번 호출하면 두 sink에 동시에 전달됩니다.

  LOG_INFO("메시지")
      └─ logger("voicebot")
              ├─ consoleSink  →  터미널 출력
              └─ fileSink     →  logs/cpp-ws.log 기록

  ---
  ⑤ 레벨 및 flush 설정

  logger->set_level(spdlog::level::info);   // INFO 미만은 무시
  logger->flush_on(spdlog::level::info);    // INFO 이상이면 즉시 flush

  set_level(info)는 DEBUG 레벨 로그는 출력하지 않겠다는 뜻입니다.

  DEBUG  →  무시
  INFO   →  출력 + 즉시 flush
  WARN   →  출력 + 즉시 flush
  ERROR  →  출력 + 즉시 flush

  ---
  ⑥ 기본 로거로 등록

  spdlog::set_default_logger(logger);

  이 설정 덕분에 spdlog::info()를 logger 인스턴스 없이 바로 호출할 수 있습니다.

  // 매크로 정의
  #define LOG_INFO(...)  spdlog::info(__VA_ARGS__)

  // 실제 동작
  spdlog::info(...)  →  default logger("voicebot")  →  두 sink로 전달
```

# `initLogger()` 어디서 호출돼?
```
⏺ 이미 main.cpp를 읽었으니 바로 답할 수 있습니다.

  // main.cpp:17
  int main() {
      initLogger();   // ← 여기서 호출
      ...
  }

  서버 시작 시 main()의 첫 번째 줄에서 호출됩니다. 이후 모든 코드에서 LOG_INFO, LOG_WARN, LOG_ERROR를 사용할 수 있습니다.

```

# 질문 내용을 좀 더 구체적이고 쉽게 설명해줘.
```
>> 질문 내용
   이 설정 덕분에 spdlog::info()를 logger 인스턴스 없이 바로 호출할 수 있습니다.

    // 매크로 정의
    #define LOG_INFO(...)  spdlog::info(__VA_ARGS__)

    // 실제 동작
    spdlog::info(...)  →  default logger("voicebot")  →  두 sink로 전달
    
⏺ 매크로란

  #define LOG_INFO(...) spdlog::info(__VA_ARGS__)는 텍스트 치환입니다.

  // 코드에 이렇게 쓰면
  LOG_INFO("callId={}", callId);

  // 컴파일 전에 이렇게 바뀜
  spdlog::info("callId={}", callId);

  __VA_ARGS__는 ...에 들어온 인자들을 그대로 전달하는 특수 키워드입니다.

  ---
  spdlog::info()가 어떻게 동작하는가

  spdlog 내부에 "현재 기본 로거"를 저장하는 전역 변수가 있습니다.

  // spdlog 내부 (간략화)
  static std::shared_ptr<logger> default_logger_;  // 전역 변수

  void info(메시지) {
      default_logger_->log(메시지);  // 기본 로거에게 전달
  }

  set_default_logger(logger)는 이 전역 변수에 우리가 만든 logger를 저장합니다.

  spdlog::set_default_logger(logger);
  // → default_logger_ = logger("voicebot")

  ---
  전체 흐름

  // 어디서든 이렇게 쓰면
  LOG_INFO("callId={} elapsed={}ms", callId, elapsed);

  // 매크로 치환
  spdlog::info("callId={} elapsed={}ms", callId, elapsed);

  // spdlog 내부
  default_logger_->log(메시지);   // "voicebot" 로거 호출

  // 두 sink로 전달
  ├─ consoleSink  →  터미널 출력
  └─ fileSink     →  logs/cpp-ws.log 기록

  ---
  logger 인스턴스 없이 가능한 이유

  set_default_logger() 를 안 했다면 이렇게 써야 합니다.

  // logger 인스턴스를 직접 들고 다녀야 함
  logger->info("callId={}", callId);

  set_default_logger() 덕분에 전역 변수에서 꺼내 쓰므로 어디서든 인스턴스 없이 호출 가능합니다.

  // 어디서든 그냥 호출 가능
  LOG_INFO("callId={}", callId);
  
>> LOG_INFO 어디에 정의 되어져 있어?
⏺ 이미 Logger.h를 읽었으니 바로 답할 수 있습니다.

  // Logger.h:109-111
  #define LOG_INFO(...)  spdlog::info(__VA_ARGS__)
  #define LOG_WARN(...)  spdlog::warn(__VA_ARGS__)
  #define LOG_ERROR(...) spdlog::error(__VA_ARGS__)

  Logger.h 맨 아래 세 줄에 정의되어 있습니다.
  
>> 그러면 이 매크로가 전역함수 역할을 해서 소스 어디에서든 사용 가능하다?
⏺ 맞습니다. 단, 조건이 하나 있습니다.

  #include "Logger.h"

  이 한 줄이 있는 파일에서만 사용 가능합니다.

  ---
  실제 코드에서 확인

  // RtzrWebSocketSttService.cpp
  #include "Logger.h"   // ← 이게 있어서
  ...
  LOG_INFO("[STT-RTZR] 연결됨 callId={}", callId_);  // 사용 가능

  // WsServer.cpp
  #include "Logger.h"   // ← 이게 있어서
  ...
  LOG_INFO("[CTI] 연결됨 sessionId={}", sessionId_);  // 사용 가능

  ---
  전역 함수와의 차이

  ┌───────────┬────────────────────────────────────┬──────────────────┐
  │           │               매크로               │    전역 함수     │
  ├───────────┼────────────────────────────────────┼──────────────────┤
  │ 사용 조건 │ #include "Logger.h" 필요           │ #include 필요    │
  ├───────────┼────────────────────────────────────┼──────────────────┤
  │ 동작 시점 │ 컴파일 전 텍스트 치환              │ 런타임 함수 호출 │
  ├───────────┼────────────────────────────────────┼──────────────────┤
  │ 결과      │ 사실상 동일하게 어디서든 사용 가능 │ 동일             │
  └───────────┴────────────────────────────────────┴──────────────────┘

  #include가 필요하다는 점은 전역 함수도 마찬가지라서, 실용적으로는 어디서든 사용 가능하다고 봐도 됩니다.

```

# SpringStyleDailyFileSink.todayStr() 설명해 줘.
```
  ---
  전체 역할

  오늘 날짜를 "2026-06-12" 형식의 문자열로 반환합니다.

  ---
  ① 현재 시각 가져오기

  auto t = std::time(nullptr);

  1970년 1월 1일 00:00:00 UTC 부터 지금까지의 초 단위 정수를 반환합니다.

  t = 1749686400   ← 이런 숫자

  nullptr을 넘기면 결과를 인자로 받지 않고 반환값으로만 받겠다는 뜻입니다.

  ---
  ② 로컬 시간으로 변환

  auto tm = *std::localtime(&t);

  초 단위 숫자를 사람이 읽을 수 있는 구조체로 변환합니다.

  tm.tm_year = 126   (1900 기준, 2026 - 1900)
  tm.tm_mon  = 5     (0 기준, 6월 = 5)
  tm.tm_mday = 12
  tm.tm_hour = 14
  tm.tm_min  = 30
  ...

  *로 역참조하는 이유는 localtime()이 포인터를 반환하기 때문입니다. 포인터가 가리키는 값을 복사해서 tm에 저장합니다.

  ---
  ③ 문자열로 포맷

  std::ostringstream oss;
  oss << std::put_time(&tm, "%Y-%m-%d");
  return oss.str();

  ostringstream은 문자열을 조립하는 버퍼입니다.

  std::put_time()이 tm 구조체를 패턴에 맞게 문자열로 변환합니다.

  %Y  →  2026   (4자리 연도)
  %m  →  06     (2자리 월)
  %d  →  12     (2자리 일)

  결과: "2026-06-12"

  oss.str()로 조립된 문자열을 꺼내서 반환합니다.

  ---
  static인 이유

  static std::string todayStr()

  인스턴스 멤버 변수(baseFile_, file_ 등)를 전혀 사용하지 않고 오늘 날짜만 계산합니다. 인스턴스 없이도 호출 가능하도록 static으로 선언했습니다.

```

# ostringstream 어떻게 읽고 무슨 역할?
```
⏺ 읽는 법

  ostringstream = output string stream

  - o → output (쓰기 전용)
  - string → 문자열을 대상으로
  - stream → 스트림 방식으로

  ---
  역할

  문자열을 조립하는 메모리 버퍼입니다.

  << 연산자로 데이터를 집어넣고, str()로 완성된 문자열을 꺼냅니다.

  std::ostringstream oss;

  oss << "안녕";
  oss << 123;
  oss << "ms";

  oss.str();  // → "안녕123ms"

  ---
  왜 그냥 문자열 더하기(+)를 안 쓰나

  // 문자열 + 로 조립
  std::string result = "안녕" + std::to_string(123) + "ms";

  // ostringstream 으로 조립
  std::ostringstream oss;
  oss << "안녕" << 123 << "ms";
  std::string result = oss.str();

  단순한 경우엔 +도 되지만, put_time()같이 스트림에만 출력할 수 있는 함수는 +로 받을 수 없습니다.

  // put_time은 스트림에만 쓸 수 있음
  oss << std::put_time(&tm, "%Y-%m-%d");  // ✅

  std::string s = std::put_time(&tm, "%Y-%m-%d");  // ❌ 컴파일 오류

  todayStr()에서 ostringstream을 쓴 이유가 바로 이것입니다.
```

# `rotate()` 설명해줘
```
>> 참조 소스
void rotate() {
      file_.close();

      auto stem     = baseFile_.stem().string();       // "cpp-ws"
      auto ext      = baseFile_.extension().string();  // ".log"
      auto dir      = baseFile_.parent_path();
      auto archived = dir / (stem + "." + currentDate_ + ext);
      fs::rename(baseFile_, archived);

      cleanOldFiles(dir, stem, ext);
      openFile();
  }
  
  ---
  전체 역할

  날짜가 바뀔 때 현재 로그 파일을 날짜가 포함된 이름으로 바꾸고, 새 로그 파일을 시작합니다.

  ---
  ① 현재 파일 닫기

  file_.close();

  파일 이름을 바꾸려면 먼저 닫아야 합니다. 열린 채로 이름을 바꾸면 OS에 따라 오류가 발생할 수 있습니다.

  ---
  ② 경로 분해

  auto stem = baseFile_.stem().string();       // "cpp-ws"
  auto ext  = baseFile_.extension().string();  // ".log"
  auto dir  = baseFile_.parent_path();         // "logs"

  baseFile_ = "logs/cpp-ws.log" 를 세 부분으로 쪼갭니다.

  logs / cpp-ws . log
   │       │       │
  dir    stem     ext

  ---
  ③ 보관 파일 경로 조립

  auto archived = dir / (stem + "." + currentDate_ + ext);

  어제 날짜가 붙은 경로를 만듭니다.

  "logs" / ("cpp-ws" + "." + "2026-06-11" + ".log")
  → "logs/cpp-ws.2026-06-11.log"

  / 연산자는 경로를 이어붙이는 std::filesystem 문법입니다.

  ---
  ④ 파일 이름 변경

  fs::rename(baseFile_, archived);

  logs/cpp-ws.log  →  logs/cpp-ws.2026-06-11.log

  실제 파일 내용은 그대로이고 이름만 바뀝니다.

  ---
  ⑤ 오래된 파일 삭제

  cleanOldFiles(dir, stem, ext);

  보관 파일이 7개를 초과하면 가장 오래된 것부터 삭제합니다.

  ---
  ⑥ 새 파일 열기

  openFile();

  logs/cpp-ws.log 를 새로 생성하고 오늘 로그부터 기록합니다.

  ---
  전체 흐름

  자정 이후 첫 LOG_INFO 호출
      └─ sink_it_() 에서 날짜 변경 감지
              └─ rotate()
                      ├─ file_.close()
                      ├─ cpp-ws.log → cpp-ws.2026-06-11.log 이름 변경
                      ├─ cleanOldFiles() → 7일 초과분 삭제
                      └─ openFile() → 새 cpp-ws.log 생성

```

# `cleanOldFiles()` 설명해줘
```
void cleanOldFiles(const fs::path& dir, const std::string& stem, const std::string& ext) {
      const auto expectedLen = stem.size() + 1 + 10 + ext.size();
      std::vector<fs::path> archived;
      for (const auto& entry : fs::directory_iterator(dir)) {
          auto name = entry.path().filename().string();
          if (name.size() == expectedLen &&
              name.substr(0, stem.size() + 1) == stem + "." &&
              name.substr(stem.size() + 1 + 10) == ext) {
              archived.push_back(entry.path());
          }
      }
      std::sort(archived.begin(), archived.end());
      while (static_cast<int>(archived.size()) > maxDays_) {
          fs::remove(archived.front());
          archived.erase(archived.begin());
      }
  }
  
  ---
  전체 역할

  logs/ 폴더에서 보관 로그 파일을 찾아 maxDays_(7개) 초과분을 오래된 것부터 삭제합니다.

  ---
  ① 파일명 길이 계산

  const auto expectedLen = stem.size() + 1 + 10 + ext.size();

  보관 파일명 "cpp-ws.2026-06-11.log" 의 정확한 길이를 계산합니다.

  "cpp-ws"  +  "."  +  "2026-06-11"  +  ".log"
     6      +   1   +      10        +    4     = 21

  이 길이가 아닌 파일은 보관 파일이 아니므로 거릅니다.

  ---
  ② 보관 파일 수집

  for (const auto& entry : fs::directory_iterator(dir)) {
      auto name = entry.path().filename().string();
      if (name.size() == expectedLen &&
          name.substr(0, stem.size() + 1) == stem + "." &&
          name.substr(stem.size() + 1 + 10) == ext) {
          archived.push_back(entry.path());
      }
  }

  logs/ 폴더의 모든 파일을 순회하며 세 가지 조건을 확인합니다.

  조건 1: name.size() == expectedLen
          파일명 길이가 21자인가
          "cpp-ws.2026-06-11.log" → 21 ✅
          "cpp-ws.log"            →  10 ❌

  조건 2: name.substr(0, stem.size() + 1) == stem + "."
          앞부분이 "cpp-ws." 로 시작하는가
          "cpp-ws.2026-06-11.log" → "cpp-ws." ✅
          "other.2026-06-11.log"  → "other."  ❌

  조건 3: name.substr(stem.size() + 1 + 10) == ext
          뒷부분이 ".log" 로 끝나는가
          "cpp-ws.2026-06-11.log" → ".log" ✅
          "cpp-ws.2026-06-11.txt" → ".txt" ❌

  세 조건을 모두 통과한 파일만 archived에 추가합니다.

  ---
  ③ 날짜순 정렬

  std::sort(archived.begin(), archived.end());

  파일명을 알파벳순으로 정렬합니다. 날짜 형식이 YYYY-MM-DD라서 알파벳순 = 날짜순이 됩니다.

  정렬 전:
      cpp-ws.2026-06-11.log
      cpp-ws.2026-06-09.log
      cpp-ws.2026-06-10.log

  정렬 후:
      cpp-ws.2026-06-09.log  ← 가장 오래된 것이 앞으로
      cpp-ws.2026-06-10.log
      cpp-ws.2026-06-11.log

  ---
  ④ 초과분 삭제

  while (static_cast<int>(archived.size()) > maxDays_) {
      fs::remove(archived.front());     // 가장 오래된 파일 삭제
      archived.erase(archived.begin()); // 리스트에서도 제거
  }

  파일 수가 7개를 초과하는 동안 앞에서부터 삭제합니다.

  maxDays_ = 7, 현재 파일 8개
      삭제: cpp-ws.2026-06-04.log  → 7개 남음 → 종료

  maxDays_ = 7, 현재 파일 10개
      삭제: cpp-ws.2026-06-02.log  → 9개
      삭제: cpp-ws.2026-06-03.log  → 8개
      삭제: cpp-ws.2026-06-04.log  → 7개 → 종료
      
>> 그런데 매일 저장이 안 되면 문제가 될 것 같은데.
⏺ 맞습니다. 이 코드의 구조적 문제입니다.

  ---
  문제 상황

  rotate()는 로그가 들어올 때 날짜 변경을 감지합니다.

  // sink_it_() 에서
  auto today = todayStr();
  if (today != currentDate_) {
      rotate();   // 로그가 들어와야 실행됨
  }

  즉, 로그가 없으면 rotate()가 호출되지 않습니다.

  ---
  구체적인 문제 시나리오

  6월 10일  서버 정상 운영 → cpp-ws.log 에 기록
  6월 11일  서버 다운 (장애) → 로그 없음 → rotate() 호출 안 됨
  6월 12일  서버 재시작 → 첫 로그 들어옴 → rotate() 실행

  결과:
      cpp-ws.log → cpp-ws.2026-06-10.log 로 이름 변경
      (날짜가 currentDate_ = "2026-06-10" 이므로)

  6월 11일 날짜의 파일이 아예 생성되지 않고, 6월 10일 파일에 6월 10일 로그가 담기게 됩니다. 날짜 공백이 생깁니다.

  ---
  또 다른 시나리오

  6월 10일 23:00  서버 시작
  6월 12일 01:00  첫 로그 발생 (6월 11일은 아무 로그 없음)

  결과:
      currentDate_ = "2026-06-10"
      cpp-ws.log → cpp-ws.2026-06-10.log
      6월 11일 파일 없음

  ---
  해결 방법

  로그 유무와 관계없이 자정에 rotate()를 실행하는 타이머를 추가해야 합니다.

  // 별도 스레드에서 자정마다 rotate() 강제 실행
  void startMidnightTimer() {
      std::thread([this]() {
          while (true) {
              // 다음 자정까지 sleep
              // rotate() 호출
          }
      }).detach();
  }

  현재 코드는 로그가 꾸준히 발생하는 환경에서는 문제없지만, 장애나 유휴 시간이 생기면 날짜 파일이 누락될 수 있습니다.

```

# SpringStyleDailyFileSink.openFile() 설명해 줘.
```
⏺ void openFile() {
      file_.open(baseFile_, std::ios::app);
  }

  ---
  전체 역할

  logs/cpp-ws.log 파일을 열어서 file_ 스트림에 연결합니다.

  ---
  std::ios::app 란

  파일을 이어쓰기 모드로 엽니다.

  기존 파일 내용: "2026-06-12 14:30:01 [INFO ] 서버 시작\n"

  app 모드로 열면:
      → 기존 내용 유지
      → 새 내용은 맨 끝에 추가

  덮어쓰기 모드(std::ios::trunc)로 열면:
      → 기존 내용 전부 삭제
      → 처음부터 다시 씀

  app 모드를 쓰는 이유는 서버가 재시작되더라도 이전 로그가 사라지지 않도록 하기 위해서입니다.

  ---
  호출되는 두 곳

  // 생성자 - 서버 시작 시
  SpringStyleDailyFileSink(...) {
      openFile();   // 처음 파일 열기
  }

  // rotate() - 날짜 바뀔 때
  void rotate() {
      file_.close();
      fs::rename(...);
      cleanOldFiles(...);
      openFile();   // 새 날짜 파일 열기
  }
  
>> std::ios::app 에서 ios 어떤 의미?
⏺ ios는 input/output stream의 약자입니다.

  C++ 표준 라이브러리에서 스트림 관련 상수와 타입을 정의하는 클래스입니다.

  ---
  ios 안에 정의된 주요 모드 상수

  std::ios::app    // 이어쓰기 (append)
  std::ios::trunc  // 덮어쓰기 (truncate)
  std::ios::in     // 읽기 (input)
  std::ios::out    // 쓰기 (output)
  std::ios::binary // 바이너리 모드

  ---
  조합해서 사용 가능

  | 연산자로 여러 모드를 동시에 지정할 수 있습니다.

  file_.open("cpp-ws.log", std::ios::out | std::ios::app);
  // 쓰기 + 이어쓰기 모드

  ---
  상속 관계

  ios는 ifstream, ofstream, fstream 의 부모 클래스입니다.

  std::ios
      ├─ std::ifstream  (읽기 전용)
      ├─ std::ofstream  (쓰기 전용)  ← file_ 의 타입
      └─ std::fstream   (읽기 + 쓰기)

  file_이 ofstream 타입이고, ofstream이 ios를 상속받기 때문에 std::ios::app 상수를 그대로 사용할 수 있습니다.

>> ios 어떤 의미?
⏺ ios는 Input/Output Stream의 약자입니다.

```

# WsSession.doAccept() 설명해 줘.
```
>> 참조 소스
void doAccept() {
      ws_.async_accept(net::bind_executor(strand_,
          [self = shared_from_this()](beast::error_code ec) {
              if (ec) return;
              LOG_INFO("[CTI] 연결됨 sessionId={} callId={}", self->sessionId_, self->callId_);
              self->startStt();
              self->doRead();
          }));
  }
  
⏺ void doAccept() {
      ws_.async_accept(net::bind_executor(strand_,
          [self = shared_from_this()](beast::error_code ec) {
              if (ec) return;
              LOG_INFO("[CTI] 연결됨 sessionId={} callId={}", self->sessionId_, self->callId_);
              self->startStt();
              self->doRead();
          }));
  }

  ---
  전체 역할

  브라우저가 WebSocket 연결을 요청할 때 핸드셰이크를 수락합니다.

  ---
  ① async_accept

  ws_.async_accept([콜백]);

  브라우저가 보낸 HTTP Upgrade 요청을 비동기로 수락합니다.

  브라우저                        C++ 서버
      │── GET /ws/cti ──────────▶│
      │   Upgrade: websocket     │
      │                          │  async_accept 대기 중
      │◀─ 101 Switching Protocols│  수락 완료 → 콜백 호출
      │
      │  이후 WebSocket 통신 시작

  ---
  ② bind_executor(strand_, 콜백)

  net::bind_executor(strand_, [콜백])

  콜백이 반드시 strand_ 위에서 실행되도록 보장합니다.

  strand_는 같은 WsSession 안의 콜백들이 동시에 실행되지 않도록 순서를 보장합니다. doRead(), doWrite(), handleFinalStt() 등이 모두 같은 strand_ 위에서 실행되므로 멤버 변수 접근 시 race condition이
  발생하지 않습니다.

  ---
  ③ 연결 완료 후 처리

  if (ec) return;
  LOG_INFO("[CTI] 연결됨 sessionId={} callId={}", ...);
  self->startStt();   // RTZR WebSocket 연결 시작
  self->doRead();     // 브라우저 메시지 수신 루프 시작

  핸드셰이크가 성공하면 두 가지를 동시에 시작합니다.

  doAccept() 완료
      ├─ startStt()  →  RTZR 연결 시작 (오디오 받을 준비)
      └─ doRead()    →  브라우저 메시지 수신 루프 시작

  startStt()를 먼저 호출하는 이유는, 브라우저가 오디오를 바로 보내더라도 RTZR 연결이 준비되어 있어야 하기 때문입니다.

>> 세션별로 WebSocket 비동기 수락(Accept) 하는거야? 별도의 소켓과 통로가 생기는거네?
⏺ 맞습니다. 정확히 이해하셨습니다.

  ---
  전체 구조

  WsServer가 연결 요청을 받아서 WsSession을 만들고, 세션이 자신만의 소켓으로 독립적으로 통신합니다.

  WsServer (9090 포트)
      │ 연결 요청 수락 (doAccept)
      │
      ├─ 브라우저 A 연결  →  WsSession A  (ws_ 소켓 A)  →  독립 통로
      ├─ 브라우저 B 연결  →  WsSession B  (ws_ 소켓 B)  →  독립 통로
      └─ 브라우저 C 연결  →  WsSession C  (ws_ 소켓 C)  →  독립 통로

  ---
  WsServer가 소켓을 넘겨주는 부분

  // WsServer.cpp:246-251
  void WsServer::doAccept() {
      acceptor_.async_accept(
          [this](beast::error_code ec, tcp::socket socket) {
              if (!ec) {
                  std::make_shared<WsSession>(
                      std::move(socket), ...)  // ← 소켓을 세션에 넘김
                      ->start();
              }
              doAccept();  // 다음 연결 요청 계속 대기
          });
  }

  acceptor_가 새 연결을 받으면 그 연결 전용 socket을 만들어 WsSession에 넘깁니다. 이후 WsServer는 다음 연결을 기다리고, WsSession은 자신의 소켓으로 독립적으로 통신합니다.

  ---
  세션마다 독립적인 것들

  WsSession A                WsSession B
      ws_  (소켓 A)               ws_  (소켓 B)
      strand_ (A 전용)            strand_ (B 전용)
      stt_  (RTZR 연결 A)         stt_  (RTZR 연결 B)
      callId_ "CTI-S1"            callId_ "CTI-S2"
      history_ [...]              history_ [...]

  A 세션과 B 세션은 완전히 독립적으로 동작합니다. A가 느려지거나 오류가 나도 B에 영향을 주지 않습니다.

```

# `WsSession.doRead()` 설명해 줘.
```
>> 참조 소스
    // ── 읽기 루프 ─────────────────────────────────────────
    void doRead() {
        ws_.async_read(readBuf_,
            net::bind_executor(strand_,
                [self = shared_from_this()](beast::error_code ec, size_t) {
                    if (ec == ws::error::closed || ec == net::error::eof) {
                        LOG_INFO("[CTI] 연결 종료 callId={}", self->callId_);
                        self->cancelled_ = true;
                        if (self->stt_) self->stt_->complete();
                        return;
                    }
                    if (ec) return;

                    bool isBinary = self->ws_.got_binary();
                    auto payload  = beast::buffers_to_string(self->readBuf_.data());
                    self->readBuf_.consume(self->readBuf_.size());

                    if (isBinary) {
                        if (self->stt_) {
                            auto* p = reinterpret_cast<const uint8_t*>(payload.data());
                            self->stt_->sendChunk({p, p + payload.size()});
                        }
                    } else {
                        self->handleTextMessage(payload);
                    }
                    self->doRead();
                }));
    }
    
  ---
  전체 역할

  브라우저가 보내는 메시지를 재귀 방식으로 계속 수신합니다. 메시지 종류에 따라 오디오 청크 또는 CTI 이벤트로 분기합니다.

  ---
  ① async_read + strand

  ws_.async_read(readBuf_, net::bind_executor(strand_, [콜백]));

  브라우저 메시지 한 개가 올 때까지 비동기로 대기합니다. strand_로 감싸서 다른 콜백과 동시에 실행되지 않도록 보장합니다.

  ---
  ② 연결 종료 처리

  if (ec == ws::error::closed || ec == net::error::eof) {
      self->cancelled_ = true;
      if (self->stt_) self->stt_->complete();
      return;
  }

  브라우저가 탭을 닫거나 전화가 끊기면 루프를 종료합니다.

  cancelled_ = true   → LLM/TTS 진행 중이면 결과를 브라우저로 보내지 않음
  stt_->complete()    → RTZR에 EOS 전송 → STT 세션 종료

  ---
  ③ 메시지 종류 판별

  bool isBinary = self->ws_.got_binary();
  auto payload  = beast::buffers_to_string(self->readBuf_.data());
  self->readBuf_.consume(self->readBuf_.size());

  WebSocket 프레임은 바이너리와 텍스트 두 종류입니다. got_binary()로 어느 쪽인지 확인합니다.

  브라우저가 보내는 메시지
      ├─ binary  →  오디오 청크 (마이크 음성)
      └─ text    →  CTI 이벤트 JSON

  ---
  ④ 분기 처리

  바이너리 (오디오 청크)
  if (isBinary) {
      auto* p = reinterpret_cast<const uint8_t*>(payload.data());
      self->stt_->sendChunk({p, p + payload.size()});
  }

  payload는 std::string 타입이라 reinterpret_cast로 uint8_t*로 변환한 뒤 RTZR로 전달합니다.

  텍스트 (CTI 이벤트)
  } else {
      self->handleTextMessage(payload);
  }

  JSON을 파싱해서 CALL_END 이벤트 등을 처리합니다.

  ---
  ⑤ 재귀 루프

  self->doRead();

  처리가 끝나면 다시 doRead()를 호출해서 다음 메시지를 기다립니다.

  ---
  전체 흐름

  doRead() 등록
      │
      └─ 브라우저 메시지 도착
              ├─ binary  →  sendChunk()  →  RTZR로 전달  →  doRead() 재호출
              ├─ text    →  handleTextMessage()           →  doRead() 재호출
              └─ 연결 끊김  →  complete()  →  루프 종료
              
>> "JSON을 파싱해서 CALL_END 이벤트 등을 처리합니다" 무슨 말이야?
⏺ handleTextMessage()를 보면 이해됩니다.

  void handleTextMessage(const std::string& payload) {
      try {
          auto j    = json::parse(payload);
          auto type = j.value("type", std::string());

          if (type == "CTI_EVENT" && j.value("event", "") == "CALL_END") {
              if (stt_) stt_->complete();
          }
      } catch (...) {}
  }

  브라우저가 텍스트 프레임으로 이런 JSON을 보냅니다.

  { "type": "CTI_EVENT", "event": "CALL_END" }

  이걸 파싱해서 CALL_END이면 stt_->complete()를 호출합니다.

  ---
  언제 브라우저가 CALL_END를 보내나

  전화 통화가 명시적으로 종료될 때입니다.

  브라우저에서 통화 종료 버튼 클릭
      └─ { "type": "CTI_EVENT", "event": "CALL_END" } 전송
              └─ handleTextMessage()
                      └─ stt_->complete()  →  RTZR에 EOS 전송

  ---
  doRead()의 연결 종료와 차이

  CALL_END 이벤트   →  발화 종료 신호 (연결은 유지)
                          → stt_->complete() 만 호출

  연결 끊김(eof)    →  브라우저가 완전히 닫힘
                          → cancelled_ = true
                          → stt_->complete() 호출

  CALL_END는 통화가 끝났다는 신호이고, 연결 끊김은 브라우저 자체가 닫힌 상황입니다.
  
>> 그러면 발화를 다시 시작하면 startStt(); 다시 호출 되어야 하는건가?
⏺ 맞습니다. 실제로 그렇게 되어 있습니다.

  handleFinalStt() 맨 끝을 보면:

  // WsServer.cpp:148
  self->startStt();   // 다음 발화를 위해 RTZR 재연결
  self->sendJson({{"type", "BOT_READY"}});

  ---
  전체 발화 사이클

  ① doAccept() 완료
          └─ startStt()  →  RTZR 첫 연결

  ② 브라우저 오디오 전송
          └─ doRead()  →  sendChunk() 반복

  ③ CALL_END 또는 연결 끊김
          └─ complete()  →  EOS 전송

  ④ RTZR 최종 결과
          └─ handleFinalStt()
                  ├─ LLM 호출
                  ├─ TTS 호출
                  ├─ 브라우저로 응답 전송
                  └─ startStt()  →  RTZR 재연결 ← 다음 발화 준비

  ⑤ 브라우저가 BOT_READY 수신
          └─ 다음 발화 시작 가능

  startStt() 내부의 recognize()가 이전 RTZR 연결을 정리하고 새로 연결하므로, 발화마다 자동으로 재연결됩니다.

```

#  `WsSession.startStt()` 설명해 줘.
```
>> 참조 소스
 void startStt() {
      if (!stt_) stt_ = std::make_shared<RtzrWebSocketSttService>(tokenMgr_);
      stt_->recognize(callId_,
          [self = shared_from_this()](SttResult r) {
              if (!r.isFinal) return;
              net::post(self->strand_,
                  [self, text = r.text]() { self->handleFinalStt(text); });
          },
          [self = shared_from_this()](std::string err) {
              net::post(self->strand_, [self, err]() {
                  self->sendJson({{"type", "ERROR"}, {"message", err}});
              });
          });
  }
  ---
  전체 역할

  RTZR STT 세션을 시작하고, 결과 콜백과 에러 콜백을 등록합니다.

  ---
  ① stt_ 인스턴스 생성

  if (!stt_) stt_ = std::make_shared<RtzrWebSocketSttService>(tokenMgr_);

  stt_가 없을 때만 생성합니다. 처음 호출 시에만 만들고, 이후 재호출 시에는 기존 인스턴스를 재사용합니다.

  재연결은 recognize() 내부에서 처리하므로 인스턴스 자체를 새로 만들 필요가 없습니다.

  첫 번째 startStt()  →  stt_ 없음  →  새로 생성 후 recognize()
  두 번째 startStt()  →  stt_ 있음  →  그대로 recognize() 재호출

  ---
  ② recognize() 호출 — 결과 콜백

  [self = shared_from_this()](SttResult r) {
      if (!r.isFinal) return;
      net::post(self->strand_,
          [self, text = r.text]() { self->handleFinalStt(text); });
  }

  RTZR이 결과를 보낼 때마다 호출됩니다.

  중간 결과(isFinal=false)는 무시하고, 최종 결과(isFinal=true)만 처리합니다.

  net::post(strand_)로 감싸는 이유는, 이 콜백이 STT 전용 스레드에서 호출되기 때문입니다. handleFinalStt()는 history_ 같은 멤버 변수에 접근하므로 반드시 strand_ 위에서 실행해야 합니다.

  STT 스레드 (ioc_ 전용)
      └─ onResult_ 콜백 호출
              └─ net::post(strand_)  →  서버 ioc 스레드로 전달
                      └─ handleFinalStt()  ← strand_ 위에서 안전하게 실행

  ---
  ③ recognize() 호출 — 에러 콜백

  [self = shared_from_this()](std::string err) {
      net::post(self->strand_, [self, err]() {
          self->sendJson({{"type", "ERROR"}, {"message", err}});
      });
  }

  RTZR 연결 오류 시 브라우저로 에러 메시지를 전송합니다.

  { "type": "ERROR", "message": "connection refused" }

  에러 콜백도 동일하게 strand_를 거쳐 안전하게 실행됩니다.
```

# `WsSession.handleFinalStt()` 설명해 줘.
```
    // ── STT 최종 결과 → LLM → TTS 파이프라인 ──────────────
    void handleFinalStt(const std::string& text) {
        if (cancelled_) return;

        LOG_INFO("[CTI] STT 최종 callId={} text={}", callId_, text);
        sendJson({{"type", "STT_FINAL"},   {"text", text}});
        sendJson({{"type", "BOT_THINKING"}});

        // history_ 는 strand 위에서만 접근 — 복사본으로 넘긴다
        auto hist = history_;
        hist.push_back({"user", text});

        // LLM/TTS 는 blocking(libcurl) → 별도 스레드
        std::thread([self = shared_from_this(), hist]() mutable {
            try {
                if (self->cancelled_) return;
                auto llmRaw = self->llm_->chat(hist, self->callId_);

                if (self->cancelled_) return;
                std::string intent   = "기타";
                std::string response = llmRaw;
                try {
                    auto j   = json::parse(llmRaw);
                    intent   = j.value("intent",   "기타");
                    response = j.value("response", llmRaw);
                } catch (...) {}

                hist.push_back({"assistant", response});

                auto audioBytes = self->tts_->synthesize(response, self->callId_);

                if (self->cancelled_) return;
                net::post(self->strand_,
                    [self, intent, response, hist, audioBytes]() {
                        if (self->cancelled_) return;
                        self->history_ = hist;
                        self->sendJson({{"type", "LLM_RESULT"},
                                        {"intent", intent},
                                        {"response", response}});
                        self->sendJson({{"type", "TTS_TEXT"}, {"text", response}});
                        self->sendBinary(audioBytes);  // MP3 오디오 전송
                        self->startStt();   // 다음 발화를 위해 RTZR 재연결
                        self->sendJson({{"type", "BOT_READY"}});
                        LOG_INFO("[CTI] 다음 발화 대기 callId={}", self->callId_);
                    });
            } catch (const std::exception& e) {
                net::post(self->strand_, [self, msg = std::string(e.what())]() {
                    if (self->cancelled_) return;
                    self->sendJson({{"type", "ERROR"}, {"message", msg}});
                });
            }
        }).detach();
    }

  ---
  전체 역할

  STT 최종 결과를 받아 LLM → TTS 파이프라인을 실행하고 브라우저로 응답을 전송합니다.

  ---
  ① 취소 확인

  if (cancelled_) return;

  통화가 이미 종료됐으면 아무것도 하지 않습니다.

  ---
  ② 브라우저에 중간 상태 전송

  sendJson({{"type", "STT_FINAL"},   {"text", text}});
  sendJson({{"type", "BOT_THINKING"}});

  브라우저에 두 가지를 알립니다.

  STT_FINAL    →  "사용자가 이렇게 말했어요"
  BOT_THINKING →  "지금 봇이 생각 중이에요" (로딩 표시용)

  ---
  ③ 대화 이력 복사

  auto hist = history_;
  hist.push_back({"user", text});

  history_를 복사해서 hist를 만들고 사용자 발화를 추가합니다.

  원본 history_를 그대로 쓰지 않는 이유는, 이후 별도 스레드에서 hist를 사용하는데 그 사이에 history_가 바뀔 수 있기 때문입니다.

  ---
  ④ 별도 스레드에서 LLM → TTS 실행

  std::thread([self = shared_from_this(), hist]() mutable {
      ...
  }).detach();

  llm_->chat()과 tts_->synthesize()는 HTTP 요청이라 수백 ms ~ 수 초 걸립니다. 메인 스레드를 블로킹하면 다른 세션 전체가 멈추므로 별도 스레드에서 실행합니다.

  .detach()는 스레드를 독립적으로 실행합니다. self를 캡처해서 스레드가 실행되는 동안 WsSession이 파괴되지 않도록 수명을 보장합니다.

  ---
  ⑤ LLM 호출

  auto llmRaw = self->llm_->chat(hist, self->callId_);

  대화 이력 전체를 넘겨서 응답을 받습니다. llmRaw는 JSON 문자열입니다.

  { "intent": "문의", "response": "무엇을 도와드릴까요?" }

  ---
  ⑥ LLM 응답 파싱

  std::string intent   = "기타";
  std::string response = llmRaw;
  try {
      auto j   = json::parse(llmRaw);
      intent   = j.value("intent",   "기타");
      response = j.value("response", llmRaw);
  } catch (...) {}

  JSON 파싱이 실패하면 llmRaw 전체를 response로 사용합니다. LLM이 JSON이 아닌 평문을 반환하는 경우를 대비한 방어 코드입니다.

  ---
  ⑦ TTS 호출

  hist.push_back({"assistant", response});
  auto audioBytes = self->tts_->synthesize(response, self->callId_);

  LLM 응답을 대화 이력에 추가한 뒤, TTS로 음성을 합성합니다.

  ---
  ⑧ strand_로 돌아와서 결과 전송

  net::post(self->strand_,
      [self, intent, response, hist, audioBytes]() {
          self->history_ = hist;
          self->sendJson({{"type", "LLM_RESULT"}, ...});
          self->sendJson({{"type", "TTS_TEXT"}, ...});
          self->sendBinary(audioBytes);
          self->startStt();
          self->sendJson({{"type", "BOT_READY"}});
      });

  별도 스레드에서 작업이 완료되면 strand_로 돌아와서 결과를 처리합니다.

  ┌────────────┬──────────────────────────────────┐
  │ 전송 내용  │               의미               │
  ├────────────┼──────────────────────────────────┤
  │ LLM_RESULT │ 의도 분류 + 응답 텍스트          │
  ├────────────┼──────────────────────────────────┤
  │ TTS_TEXT   │ TTS로 변환할 텍스트              │
  ├────────────┼──────────────────────────────────┤
  │ sendBinary │ MP3 오디오 데이터                │
  ├────────────┼──────────────────────────────────┤
  │ startStt() │ 다음 발화 준비                   │
  ├────────────┼──────────────────────────────────┤
  │ BOT_READY  │ 브라우저에 "다음 발화 가능" 알림 │
  └────────────┴──────────────────────────────────┘

  history_를 여기서 업데이트하는 이유는 strand_ 위에서 실행해야 안전하기 때문입니다.

  ---
  전체 흐름

  handleFinalStt(text)   ← strand_ 위에서 실행
      │
      ├─ STT_FINAL, BOT_THINKING 전송
      │
      └─ 별도 스레드 시작
              ├─ llm_->chat()          블로킹 HTTP
              ├─ tts_->synthesize()    블로킹 HTTP
              │
              └─ net::post(strand_)   strand_로 복귀
                      ├─ history_ 업데이트
                      ├─ LLM_RESULT, TTS_TEXT, 오디오 전송
                      ├─ startStt()    다음 발화 준비
                      └─ BOT_READY 전송
                      
>> mutable 뭔지?
⏺ mutable 이란

  람다가 캡처한 변수를 람다 안에서 수정 가능하게 만드는 키워드입니다.

  ---
  기본적으로 람다 캡처는 const

  auto hist = history_;

  // mutable 없으면
  std::thread([hist]() {
      hist.push_back({"user", text});  // ❌ 컴파일 오류 (const라 수정 불가)
  });

  // mutable 있으면
  std::thread([hist]() mutable {
      hist.push_back({"user", text});  // ✅ 수정 가능
  });

  ---
  이 코드에서 왜 필요한가

  std::thread([self = shared_from_this(), hist]() mutable {
      ...
      hist.push_back({"assistant", response});  // ← hist 수정
      ...
  })

  스레드 안에서 hist에 LLM 응답을 추가해야 하기 때문입니다.

  캡처 시점:  hist = [{"user", "안녕하세요"}]
                              ↓ mutable 덕분에 수정 가능
  스레드 안:  hist.push_back({"assistant", "무엇을 도와드릴까요?"})
                              ↓
  완료 후:    hist = [{"user", "안녕하세요"}, {"assistant", "무엇을 도와드릴까요?"}]

  ---
  원본 history_에는 영향 없음

  hist는 history_의 복사본입니다. mutable로 hist를 수정해도 원본 history_는 바뀌지 않습니다.

  // strand_로 돌아와서 원본에 반영
  self->history_ = hist;  // 완성된 복사본을 원본에 저장
  
>> 그런데 그 사이에 원본(self->history_) 변경 될 수 있는거 아니야? 그러면 새롭게 추가된 것은 덮어 쓸 수 있는건가?
⏺ 좋은 지적입니다. 이론적으로는 가능한 문제입니다.

  ---
  문제 시나리오

  발화 1 처리 중 (별도 스레드)
      hist = [user: "안녕"]
      llm->chat() 실행 중...  (수 초 걸림)

      그 사이에 발화 2가 들어오면?
          → handleFinalStt() 재호출
          → hist2 = history_ 복사
          → hist2 에 user: "또 안녕" 추가
          → llm->chat() 실행...

  발화 1 완료
      → self->history_ = hist  ← 발화 2의 내용 덮어씀 ❌

  ---
  그런데 실제로는 발생하기 어려운 이유

  startStt()가 발화 1 완료 후에 호출됩니다.

  // 발화 1 완료 후
  self->startStt();        // ← 여기서 다음 발화 준비
  self->sendJson({{"type", "BOT_READY"}});  // ← 브라우저에 알림

  브라우저는 BOT_READY를 받기 전까지 다음 발화를 보내지 않도록 설계되어 있습니다. 즉, 발화가 순차적으로 진행되는 구조입니다.

  발화 1 완료 → BOT_READY 전송 → 브라우저 다음 발화 시작

  ---
  하지만 완전히 안전하지는 않습니다

  브라우저가 BOT_READY 없이 강제로 오디오를 보내거나, 네트워크 타이밍 문제가 생기면 동시 발화가 발생할 수 있습니다. 이를 방어하려면 아래 같은 처리가 필요합니다.

  // 처리 중 플래그
  bool processing_ = false;

  void handleFinalStt(const std::string& text) {
      if (processing_) return;  // 처리 중이면 무시
      processing_ = true;
      ...
      // 완료 후
      processing_ = false;
      self->startStt();
  }

  현재 코드는 이 방어 처리가 없으므로 잠재적인 버그가 있다고 볼 수 있습니다.
  

```

# ".detach()는 스레드를 독립적으로 실행합니다." 대해서 좀 더 자세히 말해줘.
```
⏺ detach() 란

  스레드를 메인 흐름과 분리해서 독립적으로 실행시킵니다.

  ---
  detach() 없으면 어떻게 되나

  std::thread는 기본적으로 join() 또는 detach() 중 하나를 반드시 호출해야 합니다. 둘 다 안 하면 소멸자에서 std::terminate()가 호출되어 프로그램이 강제 종료됩니다.

  {
      std::thread t([]() {
          // 오래 걸리는 작업
      });
      // join도 detach도 안 하면
  }  // ← t 소멸자 → std::terminate() → 프로그램 죽음

  ---
  join() vs detach() 차이

  // join(): 스레드가 끝날 때까지 기다림
  std::thread t([](){ /* 작업 */ });
  t.join();   // 여기서 블로킹 → 작업 완료 후 다음 줄 실행

  // detach(): 기다리지 않고 바로 다음 줄 실행
  std::thread t([](){ /* 작업 */ });
  t.detach(); // 바로 다음 줄 실행 → 스레드는 백그라운드에서 계속

  ---
  이 코드에서 detach()를 쓰는 이유

  std::thread([self = shared_from_this(), hist]() mutable {
      auto llmRaw = self->llm_->chat(hist, self->callId_);   // 수 초
      auto audioBytes = self->tts_->synthesize(...);          // 수 초
      ...
  }).detach();

  join()을 쓰면 LLM + TTS가 완료될 때까지 handleFinalStt()가 반환되지 않습니다. handleFinalStt()는 strand_ 위에서 실행 중이므로 그 동안 다른 모든 콜백이 블로킹됩니다.

  join() 사용 시
      handleFinalStt() 블로킹 (수 초)
          └─ strand_ 막힘
                  └─ doRead() 콜백 대기
                  └─ sendJson() 콜백 대기
                  └─ 다른 세션 콜백 대기  ← 전체 영향

  detach()를 써야 handleFinalStt()가 즉시 반환되고 strand_가 풀립니다.

  ---
  detach() 시 수명 보장

  std::thread([self = shared_from_this(), hist]() mutable {

  detach()하면 스레드가 언제 끝날지 모릅니다. 그 사이에 WsSession이 파괴될 수 있으므로 shared_from_this()로 self를 캡처해서 스레드가 살아있는 동안 WsSession이 파괴되지 않도록 수명을 연장합니다.

  detach() 후
      WsSession 참조 카운트
          ├─ WsServer 에서 1
          └─ 스레드 캡처 self 에서 1  ← 스레드가 끝날 때까지 유지

  스레드 완료
      └─ self 소멸 → 참조 카운트 감소
```

# catch (...) {} 무슨 의미?
```
⏺ catch (...) 란

  모든 예외를 잡는 catch 문입니다.

  ...은 "어떤 타입의 예외든 전부 잡겠다"는 의미입니다.

  try {
      auto j = json::parse(llmRaw);
  } catch (...) {}   // 어떤 예외든 무시

  ---
  왜 쓰는가

  LLM이 JSON이 아닌 평문을 반환할 수 있습니다.

  LLM 응답이 JSON일 때:
      llmRaw = {"intent": "문의", "response": "도와드릴까요?"}
      → json::parse() 성공
      → intent, response 추출

  LLM 응답이 평문일 때:
      llmRaw = "도와드릴까요?"
      → json::parse() 실패 → 예외 발생
      → catch(...) 로 잡아서 무시
      → intent = "기타", response = llmRaw 그대로 사용

  ---
  {} 빈 블록의 의미

  예외를 잡고 아무것도 하지 않습니다. 즉 예외를 조용히 무시합니다.

  catch (...) {}        // 무시
  catch (...) { ... }   // 처리

  ---
  일반 catch와 비교

  catch (const std::exception& e) { }  // std::exception 계열만 잡음
  catch (...) { }                       // 모든 예외 다 잡음

  json::parse()가 어떤 타입의 예외를 던질지 모르거나, 예외 타입에 상관없이 전부 무시하고 싶을 때 catch (...)를 씁니다.

```

# "별도 스레드에서 작업이 완료되면 strand_로 돌아와서 결과를 처리합니다" 잘 이해 안돼. 현재 스레드에서 왜 '별도 스레드'라고 하지? 혹시 별도 스레드가 "std::thread([self = shared_from_this(), hist]() mutable {" 이고 그 안에 "net::post(self->strand_," 이 다시 strand 로 돌아왔다고 하는거야?
```
⏺ 맞습니다. 정확히 이해하셨습니다.

  ---
  흐름으로 보면

  strand_ 스레드 (서버 ioc)
      └─ handleFinalStt() 실행
              │
              └─ std::thread(...).detach()  ← 별도 스레드 시작
                      │                        handleFinalStt() 는 즉시 반환
                      │
                      │  [별도 스레드]
                      ├─ llm_->chat()       블로킹 (수 초)
                      ├─ tts_->synthesize() 블로킹 (수 초)
                      │
                      └─ net::post(self->strand_, [...])
                                  │
                                  │  "strand_ 스레드에서 이 람다 실행해줘"
                                  ▼
                          strand_ 스레드 (서버 ioc)  ← 여기로 돌아옴
                              └─ history_ 업데이트
                              └─ sendJson(LLM_RESULT)
                              └─ sendBinary(오디오)
                              └─ startStt()

  ---
  왜 strand_로 돌아와야 하나

  history_, sendJson(), startStt() 등은 멤버 변수에 접근합니다. 별도 스레드에서 직접 접근하면 race condition이 발생할 수 있습니다.

  // 별도 스레드에서 직접 접근 (❌ 위험)
  self->history_ = hist;
  self->sendJson(...);

  // strand_로 넘겨서 접근 (✅ 안전)
  net::post(self->strand_, [self, hist]() {
      self->history_ = hist;   // strand_ 위에서 안전하게 실행
      self->sendJson(...);
  });

  strand_는 한 번에 하나의 작업만 실행하므로 멤버 변수 접근이 안전합니다.
  
>> detach() 로 별도스레드이지만 내부에서 "net::post(self->strand_" 하면 strand_ 돌아와서 실행하게 하고 바로 별도 스레드는 종료된다. 이건가?
⏺ 맞습니다. 정확히 이해하셨습니다.

  ---
  별도 스레드 생명주기

  std::thread([self, hist]() mutable {
      // ① LLM 호출
      auto llmRaw = self->llm_->chat(hist, self->callId_);

      // ② TTS 호출
      auto audioBytes = self->tts_->synthesize(response, self->callId_);

      // ③ strand_에 작업 등록 (예약만 하고 바로 반환)
      net::post(self->strand_, [self, ...]() {
          self->history_ = hist;
          self->sendJson(...);
          self->startStt();
      });

      // ④ 별도 스레드 여기서 종료
  }).detach();

  ---
  타임라인으로 보면

  별도 스레드
      ├─ llm_->chat()          실행 중...
      ├─ tts_->synthesize()    실행 중...
      ├─ net::post(strand_)    strand_에 람다 등록만 하고 즉시 반환
      └─ 스레드 종료            ← 여기서 끝

  strand_ 스레드 (서버 ioc)
      └─ (잠시 후) 등록된 람다 실행
              ├─ history_ 업데이트
              ├─ sendJson()
              └─ startStt()

  net::post()는 "이 람다를 strand_에서 실행해줘" 라고 예약만 하고 즉시 반환합니다. 실제 실행은 서버 ioc가 담당하므로 별도 스레드는 바로 종료됩니다.

>> 그러나 detach 된 별도스레드는 내부 stran_ 에 캡처되어서 해당 예약 콜백이 전부 수행 후 종료되겠네?
⏺ 아닙니다. 별도 스레드와 strand_ 콜백은 독립적입니다.

  ---
  실제 동작

  별도 스레드
      ├─ llm_->chat()
      ├─ tts_->synthesize()
      ├─ net::post(strand_, 람다)   ← 예약만 하고
      └─ 스레드 종료                ← 바로 여기서 끝
                                      strand_ 콜백 완료 여부와 무관

  strand_ 스레드 (서버 ioc)
      └─ (별도 스레드와 독립적으로) 람다 실행

  net::post()는 우체통에 편지를 넣는 것과 같습니다.

  별도 스레드  →  우체통(strand_)에 편지 투입  →  스레드 종료
                          │
                          └─  서버 ioc가 편지 꺼내서 실행 (별도 스레드와 무관)

  ---
  strand_ 콜백이 실행되는 이유

  self = shared_from_this()로 캡처된 shared_ptr가 strand_ 람다 안에 살아있기 때문입니다.

  별도 스레드 종료
      └─ 스레드 캡처 self 소멸  →  참조 카운트 -1

  strand_ 람다 캡처 self
      └─ 아직 살아있음  →  참조 카운트 유지
              └─ 람다 실행 완료 후 self 소멸  →  참조 카운트 -1

  별도 스레드가 종료되어도 strand_ 람다가 self를 캡처하고 있으므로 WsSession은 람다 실행이 완료될 때까지 살아있습니다.

>> 그런데 net::post(self->strand_,
                      [self, intent, response, hist, audioBytes]() 보면 별도 스레드의 변수를 캡처하고 있지 않어?
⏺ 맞습니다. 좋은 지적입니다.

  캡처하는 변수들을 보면:

  net::post(self->strand_,
      [self, intent, response, hist, audioBytes]() {

  ┌────────────┬───────────────────────────────────┐
  │ 캡처 변수  │           어디서 온 값            │
  ├────────────┼───────────────────────────────────┤
  │ self       │ shared_ptr (WsSession 수명 보장)  │
  ├────────────┼───────────────────────────────────┤
  │ intent     │ 별도 스레드에서 LLM 파싱한 값     │
  ├────────────┼───────────────────────────────────┤
  │ response   │ 별도 스레드에서 LLM 파싱한 값     │
  ├────────────┼───────────────────────────────────┤
  │ hist       │ 별도 스레드에서 수정한 대화 이력  │
  ├────────────┼───────────────────────────────────┤
  │ audioBytes │ 별도 스레드에서 TTS 합성한 오디오 │
  └────────────┴───────────────────────────────────┘

  ---
  안전한 이유

  캡처는 값 복사입니다. net::post() 호출 시점에 변수들이 람다 안으로 복사됩니다.

  별도 스레드
      ├─ intent = "문의"
      ├─ response = "도와드릴까요?"
      ├─ hist = [...]
      ├─ audioBytes = [...]
      │
      └─ net::post(strand_, [self, intent, response, hist, audioBytes]())
                              ↑
                              이 시점에 전부 복사됨
                              별도 스레드 종료 후에도 람다 안에 복사본이 살아있음

  별도 스레드가 종료되어 원본 변수가 사라져도, 람다 안의 복사본은 strand_ 콜백이 실행될 때까지 유지됩니다.

>> 아! 그러면 별도 스레드 안이 strand_ 캡처리스트에 self = shared_from_this() 없기 때문에 별도 스레드가 바로 종료가 되는구나.
⏺ 정확합니다.

  별도 스레드 캡처리스트와 strand_ 캡처리스트를 비교하면:

  // 별도 스레드 캡처
  std::thread([self = shared_from_this(), hist]() mutable {
      ...
      net::post(self->strand_,
          // strand_ 캡처 (값 복사)
          [self, intent, response, hist, audioBytes]() {
              ...
          });
      // net::post() 반환 즉시
      // 별도 스레드 캡처 self 소멸 → 참조 카운트 -1
      // 별도 스레드 종료
  })

  ---
  참조 카운트 흐름

  별도 스레드 시작
      └─ 별도 스레드 self  →  참조 카운트 +1

  net::post() 호출
      └─ strand_ 람다 self  →  참조 카운트 +1

  별도 스레드 종료
      └─ 별도 스레드 self 소멸  →  참조 카운트 -1
         (strand_ 람다 self 가 살아있으므로 WsSession 유지)

  strand_ 람다 실행 완료
      └─ strand_ 람다 self 소멸  →  참조 카운트 -1

  별도 스레드가 종료되어도 strand_ 람다가 self를 들고 있으므로 WsSession은 안전하게 유지됩니다.

```

# `WsSession.sendJson()`, 'WriteItem' 설명해 줘.
```
  ---
  WriteItem 이란

  std::variant는 여러 타입 중 하나를 담을 수 있는 타입입니다.

  using WriteItem = std::variant<std::string, std::vector<uint8_t>>;

  WriteItem은 std::string 또는 std::vector<uint8_t> 둘 중 하나를 담습니다.

  WriteItem
      ├─ std::string           →  JSON 텍스트 프레임
      └─ std::vector<uint8_t>  →  오디오 바이너리 프레임

  ---
  왜 하나의 큐로 합쳤나

  텍스트와 바이너리를 전송 순서대로 처리하기 위해서입니다.

  브라우저로 보내는 순서
      1. LLM_RESULT  (텍스트)
      2. TTS_TEXT    (텍스트)
      3. 오디오      (바이너리)
      4. BOT_READY   (텍스트)

  큐를 분리하면 순서가 섞일 수 있습니다. 하나의 큐로 관리해야 순서가 보장됩니다.

  ---
  sendJson() 동작

  void sendJson(json j) {
      writeQueue_.push_back(j.dump());  // JSON → 문자열로 직렬화 후 큐에 추가
      if (!writing_) doWrite();         // 전송 중이 아니면 즉시 전송 시작
  }

  j.dump()로 JSON 객체를 문자열로 변환해서 큐에 넣습니다.

  sendJson({{"type", "LLM_RESULT"}, {"intent", "문의"}})
      └─ j.dump() = "{\"type\":\"LLM_RESULT\",\"intent\":\"문의\"}"
              └─ writeQueue_ 에 추가

  ---
  sendBinary() 동작

  void sendBinary(std::vector<uint8_t> data) {
      writeQueue_.push_back(std::move(data));  // 오디오 데이터 큐에 추가
      if (!writing_) doWrite();
  }

  std::move()로 복사 없이 큐에 넣습니다.

  ---
  writing_ 플래그 역할

  writing_ = false  →  큐에 추가 후 즉시 doWrite() 호출
  writing_ = true   →  큐에만 추가 (현재 전송 중, doWrite가 완료 후 자동으로 다음 것 처리)

  async_write는 동시에 두 번 호출하면 안 되므로 writing_ 플래그로 중복 호출을 막습니다.

>> using 은 alias 와 비슷한 건지? std::variant<~~~ 긴 내용을 WriteItem 으로 alias 한다. 맞어?
>>>> 참조 
using WriteItem = std::variant<std::string, std::vector<uint8_t>>;

⏺ 맞습니다.

  using은 C++11에서 도입된 타입 별칭(alias) 문법입니다.

  using WriteItem = std::variant<std::string, std::vector<uint8_t>>;

  이후 std::variant<std::string, std::vector<uint8_t>> 대신 WriteItem으로 씁니다.

  // using 없으면
  std::deque<std::variant<std::string, std::vector<uint8_t>>> writeQueue_;
  std::variant<std::string, std::vector<uint8_t>> item = ...;

  // using 있으면
  std::deque<WriteItem> writeQueue_;
  WriteItem item = ...;

  ---
  typedef 와 비교

  예전 C++ 방식인 typedef와 동일한 역할입니다.

  typedef std::variant<std::string, std::vector<uint8_t>> WriteItem;  // 구버전
  using WriteItem = std::variant<std::string, std::vector<uint8_t>>;  // 신버전

  현대 C++에서는 가독성이 좋은 using을 표준처럼 씁니다.
  

>> if (!writing_) doWrite(); 그럼, 전송 중이여서 못 보내면 언제 보낼 기회 있는지?
⏺ WsSession.doWrite()를 보면 답이 있습니다.

  void doWrite() {
      if (writeQueue_.empty()) { writing_ = false; return; }
      writing_ = true;

      WriteItem item = std::move(writeQueue_.front());
      writeQueue_.pop_front();

      // ... async_write 실행
      ws_.async_write(net::buffer(...),
          net::bind_executor(strand_,
              [self = shared_from_this(), ...](beast::error_code ec, size_t) {
                  if (ec) { self->writing_ = false; return; }
                  self->doWrite();  // ← 여기서 다음 것 처리
              }));
  }

  ---
  흐름

  sendJson(A)
      └─ writeQueue_ = [A]
      └─ writing_=false → doWrite() 호출
              └─ writing_=true, A 전송 시작

  sendJson(B)         ← A 전송 중에 호출
      └─ writeQueue_ = [B]
      └─ writing_=true → doWrite() 호출 안 함, 큐에만 쌓임

  sendJson(C)         ← A 전송 중에 호출
      └─ 콜백에서 doWrite() 재호출  ← B 전송 시작

  B 전송 완료
      └─ 콜백에서 doWrite() 재호출  ← C 전송 시작

  C 전송 완료
      └─ 콜백에서 doWrite() 재호출
              └─ writeQueue_ 비어있음 → writing_=false → 종료

  전송 완료 콜백에서 doWrite()를 재귀 호출하기 때문에 큐에 쌓인 것들이 순서대로 자동으로 처리됩니다.

```

# `WsSession.doWrite()` 설명해 줘.
```
>> 참조 소스
void doWrite() {
      if (writeQueue_.empty()) { writing_ = false; return; }
      writing_ = true;

      WriteItem item = std::move(writeQueue_.front());
      writeQueue_.pop_front();

      if (std::holds_alternative<std::string>(item)) {
          auto msg = std::make_shared<std::string>(std::move(std::get<std::string>(item)));
          ws_.text(true);
          ws_.async_write(net::buffer(*msg),
              net::bind_executor(strand_,
                  [self = shared_from_this(), msg](beast::error_code ec, size_t) {
                      if (ec) { self->writing_ = false; return; }
                      self->doWrite();
                  }));
      } else {
          auto buf = std::make_shared<std::vector<uint8_t>>(
              std::move(std::get<std::vector<uint8_t>>(item)));
          ws_.binary(true);
          ws_.async_write(net::buffer(*buf),
              net::bind_executor(strand_,
                  [self = shared_from_this(), buf](beast::error_code ec, size_t) {
                      if (ec) {
                          LOG_ERROR("[CTI] 오디오 전송 오류 callId={} {}", self->callId_, ec.message());
                          self->writing_ = false;
                          return;
                      }
                      self->doWrite();
                  }));
      }
  }
  
  ---
  전체 역할

  writeQueue_에서 항목을 하나씩 꺼내 브라우저로 전송합니다. 텍스트(JSON)와 바이너리(오디오)를 구분해서
  처리합니다.

  ---
  ① 큐 비어있으면 종료

  if (writeQueue_.empty()) { writing_ = false; return; }

  보낼 것이 없으면 writing_=false로 리셋하고 종료합니다. 이후 sendJson()이 호출되면 다시 doWrite()를
  시작합니다.

  ---
  ② 큐에서 꺼내기

  writing_ = true;
  WriteItem item = std::move(writeQueue_.front());
  writeQueue_.pop_front();

  큐 앞에서 항목을 꺼냅니다. std::move()로 복사 없이 가져옵니다.

  writeQueue_ = [A, B, C]
                  │
                  └─ front() 꺼냄
  writeQueue_ = [B, C]
  item = A

  ---
  ③ 타입 판별

  if (std::holds_alternative<std::string>(item))

  WriteItem이 std::string인지 std::vector<uint8_t>인지 확인합니다.

  item = "{"type":"LLM_RESULT"}"  →  holds_alternative<string> = true  →  텍스트 전송
  item = [0x49, 0x44, 0x33, ...]  →  holds_alternative<string> = false →  바이너리 전송

  ---
  ④ 텍스트 전송 (JSON)

  auto msg = std::make_shared<std::string>(std::move(std::get<std::string>(item)));
  ws_.text(true);
  ws_.async_write(net::buffer(*msg), [콜백]);

  std::get<std::string>(item)으로 string 값을 꺼냅니다.

  shared_ptr로 감싸는 이유는 async_write가 비동기라 콜백이 호출될 때까지 데이터가 살아있어야 하기
  때문입니다.

  async_write 시작
      │  (비동기, 즉시 반환)
      │  이 사이에 msg가 사라지면 안 됨
      │
      └─ 전송 완료 → 콜백 호출
              └─ msg shared_ptr 소멸

  ---
  ⑤ 바이너리 전송 (오디오)

  auto buf = std::make_shared<std::vector<uint8_t>>(
      std::move(std::get<std::vector<uint8_t>>(item)));
  ws_.binary(true);
  ws_.async_write(net::buffer(*buf), [콜백]);

  텍스트 전송과 동일한 구조입니다. ws_.binary(true)로 바이너리 프레임으로 전송합니다.

  바이너리 전송 실패 시 에러 로그를 남기는 점이 텍스트와 다릅니다.

  LOG_ERROR("[CTI] 오디오 전송 오류 callId={} {}", self->callId_, ec.message());

  ---
  ⑥ 재귀 루프

  self->doWrite();  // 전송 완료 후 다음 항목 처리

  전송이 완료되면 doWrite()를 재호출해서 큐가 빌 때까지 반복합니다.

  doWrite()
      └─ A 전송 완료
              └─ doWrite()
                      └─ B 전송 완료
                              └─ doWrite()
                                      └─ 큐 비어있음 → writing_=false → 종료

>> std::move(std::get<std::string>(item)) 은 item 에서 string 을 뽑아내는데 복사하지 않고 이동(move)
  한다는 건가?
⏺ 맞습니다.

  ---
  std::get<std::string>(item)

  variant에서 string 값을 꺼냅니다.

  WriteItem item = std::string("hello");

  std::get<std::string>(item)  →  "hello" (string 참조)

  ---
  std::move() 없이 vs 있을 때

  // 복사 (❌ 비효율)
  std::string s = std::get<std::string>(item);
  // item 안의 "hello" → s 로 글자 하나하나 복사

  // 이동 (✅ 효율적)
  std::string s = std::move(std::get<std::string>(item));
  // item 안의 "hello" → s 로 소유권만 넘김, 복사 없음
  // item 은 빈 상태가 됨

  ---
  이동이 효율적인 이유

  문자열이나 벡터는 힙에 데이터를 저장합니다.

  복사:
      item → ["h","e","l","l","o"]  (힙)
      s    → ["h","e","l","l","o"]  (힙, 새로 할당)
      → 메모리 두 배 사용, 복사 비용 발생

  이동:
      item → ["h","e","l","l","o"]  (힙)
      └─ 포인터만 s 로 넘김
      s    → ["h","e","l","l","o"]  (동일한 힙 주소)
      item → (빈 상태)
      → 메모리 추가 없음, 포인터 이동만

  오디오 데이터(std::vector<uint8_t>)는 수십 KB ~ 수백 KB일 수 있어서 이동이 특히 중요합니다.

>> std::get<std::string> 사용하는 이유는 item 이 std::variant 이여서 그런가?
⏺ 맞습니다.

  variant는 여러 타입 중 하나를 담는 컨테이너라서, 꺼낼 때 어떤 타입으로 꺼낼지 명시해야 합니다.

  using WriteItem = std::variant<std::string, std::vector<uint8_t>>;

  WriteItem item = std::string("hello");

  // 타입을 명시해서 꺼냄
  std::get<std::string>(item)           // ✅ string으로 꺼냄
  std::get<std::vector<uint8_t>>(item)  // ❌ 실제 타입이 string이라 예외 발생

  ---
  일반 변수와 비교

  // 일반 변수는 타입이 고정 → 그냥 꺼내면 됨
  std::string s = "hello";
  s  // 그냥 사용

  // variant는 타입이 런타임에 결정 → 타입 명시 필요
  std::variant<std::string, std::vector<uint8_t>> item = "hello";
  std::get<std::string>(item)  // 타입 명시해서 꺼냄

  ---
  그래서 항상 holds_alternative로 먼저 확인

  // 타입 확인 후 꺼내는 패턴
  if (std::holds_alternative<std::string>(item)) {
      std::get<std::string>(item)  // 안전하게 꺼냄
  } else {
      std::get<std::vector<uint8_t>>(item)  // 안전하게 꺼냄
  }

  확인 없이 std::get을 잘못된 타입으로 호출하면 std::bad_variant_access 예외가 발생합니다.

>> 큐 비어있음 → writing_=false → 종료 => doWrite() 한 번 호출 되고 큐 비어 있을 때까지 재귀호출해서
  큐에 내용을 다 처리한다. 그리고 끝. 다음에 doWrite() 호출 할 때까지는 종료된 상태가 되는거네. 맞어?
⏺ 맞습니다.

  ---
  전체 생명주기

  sendJson(A)
      └─ writing_=false → doWrite() 호출  ← 시작

          doWrite()
              └─ A 전송 시작 (writing_=true)
                      └─ 완료 → doWrite() 재호출

          doWrite()
              └─ B 전송 시작
                      └─ 완료 → doWrite() 재호출

          doWrite()
              └─ 큐 비어있음 → writing_=false → 종료  ← 끝

                      [대기 상태]

  sendJson(X)  ← 나중에 새 메시지 오면
      └─ writing_=false → doWrite() 호출  ← 다시 시작

  ---
  핵심 정리

  doWrite() 동작 여부는 writing_ 플래그로 결정

  writing_=false  →  유휴 상태, sendJson() 이 doWrite() 를 시작시킴
  writing_=true   →  실행 중, sendJson() 은 큐에만 쌓음
                     완료 콜백이 자동으로 다음 doWrite() 호출

  sendJson()과 doWrite()가 역할을 나눠서, sendJson()은 시작 트리거 역할만 하고 이후는 doWrite() 재귀가
  자동으로 처리하는 구조입니다.

```

# `WsServer.doAccept()` 설명해 줘.
```
>> 참조 소스
void WsServer::doAccept() {
      acceptor_.async_accept(
          [this](beast::error_code ec, tcp::socket socket) {
              if (!ec) {
                  std::make_shared<WsSession>(
                      std::move(socket), ioc_, tokenMgr_, llm_, tts_)->start();
              }
              doAccept();
          });
  }
  

```

# RtzrWebSocketSttService.ioc_ 은 어디서 생성되지?
```
⏺ 별도 생성 코드가 없습니다. 멤버 변수 선언과 동시에 자동 생성됩니다.

  class RtzrWebSocketSttService {
      net::io_context ioc_;  // ← 선언만으로 기본 생성자 자동 호출
  };

  C++에서 클래스 멤버 변수는 객체가 생성될 때 자동으로 초기화됩니다.

  RtzrWebSocketSttService 객체 생성
          │
          ▼
  멤버 변수 순서대로 초기화
      ioc_       → net::io_context 기본 생성자 호출 (자동)
      sslCtx_    → ssl::context 생성
      writing_   → false
  RtzrWebSocketSttService 객체 생성
          │
          ▼
  멤버 변수 순서대로 초기화
      ioc_       → net::io_context 기본 생성자 호출 (자동)
      sslCtx_    → ssl::context 생성
      writing_   → false
      connected_ → false
      ...
          │
          ▼
  RtzrWebSocketSttService 생성자 본문 실행
      sslCtx_.set_default_verify_paths();

  int x = 0;처럼 명시적으로 초기화하지 않아도 net::io_context는 기본 생성자가 있어서 자동으로 만들어집니다.

```

# WsServer::doAccept() 에서 acceptor_.async_accept(... => 브라우저 Websocket 연결 수락하는 부분?
```
⏺ 맞습니다. 단계별로 보면 두 번의 수락이 있습니다.

  ---
  acceptor_.async_accept() — TCP 연결 수락

  // WsServer::doAccept()
  acceptor_.async_accept([this](beast::error_code ec, tcp::socket socket) {

  9090 포트로 들어오는 TCP 연결을 수락합니다. 아직 WebSocket이 아닌 일반 TCP 소켓입니다.

  ---
  ws_.async_accept() — WebSocket 핸드셰이크 수락

  // WsSession::doAccept()
  ws_.async_accept(net::bind_executor(strand_, [콜백]));

  TCP 연결 위에서 HTTP Upgrade 요청을 수락해서 WebSocket으로 업그레이드합니다.

  ---
  전체 흐름

  브라우저
      │
      │── TCP 연결 요청 ──────────────▶ WsServer::doAccept()
      │                                  acceptor_.async_accept()
      │◀─ TCP 연결 수락 ─────────────────
      │                                  WsSession 생성 → start()
      │
      │── HTTP Upgrade 요청 ──────────▶ WsSession::doAccept()
      │   GET /ws/cti                    ws_.async_accept()
      │   Upgrade: websocket
      │◀─ 101 Switching Protocols ───────
      │
      │  WebSocket 통신 시작

  WsServer는 TCP 연결을, WsSession은 WebSocket 업그레이드를 각각 담당합니다.

```

# 아래의 최근 변경 사항인 아래 내용을 좀 더 구체적이고 쉽게 설명해줘.
```
>> 최근 변경 사항
 - Boost::system → Boost::boost 로 변경 (헤더 전용 라이브러리 대응)
 - CMP0167 policy 추가 (CMake 3.30+ 호환)
 
● ---
  1. Boost::system → Boost::boost

  배경: Boost는 두 종류의 라이브러리가 섞여 있다

  ┌─────────────────────────┬────────────────────────────┬──────────────────────────────────────────────┐
  │          종류           │            예시            │                     특징                     │
  ├─────────────────────────┼────────────────────────────┼──────────────────────────────────────────────┤
  │ 헤더 전용 (header-only) │ Asio, Beast                │ .h 파일만 include하면 됨. .so/.a 링크 불필요 │
  ├─────────────────────────┼────────────────────────────┼──────────────────────────────────────────────┤
  │ 컴파일 필요 (compiled)  │ boost_system, boost_thread │ 별도 .so/.a 파일이 필요                      │
  └─────────────────────────┴────────────────────────────┴──────────────────────────────────────────────┘

  이 프로젝트에서 실제로 쓰는 Boost 기능(Asio, Beast)은 헤더 전용이라 .so 파일 링크가 필요 없다.

  Boost::system을 쓰면 생기는 문제

  Boost::system은 컴파일된 라이브러리 타겟이다. 이게 없는 환경(헤더만 설치된 경우)에서는:
  Could not find Boost::system
  빌드가 실패한다.

  Boost::boost는 뭔가?

  Boost::boost는 "헤더 파일 경로만 제공하는 인터페이스 타겟"이다. 실제 .so 링크 없이 #include <boost/asio.hpp> 같은 include만 해결해 준다.

  Boost::system  →  libboost_system.so 링크 필요  (없으면 빌드 실패)
  Boost::boost   →  헤더 경로만 추가              (항상 동작)

  ---
  2. CMP0167 policy

  배경: Boost를 찾는 방법이 두 가지다

  CMake가 find_package(Boost)를 실행할 때, 내부적으로 두 가지 방식 중 하나로 Boost를 탐색한다.

  ┌──────────────────────┬────────────────────────────────────────────────────────┐
  │         방식         │                          설명                          │
  ├──────────────────────┼────────────────────────────────────────────────────────┤
  │ FindBoost (구버전)   │ CMake 내장 스크립트. CMake 버전마다 동작이 조금씩 다름 │
  ├──────────────────────┼────────────────────────────────────────────────────────┤
  │ BoostConfig (신버전) │ Boost 설치 시 함께 오는 공식 CMake 설정 파일 사용      │
  └──────────────────────┴────────────────────────────────────────────────────────┘

  CMake 3.30에서 CMP0167 정책이 도입됐다. 이 정책의 의미:

  NEW = "BoostConfig 방식을 우선 사용해라"
  OLD = "FindBoost 방식을 그대로 써라" (기본값, deprecated 경고 발생)

  if(POLICY CMP0167)가 필요한 이유

  cmake_policy(SET CMP0167 NEW)를 조건 없이 쓰면, CMake 3.30 미만 버전에서는 이 정책 자체를 모르기 때문에 에러가 난다. if(POLICY CMP0167)로 감싸면 3.30 이상에서만
  적용, 이하에서는 조용히 무시한다.

  if(POLICY CMP0167)          # CMake 3.30+에서만 이 블록 진입
    cmake_policy(SET CMP0167 NEW)
  endif()

  결론: 구버전/신버전 CMake 어디서 빌드해도 경고 없이 동작하게 하는 호환성 코드다.

>> `Boost::boost` 랑 `Boost::system` 차이 예시 코드로 보여줘
● 프로젝트 구조 예시

  my_app/
  ├── CMakeLists.txt
  └── main.cpp

  ---
  Case 1: Boost::system (컴파일된 라이브러리 필요)

  # CMakeLists.txt
  find_package(Boost REQUIRED COMPONENTS system)

  target_link_libraries(my_app
      Boost::system      # libboost_system.so 링크 필요
  )

  // main.cpp
  #include <boost/system/error_code.hpp>

  int main() {
      boost::system::error_code ec;
      // error_code는 boost_system 라이브러리 내부 구현에 의존
      // → libboost_system.so 가 없으면 링크 에러
  }

  Boost::system이 없는 환경에서 빌드하면:
  -- Could NOT find Boost (missing: system)
  CMake Error: Could not find Boost::system

  ---
  Case 2: Boost::boost (헤더만 있으면 됨)

  # CMakeLists.txt
  find_package(Boost REQUIRED)   # COMPONENTS 없음

  target_link_libraries(my_app
      Boost::boost       # 헤더 경로만 추가. .so 링크 없음
  )

  // main.cpp
  #include <boost/asio.hpp>      // 헤더 전용 → 항상 OK
  #include <boost/beast.hpp>     // 헤더 전용 → 항상 OK

  int main() {
      boost::asio::io_context ioc;
      // Asio는 헤더 안에 구현이 전부 들어있음
      // → libboost_asio.so 같은 건 존재하지 않음
  }

  빌드 결과:
  -- Found Boost: /usr/include (헤더 경로만 설정)
  -- Build succeeded

  ---
  왜 헤더 전용이 가능한가?

  // boost/asio/io_context.hpp (실제 Boost 헤더 구조 개념)

  #ifndef BOOST_ASIO_IO_CONTEXT_HPP
  #define BOOST_ASIO_IO_CONTEXT_HPP

  // 클래스 선언 + 구현이 모두 .hpp 안에 있음
  class io_context {
  public:
      void run() {
          // 구현 코드가 여기 전부 있음
          // → 컴파일러가 #include 할 때 직접 컴파일
      }
  };

  #endif

  일반 라이브러리는 선언(.h) 과 구현(.cpp → .so) 이 분리되어 있지만,
  헤더 전용은 구현까지 .hpp 에 다 들어있어서 .so 가 필요 없다.

  ---
  정리

  내가 쓰는 것          필요한 타겟
  ─────────────────────────────────────────
  boost::asio           Boost::boost   (헤더 전용)
  boost::beast          Boost::boost   (헤더 전용)
  boost::system         Boost::system  (libboost_system.so 필요)
  boost::thread         Boost::thread  (libboost_thread.so 필요)
  boost::filesystem     Boost::filesystem (libboost_filesystem.so 필요)

  이 프로젝트는 Asio/Beast만 쓰므로 Boost::boost 로 충분하다.
  
>> 그럼, 기존에는 왜 된거야?
● 좋은 질문입니다. 두 가지 이유가 겹쳐 있습니다.

  ---
  이유 1: 그 환경에 libboost_system.so가 설치되어 있었다

  # apt로 Boost 설치하면 컴파일된 라이브러리도 함께 딸려옴
  apt install libboost-dev         # 헤더만
  apt install libboost-system-dev  # 헤더 + libboost_system.so ← 이게 있으면 Boost::system 동작

  devcontainer나 이전 빌드 환경에 libboost-system-dev가 설치되어 있었다면 문제없이 빌드된다. 우연히 환경이 맞았던 것.

  ---
  이유 2: Boost 1.69부터 boost_system이 사실상 헤더 전용으로 바뀌었다

  Boost 1.68 이하:  boost::system::error_code 구현 → libboost_system.so 안에 있음
  Boost 1.69 이상:  boost::system::error_code 구현 → 헤더로 이동, .so는 빈 껍데기

  1.69 이후엔 libboost_system.so가 있어도 내용이 거의 없다. 그러면서도 CMake 타겟(Boost::system)은 여전히 존재하고 find_package에서 찾아지기 때문에, 빌드는
  통과된다.

  ---
  그럼 왜 바꿨나?

  환경이 달라지면 깨진다는 게 문제다.

  환경 A (이전 devcontainer)
    libboost-system-dev 설치됨 → Boost::system 찾음 → 빌드 성공 ✅

  환경 B (새 머신 / 최소 Docker 이미지)
    libboost-dev만 설치됨 → Boost::system 못 찾음 → 빌드 실패 ❌

  Boost::boost로 바꾸면 .so 유무와 무관하게 어느 환경에서든 동작한다.

  결론: 기존에 된 건 "운 좋게 환경이 맞았던 것"이고, 지금 수정은 환경에 의존하지 않도록 명시적으로 고친 것이다.

>> Boost::boost 쓰면 pthread는 왜 따로 링크해?
● 핵심 개념 차이 때문입니다.

  ---
  Boost::boost = "헤더 경로만 추가"

  target_link_libraries(my_app
      Boost::boost   # -I/usr/include/boost 만 추가
                     # 그 이상은 아무것도 안 함
  )

  Boost::boost는 #include <boost/asio.hpp> 가 찾아지도록 경로만 잡아줄 뿐이다.
  헤더 안의 코드가 무엇을 호출하는지는 신경 쓰지 않는다.

  ---
  Asio 헤더가 컴파일되면 pthread 심볼을 호출한다

  // boost/asio/detail/posix_thread.hpp (개념적으로)

  #include <pthread.h>

  class posix_thread {
      void start() {
          pthread_create(&thread_, ...);  // ← 이 심볼이 어디 있지?
      }
      void join() {
          pthread_join(thread_, ...);     // ← 이것도
      }
  };

  Asio는 헤더 전용이지만, 그 헤더 안에서 OS 레벨 함수인 pthread_create, pthread_join, pthread_mutex_lock 등을 직접 호출한다.

  ---
  링커 관점에서 보면

  컴파일 단계: main.cpp + Asio 헤더 → main.o
               main.o 안에 pthread_create 호출 코드가 생성됨

  링크 단계:   main.o → 실행파일
               "pthread_create 가 어느 .so에 있지?" → libpthread.so
               pthread 안 링크하면 → undefined reference to `pthread_create` ❌

  ---
  왜 Boost가 자동으로 pthread를 안 붙여주나?

  Boost 입장에서는 "내가 헤더만 제공하면 됐고, 네가 Asio를 쓸지 말지 모른다"는 것이다.

  Boost.Optional 만 쓰는 프로젝트  → pthread 불필요
  Boost.Algorithm 만 쓰는 프로젝트 → pthread 불필요
  Boost.Asio 쓰는 프로젝트         → pthread 필요

  Boost::boost가 pthread를 자동으로 달아버리면, Asio를 안 쓰는 프로젝트까지 불필요하게 pthread를 링크하게 된다. 그래서 사용자가 명시적으로 선언하도록 설계되어
  있다.

  ---
  한 줄 요약

  ▎ Boost::boost = Boost 헤더 경로
  ▎ pthread = Asio 헤더가 내부적으로 호출하는 OS 함수
  ▎ 둘은 레이어가 달라서 따로 링크해야 한다.

```

# 
```
```

# 
```
```

# 
```
```

# 
```
```

# 
```
```

# 
```
```

# 
```
```

# 
```
```


