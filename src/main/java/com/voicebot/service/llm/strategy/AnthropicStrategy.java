package com.voicebot.service.llm.strategy;

import com.voicebot.service.llm.ClaudeApiLlmService;
import com.voicebot.service.llm.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("real")
@RequiredArgsConstructor
public class AnthropicStrategy implements LlmStrategy {

    private final ClaudeApiLlmService claudeApiLlmService;

    @Override
    public String execute(List<LlmService.Message> messages, String callId) {
        return claudeApiLlmService.chat(messages, callId);
    }
}
