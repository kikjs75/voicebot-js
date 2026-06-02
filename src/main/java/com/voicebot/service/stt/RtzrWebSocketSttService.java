package com.voicebot.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@Profile("real")
@RequiredArgsConstructor
public class RtzrWebSocketSttService implements SttService {

    @Value("${voicebot.stt.rtzr.client-id}")
    private String clientId;

    @Value("${voicebot.stt.rtzr.client-secret}")
    private String clientSecret;

    @Value("${voicebot.stt.rtzr.sample-rate:8000}")
    private int sampleRate;

    @Value("${voicebot.stt.rtzr.encoding:LINEAR16}")
    private String encoding;

    private final WebClient webClient;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private final AtomicReference<String> accessToken = new AtomicReference<>("");
    private volatile long expireAt = 0;

    @PostConstruct
    public void init() {
        refreshToken();
    }

    // 5분마다 실행 — 만료 10분 이내면 갱신
    @Scheduled(fixedRate = 300_000)
    public void scheduleTokenRefresh() {
        long now = System.currentTimeMillis() / 1000;
        if (expireAt - now < 600) {
            log.info("[STT-RTZR] 토큰 갱신 시작");
            refreshToken();
        }
    }

    @Override
    public Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId) {
        return Flux.create(emitter -> {
            Request request = new Request.Builder()
                    .url(buildWsUrl())
                    .header("Authorization", "Bearer " + accessToken.get())
                    .build();

            WebSocket ws = okHttpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                    try {
                        JsonNode node = objectMapper.readTree(text);
                        boolean isFinal = node.path("final").asBoolean();
                        JsonNode alternatives = node.path("alternatives");
                        if (alternatives.isArray() && !alternatives.isEmpty()) {
                            String recognized = alternatives.get(0).path("text").asText();
                            log.debug("[STT-RTZR] callId={} final={} text={}", callId, isFinal, recognized);
                            emitter.next(new SttResult(recognized, isFinal));
                            if (isFinal) {
                                webSocket.close(1000, "done");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[STT-RTZR] 응답 파싱 오류 callId={}", callId, e);
                    }
                }

                @Override
                public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                    emitter.complete();
                }

                @Override
                public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t,
                                      @Nullable Response response) {
                    log.error("[STT-RTZR] WebSocket 오류 callId={}", callId, t);
                    emitter.error(t);
                }
            });

            audioStream.subscribe(
                    chunk -> ws.send(ByteString.of(chunk)),
                    error -> {
                        ws.close(1000, "error");
                        emitter.error(error);
                    },
                    () -> {
                        log.debug("[STT-RTZR] EOS 전송 callId={}", callId);
                        ws.send("EOS");
                    }
            );

            emitter.onCancel(() -> ws.close(1000, "cancelled"));
        });
    }

    private String buildWsUrl() {
        return "wss://openapi.vito.ai/v1/transcribe:streaming" +
               "?sample_rate=" + sampleRate +
               "&encoding=" + encoding +
               "&domain=CALL" +
               "&use_itn=true" +
               "&use_disfluency_filter=true" +
               "&use_profanity_filter=false" +
               "&use_punctuation=false";
    }

    private synchronized void refreshToken() {
        try {
            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                          "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

            Map<?, ?> response = webClient.post()
                    .uri("https://openapi.vito.ai/v1/authenticate")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            accessToken.set((String) response.get("access_token"));
            expireAt = ((Number) response.get("expire_at")).longValue();
            log.info("[STT-RTZR] 토큰 발급 완료 expire_at={}", expireAt);
        } catch (Exception e) {
            log.error("[STT-RTZR] 토큰 발급 실패", e);
        }
    }
}
