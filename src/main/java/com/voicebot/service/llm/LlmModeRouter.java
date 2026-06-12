package com.voicebot.service.llm;

import com.voicebot.service.llm.strategy.AnthropicStrategy;
import com.voicebot.service.llm.strategy.HybridStrategy;
import com.voicebot.service.llm.strategy.InternalStrategy;
import com.voicebot.service.llm.strategy.LlmStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@Profile("real")
@RequiredArgsConstructor
public class LlmModeRouter implements LlmService {

    @Value("${voicebot.llm.mode:ANTHROPIC}")
    private String mode;

    private final AnthropicStrategy anthropicStrategy;
    private final InternalStrategy internalStrategy;
    private final HybridStrategy hybridStrategy;

    @Override
    public String chat(List<Message> messages, String callId) {
        log.info("[LLM-MODE] callId={} mode={}", callId, mode);
        LlmStrategy strategy = switch (mode.toUpperCase()) {
            case "INTERNAL" -> internalStrategy;
            case "HYBRID"   -> hybridStrategy;
            default         -> anthropicStrategy;
        };
        return strategy.execute(messages, callId);
    }
}
