package com.voicebot.service.llm.strategy;

import com.voicebot.domain.IntentPlaybook;
import com.voicebot.domain.IntentResult;
import com.voicebot.service.llm.ClaudeApiLlmService;
import com.voicebot.service.llm.LlmService;
import com.voicebot.service.llm.PlaybookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@Profile("real")
@RequiredArgsConstructor
public class HybridStrategy implements LlmStrategy {

    private final ClaudeApiLlmService claudeApiLlmService;
    private final PlaybookService playbookService;

    @Value("${voicebot.llm.hybrid.confidence-threshold:0.7}")
    private double confidenceThreshold;

    @Override
    public String execute(List<LlmService.Message> messages, String callId) {
        String userText = extractLastUserText(messages);

        IntentResult intentResult = claudeApiLlmService.classifyIntent(userText, callId);

        Optional<IntentPlaybook> playbookOpt = playbookService.findByIntent(intentResult.intent());

        if (playbookOpt.isPresent() && intentResult.confidence() >= confidenceThreshold) {
            IntentPlaybook playbook = playbookOpt.get();
            log.info("[PLAYBOOK] callId={} hit=true action={} → Playbook 응답", callId, playbook.getAction());
            String escaped = playbook.getResponse().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            return "{\"intent\":\"" + intentResult.intent() + "\",\"response\":\"" + escaped + "\"}";
        }

        log.info("[PLAYBOOK] callId={} hit=false confidence={} → Claude fallback", callId, intentResult.confidence());
        return claudeApiLlmService.chat(messages, callId);
    }

    private String extractLastUserText(List<LlmService.Message> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.role()))
                .reduce((a, b) -> b)
                .map(LlmService.Message::content)
                .orElse("");
    }
}
