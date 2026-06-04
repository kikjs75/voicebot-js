#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

struct SttResult {
    std::string text;
    bool isFinal;
};

using SttCallback      = std::function<void(SttResult)>;
using SttErrorCallback = std::function<void(std::string)>;

class SttService {
public:
    virtual void recognize(const std::string& callId,
                           SttCallback onResult,
                           SttErrorCallback onError) = 0;
    virtual void sendChunk(const std::vector<uint8_t>& chunk) = 0;
    virtual void complete() = 0;
    virtual ~SttService() = default;
};
