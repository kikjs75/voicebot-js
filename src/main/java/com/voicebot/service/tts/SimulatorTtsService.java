package com.voicebot.service.tts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Service
@Profile("sim")
@RequiredArgsConstructor
public class SimulatorTtsService implements TtsService {

    @Value("${voicebot.tts.url}")
    private String ttsUrl;

    private final WebClient webClient;

    @Override
    public byte[] synthesize(String text, String callId) {
        log.debug("[TTS-SIM] callId={} text={}", callId, text);

        return webClient.post()
                .uri(ttsUrl + "/synthesize")
                .header("X-Call-Id", callId)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }
}
