package com.voicebot.call;

import com.voicebot.domain.CallRecord;
import com.voicebot.repository.CallRecordRepository;
import com.voicebot.service.llm.LlmService;
import com.voicebot.service.stt.SttService;
import com.voicebot.service.tts.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallHandler {

    private final SttService sttService;
    private final LlmService llmService;
    private final TtsService ttsService;
    private final CallRecordRepository callRecordRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String SESSION_PREFIX = "call:session:";
    private static final Duration SESSION_TTL = Duration.ofHours(1);

    public byte[] process(byte[] audioData, String callId) {
        long totalStart = System.currentTimeMillis();

        // 1. STT
        long sttStart = System.currentTimeMillis();
        String sttText = sttService.recognize(Flux.just(audioData), callId)
                .filter(SttService.SttResult::isFinal)
                .map(SttService.SttResult::text)
                .collectList()
                .map(parts -> String.join(" ", parts))
                .timeout(Duration.ofSeconds(30))
                .onErrorReturn("")
                .block();
        long sttElapsed = System.currentTimeMillis() - sttStart;
        log.info("[STT-PERF] callId={} elapsed={}ms text=\"{}\"", callId, sttElapsed, sttText);

        if (sttText == null || sttText.isBlank()) {
            log.warn("[STT] callId={} 인식 결과 없음 — 음성 없음 또는 타임아웃", callId);
            return ttsService.synthesize("죄송합니다, 말씀을 잘 듣지 못했습니다. 다시 말씀해 주세요.", callId);
        }

        // 2. 세션 조회/생성, 사용자 발화 추가
        CallSession session = getOrCreateSession(callId);
        session.getMessages().add(Map.of("role", "user", "content", sttText));

        // 3. LLM
        long llmStart = System.currentTimeMillis();
        String llmResponse = llmService.chat(toLlmMessages(session.getMessages()), callId);
        long llmElapsed = System.currentTimeMillis() - llmStart;
        log.info("[LLM-PERF] callId={} elapsed={}ms", callId, llmElapsed);

        session.getMessages().add(Map.of("role", "assistant", "content", llmResponse));
        saveSession(callId, session);

        // 4. TTS
        long ttsStart = System.currentTimeMillis();
        byte[] audio = ttsService.synthesize(llmResponse, callId);
        long ttsElapsed = System.currentTimeMillis() - ttsStart;
        log.info("[TTS-PERF] callId={} elapsed={}ms", callId, ttsElapsed);

        long totalElapsed = System.currentTimeMillis() - totalStart;
        log.info("[CALL-PERF] callId={} elapsed={}ms", callId, totalElapsed);

        // 5. 콜 레코드 저장
        callRecordRepository.save(CallRecord.builder()
                .callId(callId)
                .sttText(sttText)
                .llmResponse(llmResponse)
                .sttElapsedMs(sttElapsed)
                .llmElapsedMs(llmElapsed)
                .ttsElapsedMs(ttsElapsed)
                .totalElapsedMs(totalElapsed)
                .build());

        return audio;
    }

    private CallSession getOrCreateSession(String callId) {
        Object stored = redisTemplate.opsForValue().get(SESSION_PREFIX + callId);
        if (stored instanceof CallSession session) {
            return session;
        }
        return CallSession.builder()
                .callId(callId)
                .state("ACTIVE")
                .startedAt(System.currentTimeMillis())
                .build();
    }

    private void saveSession(String callId, CallSession session) {
        redisTemplate.opsForValue().set(SESSION_PREFIX + callId, session, SESSION_TTL);
    }

    private List<LlmService.Message> toLlmMessages(List<Map<String, String>> messages) {
        return messages.stream()
                .map(m -> new LlmService.Message(m.get("role"), m.get("content")))
                .collect(Collectors.toList());
    }
}
