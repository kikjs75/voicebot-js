#pragma once
#include <string>
#include <vector>
#include "service/LlmService.h"

struct CallSession {
    std::string sessionId;
    std::string callId;
    std::vector<LlmService::Message> history;
};
