#pragma once
#include <string>
#include <vector>

class LlmService {
public:
    struct Message {
        std::string role;
        std::string content;
    };
    virtual std::string chat(const std::vector<Message>& messages,
                             const std::string& callId) = 0;
    virtual ~LlmService() = default;
};
