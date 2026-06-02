package com.voicebot.service.stt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Service
@Profile("sim")
@RequiredArgsConstructor
public class SimulatorSttService implements SttService {

    @Value("${voicebot.stt.url}")
    private String sttUrl;

    private final WebClient webClient;

    @Override
    public Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId) {
        return audioStream
                .collectList()
                .map(this::mergeChunks)
                .flatMapMany(audio -> {
                    log.debug("[STT-SIM] callId={} audioSize={}bytes", callId, audio.length);
                    return webClient.post()
                            .uri(sttUrl + "/recognize")
                            .header("X-Call-Id", callId)
                            .bodyValue(audio)
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(text -> new SttResult(text, true))
                            .flux();
                });
    }

    private byte[] mergeChunks(List<byte[]> chunks) {
        int total = chunks.stream().mapToInt(b -> b.length).sum();
        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }
}
