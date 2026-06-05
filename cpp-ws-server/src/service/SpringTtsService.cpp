#include "SpringTtsService.h"
#include "Logger.h"
#include <chrono>
#include <curl/curl.h>
#include <nlohmann/json.hpp>
#include <stdexcept>

static size_t curlWriteBytes(void* ptr, size_t size, size_t nmemb,
                              std::vector<uint8_t>* v) {
    auto* p = static_cast<uint8_t*>(ptr);
    v->insert(v->end(), p, p + size * nmemb);
    return size * nmemb;
}

SpringTtsService::SpringTtsService(const std::string& springUrl)
    : springUrl_(springUrl) {}

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
