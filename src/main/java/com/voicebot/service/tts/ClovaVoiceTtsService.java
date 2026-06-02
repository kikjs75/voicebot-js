package com.voicebot.service.tts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@Profile({"real", "real-grpc"})
@RequiredArgsConstructor
public class ClovaVoiceTtsService implements TtsService {

    @Value("${voicebot.tts.url}")
    private String ttsUrl;

    @Value("${voicebot.tts.client-id}")
    private String clientId;

    @Value("${voicebot.tts.client-secret}")
    private String clientSecret;

    @Value("${voicebot.tts.speaker:nara}")
    private String speaker;

    @Value("${voicebot.tts.format:wav}")
    private String format;

    @Value("${voicebot.tts.sampling-rate:8000}")
    private int samplingRate;

    private final WebClient webClient;

    @Override
    public byte[] synthesize(String text, String callId) {
        log.debug("[TTS] callId={} text={}", callId, text);

        String body = "speaker=" + URLEncoder.encode(speaker, StandardCharsets.UTF_8) +
                      "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8) +
                      "&format=" + format +
                      "&sampling-rate=" + samplingRate +
                      "&volume=0&speed=0&pitch=0";

        return webClient.post()
                .uri(ttsUrl)
                .header("X-NCP-APIGW-API-KEY-ID", clientId)
                .header("X-NCP-APIGW-API-KEY", clientSecret)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }
}
