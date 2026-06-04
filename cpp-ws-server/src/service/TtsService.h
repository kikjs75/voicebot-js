#pragma once
#include <cstdint>
#include <string>
#include <vector>

class TtsService {
public:
    virtual std::vector<uint8_t> synthesize(const std::string& text,
                                            const std::string& callId) = 0;
    virtual ~TtsService() = default;
};
