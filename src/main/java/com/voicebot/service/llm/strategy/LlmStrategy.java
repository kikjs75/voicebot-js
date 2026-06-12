package com.voicebot.service.llm.strategy;

import com.voicebot.service.llm.LlmService;

import java.util.List;

public interface LlmStrategy {
    String execute(List<LlmService.Message> messages, String callId);
}
