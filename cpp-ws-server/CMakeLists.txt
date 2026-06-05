cmake_minimum_required(VERSION 3.16)
project(voicebot-cpp)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Boost REQUIRED COMPONENTS system)
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
    Boost::system
    OpenSSL::SSL
    OpenSSL::Crypto
    CURL::libcurl
    nlohmann_json::nlohmann_json
    spdlog::spdlog
    pthread
)
