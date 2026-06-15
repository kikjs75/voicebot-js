package com.voicebot.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicebot.domain.IntentResult;
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
public class ClaudeApiLlmService {

    @Value("${voicebot.llm.api-key}")
    private String apiKey;

    @Value("${voicebot.llm.model:claude-sonnet-4-6}")
    private String model;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final String INTENT_SYSTEM_PROMPT = """
            당신은 콜센터 상담 AI입니다.
            고객 발화를 분석하여 반드시 아래 JSON 형식으로만 응답하세요.
            다른 텍스트는 포함하지 마세요.

            intent는 반드시 다음 중 하나여야 합니다:
            인사 | 배송문의 | 반품환불 | 교환 | 결제 | 회원 | 주문조회 | 상담원연결 | 종료 | 기타

            {"intent": "배송문의", "confidence": 0.95}
            """;

    public String chat(List<LlmService.Message> messages, String callId) {
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
        return stripCodeBlock((String) first.get("text"));
    }

    private String stripCodeBlock(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("(?s)```\\s*", "").trim();
    }

    public IntentResult classifyIntent(String userText, String callId) {
        long start = System.currentTimeMillis();
        String rawText = null;
        try {
            Map<?, ?> response = webClient.post()
                    .uri("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .bodyValue(Map.of(
                            "model", model,
                            "max_tokens", 100,
                            "system", INTENT_SYSTEM_PROMPT,
                            "messages", List.of(Map.of("role", "user", "content", userText))
                    ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<?> content = (List<?>) response.get("content");
            Map<?, ?> first = (Map<?, ?>) content.get(0);
            rawText = (String) first.get("text");
            String text = stripCodeBlock(rawText);

            JsonNode node = objectMapper.readTree(text);
            String intent = node.path("intent").asText("기타");
            double confidence = node.path("confidence").asDouble(0.5);

            log.info("[INTENT] callId={} intent={} confidence={} elapsed={}ms",
                    callId, intent, confidence, System.currentTimeMillis() - start);

            return new IntentResult(intent, confidence);
        } catch (Exception e) {
            log.warn("[INTENT] callId={} 분류 실패 → 기타 반환: {} | raw={}", callId, e.getMessage(), rawText);
            return new IntentResult("기타", 0.0);
        }
    }
}
