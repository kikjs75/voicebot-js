package com.voicebot.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicebot.service.llm.LlmService;
import com.voicebot.service.stt.SttService;
import com.voicebot.service.tts.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class CtiWebSocketHandler extends AbstractWebSocketHandler {

    private final SttService sttService;
    private final LlmService llmService;
    private final TtsService ttsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // sessionId → Sink
    private final Map<String, Sinks.Many<byte[]>> sinkMap = new ConcurrentHashMap<>();
    // sessionId → 대화 이력
    private final Map<String, List<LlmService.Message>> historyMap = new ConcurrentHashMap<>();
    // sessionId → callId
    private final Map<String, String> callIdMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String callId = "CTI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        callIdMap.put(session.getId(), callId);
        historyMap.put(session.getId(), new ArrayList<>());

        Sinks.Many<byte[]> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinkMap.put(session.getId(), sink);

        log.info("[CTI] 연결됨 sessionId={} callId={}", session.getId(), callId);

        // callId를 클로저로 캡처 — afterConnectionClosed 이후 map에서 제거되어도 참조 유지
        // publishOn(boundedElastic): LlmService.chat()이 block()을 사용하므로 NIO 스레드에서 실행 금지
        sttService.recognize(sink.asFlux(), callId)
                .filter(SttService.SttResult::isFinal)
                .timeout(Duration.ofSeconds(60))
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> handleFinalStt(session, callId, result.text()),
                        error -> log.error("[CTI] STT 오류 callId={}", callId, error)
                );
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Sinks.Many<byte[]> sink = sinkMap.get(session.getId());
        if (sink != null) {
            byte[] chunk = message.getPayload().array();
            log.debug("[CTI] 음성 청크 수신 callId={} size={}bytes",
                    callIdMap.get(session.getId()), chunk.length);
            sink.tryEmitNext(chunk);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> event = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) event.get("type");
        String callId = callIdMap.get(session.getId());

        log.info("[CTI] 이벤트 수신 type={} callId={}", type, callId);

        if ("CTI_EVENT".equals(type)) {
            String ctiEvent = (String) event.get("event");
            if ("CALL_END".equals(ctiEvent)) {
                handleCallEnd(session);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String callId = callIdMap.get(session.getId());
        log.info("[CTI] 연결 종료 sessionId={} callId={} status={}", session.getId(), callId, status);

        // sink 완료를 먼저 — STT 콜백이 map 제거 전에 callId를 클로저로 참조하므로 순서 무관하나 명시적으로 선행
        Sinks.Many<byte[]> sink = sinkMap.remove(session.getId());
        if (sink != null) sink.tryEmitComplete();

        // map 정리는 sink 완료 신호 발행 후
        historyMap.remove(session.getId());
        callIdMap.remove(session.getId());
    }

    private void handleCallEnd(WebSocketSession session) {
        String callId = callIdMap.get(session.getId());
        log.info("[CTI] CALL_END callId={}", callId);

        Sinks.Many<byte[]> sink = sinkMap.get(session.getId());
        if (sink != null) sink.tryEmitComplete();
    }

    private void handleFinalStt(WebSocketSession session, String callId, String finalText) {
        log.info("[CTI] STT 최종 callId={} text=\"{}\"", callId, finalText);

        try {
            sendJson(session, Map.of("type", "STT_FINAL", "text", finalText));

            List<LlmService.Message> history = historyMap.get(session.getId());
            if (history == null) return;

            history.add(new LlmService.Message("user", finalText));

            long llmStart = System.currentTimeMillis();
            String llmResponse = llmService.chat(history, callId);
            log.info("[CTI-LLM-PERF] callId={} elapsed={}ms", callId, System.currentTimeMillis() - llmStart);

            history.add(new LlmService.Message("assistant", llmResponse));

            sendJson(session, Map.of("type", "LLM_RESULT", "response", llmResponse));

            long ttsStart = System.currentTimeMillis();
            ttsService.synthesize(llmResponse, callId);
            log.info("[CTI-TTS-PERF] callId={} elapsed={}ms", callId, System.currentTimeMillis() - ttsStart);

            sendJson(session, Map.of("type", "TTS_TEXT", "text", llmResponse));

        } catch (Exception e) {
            log.error("[CTI] 파이프라인 오류 callId={}", callId, e);
            try {
                sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
            } catch (Exception ignored) {}
        }
    }

    private void sendJson(WebSocketSession session, Object data) throws Exception {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        }
    }
}
