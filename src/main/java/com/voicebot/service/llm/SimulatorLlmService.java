package com.voicebot.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Profile("sim")
@RequiredArgsConstructor
public class SimulatorLlmService implements LlmService {

    @Value("${voicebot.llm.url}")
    private String llmUrl;

    private final WebClient webClient;

    @Override
    public String chat(List<Message> messages, String callId) {
        log.debug("[LLM-SIM] callId={} messages={}", callId, messages.size());

        return webClient.post()
                .uri(llmUrl + "/chat")
                .header("X-Call-Id", callId)
                .bodyValue(Map.of("messages", messages))
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
