# 단축키
## VS Code 기준으로:
- 뒤로 가기 (Go Back): Ctrl + - (Control + 마이너스)
- 앞으로 가기 (Go Forward): Ctrl + Shift + -

# 콜백함수에 리턴 타입 명시는 파라미터 옆에 화살표(->) 으로 표시
```
[캡처](파라미터) -> 리턴타입 { 본문 }

>> 예시
[](int a, int b) -> int { return a + b; }   // int 반환
[](std::string s) -> bool { return s.empty(); }  // bool 반환
[](std::shared_ptr<Session> s) -> void { ... }   // void (생략 가능)

void 는 생략해도 추론되지만, 나머지는 복잡한 경우 명시해주는 게 좋아요.
```

# C++ Boost.Asio
## 참조
### (동영상)(최홍배)C++ Boost.Asio로 만드는 온라인 게임 서버
- https://www.youtube.com/watch?v=3DT2EcIlGkU&list=PLW_xyUw4fSdYAPV47O-ArzQXiZnsY1pZ8&index=11
### (동영상)(최홍배)C++
- https://www.youtube.com/watch?v=cAOjUh6Bttg&list=PLW_xyUw4fSdaUT6mUOlMUvlx9JGtpPsWk&index=67

# STL(Standard Template Library : 표준 템플릿 라이브러리)
- 프로그래머들이 공통적으로 사용하는 자료구조와 알고리즘을 구현한 클래스들로 이루어져 있다.
- 템플릿 기법을 사용하였기에 어떤 자료형에 대해서도 사용할 수 있다.
- 3가지 종류의 컴포넌트 제공. 종류는 컨테이너, 반복자, 알고리즘
## 컨테이너
- 자료를 저자앟는 창고와 같은 역할을 하는 구조이다. 즉, 배열이나 연결 리스트, 벡터, 집합, 사전, 트리 등 이 여기에 해당된다.
## 반복자
- 컨테이너의 요소를 가리키는 데 사용된다. 반복자는 실제로 컨테이너와 알고리즘 사이의 연결고리 역할을 한다.
## 알고리즘
- 새로운 프로그램을 작성할 때 상당히 많은 알고리즘들이 필요하다. 알고리즘은 처음부터 새로 만드는 것보다는 누군가의 구현한 오류 없는 버전을 사용하는 편이 훨씬 빠르고 간편한다.
# 컨테이너
- 컨테이너 : 순차 컨테이너(시퀀스), 연관 컨테이너(연관 시퀀스)
  - 시퀀스 : 벡터, 리스트(node기반), 덱
  - 연관 시퀀스 : 집합(set)(node기반), Map(node기반)
## 예제
```
int main()
{
    list<int> values;
    
    srand(time(NULL));
    for(int i=0; i < 10; i++){
        values.push_back(rand()%100);
    }
    
    values.sort();
    
    for(auto& e: values){
        std:cout << e << ' ';
    }
    
    std::cout << endl;
    return 0;
}
```
# 반복자
- 컨테이너와 알고리즘의 연결고리 역할.
- 컨테이너와 알고리즘을 제대로 사용하려면 반복자를 올바르게 이해해야한다.
- 컨테이너의 종류에 따라서 요소에 접근하는 방법이 상당히 다르다.
- STL을 작성한 사람들은 컨테이너의 종류에 관계없이 요소들에 접근하게 하기 위하여 반복자라는 방식을 제안하였다.반복자는 컨테이너의 요소를 가리키는 객체이다. 기존의 포인터와 비슷하여 반복자를 흔히 일반화된 포인터(generalized pointer) 라고도 한다. 반복자를 사용하게 되면 컨테이너의 종류에 상관없이 일관된 방법으로 컨테이너의 접근할 수 있다.
- 반복자는 어디에 이용되는가?
   - 알고리즘은 컨테이너의 요소에 접근하여서 읽거나 써야한다.
- STL 의 핵심 개념은 시퀀스이다. 시퀀스는 어떤 순서를 가지고 있는 일련의 데이터이다.
- 반복자는 시퀀스의 요소를 식별하는 객체이다.
- begin() 으로 식별되는 요소는 시퀀스의 첫 번째 요소이고 end() 는 시퀀스의 끝을 하나 지난 위치를 가리킨다. 그래서 컨테이너의 끝을 나타내는 보초값(sentinel) 을 반환. 반환하는 값은 포인터에서의 NULL 값과 같은 의미.
- 반복자는 다음의 연산자가 중복 정의 되어 있는 객체이다.
  - 컨테이언에서 다음 요소 : ++ 연산자, 
  - 컨테이언에서 이음 요소 : --연산자, 
  - 두 개 요소가 같은지 여부 : == 연산자, != 연산자
  - 요소의 값 추출 : *(역참조) 연산자
- 반복자는 코드(알고리즘)을 데이터에 연결하는 데 사용.
- 반복자는 알고리즘과 컨테이너 사이에 독립성 제공.
- STL 을 작성하였던 Alex Stepanov 는 다음과 같이 말하고 있다. "STL 알고리즘과 컨테이너가 잘 작동하는 이유는 서로에 대해서 알지 못하기 때문이다."
- 반복자의 종류
   - 전향 반복자(forward iterator) : ++ 연산자만 가능.
   - 양방향 반복자(bidirectional iterator) : ++ 연산자, --연산자 가능.
   - 무작위 접근 반복자(random access iterator) : ++ 연산자, --연산자, []연산자 가능.
   - 범위 기반 루프(range-base loop) : C++ 11 부터 가능.
      - 반복자 사용성 감소.
      - 그러나 컨터에이너의 중간에 삽입하는 경우에는 반드시 반복자를 사용.
- OLD C++ 버전
   - 반복자의 정확한 자료형을 알야하 하는 단점.
- C++14 버전 #1
  - 반복자의 정확한 데이터 구조 몰라도 auto 키워드 사용 가능.
- C++14 버전 #2
  - 범위 기반 루프(range-base loop) 사용.
```
    for(auto& e: values){
        std:cout << e << ' ';
    }
```
- 컨테이너의 공통 멤버 함수
  - Container() : 기본 생성자
  - Container(size) : 크기가 size인 컨테이너 생성
  - Container(size, value) : 크기가 size 이고 값이 value 인 컨테이너 생성
  - Container(iterator, iterator) : 다른 컨테이너로부터 초기값의 범위를 받아서 생성
  - begin() : 첫 번째 요소의 위치
  - clear() : 모든 요소를 삭제
  - empty() : 비어있는지 검사
  - end() : 반복자가 마지막 요소를 지난 위치
    - 마지막 번째 요소 바로 뒤 가상 공간
    - "순방향 순회 종료 신호" 
  - erase(iterator) : 컨테이너의 중간 요소를 삭제
  - erase(iterator, iterator) : 컨테이너의 저장된 범위를 삭제
  - front() : 컨테이너의 첫 번째 요소 반환
  - insert(iterator, value) : 컨테이너의 중간에 value 를 삽입
  - pop_back() : 컨테이너의 마지막 요소를 삭제
  - push_back(value) : 컨테이너의 끝에 데이터를 추가
  - rbegin() : 끝을 나타내는 역반복자
  - rend() : 역반복자가 처음을 지난 위치
     - 첫 번째 요소 바로 앞 가상 공간
     - "역방향 순회 종료 신호"
     - 정방향의 end() 와 대칭되는 개념
  - size() : 컨테이너의 크기
  - operator=(Container) : 대입연산자의 중복 정의
# 덱(deque)
- 'double-ended queue' 의 약자.
- 덱은 양방향으로 커질 수 있도록 구현된 동적 배열.
- queue은 한 쪽에서만 삽입, 삭제 가능.
- vector iterator 크기는 24byte 이다.
# 리스트
- 외부에서 보면 벡터와 완전히 동일. 순차적인 데이터 저장. 벡터를 리스트로 대체하는 것도 가능.
- 하디만 벡터와 내부 구조가 다르다. 이중 연결 리스트로 구현.
- 중간 위치에 삽입이나 삭제가 빈번한 경우에 효율적이다.
- 모든 노드가 앞 노드와 뒤 노드를 가리키는 포인터를 동시에 갖고 있다. 따라서 반복자 이용해서 양방향 이동 가능.
- 만약 삽입이나 삭제가 양 끝단에서만 빈번하면 덱(deque) 이 낫다. 
- 순차 컨테이너 클래스 가지고 있는 공통 멤버 함수이외에 추가로 갖는 함수가 있다
   - push_front()
   - pop_front()
   - remove()
   - unique() : 인접한 양 옆의 원소가 같으면 유일하게 만든다(하나만 빼고 삭제)
   - merge() : 합병 정렬, 기본은 오름차순
   - reverse() : 요소들의 순차열을 뒤집는다
   - sort() : 정렬
   - splice() : 이어 붙이기
# C++ 캐스트
- static_cast
- dynamic_cast
  - RTTI
# C++ 스레드와 뮤텍스 입문
- Mutex
  - lock
  - unlock
  - dead lock
- std::lock_guard
  - RAII 패턴
```azure
#include <thread>
  #include <iostream>
  #include <mutex>

  int main() {
  std::mutex mtx_lock;

std::thread Thread1([&]() {
    for(int i = 0; i < 5; ++i) {
      std::lock_guard<std::mutex> guard(mtx_lock);
      std::cout << "Hello from Thread1 : "<< i << std::endl;
    }
  });

std::thread Thread2;
Thread2 = std::thread([&]() {
    for(int i = 10; i < 15; ++i) {
      std::lock_guard<std::mutex> guard(mtx_lock);
      std::cout << "Hello from Thread2 : "<< i << std::endl;
    }
  });

std::thread Thread3 = std::thread([&](int nParam) {
     for(int i = 20; i < 25; ++i) {
      std::lock_guard<std::mutex> guard(mtx_lock);
      std::cout << "Hello from Thread3 : "<< i << " with param : " << nParam << std::endl;
    }
  }, 100);

std::thread Threads1([&] () {
    for(int i = 1; i < 5; i++){
        std::lock_guard<std::mutex> guard(mtx_lock);
        std::cout << "Threads1 Num : " << i << std::endl;
    }
});

Thread1.join();
Thread2.join();
Thread3.join();
Threads1.join();

return 0;
}
```

# C++ 게임 개발과 디자인 패턴
## 싱글턴 패턴 : 오직 1개만 | 오직 하나만 존재해야 할 때
## 플라이웨이트 패턴 : 재활용 | 수많은 객체를 효율적으로 관리해야 할 때
## 미디에이터 패턴 : 중개자. 서로를 모른다 | 복잡한 객체 관계를 풀어야 할 때

# C++ vilatile 동시성 코드의 숨겨진 순서
## atomic 변수(원자적 변수)
## 재정렬(Reordering)
### 메모리 배리어
- release 배리어
- acquire 배리어
## Happen-Before 보증
- store_release
- load_acquire

# C++ 스마트 포인터 피해야 할 10가지 함정
## unique_ptr, shared_ptr
### unique_ptr : 독점, 1곳만 사용.
### shared_ptr : 공유, 여러 곳에서 사용. 참조 카운트 필요.
### 추천 : 기본으로서는 unique_ptr 써야 한다.
## 안정한 포인터 생성(초기화 함정 피하기)
### new : 두 번 메모리 할당.
```
shared_ptr<aircraft>pAircraft=(new AireCraft("F-16"); // Two Dynamic allocation-Slow;
```
### make_shared : 이걸 쓰는 게 낫다. 한 번 메모리 할당
```
shared_ptr<aircraft>pAircraft=make_shared<aircraft>("F-16"); // Single allocation-Fast;
```
## 동일한 원시 포인터로 여러 shared_prt 를 생성하는 것. 각자 자신이 유일한 소유자라고 착각합니다.
```
#include <iostream>

int main() {
  Aircraft* aircraft = new Aircraft("F-16");

  shared_ptr<Aircraft> sharedAircraft1(aircraft);
  cout << "Shared Aircraft 1: " << sharedAircraft1->getName() << std::endl;

  shared_ptr<Aircraft> sharedAircraft2(aircraft);
  cout << "Shared Aircraft 2: " << sharedAircraft1->getName() << std::endl;

  return 0;
}
```
# 포인터 생명주기 관리-manual deletion(수동 삭제)
- 제발 간선하지 말아줘.

# 고급 포인터 관계(순환 고리 끊기)
- shared_ptr 로 순환 참조 만들기. 두 객체가 서로를 가리키면 참조 카운트가 절대 0이 될 수 없음.
- weak_ptr : 해결 방법.
   - 소유권이 없는 스마트 포인터, 참조 카운트에 영향을 주지 않고 'shared_ptr' 객체를 관찰하여 순환 참조를 끊습니다.

# 스마트 포인터 실정 가이드(황금률)
## 기본은 unique_ptr : 공유 소유권이 필요할 때만 shared_ptr 사용
## make_ 함수 사용 : make_unique와 make_shared 로 안전하고 효율적인 생성
## 수동 delete 금지 : 스마트 포인터가 관리하는 포인터는 절대 직접 삭제하지 마세요
## weak_ptr로 순환방지 : 복잡한 객체 관계에서 메모리 누수를 막으세요
## weak_ptr 사용 전 확인 : lock() 으로 유효한 shared_ptr 를 얻은 후 사용

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter1~3
## 컴파일러는 최신 버전 설정 : C++ 11 이상.
## 변수 초기화는 중괄호 : int x{10}; 더 안전한 코드 작성 가능. Safe Code!
## Auto 타입 사용 : 복잡한 타임 일므을 단순화하여 코드를 읽고 유지보수하기 쉽게 만듭니다.
## 디버거 활용
## 컴파일 시점 상수 사용 : const vs constexpr
### const : 런타임 상수 프로그램 실행 중 결정되는 값에 사용
### constexpr : 컴파일 타임 상수 컴파일 시점에 이미 확정된 값에 사용. 이걸 사용해서 잘못된 값 할당 방지.

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter4~7
- 모던 C++ 툴킷 네 가지 필수 도구
## 1. C++ 의 핵심 연산(계산을 위한 도구)
### 비교
- 기존의 함정
  - int result = 10 / 3; => 결과:3(정밀도 손실)
- 모던 C++ 해결책
  - static_cast<double>(10) / 3; => 결과: 3.333...(정확한 결과)
### C++의 4가지 캐스트
- 평소엔 static_cast, 상속 다운캐스팅엔 dynamic_cast, 나머지는 특수한 경우에만.
```cpp
```
```
캐스트             용도                   안전도
static_cast       일반적인 타입 변환        ✅ 높음
dynamic_cast      상속 관계에서 안전한 변환   ✅ 런타임 체크
const_castconst   속성 제거/추가            ⚠️ 주의 필요
reinterpret_cast  메모리 재해석             ❌ 위험
```
```cpp
// 컴파일 타임에 타입 변환, 말이 되는 변환만 허용
static_cast<double>(10) / 3;   // int → double ✅
static_cast<int>(3.14);        // double → int (3) ✅
static_cast<std::string>(10);  // ❌ 컴파일 에러 (말이 안 됨)
```
```cpp
// 부모 → 자식 변환이 실제로 맞는지 런타임에 확인
Animal* a = new Dog();
Dog* d = dynamic_cast<Dog*>(a);  // 성공 → Dog 포인터
Cat* c = dynamic_cast<Cat*>(a);  // 실패 → nullptr 반환
```
```cpp
const int x = 10;
int* p = const_cast<int*>(&x);  // const 제거
// ⚠️ 실제로 수정하면 undefined behavior
// 레거시 API 연동할 때 어쩔 수 없이 씀
```
```cpp
int x = 65;
char* p = reinterpret_cast<char*>(&x);
// x의 메모리를 char로 그냥 재해석
// 🔧 하드웨어/네트워크 저수준 코드에서만 씀
```


## 2. 프로그램과의 대화법(소통을 위한 도구)
### 비교
- 기존 C++
  - std::cout << "..." << name; => 장황하고 오류 발생 가능성 있음. 
- 모던 C++23
  - std:print("User: {}", name); => 깔끔하고, 타입에 안전하며, 가독성 높음. 
### RAII= Resource Acquisition(애퀴지션:획득) Is Initialization => 자원 획득이 곧 초기화다
- 자원 관리를 객체의 생명 주기에 묶는 C++ 패턴. 객체 소멸 시 파일 같은 자원이 자동으로 해제됩니다.
- C++의 핵심 관용구로, 객체의 생명주기에 자원 관리를 묶는 패턴입니다.
- 핵심은 예외가 발생해도, return을 해도 스코프를 벗어나는 순간 소멸자가 반드시 실행된다는 보장입니다. 
- 덕분에 delete / close() / unlock()을 직접 호출할 필요가 없어 자원 누수를 방지합니다.
```
시점        동작
객체 생성   (초기화)자원 획득 (메모리 할당, 파일 열기, 락 잠금 등)
객체 소멸   (스코프 벗어남)자원 반납 (자동 해제)
```
```cpp
{
    std::lock_guard<std::mutex> lock(mtx);  // 생성 → 락 획득
    // ... 임계 구역 ...
}   // 스코프 끝 → 소멸자 자동 호출 → 락 해제
```
## 3. 코드의 의사 결정(논리와 선택을 위한 도구)
### if-else 의 논리
### if-init 문법 : C++17에서 추가된 if with initializer 문법입니다.
```cpp
if (auto file = openFile("data.txt"); file.is_open()) {
    // file 사용
}
// 여기서 file 소멸 → 자동으로 닫힘 (RAII)
```
```cpp
if ( 초기화문 ; 조건식 ) { ... }
```
```cpp
// ❌ 옛날 방식 — file이 if 블록 밖에서도 살아있음
auto file = openFile("data.txt");
if (file.is_open()) {
    // ...
}
// file이 여기서도 접근 가능 (의도치 않은 사용 위험)


// ✅ C++17 방식 — file의 스코프가 if 블록으로 제한됨
if (auto file = openFile("data.txt"); file.is_open()) {
    // ...
}
// file 여기서 접근 불가 → 컴파일 에러
```
```cpp
#include <fstream>
#include <string>
#include <print>

int main() {
    if (std::ifstream file("data.txt"); file.is_open()) {  // if-init (C++17)
        std::string content((std::istreambuf_iterator<char>(file)),
                             std::istreambuf_iterator<char>());
        std::print("내용: {}\n", content);
    }  // 블록 끝 → file 소멸 → 자동 close() (RAII)
}
```
## 4. 효율적인 반복 작업(자동화를 위한 도구)
### 비교
- 클래식 for 루프
- 모던 범위 기반 for(범위 기반 루프)
### 모던 범위 가반 for : C++11에서 추가된 문법입니다.
- 일반 for = 책의 "1페이지부터 100페이지까지 읽어라" / 범위 기반 for = "책 전체를 처음부터 끝까지 읽어라"
```cpp
for (자료형 변수 : 컨테이너) {
    // 변수 사용
}
```
```cpp
// ① 일반 배열
int arr[] = {10, 20, 30};
for (int n : arr) { ... }  // ✅

// ② vector
std::vector<int> v = {1, 2, 3};
for (int n : v) { ... }  // ✅

// ③ string (문자 하나씩)
std::string s = "hello";
for (char c : s) { ... }  // ✅

// ④ map
std::map<std::string, int> m = {{"Alice", 1}, {"Bob", 2}};
for (auto& [key, value] : m) { ... }  // ✅

// ⑤ 초기화 리스트
for (int n : {10, 20, 30}) { ... }  // ✅
```
- 복사 vs 참조
```cpp
for (int n : arr)        // 📋 복사 — 원본 변경 안 됨
for (int& n : arr)       // 🔗 참조 — 원본 변경 가능
for (const int& n : arr) // 🔒 읽기 전용 참조 — 가장 권장
```
### 범위 기반 for 문
- 컨테이너 순회 시 매우 편리하며 Moden C++ 에서 권장하는 방식입니다.
## 최종 정리(모던 C++ 툴킷)
1. 계산 : 정밀도를 위해서 static_cast를 올바르게 사용하세요.
2. 소통 : 깔끔하고 타입에 안전한 std::print를 선호하세요.
3. 결정 : 안전한 변수 범위를 위해 초기화 구문 있는 if를 사용하세요.
4. 반복 : 명확성과 안정성을 위해 범위 기반 for 루프를 기본으로 사용하세요.

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter8~9
```
1. C++의 위험한 과거
2. 안전한 배열: std::vector
3. 강력한 문자열: std::string
4. 스마트 함수 설계
5. 효율적인 매개변수 전달법
6. 이제, 모던 C++ 개발자로
```

## 1. C++의 위험한 과거(C 스타일 배열과 문자열의 함정)
```
int arr[5];
arr[5] = 100; // 없는 5번째 방에 100을 넣으려해서 문제.
// 컴파일 시점에 파악 안 된다. 실행 시 에러.
```
### 버퍼 오버플로우(Buffer Overflow)
- 할당된 메모리 공간(버퍼)을 넘어서는 데이터를 쓸 때 발생하는 현상. 프로그램 충돌, 데이터 손상, 심각한 보안 취약점의 원인이 됩니다.

## 2. 안전한 배열: std::vector(Modern C++ 가 제시하는 첫 번째 해답)
## 비교
1. C 스타일 배열
2. std:arrary
## std:vector 동작 확장
- Step 1: 요소 추가(push back)
- Step 2: 용량(capacity) 부족 감지
- Step 3: 더 큰 메모리 공간 확보
- Step 4: 기존 요소들 복사
- Step 5: 새 요소 추가

## 3. 강력한 문자열: std::string(복잡한 문자열 처리를 간결하게)
- 배열의 특수한 형태. std::string
### 비교
#### C 스타일 문자열의 문제점
#### std::string
- 더하기(+)로 연결
- 등호(==)로 비교
- find, substr 함수로 안전하게 검색과 추출 수행

## 4. 스마트 함수 설계(코드를 재사용 가능한 예술로 만들기)
### 비교
1. Pass by value : 비용 많이 발생. 사용 안 함.
2. Pass by reference : 참조(const & : 복사 없어 비용 없음)
- 읽기만 할 때는 const T&
- 수정이 필요할 때는 T&
### 오버로딩 : 같은 이름 함수 다른 타입 데이터 처리

```

```

## 5. 효율적인 매개변수 전달법(코드의 성능과 안전성을 높이는 핵심 기술)
### 매개변수 전달 황금률
- 읽기 전용, 큰 객체: const T&(복사 방지)
- 함수 내에서 수정 필요: T&(원본 수정)
- 작고 저렴한 타입(int, char): T(값 전달)
- nullptr 이 필요할 때(드물다): T*(포인터)
### 매개변수 전달 황금률 예제
```
for(const auto& item : my_vector)
  - auto : 타입 자동 인식 타입
```
#### nullptr 이 필요할 때(드물다): T*(포인터)
- 핵심 질문: "값이 없는 상태"를 표현할 수 있냐?
```
// T& (참조) — "없음"을 표현 불가
void func(int& n) { ... }
func(???);  // 반드시 실제 변수를 넘겨야 함, "없음" 불가

// T* (포인터) — "없음(nullptr)"을 표현 가능
void func(int* n) { ... }
func(nullptr);  // "값 없음"을 전달 가능 ✅
```
- 실제 사용 예시 : 포인터를 쓰면 항상 nullptr 체크를 해야 합니다. 안 하면 아까의 버퍼 오버플로우처럼 크래시 위험.
```
// 사용자를 찾는 함수
// 찾으면 User 반환, 못 찾으면 "없음" 반환해야 함
void findUser(int id, User* result) {

    if (id == 1) {
        result = &user;   // 찾았을 때 → 실제 값
    } else {
        result = nullptr; // 못 찾았을 때 → "없음"
    }
}

void printUser(User* user) {
    if (user == nullptr) {
        std::print("유저 없음\n");
        return;
    }
    std::print("{}\n", user->name);  // 안전하게 사용
}
```
- 왜 "드물다"고 했냐? : 모던 C++에서는 포인터 대신 더 안전한 대안이 있기 때문입니다:
```
상황          옛날 방식               모던 C++
없음을 표현    T* + nullptr 체크       std::optional<T>
동적 메모리    T* + deletestd::       unique_ptr<T>
```

#### std::optional<T>
- "값이 있을 수도, 없을 수도 있다"를 안전하게 표현하는 타입
##### 비교
- 일반 변수  = 반드시 뭔가 들어있는 택배 상자
- optional   = 비어있을 수도 있는 택배 상자
##### 포인터 vs optional 비교
```
// ❌ 포인터 방식 — nullptr 체크 실수하면 크래시
User* findUser(int id) {
    if (id == 1) return &user;
    return nullptr;  // 없으면 nullptr
}

User* u = findUser(999);
u->name;  // nullptr 체크 안 하면 💥 크래시


```

```
// ✅ optional 방식 — 안전
std::optional<User> findUser(int id) {
    if (id == 1) return user;
    return std::nullopt;  // 없으면 nullopt
}

std::optional<User> result = findUser(999);

// 값이 있는지 체크
if (result.has_value()) {
    std::print("{}\n", result->name);  // 안전하게 사용
} else {
    std::print("유저 없음\n");
}

// 더 간단하게
if (result) {           // has_value() 와 동일
    std::print("{}\n", result->name);
}
```

##### optional<T> - nullptr 체크 없이 바로 사용하면?
```
std::optional<User> result = findUser(999);  // 없는 유저 검색

result->name;        // 💥 크래시 (undefined behavior)
result.value().name; // 💥 예외(exception) 던짐
```
##### optional<T>- 두 가지 접근 방식의 차이
```
// ① -> 연산자 — 체크 없이 접근
result->name;
// 값이 없으면 → 💥 undefined behavior (예측 불가 크래시)


// ② .value() — 체크 없이 접근
result.value();
// 값이 없으면 → std::bad_optional_access 예외 던짐
//              그나마 원인을 알 수 있음
```
##### optional<T>- 안전하게 쓰는 방법 3가지
```
// ① has_value() 체크
if (result.has_value()) {
    result->name;
}

// ② if (result) — 더 간단
if (result) {
    result->name;
}

// ③ value_or() — 없으면 기본값 사용
std::string name = result->name.value_or("이름없음");
//                                        ↑
//                              없을 때 대신 쓸 값
```

#### std::unique_ptr<T> : "동적으로 할당한 메모리를 자동으로 해제해주는 스마트 포인터"입니다.
##### 먼저 문제 상황 (일반 포인터)
```
// ❌ 옛날 방식
User* user = new User();  // 힙에 메모리 할당
// ... 코드 ...
delete user;              // 직접 해제해야 함

// 만약 delete 깜빡하면?
// → 메모리 누수 (Memory Leak) 💥
// 프로그램이 메모리를 계속 먹다가 죽음
```
##### unique_ptr 이 해결
- RAII 그대로 적용됩니다:
  - 생성 → 메모리 할당
  - 소멸 → 메모리 자동 해제
```
// ✅ unique_ptr 방식
std::unique_ptr<User> user = std::make_unique<User>();
// ... 코드 ...
// delete 필요 없음! 스코프 벗어나면 자동 해제 ✅
```
##### "unique(유일한)" 의미
```
std::unique_ptr<User> a = std::make_unique<User>();

// 복사 불가 — 소유자는 단 하나!
std::unique_ptr<User> b = a;  // ❌ 컴파일 에러

// 이전(move)은 가능 — 소유권 넘김
std::unique_ptr<User> b = std::move(a);
// 이후 a는 nullptr, b가 새 소유자
```
##### 비유
- 일반 포인터  = 열쇠 복사 가능 🔑🔑🔑 (누가 문 잠그지?)
- unique_ptr  = 열쇠가 단 하나 🔑 (이 사람이 나가면 자동으로 문 잠김)
##### 한 줄 요약
- unique_ptr = 메모리를 혼자 독점 소유하고, 스코프 벗어나면 자동 해제 — delete 직접 쓸 필요 없음

#### std::movestd::move : "소유권을 이전한다" — 복사 없이 통째로 넘기는 것입니다.
##### 비유
- 복사 (copy)  = 문서를 복사기로 찍어서 전달 📄📄
- 이동 (move)  = 원본 문서를 그냥 건네줌 📄 (내 손엔 없음)

##### 코드로 보면
```
std::unique_ptr<User> a = std::make_unique<User>();
// a가 User 소유 중

std::unique_ptr<User> b = std::move(a);
// a → b 로 소유권 이전

// 이후
a;  // nullptr (텅 빔) 📭
b;  // User 소유 중 ✅
```
##### unique_ptr 말고 string 에서도
```
std::string a = "Hello World";

std::string b = a;             // 복사 — a도 b도 "Hello World"
std::string c = std::move(a);  // 이동 — c는 "Hello World", a는 텅 빔

std::print("{}\n", a);  // "" (비어있음)
std::print("{}\n", c);  // "Hello World"
```
##### 왜 쓰냐?
```
복사  = 데이터를 두 벌 만듦 → 메모리, 시간 소모
이동  = 그냥 주소만 넘김   → 거의 공짜 ⚡
큰 데이터를 함수에 넘길 때 복사 비용을 없애기 위해 씁니다.
```
##### 한 줄 요약
```
std::move = 복사 없이 소유권만 통째로 넘김 — 원본은 텅 비고, 속도는 빠름 ⚡
```

## 6. 이제, 모던 C++ 개발자로(과거와 작별하고 미래를 코딩하세요)
### 핵심 요약(The Takeaway)
- C 스타일 배열/문자열 대신 {std::vector, std:: string} 을 사용하세요.
- 함수에 객체를 전달할 땐 {const 참조}가 기본입니다.
- {범위 기반 for문}을 적극적으로 활용하여 순회를 안전하게 만드세요.
- {전역 변수}를 피하고, 함수의 역할과 범위를 명확히 하세요.
#### 전역변수 대신 쓰는 스마트한 방법들
##### 먼저 전역변수가 왜 나쁜가?
```
// ❌ 전역변수
int userCount = 0;  // 어디서든 접근, 수정 가능

void addUser() { userCount++; }
void removeUser() { userCount--; }
// 누가 언제 바꿨는지 추적 불가 → 버그 찾기 지옥
```

##### 해결책 1: 함수에 매개변수로 전달
```
// ✅ 필요한 곳에만 전달
void addUser(int& count) {
    count++;
}

int main() {
    int userCount = 0;  // 지역변수로
    addUser(userCount);
}
```

##### 해결책 2: 클래스로 묶기 (가장 권장)
```
// ✅ 관련 데이터와 함수를 클래스로 묶음
class UserManager {
private:
    int count = 0;  // 외부에서 직접 접근 불가

public:
    void add()    { count++; }
    void remove() { count--; }
    int getCount() const { return count; }
};

int main() {
    UserManager manager;
    manager.add();
    std::print("{}\n", manager.getCount());  // 1
}
```

##### 해결책 3: 함수 내 static (딱 하나만 필요할 때)
```
// ❌ 전역변수로 결과 공유
int result = 0;
void calculate() { result = 42; }

// ✅ 반환값으로 명확하게
int calculate() { return 42; }

int main() {
    int result = calculate();  // 어디서 왔는지 명확
}
```

##### 해결책 4: 함수 반환값으로 주고받기
```
// ❌ 전역변수로 결과 공유
int result = 0;
void calculate() { result = 42; }

// ✅ 반환값으로 명확하게
int calculate() { return 42; }

int main() {
    int result = calculate();  // 어디서 왔는지 명확
}
```

##### 정리
```
상황                        해결책
여러 함수가 같은 데이터 공유      클래스로 묶기
함수 간 데이터 전달             매개변수 + 반환값
딱 하나만 유지되는 값            함수 내 static설정값 등 
진짜 전역이 필요               const 전역상수만 허용
```

#### 여러 함수가 같은 데이터 공유-클래스로 묶기 해결책
##### 상황
```
UserManager m1;
UserManager m2;

m1.add();
// m2는 m1의 count를 모름 → 공유 안 됨 ❌
```


##### 해결책-① 인스턴스를 하나만 만들어서 전달 (가장 권장)
```
UserManager manager;  // 딱 하나만 생성

void printCount(const UserManager& m) { ... }  // 참조로 전달
void addUser(UserManager& m) { m.add(); }      // 참조로 전달

// 항상 같은 manager 를 넘기니까 공유됨 ✅
```

##### 해결책-② 싱글톤 패턴 — 인스턴스가 전역에 딱 하나
```
class UserManager {
private:
    int count = 0;
    UserManager() {}  // 외부에서 생성 불가

public:
    static UserManager& getInstance() {
        static UserManager instance;  // 딱 하나만 존재
        return instance;
    }
    void add() { count++; }
    int getCount() const { return count; }
};

// 사용
UserManager::getInstance().add();
UserManager::getInstance().getCount();  // 어디서든 같은 인스턴스
```

##### 해결책-③ 공유 포인터 shared_ptr
```
// 여러 곳에서 같은 객체를 가리킴
auto manager = std::make_shared<UserManager>();

auto a = manager;  // 복사가 아니라 같은 객체 공유
auto b = manager;

a->add();
std::print("{}\n", b->getCount());  // 1 ✅ 같은 객체
```

##### 정리
```
방법                    언제
인스턴스 하나 + 참조 전달   대부분의 경우 ✅
싱글톤                   앱 전체에서 딱 하나여야 할 때
shared_ptr              여러 곳에서 동시에 소유해야 할 때


```
# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter10 - 람다 표현식과 함수 객체(Functor)
##### 
```
```

##### 목차 
```
섹션1: 람다란 무엇인가?
섹션2: 캡처의 힘
섹션3: 람다 저장하기
섹션4: 실제 사용 사례
섹션5: 람다 vs. 함수 객체
```

##### 섹션1: 람다란 무엇인가?(C++ 익명 함수의 기초)
###### 람다 표현식
- 필요한 위치에서 즉성으로 정의하는 이름 없는 함수. 코드의 가독성과 응집도를 향상시킵니다.
###### 람다 해부학
1. [] 캡처 리스트 : 외부 변수에 접근하는 방법을 정의합니다.
2. () 매개변수 : 람다 함수가 받는 인자입니다.
3. -> 반환타입 : 반환 타입을 명시하며, 생략 가능합니다.
4. {} 본문 : 람다가 실행할 코드입니다.

##### 섹션2: 캡처의 힘(주변환경과 상호작용) : "람다가 바깥 세계에서 뭘, 어떻게 가져올지" 를 정의하는 것
###### 방법
1. Capture by Value
- 값에 의한 캡처 [=] : 외부 변수를 복사합니다 원본에 영향을 주지 않아 안전합니다. 비동기 콜백 함수.
2. Capture by Reference [&]
- 참조에 의한 캡처 [&] : 외부 변술ㄹ 참조합니다. 내부 변경이 원본을 수정합니다.
3. More Capture Options
- [] : 아무것도 안 가져옴
- [x, &y] : 특정 변수 지정 : x는 값, y는 참조
- [this] : 클래스 멤버 접근 
- [newVar = x*2] : 새 변수 생성해서 캡처
##### ① [] — 아무것도 캡처 안 함 ✅ 맞습니다
```
int x = 10;
auto f = []() {
    // x 사용 불가 ❌ 컴파일 에러
};
```

##### ② 특정 변수 지정 ✅ 맞습니다
```
int x = 10;
int y = 20;

// x는 값으로, y는 참조로 — 각각 따로 지정
auto f = [x, &y]() {
    x;   // 복사본 (원본 영향 없음)
    y;   // 원본 직접 접근
};
```

##### ③ this 캡처 ✅ 맞습니다
```
class UserManager {
    int count = 0;

    void doSomething() {
        // 클래스 멤버에 접근하려면 this 캡처 필요
        auto f = [this]() {
            count++;  // this->count++ 와 동일
        };
        f();
    }
};
```

##### ④ 새로운 변수 (초기화 캡처) ✅ 맞습니다 — C++14 추가
```
int x = 10;

// 람다 안에서만 쓸 새 변수를 캡처 시점에 생성
auto f = [newVar = x * 2]() {
    std::print("{}\n", newVar);  // 20
};

// std::move 와 함께 — unique_ptr 같은 이동 전용 타입
auto ptr = std::make_unique<int>(42);
auto f2 = [p = std::move(ptr)]() {
    std::print("{}\n", *p);  // 42
};
```

##### 
```
```

##### 섹션3: 람다 저장하기(auto vs. std::function)
```
```
###### 비교
- 성능을 위해서는 auto, 유연성을 위해서는 std::function 사용.
```
카테고리      auto              std::function
성능        높음(오버헤드 없음)     낮을 수 있음(타입 소거)
유연성       낮음(고유 타입)       높은(모든 호출 가능 객체)
주요 용도     성능 우선, 지역 변수   유연선 우선, 함수 매개변수
```

###### std::function
1. auto — 성능 우선
```
// 컴파일러가 람다 타입을 정확히 알고 있음
// → 인라인 최적화 가능 ⚡
auto add = [](int a, int b) {
    return a + b;
};

add(1, 2);  // 컴파일러가 직접 코드 삽입 → 함수 호출 오버헤드 없음
```

2. std::function — 유연성 우선 : 레시피 책 📖 — 어떤 레시피든 꽂을 수 있지만, 꺼내 보는 비용이 있음
```
// 타입이 고정됨 → 람다, 일반함수, 멤버함수 뭐든 담을 수 있음
std::function<int(int, int)> operation;

// 상황에 따라 다른 함수를 담을 수 있음
if (userChoice == "add") {
    operation = [](int a, int b) { return a + b; };
} else {
    operation = [](int a, int b) { return a * b; };
}

operation(3, 4);  // 7 또는 12
```
3. 유연성 예시 — 콜백 함수
```
// ✅ std::function 이 빛나는 순간
// 어떤 함수든 받을 수 있는 매개변수
void processUsers(
    const std::vector<User>& users,
    std::function<void(const User&)> callback  // 뭐든 받음
) {
    for (const auto& user : users) {
        callback(user);
    }
}

// 람다도 되고
processUsers(users, [](const User& u) {
    std::print("{}\n", u.name);
});

// 일반 함수도 되고
void printUser(const User& u) { std::print("{}\n", u.name); }
processUsers(users, printUser);
```

4. 뭐든 담기는 예시
```
std::function<int(int, int)> f;

// ① 람다
f = [](int a, int b) { return a + b; };

// ② 일반 함수
int multiply(int a, int b) { return a * b; }
f = multiply;

// ③ 멤버 함수
class Calculator {
public:
    int subtract(int a, int b) { return a - b; }
};
Calculator calc;
f = [&calc](int a, int b) { return calc.subtract(a, b); }; // reference
Calculator calc;
f2 = [calc](int a, int b) { return calc.subtract(a, b); }; // value

```

5. 성능 차이가 나는 이유
```
auto          → 타입이 컴파일 타임에 확정 → 최적화 가능
std::function → 런타임에 "어떤 함수인지" 확인 → 오버헤드 발생
                (내부적으로 힙 할당, 가상 함수 호출 등)
```


##### 섹션4: 실제 사용 사례(콜백과 이벤트 처리)
###### 설명
- 람다는 {이상적인 콜백}을 만듭니다.
- 다른 함수에 전달되어 나중에 호출되는 {간결한} 인라인 코드 조각이죠.
###### 주요 콜백 활용 사례
- STL 알고리즘: 정렬, 검색 등이 기준 을 즉석에서 제공
- 이벤트 핸들러: UI 버튼 클릭, 네트워크 응답 등 처리
- 비동기 작업: 작업 완료 후 실행될 로직 정의
- 조건부 콜백: 특정 조건 만족 시에만 호출
```
```

##### 섹션5: 람다 vs. 함수 객체(람다의 기원 이해하기)
```
```
###### 함수 객체(Functor)
- 함수 호출 연산자 'operator()'를 오버로딩한 클래스의 객체. 내부 상태를 가질 수 있습니다.
- 람다는 컴파일러가 자동으로 생성하는 함수 객체를 위한 {'문법적 설탕'} 입니다. 복잡한 클래스 정의를 {대신}해주는 것이죠.
###### 비교
```
상황          람다 표현식          함수 객체(Functor)
로직 복잡도    간단한 로직          복잡한 로직
재사용성      한 곳에서만 사용       여러 곳에서 재사용
상태 관리     최소한의 상태         많은 상태 관리
가독성       짧고 지역적일 때       명확한 이름 필요 시
```
###### 정리
- 람다는
  - 간결하고, 읽기 쉬이며,
  - 고도로 지역화된 코드를 작성기 위한
  - 모던 C++의 강력한 도구
- 함수 객체 = 클래스로 직접 만든 것 / 람다 = 컴파일러가 대신 만들어주는 간편 버전(문법적 설탕)
###### 예시-람다 vs 함수 객체 — 같은 동작
```
// ✅ 람다 (짧고 간결)
auto add = [](int a, int b) { return a + b; };
add(3, 4);  // 7


// ✅ 함수 객체 (람다가 내부적으로 이렇게 변환됨)
class Add {
public:
    int operator()(int a, int b) {  // () 연산자 오버로딩
        return a + b;
    }
};

Add add;
add(3, 4);  // 7 — 함수처럼 호출!
```

###### 예시-상태를 가지는 함수 객체
```
// 호출 횟수를 기억하는 함수 객체
class Counter {
private:
    int count = 0;  // 내부 상태

public:
    int operator()(int a, int b) {
        count++;  // 호출될 때마다 증가
        std::print("{}번째 호출\n", count);
        return a + b;
    }
    int getCount() const { return count; }
};

Counter counter;
counter(1, 2);  // 1번째 호출
counter(3, 4);  // 2번째 호출
counter(5, 6);  // 3번째 호출
std::print("총 {}번 호출됨\n", counter.getCount());  // 총 3번
```

###### 문법적 설탕 (Syntax Sugar) 
- 동일한 동작을 더 달콤하게(간결하게) 표현하는 문법

###### 문법적 설탕 (Syntax Sugar)-람다가 문법적 설탕인 이유
```
// 🔧 컴파일러가 실제로 만드는 것 (함수 객체)
class __Lambda_1 {
public:
    int operator()(int a, int b) const {
        return a + b;
    }
};
__Lambda_1 add;


// 🍬 개발자가 쓰는 것 (문법적 설탕 = 람다)
auto add = [](int a, int b) { return a + b; };
```

###### 문법적 설탕 (Syntax Sugar)-C++ 의 다른 문법적 설탕 예시
```
// 범위 기반 for 도 문법적 설탕
for (int n : arr) { ... }
// 사실은 이것
for (auto it = arr.begin(); it != arr.end(); ++it) { ... }
```

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter11 - 스마트 포인터와 메모리 관리
##### 
```
```
##### 목차
```
1. Raw 포인터의 위험성(우리를 기다리는 함정들)
2. 해결책: RAII 패턴(근본적인 생각의 전환)
3. 스마트 포인터 툴킷(RAII 패턴의 완벽한 구현)차
4. 올바른 포인터 선택하기(간단한 의사결정 가이드)
```

##### C++ 코드의 가장 큰 적은 무엇일까?
- 메모리

##### 1. Raw 포인터의 위험성(우리를 기다리는 함정들)
###### Memory Leaks
- 메모리 누수는 할당된 메모리를 해제하지 않아 발생하는 가장 흔한 문제.
```
```
###### 댕글리 포인터(Dangling Pointer)
- 이미 해제된 메모리를 가리키는 포인터.
- 예측 불가능한 버그. 프로그램 크러시.

###### 이중 해제(Double Delete)
- 같은 메로리를 두 번 해제하는 치명적인 실수. 프로그램이 충돌 할 수 있다.

###### Unclear Ownership(소유권 불명확)
- 여러 포인터가 같은 메모리를 가리킬 때 누가 해제해야 하는지 불명확한 문제
- Unclear Ownership = "이 메모리 누가 책임져?" 가 애매한 상태 → 버그와 메모리 누수의 근원
1. 단어 분해 
```
Unclear: 불명확한, 애매한
Ownership: 소유권, 누가 주인인가
```
2. C++ 에서의 의미: 이 메모리(객체)를 누가 책임지고 해제할 것인가가 불명확한 상태
```
// ❌ Unclear Ownership 예시
User* user = new User();

void funcA(User* u) { ... }  // 내가 delete 해야 하나?
void funcB(User* u) { ... }  // 내가 delete 해야 하나?

// 누가 delete 할지 불명확
// → 아무도 안 하면 메모리 누수
// → 둘 다 하면 이중 해제 💥
```
3. 해결책 — 소유권을 명확히
```
// ✅ unique_ptr — 소유자가 딱 하나
std::unique_ptr<User> user = std::make_unique<User>();
// "내가 유일한 주인, 내가 사라질 때 해제"

// ✅ shared_ptr — 공동 소유, 마지막이 해제
std::shared_ptr<User> user = std::make_shared<User>();
// "여럿이 공동 소유, 마지막 소유자가 해제"
```

##### 2. 해결책: RAII 패턴(근본적인 생각의 전환)
```
```
###### RAII= Resource Acquisition(애퀴지션:획득) Is Initialization => 자원 획득이 곧 초기화다
- 리소스의 획득과 해제를 {객체의 생성과 소멸}에 묶는 C++ 의 {핵심 패턴} 입니다.
- 장점
  - 리소스 획득은 생성자에서
  - 리소스 해제는 소멸자에서
  - 예외가 발생해도 안전하게 해제
  - 수동 delete 호출 불필요

##### 3. 스마트 포인터 툴킷(RAII 패턴의 완벽한 구현)
```
```
###### 설명
- {가장 기본적}이고 효율적인 스마트 포인터
- {한 번에 하나}의 포인터만 이 객체를 소유하며, => unique(복제 불가능한 마스터키) 
- 스코프를 벗어나면 자동으로 메모리를 해제합니다.

###### shared_ptr 
- {여러 곳}에서 동일한 객체를 참조해야 할 때 사용.
- {참조 횟수}를 세어 마지막 참조가 사라질 때 객체를 해제합니다.

###### 비교
```
기능      std::unique_ptr     std::shared_ptr
소유권     단독 소유             공유 소유
성능      가볍고 빠름            참조 카운팅 오버헤드
사용      기본적으로 사용          공유가 필요할 때 사용
```

###### shared_ptr 주의사항: 순환 참조(Circular Reference)
1. 순환 참조(Circular Reference)
- shared_ptr <-> shared_ptr
- 서로를 잡고 있어서 절대로 참조 카운트가 0이 될 수 없다.
2. 해결책(Solution): weak_ptr : 약한 참조이여서 참조 카운트 증가시키지 않고 지켜보기만 한다. 
- shared_ptr <-> weak_ptr

##### 4. 올바른 포인터 선택하기(간단한 의사결정 가이드)
```
```
###### 스마트 포인터 선택 가이드
1. 기본: unique_ptr: 가볍고 빠르며 명확한 소유권을 위해 기본으로 사용하세요.
2. 공유: shared_ptr: 공유 필요.
3. 관찰: weak_ptr: shared_ptr의 순환 참조를 방지하기 위해서 사용하세요.
4. 금지: raw pointer: 관찰자 역할처럼 제한적으로만 사용.

##### 정리
```
```
- 컴파일러가 당신을 위해 메모리를 관리하게 하세요.
- 스마트 포인터와 RAII 패턴은 메모리 문제를 자동으로 방지합니다.

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter12-컨테이너와 알고리즘
##### 
```
```
##### 목차
```
1. 데이터 처리, 과거의 문제점(C 스타일 배열의 한계)
2. STL 컨테이너(안정하고 효율적인 데이터 금고)
3. 반복자(Iterator)(컨테이너와 알고리즘의 연결고리)
4. C++20 Ranges(코드의 혁명)
5. 컨테이너 선택 가이드(어떤 도구를 선택할 것인가?)
```

##### 1. 데이터 처리, 과거의 문제점(C 스타일 배열의 한계)
```
```
###### 비교
1. C 스타일 배열
2. STL 컨테이너

##### 2. STL 컨테이너(안정하고 효율적인 데이터 금고)
```
```
###### 종류
```
분류    설명                              주요 컨테이너
시퀀스   선형 순서로 저장                     vector, array, list
연관    키 기반으로 정렬                     map(키만 저장, 정렬 O, 이 값이 존재하냐?), set(키-값 쌍 저장, 정렬 O, 이 키에 해당하는 값이 뭐야?)
비정렬   해시 테이블 기반                     unordered_map(키만 저장, 정렬 X, 원하는 값 빠른 조회), unordered_set(키-값 쌍 저장, 정렬 X, 원하는 값 빠른 조회)
어댑터   특정 인터페이스 제공(특정 목적에 맞게)    stack, queue
```

###### std:vector
- {가장 많이 사용되는} 컨테이너로, 크기를 자동으로 조절하는 {동적 배열} 입니다.
- 업그레이드 된 동적 배열

##### 3. 반복자(Iterator)(컨테이너와 알고리즘의 연결고리) 
```
```
###### 반복자(Iterator)
- 컨테이너의 스마트 포인터
- 컨네이너의 원소를 순회하는 일반화된 방법을 제공합니다. 포인터보다 더 안전하고 추상화되고 있습니다.
- 반복자는 모든 컨테이너를 모든 일반 알고리즘과 연결하는 보편적인 언어입니다.

##### 4. C++20 Ranges(코드의 혁명)
```
```
###### 비교
1. How To do it : 어떻게 하는지 => 단계별로 지시.
2. Wath I want : 내가 원하는 것 => 내가 원하는 것은 이런 조건을 만족하는 것.

###### Ranges 핵심 개념 - 뷰(View)
- 범위를 '지연 평가'로 변형하고 필터링하는 가볍고, 소유권이 없는(non-owning) 어댑터입니다.
- 여러 필터를 겹쳐보고 최종적으로 결정되면 적용하는 지연 평가.
- 문제 : 1~100 중 {3의 배수}이면서 {5의 배수가 아닌} 수들의 제곱의 합은?
  - 데이터 흐름을 {파이프라인}으로 연결하여 {선언적으로} 표현합니다.
  - Ranges 파이프라인
  1. 숫자 생성 : 1부터 100가지의 숫자를 만듭니다.
  2. 3의 배수 필터 : 3의 배수만 남깁니다.
  3. 5의 배수 제외 : 5의 배수는 다시 제외합니다.
  4. 제곱으로 변환 : 남은 숫자들을 제곱합니다.
  5. 결과 합산 : 모든 제곱 값을 더합니다.

###### std::ranges 의 핵심 목표
1. 개념(Concepts): 컴파일 타임에 오류를 진단하여 안전성 향상
2. 뷰(View): 데이터 흐름을 선언적으로 조합하여 가독서 증대
3. 알고리즘: 범위 전체에 자연스럽게 적용하여 사용 편의성 증대
4. 투영(Projection): 불필요한 코드를 줄여 로직을 간결하게 작성

```
struct Student {
std::string name;
int score;
std::string grade;  // "A", "B", "C"
};

std::vector<Student> students = {
{"Alice",   95, "A"},
{"Bob",     72, "B"},
{"Charlie", 88, "A"},
{"Dave",    60, "C"},
{"Eve",     91, "A"}
};

// 1단계: 먼저 정렬
// 3) 알고리즘 — students 통째로 (begin/end 없음)
// 1) 개념(Concepts) — std::greater 는 "비교 가능한 타입" 조건 검사
// 4) 투영 — score 면만 골라서 비교
std::ranges::sort(students, std::greater{}, &Student::score);
     ↑                ↑           ↑              ↑
  알고리즘          범위전체     Concepts        투영
 (ranges)        (통째로)    (타입검사)      (score만)
// students = {Alice(95), Eve(91), Charlie(88), Bob(72), Dave(60)}

// 2단계: 정렬된 students 에 filter + transform
// 2) 뷰(View) — 선언적 조합 → 가독성 : 선언적 — 뭘 원하는지만 말함, "어떻게 처리할지" 말고 "무엇을 원하는지"만 말하는 것
auto result = students // ← 복사 아님! // result 는 students 의 복사본이 아니라 뷰(창문) — 이게 뷰의 핵심
| std::views::filter([](const Student& s) {
return s.grade == "A";   // A학점만 // ← 바라보는 창문
})
| std::views::transform([](const Student& s) {
return s.name;           // 이름만 // ← 데이터는 students 에 그대로
});

for (const auto& name : result) {
std::print("{}\n", name);
}
// Alice   (95)
// Eve     (91)
// Charlie (88)
```

##### 5. 컨테이너 선택 가이드(어떤 도구를 선택할 것인가?)
```
```
###### 비교
```
사용 사례                 추천 컨테이너
동적 크기, 순차 접근          std::vector
고정 크기, 스택 기반 배열      std::array
키-값 쌍, 정렬된 검색         std::map
키-값 쌍, 가장 빠른 검색       std::unordered_map
```
###### std::vector vs std::array
1. 핵심 차이 한 줄
```
vector = 고무줄 📏 (크기 늘었다 줄었다)
array  = 자 📐    (크기 고정)
```
2. std::vector — 동적 크기
```
std::vector<int> v;

v.push_back(10);  // 크기 1
v.push_back(20);  // 크기 2
v.push_back(30);  // 크기 3
// 얼마든지 추가 가능 ✅

v.pop_back();     // 크기 2
// 줄이기도 가능 ✅
```
3. std::array — 고정 크기
```
std::array<int, 3> arr = {10, 20, 30};
//                  ↑
//            크기를 미리 고정 (컴파일 타임에 결정)

arr.push_back(40);  // ❌ 불가능 — 크기 고정
```
4. 비유
```
vector = 장바구니 🛍️
         물건 넣고 빼고 자유자재
         크기 신경 안 써도 됨

array  = 달걀 판 🥚
         칸이 6개면 6개 고정
         더 넣을 수 없음
         대신 가볍고 빠름
```
5. 속도 차이
```
array  → 스택(stack) 에 저장 → 매우 빠름 ⚡
vector → 힙(heap) 에 저장   → 상대적으로 느림

std::array<int, 5> arr;   // 스택 — 함수 끝나면 자동 해제
std::vector<int> v;       // 힙  — RAII 로 자동 해제
```
6. 실제 사용 예시
```
// ✅ array — 크기가 정해진 것
std::array<int, 7> daysOfWeek = {1, 2, 3, 4, 5, 6, 7};
std::array<double, 3> rgb = {255.0, 128.0, 0.0};

// ✅ vector — 크기가 변하는 것
std::vector<std::string> searchHistory;  // 검색 기록 (얼마나 쌓일지 모름)
searchHistory.push_back("C++ 란?");
searchHistory.push_back("std::vector");
searchHistory.push_back("std::array");
```
7. JSON ↔ std::map 구조가 똑같습니다
```
// JSON
{
    "name": "Alice",
    "score": 95,
    "grade": "A"
}

// std::map
std::map<std::string, std::string> data;
data["name"]  = "Alice";
data["score"] = "95";
data["grade"] = "A";
```
- JSON 파싱 라이브러리도 map 계열 사용
```
// nlohmann/json (C++ 대표 JSON 라이브러리)
#include <nlohmann/json.hpp>

nlohmann::json j = {
    {"name",  "Alice"},
    {"score", 95},
    {"grade", "A"}
};

// map 처럼 접근
std::string name = j["name"];   // "Alice"
int score = j["score"];         // 95
```
- map vs unordered_map 선택
```
상황                    선택
JSON 키 순서 유지 필요     std::map
빠른 검색만 필요           std::unordered_map
JSON 라이브러리 사용       라이브러리가 알아서 처리
```

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter13~15 - 모던 C++ OOP 심층 분석
##### 
```
```
##### 목차
```
1. 설계도: 클래스와 객체
2. 객체의 생애
3. 모던 C++이 소유권 규칙
4. 상속을 통한 코드 재사용
5. 다형성의 힘
```

###### 1. 설계도: 클래스와 객체(우리 코드의 구조를 정의한다)
```
```
1. 캡슐화

###### 2. 객체의 생애(생성부터 소멸까지)
```
```
1. 생성자
2. 소멸자
3. 생성/소멸 호출 순서
- 생성1: 기본 클래스 생성자 호출
- 생성2: 파생 클래스 생성자 호출
- 객체 사용: 객체가 자신의 역할을 수행
- 소멸1: 파생 클래스 소멸자 호출
- 소멸2: 기본 클래스 소멸자 호출(생성 역순)

###### 3. 모던 C++이 소유권 규칙(안전하고 효율적인 메모리 관리)
```
```
1. Shallow Copy vs. Deep Copy 
- Shallow Copy
  - 포인터 주소만 복사. 이중 해제 오류 유발
- Deep Copy
  - 데이터 자체 복사. 각 객체가 독립적.
2. 과거 방식: Rule of Five(해결을 위해 만들어야 하는 5개 함수)
- 소멸자: 메모리 해제
- 복사 생성자: Deep Copy 로 새 객체 생성
- 복사 대입 연산자: Deep Copy 로 기존 객체에 대입
- 이동 생성자: 소유권 이전으로 새 객체 생성
- 이동 대입 연산자: 소유권 이전으로 기존 객체에 대입
```
class MyData {
    int* data;
    int size;

public:
    // 1️⃣ 소멸자 (Destructor)
    ~MyData() {
        delete[] data;  // 메모리 해제
    }

    // 2️⃣ 복사 생성자 (Copy Constructor)
    MyData(const MyData& other) {
        size = other.size;
        data = new int[size];          // 새 메모리 할당
        memcpy(data, other.data, size); // 데이터 복사 (Deep Copy)
    }

    // 3️⃣ 복사 대입 연산자 (Copy Assignment Operator)
    MyData& operator=(const MyData& other) {
        if (this == &other) return *this; // 자기 자신 체크
        delete[] data;                    // 기존 메모리 해제
        size = other.size;
        data = new int[size];
        memcpy(data, other.data, size);
        return *this;
    }

    // 4️⃣ 이동 생성자 (Move Constructor)
    MyData(MyData&& other) {
        data = other.data;   // 주소만 가져옴
        size = other.size;
        other.data = nullptr; // 원본은 비움
        other.size = 0;
    }

    // 5️⃣ 이동 대입 연산자 (Move Assignment Operator)
    MyData& operator=(MyData&& other) {
        if (this == &other) return *this;
        delete[] data;        // 기존 메모리 해제
        data = other.data;    // 주소만 가져옴
        size = other.size;
        other.data = nullptr; // 원본은 비움
        other.size = 0;
        return *this;
    }
};
```
3.MyData& 와 MyData&& 차이점
```
```
- 한 줄 차이
```
MyData&  = 왼값 참조 (lvalue reference) — 이름 있는 변수
MyData&& = 오른값 참조 (rvalue reference) — 임시 객체, 곧 사라질 값
```

- 이름으로 구분
```
MyData a;           // 이름 있음 → lvalue
MyData();           // 이름 없음 → rvalue (임시 객체)
std::move(a);       // 강제로 rvalue 취급
```

- 코드로 보면
```
MyData a;
MyData b;

// MyData& — 이름 있는 변수 받음
b = a;              // a 는 이름 있는 변수 → 복사 대입 연산자 호출
                    // a 는 그대로 살아있음

// MyData&& — 임시/이동 받음
b = std::move(a);   // move → 이동 대입 연산자 호출
                    // a 는 텅 빔
b = MyData();       // 임시 객체 → 이동 대입 연산자 호출
```

- 함수 매칭
```
void func(MyData& x)  { ... }  // lvalue 만 받음
void func(MyData&& x) { ... }  // rvalue 만 받음

MyData a;
func(a);             // → MyData&  호출 (이름 있으니까)
func(std::move(a));  // → MyData&& 호출 (이동이니까)
func(MyData());      // → MyData&& 호출 (임시 객체니까)
```

- 비유
```
MyData&  = 진짜 주민 🏠 (이름 있고, 오래 삶)
           → 복사해서 사용

MyData&& = 하룻밤 손님 🧳 (곧 떠남)
           → 짐을 그냥 넘겨받음 (이동)
```

- 한 줄 요약
```
        MyData&         MyData&&
대상    이름 있는 변수      임시 객체, std::move
동작    복사              이동 (소유권 이전)
원본    그대로             텅 빔
```

- 인스턴스 생성
```
MyData a;      // ① 이름 있는 인스턴스 — 스택
MyData();      // ② 이름 없는 인스턴스 — 스택 (임시)
new MyData();  // ③ 이름 없는 인스턴스 — 힙
```

- 차이는 "이름"과 "위치"
```
MyData a;     → 이름 있음, 스택, 오래 삶
MyData();     → 이름 없음, 스택, 즉시 사라짐
new MyData(); → 이름 없음, 힙,   delete 전까지 삶
```

- 생성자는 똑같이 호출됨
```
class MyData {
public:
    MyData() {
        std::print("MyData 생성!\n");  // 셋 다 이게 출력됨
    }
};

MyData a;      // "MyData 생성!" 출력
MyData();      // "MyData 생성!" 출력
new MyData();  // "MyData 생성!" 출력
```

4. 해결방안
- 직접 리소스를 관리하지 말고, RAII 래퍼를 사용하라.
- Rule of Zero
  - 스마트 포인터나 표준 컨테이너를 사용하여 자원 관리를 위임하면, 특별 멤버 함수를 직접 구현할 필요가 없다는 규칙.

###### 4. 상속을 통한 코드 재사용('is-a' 관계로 기능 확장)
```
```
1. 설명
- 설계에 집중하자.
- is-a : 상속. 개는 동물이다.
2. 가상 소멸자(virtual destructor)
- 상속될 기본 클래스의 소멸자에 'virtual'을 붙여, 파생 클래스 객체가 올바르게 소멸되도록 보장하고 메모리 누수를 방지합니다.
- 'virtual' 없으면 부모 클래스 포인터로 자식 클래스 객체를 지울 때 자식 클래스 소멸자 호출 안 된다.
- ❌ virtual 없을 때 — 메모리 누수
```
class Animal {
public:
    ~Animal() {  // virtual 없음
        std::print("Animal 소멸\n");
    }
};

class Dog : public Animal {
    int* data = new int[100];  // 힙 메모리
public:
    ~Dog() {
        delete[] data;  // 해제해야 함
        std::print("Dog 소멸\n");
    }
};

Animal* a = new Dog();
delete a;
// 출력: "Animal 소멸" 만 출력
// Dog 소멸자 호출 안 됨 → data 메모리 누수! 💥
```
- ✅ virtual 있을 때 — 정상
```
class Animal {
public:
    virtual ~Animal() {  // virtual 추가
        std::print("Animal 소멸\n");
    }
};

class Dog : public Animal {
    int* data = new int[100];
public:
    ~Dog() {
        delete[] data;
        std::print("Dog 소멸\n");
    }
};

Animal* a = new Dog();
delete a;
// 출력: "Dog 소멸" → "Animal 소멸" 순서로 둘 다 호출 ✅
```

###### 5. 다형성의 힘(가상 함수로 유연한 코드 만들기)
```
```
1. 기존 문제점 - Slient Faileure: No 'override' Keyword : Compiler confused, bug slips through!
- Silent Failure 란?
```
  Silent  = 조용한
  Failure = 실패

"컴파일러가 에러도 안 내고 조용히 실패하는 것"
```

- 문제 상황
```
class Animal {
public:
    virtual void speak() {      // 부모 함수
        std::print("...\n");
    }
};

class Dog : public Animal {
public:
    void speak() {              // 오버라이드 의도
        std::print("멍멍\n");
    }
};

>> 여기서 개발자가 실수로 함수 이름을 틀리면?

class Dog : public Animal {
public:
    void Speak() {   // ← 대문자 S — 오타!
        std::print("멍멍\n");
    }
};


>> 어떤 일이 벌어지냐

Animal* a = new Dog();
a->speak();  // "멍멍" 이 출력되길 기대했지만
             // "..." 출력 — 부모 함수 호출됨 😱

// 컴파일 에러? ❌ 없음!
// 경고?      ❌ 없음!
// 그냥 조용히 잘못 동작 = Silent Failure

>> 이유
Dog::Speak() 는 Animal::speak() 를 오버라이드한 게 아니라
완전히 새로운 함수를 만든 것
컴파일러는 "아 새 함수 만들었구나" 하고 넘어감
```

2. 해결방법
- override 키워드는 함수가 기본 클래스의 가상 함수를 {오버라이드} 하고 있음을 명시합니다.
- 실수를 {컴파일 시점}에 잡아낼 수 있습니다.
- override 키워드로 해결
```
class Dog : public Animal {
public:
    void Speak() override {  // ← override 추가
        std::print("멍멍\n");
    }
};
// ✅ 컴파일 에러 발생!
// "Animal 에 Speak() 가 없습니다"
// → 오타를 즉시 발견!
```

3. 설계 의도 명확하게 하는 도구 - final
- 클래스: 상속하지마!
```
  class Animal final {  // 이 클래스 상속 금지!
  };

class Dog : public Animal {  // ❌ 컴파일 에러
// "Animal 은 final 이라 상속 불가"
};
```

- 함수: override 하지마!
```
class Animal {
public:
    virtual void speak() final {  // 이 함수 오버라이드 금지!
    }
};

class Dog : public Animal {
    void speak() override {  // ❌ 컴파일 에러
    }
};
```

4. 설계 의도 명확하게 하는 도구 - pure virtual functions: 나는 설계도일 뿐이야 상속받는 너는 받드시 구현해야 해!
- final 과 반대
- 나는 껍데기만 선언, 구현은 자식이 반드시 해라!
- 문법
```
  virtual void speak() = 0;
  //                    ↑
  //               "= 0" 이 pure virtual 표시
  //               "구현 없음, 자식이 해야 함"

```

- 비유
```
Pure Virtual = 건물 설계도 📐
              "화장실은 반드시 있어야 한다"
              하지만 설계도 자체가 화장실은 아님
              실제 건물(자식 클래스)이 화장실을 만들어야 함
```

- 실제 예시
```
// 부모 — 추상 클래스 (Abstract Class)
class Shape {
public:
    virtual double area() = 0;    // 순수 가상 함수
    virtual void draw() = 0;      // 순수 가상 함수
    // "도형이면 넓이와 그리기는 반드시 있어야 함"
    // "하지만 Shape 자체는 구현 못함 — 어떤 도형인지 모르니까"
};

// 자식 — 반드시 구현해야 함
class Circle : public Shape {
    double radius;
public:
    double area() override {           // ✅ 구현
        return 3.14 * radius * radius;
    }
    void draw() override {             // ✅ 구현
        std::print("원 그리기\n");
    }
};

class Rectangle : public Shape {
    double width, height;
public:
    double area() override {           // ✅ 구현
        return width * height;
    }
    void draw() override {             // ✅ 구현
        std::print("사각형 그리기\n");
    }
};
```

- 구현 안 하면?
```
class Triangle : public Shape {
    // area() 구현 안 함
    void draw() override {
        std::print("삼각형 그리기\n");
    }
};

Triangle t;  // ❌ 컴파일 에러
// "area() 가 구현되지 않았습니다"
```

- 추상 클래스 특징
```
Shape s;          // ❌ 직접 생성 불가
Shape* s;         // ✅ 포인터는 가능

// 포인터로 다형성 활용
Shape* shapes[] = {new Circle(), new Rectangle()};
for (auto shape : shapes) {
    shape->area();  // 각자의 area() 호출
    shape->draw();  // 각자의 draw() 호출
}
```

- 정리
```
일반  virtual             pure virtual
선언  virtual void f()    virtual void f() = 0
부모  구현있음              없음
자식  구현선택              강제직접 
생성  가능                  불가능
```

5. 모던 C++의 OOP 사고방식
- 캡슐화: 데이터를 안전하게 보호하세요.
- Rule of Zero: 자원 관리는 표준 라이브러리에 맡기세요.
- 안전한 다형형: virtual 소멸자와 override 를 사용하세요

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter16 - 템플릿 기초
##### 
```
```
##### 목차
```
1. 템플릿: 코드 재사용의 마법
2. 함수와 클래스 템플릿
3. 템플릿 특수화: 예외 다루기
4. C++20 컨셉: 안전한 템플릿
5. 실전: 제네릭 스택 만들기
6. 핵심 요약과 다음 단계
```

###### 질문 
```
```
1. 똑같은 로직, 몇 번이나 {복사}했나요?
- 코드 중복은 유지보수가 힘들다. 이를 해결하는 것인 템플릿 이다.

###### 1. 템플릿: 코드 재사용의 마법(타입에 독립적인 코드 작성법)
```
```
1. 함수 템플릿
- 타입을 매개변수로 받는 함수. 컴파일러가 인자 타입을 보고 자동으로 코드를 생성하는 '설계도'입니다.
- 예시
```
template <typename T>
T max(T a, T b)

max(10, 20)       // T is int
max(3.14, 3.333)  // T is double
```

2. 클래스 템플릿 = "타입만 다른 똑같은 클래스를 하나로" — std::vector, std::map 등이 모두 템플릿으로 만들어진 것
- 템플릿 없을 때 — 타입마다 클래스 따로
```
// int 용
class IntBox {
    int value;
public:
    void set(int v) { value = v; }
    int get() { return value; }
};

// double 용
class DoubleBox {
    double value;
public:
    void set(double v) { value = v; }
    double get() { return value; }
};

// string 용
class StringBox {
    std::string value;
public:
    void set(std::string v) { value = v; }
    std::string get() { return value; }
};
// 구조가 똑같은데 타입만 달라서 3개나 만들어야 함 😱
```

- 템플릿으로 해결 — 하나로 통합
```
template <typename T>  // T = 나중에 정할 타입
class Box {
    T value;
public:
    void set(T v) { value = v; }
    T get() { return value; }
};

// 사용할 때 타입 지정
Box<int>         intBox;     // T = int
Box<double>      doubleBox;  // T = double
Box<std::string> stringBox;  // T = string

intBox.set(42);
doubleBox.set(3.14);
stringBox.set("Hello");

std::print("{}\n", intBox.get());     // 42
std::print("{}\n", doubleBox.get());  // 3.14
std::print("{}\n", stringBox.get());  // Hello
```

- 실용적인 예시 — 스택(Stack)
```
template <typename T>
class Stack {
    std::vector<T> data;

public:
    void push(T value) {
        data.push_back(value);
    }

    T pop() {
        T top = data.back();
        data.pop_back();
        return top;
    }

    bool empty() const {
        return data.empty();
    }
};

// int 스택
Stack<int> intStack;
intStack.push(1);
intStack.push(2);
intStack.push(3);
std::print("{}\n", intStack.pop());  // 3

// string 스택
Stack<std::string> strStack;
strStack.push("Alice");
strStack.push("Bob");
std::print("{}\n", strStack.pop());  // "Bob"
```

- 컴파일러가 하는 일
```
Box<int> b;
// 컴파일러가 자동으로 아래를 생성
class Box_int {
    int value;
public:
    void set(int v) { value = v; }
    int get() { return value; }
};
```


###### 2. 함수와 클래스 템플릿(함수를 넘어 데이터 구조까지)
```
```
1. 비교
- C++14: Box<int> myIntBox(10); : 타입을 명시적으로 지정해야 합니다.
- C++17: Box myIntBox(10); : 생성자 인자로 타입을 자동으로 추론합니다.
  - CTAD = Class Template Argument Deduction(디덕션, 추론), 클래스 템플릿 인수 추론 

###### 3. 템플릿 특수화: 예외 다루기(일반 규칙이 통하지 않을 때)
```
```

1. 질문 : C 스타일 {문자열}을 {비교}하면 어떻게 될까요?
```
```
- C 스타일 문자열 = char 배열 — == 로 비교하면 내용이 아닌 주소를 비교해서 항상 틀린 결과 나옴
```
char a[] = "Alice";
char b[] = "Alice";

// ❌ C 스타일 비교 — 주소값 비교 (내용 비교 아님!)
if (a == b) { ... }  // 항상 false
//  ↑
// "Alice" 내용이 아니라
// a 와 b 의 메모리 주소를 비교
// 주소가 다르니까 항상 false 😱

// ✅ C 스타일 올바른 비교
if (strcmp(a, b) == 0) { ... }  // 내용 비교

// ✅ std::string 비교 — 그냥 == 사용 가능
std::string sa = "Alice";
std::string sb = "Alice";
if (sa == sb) { ... }  // ✅ 내용 비교 정상 동작
```

2. 문제해결 : 템플릿의 특수화
- template<> 키워드로 const char * 타입에 대한 {특별한 버전}을 {정의}합니다 포인터 대신 문자.
- 템플릿 특수화 = 특정 타입만 별도로 다르게 처리 — const char* 는 == 대신 strcmp() 를 써야 하므로 특수화 버전을 따로 정의
- 먼저 문제 상황
```
template <typename T>
bool isEqual(T a, T b) {
    return a == b;  // 일반적인 비교
}

isEqual(10, 10);          // ✅ int 비교 — 정상
isEqual(3.14, 3.14);      // ✅ double 비교 — 정상
isEqual("Alice", "Alice"); // ❌ char* 비교 — 주소 비교 😱
```

- 왜 char* 가 문제냐
```
"Alice"       "Alice"
   ↑              ↑
주소: 0x100    주소: 0x200   ← 다른 주소!

a == b  →  0x100 == 0x200  →  false 😱
// 내용이 같아도 주소가 다르면 false
```

- template<> 특수화로 해결
```
// 일반 템플릿 — 모든 타입에 적용
template <typename T>
bool isEqual(T a, T b) {
    return a == b;
}

// 특수화 — const char* 타입만 별도 처리
template <>                           // ← 특수화 표시
bool isEqual<const char*>(const char* a, const char* b) {
    return strcmp(a, b) == 0;         // 문자 내용 비교
}
```

- 동작 방식
```
isEqual(10, 10);              // → 일반 템플릿 호출 (int)
isEqual(3.14, 3.14);          // → 일반 템플릿 호출 (double)
isEqual("Alice", "Alice");    // → 특수화 버전 호출 (const char*)
//       ↑
// 컴파일러가 "아 const char* 네 → 특수화 버전 써야겠다" 자동 선택
```

- 비유
```
일반 템플릿  = 일반 손님 응대 매뉴얼 📋
              "모든 손님은 이렇게 응대"

특수화       = VIP 손님 별도 매뉴얼 📋⭐
              "VIP(const char*) 는 이렇게 별도 응대"

컴파일러     = 직원
              손님 보고 → 일반/VIP 판단 → 맞는 매뉴얼 적용
```

- const char* 가 뭔가?
```
const char* str = "Alice";
//  ↑
// char 배열을 가리키는 포인터
// C 스타일 문자열의 타입

// 포인터라서 == 비교하면 주소 비교
// → strcmp() 로 내용 비교해야 함
```

- 전체 흐름
```
template<typename T>        ← 일반 버전 (모든 타입)
    a == b

        +

template<>                  ← 특수화 버전 (const char* 만)
    strcmp(a, b) == 0

        ↓

컴파일러가 타입 보고 자동 선택
```
-
```
```
###### 4. C++20 컨셉: 안전한 템플릿(템플릿의 오랜 골칫거리 해결)
```
```
1. 문제
- 템플릿은 {강력하지만}, 오류 메시지가 {복잡하고} 이해하기 어렵다는 단점이 있다.

2. 컨셉(Concept)
- 템플릿 매개변수가 만족해야 하는 제약 조건의 집합.
- 코드를 더 명확하고 안전하게 만들며, 컴파일 오류를 획기적으로 개선합니다.
- 실제 메시지는 영어로 길게 나오지만 핵심은 "이 타입은 조건을 만족하지 않는다" — 옛날보다 훨씬 명확해진 것이 Concepts 의 장점
- 나만의 컨셉 정의하기
```
Step1: 조건 정의
template <typename T>
concept Addable = requires(T a, T b) {
    a + b;  // "T 타입은 + 연산이 가능해야 함"
};

Step2: 컨셉 적용
template <Addable T>
T add(T a, T b) {
    return a + b;
}

>> 동작 확인
add(1, 2);          // ✅ int — + 가능
add(1.5, 2.5);      // ✅ double — + 가능
add("a", "b");      // ❌ 컴파일 에러
                    // "const char* 는 Addable 조건 불만족"
                    
>> 옛날 템플릿 에러와 비교
// ❌ 옛날 — Concepts 없을 때
// 수십 줄의 알아보기 힘든 에러 폭탄 💥
error: no match for 'operator+'
  in '__x + __y'
  instantiated from 'T add(T, T) [with T = const char*]'
  ... (수십 줄 계속)

// ✅ Concepts 있을 때
// "const char* 는 Addable 조건 불만족" 핵심만 명확히
note: because 'const char *' does not satisfy 'Addable'

>> 실제 컴파일 에러 메시지
error: no matching function for call to 'add'
add("a", "b");
^~~
note: candidate template ignored: 
      constraints not satisfied [with T = const char *]
template <Addable T>
          ^
note: because 'const char *' does not satisfy 'Addable'
concept Addable = requires(T a, T b) { a + b; };
                                       ^
note: because 'a + b' would be invalid with T = const char*
```

###### 5. 실전: 제네릭 스택 만들기(배운 모든 것을 종합하여)
```
```
1. 일반
- template<teypename T> class Stack 설게도 하나로
- Stack<int>, Stack<string> 등 {무엇이든} 만들어낼 수 있습니다.

2. 예시 - Stack + 템플릿 특수화 + 컨셉
```
#include <iostream>
#include <vector>
#include <print>
#include <string>
#include <cstring>  // strcmp

// ─────────────────────────────────────
// 1. 컨셉 정의
// ─────────────────────────────────────
template <typename T>
concept Stackable = requires(T a, T b) {
    { a == b } -> std::convertible_to<bool>;  // == 비교 가능해야 함
};

// ─────────────────────────────────────
// 2. 일반 템플릿 Stack
// ─────────────────────────────────────
template <Stackable T>  // Stackable 컨셉 적용
class Stack {
private:
    std::vector<T> data;

public:
    void push(T value) {
        data.push_back(value);
    }

    T pop() {
        if (empty()) throw std::runtime_error("Stack 이 비어있습니다!");
        T top = data.back();
        data.pop_back();
        return top;
    }

    T peek() const {
        if (empty()) throw std::runtime_error("Stack 이 비어있습니다!");
        return data.back();
    }

    // 특정 값이 있는지 확인 — == 연산 사용
    bool contains(T value) const {
        for (const auto& item : data) {
            if (item == value) return true;  // == 비교
        }
        return false;
    }

    bool empty() const { return data.empty(); }
    int size()   const { return data.size(); }
};

// ─────────────────────────────────────
// 3. 템플릿 특수화 — const char* 전용
//    == 대신 strcmp() 로 내용 비교
// ─────────────────────────────────────
template <>
class Stack<const char*> {
private:
    std::vector<const char*> data;

public:
    void push(const char* value) {
        data.push_back(value);
    }

    const char* pop() {
        if (empty()) throw std::runtime_error("Stack 이 비어있습니다!");
        const char* top = data.back();
        data.pop_back();
        return top;
    }

    const char* peek() const {
        if (empty()) throw std::runtime_error("Stack 이 비어있습니다!");
        return data.back();
    }

    // 특수화 핵심 — strcmp() 로 내용 비교
    bool contains(const char* value) const {
        for (const auto& item : data) {
            if (strcmp(item, value) == 0) return true;  // 내용 비교
        }
        return false;
    }

    bool empty() const { return data.empty(); }
    int size()   const { return data.size(); }
};

int main() {
    // ① int 스택 — 일반 템플릿
    std::print("=== int Stack ===\n");
    Stack<int> intStack;
    intStack.push(10);
    intStack.push(20);
    intStack.push(30);
    std::print("크기: {}\n",          intStack.size());         // 3
    std::print("peek: {}\n",           intStack.peek());         // 30
    std::print("20 있냐: {}\n",        intStack.contains(20));   // true
    std::print("pop: {}\n",            intStack.pop());          // 30

    std::print("---\n");

    // ② string 스택 — 일반 템플릿
    std::print("=== string Stack ===\n");
    Stack<std::string> strStack;
    strStack.push("Alice");
    strStack.push("Bob");
    strStack.push("Charlie");
    std::print("크기: {}\n",           strStack.size());              // 3
    std::print("Bob 있냐: {}\n",        strStack.contains("Bob"));    // true
    std::print("pop: {}\n",             strStack.pop());              // Charlie

    std::print("---\n");

    // ③ const char* 스택 — 특수화 버전
    std::print("=== const char* Stack (특수화) ===\n");
    Stack<const char*> cstrStack;
    cstrStack.push("Alice");
    cstrStack.push("Bob");
    cstrStack.push("Charlie");
    std::print("크기: {}\n",            cstrStack.size());            // 3
    std::print("peek: {}\n",            cstrStack.peek());            // Charlie
    std::print("Alice 있냐: {}\n",      cstrStack.contains("Alice")); // true ✅
    std::print("pop: {}\n",             cstrStack.pop());             // Charlie

    std::print("---\n");

    // ④ 컨셉 위반 확인
    // Stack<std::vector<int>> 는 == 비교 불가 → 컴파일 에러
    // Stack<std::vector<int>> badStack;  // ❌ Stackable 조건 불만족
}

>> 실행 결과
=== int Stack ===
크기: 3
peek: 30
20 있냐: true
pop: 30
---
=== string Stack ===
크기: 3
Bob 있냐: true
pop: Charlie
---
=== const char* Stack (특수화) ===
크기: 3
peek: Charlie
Alice 있냐: true
pop: Charlie
---

>> 3가지 핵심 역할
컨셉 (Stackable)
    → "== 비교 가능한 타입만 허용"
    → 조건 불만족 시 컴파일 에러

일반 템플릿 Stack<T>
    → int, double, string 등 모든 Stackable 타입 처리
    → == 로 contains() 비교

특수화 Stack<const char*>
    → C 스타일 문자열 전용
    → strcmp() 로 내용 비교 (== 대신)
    
>> convertible_to 란? "변환 가능한" — 표준 라이브러리에서 제공하는 컨셉입니다.
{ a == b } -> std::convertible_to<bool>;
//  ↑                ↑
// a == b 의 결과가  bool 로 변환 가능해야 함

>> 왜 필요한가?
// == 연산자가 있어도 결과가 bool 이 아닐 수 있음
struct Weird {
    int operator==(const Weird&) { return 42; }  // bool 아닌 int 반환
};

// convertible_to<bool> 이 있으면
// int → bool 변환 가능하니까 ✅ 통과

// convertible_to<bool> 없이 그냥 쓰면
// 결과 타입 체크 안 함 → 위험

>> 실제 타입으로 확인
// int
int a, b;
a == b;  // ✅ 가능, 결과 bool → Stackable ✅

// std::string
std::string a, b;
a == b;  // ✅ 가능, 결과 bool → Stackable ✅

// 비교 불가한 타입
struct Foo {};  // == 없음
Foo a, b;
a == b;  // ❌ 불가 → Stackable ❌ 컴파일 에러

>> == 연산자가 없어서 비교 불가
struct Foo {};  // 아무것도 없음

>> == 연산자 추가하면 비교 가능
// ❌ == 없음
struct Foo {};
Foo a, b;
a == b;  // 컴파일 에러

// ✅ == 추가
struct Foo {
    int value;
    bool operator==(const Foo& other) const {
        return value == other.value;  // 비교 방법 정의
    }
};
Foo a{10}, b{10};
a == b;  // ✅ true → Stackable 통과

>> int, string 은 왜 됐냐?
// int — C++ 이 기본으로 == 제공
int a = 10, b = 10;
a == b;  // ✅ 기본 제공

// std::string — 클래스 내부에 == 이미 정의됨
std::string a = "hi", b = "hi";
a == b;  // ✅ string 클래스가 이미 구현
```


###### 6. 핵심 요약과 다음 단계(템플릿의 힘을 당신의 것으로)
```
```
1. 정리
```
기능                핵심 역할
함수/클래스 템플릿      타입에 독립적인 코드 작성
템플릿 특수화          특정 타입에 대한 예외 처리
C++20 Concepts      명확한 제약 조건 부쳐(안정성)
```

2. 핵심 정리(Key Takeaways)
- Code Reuse: 코드 재사용성
- STL Foundation: 자주 사용하는 컨테이너 근간이 템플릿
- Specialization: 예외 상황
- Concepts: 안정성과 편의성

# Modern C++로 시작하는 안전하고 쉬운 C++ 프로그래밍: Chapter17 - 예외 처리와 오류 관리
##### 
```
```
##### 목차
```
1. 코든느 왜 실패하는가?
2. 전통적 해법: try/catch
3. 값이 없을 때: std::optional
4. 오류 정보가 필요 할 때: std::excpected
5. 올바른 도구 선택하기
```

###### 1. 코든느 왜 실패하는가?(일상 코드 속 숨겨짐 위험)
```
```
1. 질문
- 내 코드는 {정말}{안전할까?}
2. 전통적 오류 처리의 한계

###### 2. 전통적 해법: try/catch(예외적 상황을 위한 강력한 도구)
```
```
1. try-catch-throw 흐름
- 1단계: try: 예외 발생 가능성이 있는 코드를 실행합니다.
- 2단계: throw: 문제가 발생하면 예외를 던집니다.
- 3단계: catch: 던져진 예외를 잡아 처리합니다.
2. 설명
- try 블록으로 위험한 코드를 보호하고, 
- catch 블록으로 특정 유형의 예외를 잡아 프로그램의 비정상 종료를 막습니다.

###### 3. 값이 없을 때: std::optional(값의 부재를 다루는 우아한 방법)
```
```
1. std::optional(C++17)
- 값이 존재할 수도, 존재하지 않을 수도 있는 상황을 표현하는 타입입니다.
- 오류가 아닌, 정상적인 가능성입니다.
2. 비교
- 과거 방식
```
int find_user_id(string name); // 실패 시 -1 반환
```
- 과거 방식 — -1 로 실패 표현
```
// ❌ 과거 방식
int find_user_id(std::string name) {
    if (name == "Alice") return 1;
    if (name == "Bob")   return 2;
    return -1;  // 실패 시 -1 반환 — 약속이 불명확
}

int id = find_user_id("Dave");
if (id == -1) {          // -1 이 실패인지 개발자가 알아야 함
    std::print("없음\n");
} else {
    std::print("id: {}\n", id);
}
// 문제: -1 이 실패인지 모르고 그냥 쓰면?
// id 가 -1 인 사용자가 실제로 있다면?
```
- 모던 방식
```
std::optional<int> name; 명확한 객체 반환
```
- 모던 방식 — optional 로 명확하게
```
// ✅ 모던 방식
std::optional<int> find_user_id(std::string name) {
    if (name == "Alice") return 1;   // 값 있음
    if (name == "Bob")   return 2;   // 값 있음
    return std::nullopt;             // 명확하게 "없음"
}

// 사용
auto id = find_user_id("Alice");
if (id.has_value()) {
    std::print("id: {}\n", id.value());  // 1
} else {
    std::print("없음\n");
}

auto id2 = find_user_id("Dave");
if (id2) {                               // has_value() 축약
    std::print("id: {}\n", *id2);
} else {
    std::print("없음\n");                // "없음"
}

// 없을 때 기본값
int result = find_user_id("Dave").value_or(-1);
std::print("id: {}\n", result);          // -1
```

- 실용 예시 — DB 조회
```
struct User {
    int id;
    std::string name;
    int age;
};

std::vector<User> db = {
    {1, "Alice", 25},
    {2, "Bob",   30},
    {3, "Charlie", 35}
};

// optional 반환
std::optional<User> find_user(std::string name) {
    for (const auto& user : db) {
        if (user.name == name) return user;   // 찾으면 반환
    }
    return std::nullopt;                      // 못 찾으면 없음
}

int main() {
    // 있는 사람
    auto user1 = find_user("Alice");
    if (user1) {
        std::print("찾음: {} ({}세)\n", user1->name, user1->age);
        // 찾음: Alice (25세)
    }

    // 없는 사람
    auto user2 = find_user("Dave");
    if (!user2) {
        std::print("Dave 없음\n");
        // Dave 없음
    }

    // value_or 로 기본값
    auto user3 = find_user("Eve");  // optional<User> — 없음(nullopt)
    .and_then([](const User& u) -> std::optional<int> {
        return u.id;   // User 에서 id 만 꺼냄
    })
    .value_or(-1);     // id 가 없으면 -1
    
    /*
      find_user("Eve")         → optional<User> (nullopt)
          ↓
      and_then(id 만 꺼냄)     → optional<int> (nullopt)
          ↓
      value_or(-1)             → -1    
    */
    
    /* Eve 가 있을 때 vs 없을 때
      // Eve 없을 때
      find_user("Eve")   → nullopt
      and_then(...)      → nullopt (건너뜀)
      value_or(-1)       → -1
      
      // Alice 있을 때
      find_user("Alice") → User{1, "Alice", 25}
      and_then(...)      → 1 (id 만 꺼냄)
      value_or(-1)       → 1    
    */
    
    /* and_then 핵심
      값이 있으면  → 람다 실행 (id 추출)
      값이 없으면  → 람다 건너뜀 → 그냥 nullopt 전달
    
      and_then = 값이 있을 때만 변환 / value_or = 최종적으로 없으면 기본값 — 파이프라인처럼 연결되는 것
    */
}
```

###### 4. 오류 정보가 필요 할 때: std::excpected(성공 시 값, 실패 시 원인 얻기)
```
```
1. std::expected(C++23)
- 성공 시의 기대값 또는 실패 시의 오류 객체, 
- 둘 중 하나를 담는 타입니다.
- 예외 없이 오류 정보를 전달합니다.
- 에러는 지정해줘야 한다. 컴파일러가 알아서 주지 않는다.
```
std:expected<FileData, read_file(path);
```

2. 기본 구조
```
std::expected<성공타입, 실패타입>
//             ↑         ↑
//           성공 시    실패 시 반환
```

3. 파일 읽기 예시
```
#include <expected>
#include <string>
#include <fstream>
#include <print>

// 에러 타입 정의
enum class FileError {
    NotFound,     // 파일 없음
    NoPermission, // 권한 없음
    Empty         // 파일 비어있음
};

// ✅ std::expected 반환
std::expected<std::string, FileError> read_file(std::string path) {

    std::ifstream file(path);

    if (!file.is_open()) {
        return std::unexpected(FileError::NotFound);  // 실패 반환
    }

    std::string content((std::istreambuf_iterator<char>(file)),
                         std::istreambuf_iterator<char>());

    if (content.empty()) {
        return std::unexpected(FileError::Empty);     // 실패 반환
    }

    return content;  // 성공 반환
}

int main() {
    // ① 성공 케이스
    auto result1 = read_file("data.txt");
    if (result1.has_value()) {
        std::print("내용: {}\n", result1.value());
    }

    // ② 실패 케이스
    auto result2 = read_file("없는파일.txt");
    if (!result2.has_value()) {
        switch (result2.error()) {
            case FileError::NotFound:
                std::print("파일 없음\n");     // ← 여기 출력
                break;
            case FileError::NoPermission:
                std::print("권한 없음\n");
                break;
            case FileError::Empty:
                std::print("파일 비어있음\n");
                break;
        }
    }

    // ③ value_or 로 기본값
    std::string content = read_file("없는파일.txt")
        .value_or("기본 내용");
    std::print("{}\n", content);  // "기본 내용"
}
```

4. optional vs expected 비교
```
// optional — "있냐 없냐" 만
std::optional<std::string> read_file(std::string path) {
    if (실패) return std::nullopt;  // 왜 실패했는지 모름 😱
    return content;
}

// expected — "성공이냐, 왜 실패했냐"
std::expected<std::string, FileError> read_file(std::string path) {
    if (실패) return std::unexpected(FileError::NotFound);  // 이유 명확 ✅
    return content;
}
```

5. 흐름 정리
```
read_file("data.txt")
         ↓
   파일 있음? ──── YES ──→ string 반환 (성공)
         │
         NO
         ↓
   FileError 반환 (실패 이유 포함)
         ↓
   NotFound / NoPermission / Empty
```

6. optional vs expected vs exception 비교
```
          optional        expected          exception
실패 이유   ❌ 모름          ✅ 알 수 있음        ✅ 알 수 있음
성능      ✅ 빠름          ✅ 빠름              ❌ 느림
용도      단순 없음/있음      오류 정보 필요        예외적 상황
```

7. 한 줄 요약
```
expected = 성공하면 값, 실패하면 이유 — optional 보다 정보가 많고, 예외보다 빠름
```

8. enum class FileError vs. enum FileError 차이
- 둘 다 됩니다. 하지만 enum class 가 더 안전합니다.

- 차이점
```
// ❌ 일반 enum
enum FileError {
    NotFound,
    NoPermission,
    Empty
};

// ✅ enum class (범위 있는 열거형)
enum class FileError {
    NotFound,
    NoPermission,
    Empty
};
```

- 왜 enum class 가 더 좋냐? - ① 이름 충돌 방지
```
// 일반 enum — 전역으로 노출
enum FileError { NotFound };
enum NetworkError { NotFound };  // ❌ 충돌! NotFound 중복

// enum class — 범위 안에 격리
enum class FileError { NotFound };
enum class NetworkError { NotFound };  // ✅ 충돌 없음

FileError::NotFound     // 명확히 구분
NetworkError::NotFound  // 명확히 구분
```

- 왜 enum class 가 더 좋냐? - ② 암시적 변환 방지
```
// 일반 enum — int 로 자동 변환 (위험)
enum FileError { NotFound = 0 };
int x = NotFound;  // ✅ 그냥 됨 — 의도치 않은 변환

// enum class — int 변환 불가
enum class FileError { NotFound = 0 };
int x = FileError::NotFound;  // ❌ 컴파일 에러 — 안전
```

- 한 줄 요약
```
enum = 이름 전역 노출, int 자동 변환 위험 / enum class = 범위 격리, 타입 안전 — 모던 C++ 에서는 enum class 권장
```

###### 5. 올바른 도구 선택하기(장인을 위한 실용 가이드)
```
```
1. 비교
```
도구              사용 사례                  키워드
예외(exception)   복구 불가능한 예외적 오류      throw / try / catch
std::optional    값이 없는 경이 정상인 경우     has_value() / value_or() / nullopt
std::expected    오류 정보가 필요한 경우        error() / value() / unexpected
```

- 예외
```
throw FileException("에러");  // 던지기
try { ... }                   // 시도
catch (exception& e) { ... }  // 잡기
// 셋이 항상 함께 씀
```

- optional
```
return std::nullopt;          // 없음 표시
result.has_value();           // 있냐 확인
result.value_or("기본값");    // 없으면 기본값
```

- expected
```
return std::unexpected(FileError::NotFound);  // 실패 반환
result.error();               // 실패 이유 꺼내기
result.value();               // 성공값 꺼내기
```

2. 핵심 원칙
- 예외는 {정말 에외적인} 상황에만 사용합니다.
- 값이 없는 정상적인 경우는 std::optional 을 사용합니다.
- 오류 정보가 필요하다면 std::expected 을 사용합니다.
- RAII 를 활용하여 예외 안전성을 보장합니다.

