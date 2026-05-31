package com.voicebot.service.stt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@Profile("sim")
@RequiredArgsConstructor
public class SimulatorSttService implements SttService {

    @Value("${voicebot.stt.url}")
    private String sttUrl;

    private final WebClient webClient;

    @Override
    public String recognize(byte[] audioData, String callId) {
        log.debug("[STT-SIM] callId={} audioSize={}bytes", callId, audioData.length);

        // 시뮬레이터 서버에 오디오 전송 → 텍스트 반환
        return webClient.post()
                .uri(sttUrl + "/recognize")
                .header("X-Call-Id", callId)
                .bodyValue(audioData)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
