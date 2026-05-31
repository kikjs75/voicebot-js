package com.voicebot.service.llm;

import java.util.List;

/**
 * LLM 서비스 인터페이스.
 * 실제 구현: ClaudeApiLlmService
 * 시뮬레이터: SimulatorLlmService
 */
public interface LlmService {

    /**
     * 대화 이력을 기반으로 다음 응답을 생성한다.
     *
     * @param messages 대화 이력 (role: user/assistant)
     * @param callId   콜 세션 ID (로깅용)
     * @return 생성된 응답 텍스트
     */
    String chat(List<Message> messages, String callId);

    record Message(String role, String content) {}
}
