#include "SpringLlmService.h"
#include <chrono>
#include <curl/curl.h>
#include <iostream>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

static size_t curlWrite(void* ptr, size_t size, size_t nmemb, std::string* s) {
    s->append(static_cast<char*>(ptr), size * nmemb);
    return size * nmemb;
}

SpringLlmService::SpringLlmService(const std::string& springUrl)
    : springUrl_(springUrl) {}

std::string SpringLlmService::chat(const std::vector<Message>& messages,
                                   const std::string& callId) {
    json arr = json::array();
    for (const auto& m : messages)
        arr.push_back({{"role", m.role}, {"content", m.content}});

    std::string body = arr.dump();
    std::string response;
    std::string url = springUrl_ + "/api/cti/llm/chat";

    auto start = std::chrono::steady_clock::now();

    CURL* curl = curl_easy_init();
    if (!curl) throw std::runtime_error("curl_easy_init failed");

    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, body.size());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curlWrite);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);

    CURLcode res = curl_easy_perform(curl);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();
    std::cout << "[LLM-PERF] callId=" << callId << " elapsed=" << elapsed << "ms\n";

    if (res != CURLE_OK)
        throw std::runtime_error(std::string("LLM HTTP 오류: ") + curl_easy_strerror(res));

    return response;
}
