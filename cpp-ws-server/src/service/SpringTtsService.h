#pragma once
#include "TtsService.h"

class SpringTtsService : public TtsService {
public:
    explicit SpringTtsService(const std::string& springUrl);
    std::vector<uint8_t> synthesize(const std::string& text,
                                    const std::string& callId) override;

private:
    std::string springUrl_;
};
