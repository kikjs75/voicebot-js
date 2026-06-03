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
@Profile("real")
@RequiredArgsConstructor
public class ClaudeApiLlmService implements LlmService {

    @Value("${voicebot.llm.api-key}")
    private String apiKey;

    @Value("${voicebot.llm.model:claude-sonnet-4-6}")
    private String model;

    private final WebClient webClient;

    @Override
    public String chat(List<Message> messages, String callId) {
        log.debug("[LLM] callId={} messages={}", callId, messages.size());

        List<Map<String, String>> messageList = messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<?, ?> response = webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(Map.of(
                        "model", model,
                        "max_tokens", 1024,
                        "system", """
                                당신은 한국어 콜센터 AI 상담원입니다.
                                고객 발화를 분석하고 반드시 아래 JSON 형식으로만 응답하세요.
                                {"intent": "의도", "response": "상담 응답"}
                                규칙:
                                - intent: 고객 의도 한 단어 (환불/배송문의/기술지원/요금문의/예약/기타)
                                - response: 2~3문장 이내 자연스러운 구어체 한국어
                                - 마크다운(#, **, -), 이모지, 특수문자 사용 금지
                                - JSON 외 다른 텍스트 출력 금지
                                """,
                        "messages", messageList
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<?> content = (List<?>) response.get("content");
        Map<?, ?> first = (Map<?, ?>) content.get(0);
        return (String) first.get("text");
    }
}
