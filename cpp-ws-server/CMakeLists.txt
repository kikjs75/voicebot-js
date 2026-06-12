cmake_minimum_required(VERSION 3.16)
project(voicebot-cpp)

# CMP0167: CMake 3.30+에서 도입 (Boost Config 방식 우선). 이전 버전은 무시
if(POLICY CMP0167)
  cmake_policy(SET CMP0167 NEW)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Boost REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(CURL REQUIRED)
find_package(nlohmann_json REQUIRED)

include(FetchContent)
FetchContent_Declare(
    spdlog
    GIT_REPOSITORY https://github.com/gabime/spdlog.git
    GIT_TAG        v1.14.1
)
FetchContent_MakeAvailable(spdlog)

# spdlog 내부 fmt 라이브러리의 Apple Clang deprecated 경고 억제
if(TARGET spdlog)
    target_compile_options(spdlog PRIVATE -Wno-deprecated-declarations)
endif()

add_executable(voicebot-cpp
    main.cpp
    src/WsServer.cpp
    src/service/RtzrTokenManager.cpp
    src/service/RtzrWebSocketSttService.cpp
    src/service/SpringLlmService.cpp
    src/service/SpringTtsService.cpp
)

target_include_directories(voicebot-cpp PRIVATE src)

target_link_libraries(voicebot-cpp
    Boost::boost
    OpenSSL::SSL
    OpenSSL::Crypto
    CURL::libcurl
    nlohmann_json::nlohmann_json
    spdlog::spdlog
    pthread
)
