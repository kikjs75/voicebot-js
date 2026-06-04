#include "RtzrTokenManager.h"
#include <chrono>
#include <curl/curl.h>
#include <iostream>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

static size_t curlWrite(void* ptr, size_t size, size_t nmemb, std::string* s) {
    s->append(static_cast<char*>(ptr), size * nmemb);
    return size * nmemb;
}

RtzrTokenManager::RtzrTokenManager(const std::string& clientId,
                                   const std::string& clientSecret)
    : clientId_(clientId), clientSecret_(clientSecret) {
    refreshToken();
}

RtzrTokenManager::~RtzrTokenManager() {
    running_ = false;
    if (schedulerThread_.joinable()) schedulerThread_.join();
}

std::string RtzrTokenManager::getAccessToken() {
    std::lock_guard<std::mutex> lock(tokenMutex_);
    return accessToken_;
}

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

void RtzrTokenManager::refreshToken() {
    CURL* curl = curl_easy_init();
    if (!curl) return;

    std::string postData = "client_id=" + clientId_ + "&client_secret=" + clientSecret_;
    std::string response;

    curl_easy_setopt(curl, CURLOPT_URL, "https://openapi.vito.ai/v1/authenticate");
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, postData.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curlWrite);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);

    CURLcode res = curl_easy_perform(curl);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        std::cerr << "[STT-RTZR] 토큰 발급 실패: " << curl_easy_strerror(res) << "\n";
        return;
    }

    try {
        auto j = json::parse(response);
        std::lock_guard<std::mutex> lock(tokenMutex_);
        accessToken_ = j["access_token"].get<std::string>();
        expireAt_    = j["expire_at"].get<long>();
        std::cout << "[STT-RTZR] 토큰 발급 완료 expire_at=" << expireAt_ << "\n";
    } catch (const std::exception& e) {
        std::cerr << "[STT-RTZR] 토큰 파싱 오류: " << e.what() << "\n";
    }
}
