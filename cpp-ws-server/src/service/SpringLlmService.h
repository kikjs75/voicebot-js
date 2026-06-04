#pragma once
#include "LlmService.h"

class SpringLlmService : public LlmService {
public:
    explicit SpringLlmService(const std::string& springUrl);
    std::string chat(const std::vector<Message>& messages,
                     const std::string& callId) override;

private:
    std::string springUrl_;
};
