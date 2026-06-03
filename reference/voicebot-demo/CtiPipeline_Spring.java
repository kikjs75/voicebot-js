// ============================================================
// 1. WebSocketConfig.java
// ============================================================
package com.example.cti.config;

import com.example.cti.handler.CtiWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CtiWebSocketHandler ctiWebSocketHandler;

    public WebSocketConfig(CtiWebSocketHandler ctiWebSocketHandler) {
        this.ctiWebSocketHandler = ctiWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(ctiWebSocketHandler, "/ws/cti")
                .setAllowedOrigins("*");   // 개발용: 운영에서는 도메인 제한
    }
}


// ============================================================
// 2. CtiWebSocketHandler.java  — 핵심 오케스트레이터
// ============================================================
package com.example.cti.handler;

import com.example.cti.service.LlmService;
import com.example.cti.service.SttService;
import com.example.cti.service.TtsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class CtiWebSocketHandler extends AbstractWebSocketHandler {

    private final SttService sttService;
    private final LlmService llmService;
    private final TtsService ttsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 세션별 STT 스트림 관리
    private final Map<String, SttService.SttStream> sttStreams = new ConcurrentHashMap<>();

    public CtiWebSocketHandler(SttService sttService,
                                LlmService llmService,
                                TtsService ttsService) {
        this.sttService = sttService;
        this.llmService = llmService;
        this.ttsService = ttsService;
    }

    // ── 연결 수립
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[CTI] 연결됨: sessionId={}", session.getId());
        // STT 스트림 초기화
        SttService.SttStream stream = sttService.createStream(
            session,
            finalText -> handleFinalStt(session, finalText)  // 콜백
        );
        sttStreams.put(session.getId(), stream);
    }

    // ── 텍스트 메시지 (CTI 이벤트 JSON)
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> event = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) event.get("type");

        log.info("[CTI] 이벤트 수신: type={}, sessionId={}", type, session.getId());

        switch (type) {
            case "CTI_EVENT" -> handleCtiEvent(session, event);
            default -> log.warn("[CTI] 알 수 없는 이벤트: {}", type);
        }
    }

    // ── 바이너리 메시지 (음성 청크)
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        byte[] audioChunk = message.getPayload().array();
        log.debug("[CTI] 음성 청크 수신: {}bytes", audioChunk.length);

        SttService.SttStream stream = sttStreams.get(session.getId());
        if (stream != null) {
            stream.sendChunk(audioChunk);
        }
    }

    // ── 연결 종료
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[CTI] 연결 종료: sessionId={}, status={}", session.getId(), status);
        SttService.SttStream stream = sttStreams.remove(session.getId());
        if (stream != null) stream.close();
    }

    // ── CTI 이벤트 처리
    private void handleCtiEvent(WebSocketSession session, Map<String, Object> event) {
        String ctiEvent = (String) event.get("event");
        log.info("[CTI] 통화 이벤트: {}", ctiEvent);
        // CALL_START, CALL_END, HOLD, TRANSFER 등 처리
    }

    // ── STT 최종 결과 → LLM → TTS
    private void handleFinalStt(WebSocketSession session, String finalText) {
        try {
            log.info("[STT→LLM] 최종 텍스트: {}", finalText);

            // 1. 클라이언트에 STT 결과 전송
            sendJson(session, Map.of("type", "STT_FINAL", "text", finalText));

            // 2. LLM 분석
            LlmService.LlmResult result = llmService.analyze(finalText);
            log.info("[LLM] intent={}, response={}", result.intent(), result.response());
            sendJson(session, Map.of(
                "type", "LLM_RESULT",
                "intent", result.intent(),
                "response", result.response()
            ));

            // 3. TTS 변환 후 텍스트 전송
            ttsService.synthesize(result.response(), audioBytes -> {
                // 음성 바이트 → 미디어 서버로 전송 (별도 처리)
                log.info("[TTS] 음성 생성 완료: {}bytes", audioBytes.length);
            });

            // 4. 화면에 텍스트 출력
            sendJson(session, Map.of("type", "TTS_TEXT", "text", result.response()));

        } catch (Exception e) {
            log.error("[PIPELINE] 처리 오류", e);
        }
    }

    private void sendJson(WebSocketSession session, Object data) throws Exception {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        }
    }
}


// ============================================================
// 3. SttService.java  — Google STT 스트리밍 (인터페이스)
// ============================================================
package com.example.cti.service;

import com.google.cloud.speech.v1.*;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.function.Consumer;

@Slf4j
@Service
public class SttService {

    public SttStream createStream(WebSocketSession session, Consumer<String> onFinalResult) {
        return new SttStream(session, onFinalResult);
    }

    public static class SttStream {
        private final Consumer<String> onFinalResult;
        private final SpeechClient speechClient;
        private ClientStream<StreamingRecognizeRequest> clientStream;
        private final StringBuilder interimBuffer = new StringBuilder();

        public SttStream(WebSocketSession session, Consumer<String> onFinalResult) {
            this.onFinalResult = onFinalResult;
            try {
                this.speechClient = SpeechClient.create();
                initStream();
            } catch (Exception e) {
                throw new RuntimeException("STT 초기화 실패", e);
            }
        }

        private void initStream() {
            // 스트리밍 인식 설정
            RecognitionConfig recognitionConfig = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.WEBM_OPUS)  // 브라우저 마이크
                // .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16) // WAV 파일
                .setSampleRateHertz(16000)
                .setLanguageCode("ko-KR")  // 한국어
                .build();

            StreamingRecognitionConfig streamingConfig = StreamingRecognitionConfig.newBuilder()
                .setConfig(recognitionConfig)
                .setInterimResults(true)  // 중간 결과도 받기
                .build();

            ResponseObserver<StreamingRecognizeResponse> observer =
                new ResponseObserver<>() {
                    @Override
                    public void onResponse(StreamingRecognizeResponse response) {
                        for (StreamingRecognitionResult result : response.getResultsList()) {
                            String transcript = result.getAlternatives(0).getTranscript();

                            if (result.getIsFinal()) {
                                log.info("[STT] 최종: {}", transcript);
                                onFinalResult.accept(transcript);
                            } else {
                                log.debug("[STT] 중간: {}", transcript);
                                // 필요시 interim 결과도 WebSocket으로 전송 가능
                            }
                        }
                    }
                    @Override public void onComplete() {}
                    @Override public void onError(Throwable t) {
                        log.error("[STT] 오류", t);
                    }
                };

            clientStream = speechClient.streamingRecognizeCallable().splitCall(observer);

            // 설정 첫 요청 전송
            clientStream.send(
                StreamingRecognizeRequest.newBuilder()
                    .setStreamingConfig(streamingConfig)
                    .build()
            );
        }

        public void sendChunk(byte[] audioChunk) {
            if (clientStream != null) {
                clientStream.send(
                    StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(audioChunk))
                        .build()
                );
            }
        }

        public void close() {
            if (clientStream != null) clientStream.closeSend();
            if (speechClient != null) speechClient.close();
        }
    }
}


// ============================================================
// 4. LlmService.java  — Claude API 호출
// ============================================================
package com.example.cti.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class LlmService {

    @Value("${anthropic.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    private static final String SYSTEM_PROMPT = """
        너는 콜센터 AI 상담사야. 고객 발화를 분석하고 아래 JSON 형식으로만 응답해:
        {
          "intent": "환불|문의|불만|상담|기타 중 하나",
          "response": "고객에게 할 친절한 응답 메시지"
        }
        JSON 외에 다른 텍스트는 절대 포함하지 마.
        """;

    public LlmResult analyze(String userText) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> body = Map.of(
                "model", "claude-sonnet-4-20250514",
                "max_tokens", 512,
                "system", SYSTEM_PROMPT,
                "messages", new Object[]{
                    Map.of("role", "user", "content", userText)
                }
            );

            ResponseEntity<String> response = restTemplate.exchange(
                CLAUDE_URL, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("content").get(0).path("text").asText();

            // JSON 파싱
            JsonNode result = objectMapper.readTree(content);
            return new LlmResult(
                result.path("intent").asText("기타"),
                result.path("response").asText("잠시 후 다시 말씀해 주세요.")
            );

        } catch (Exception e) {
            log.error("[LLM] 호출 오류", e);
            return new LlmResult("오류", "죄송합니다. 잠시 후 다시 말씀해 주세요.");
        }
    }

    public record LlmResult(String intent, String response) {}
}


// ============================================================
// 5. TtsService.java  — Clova Voice TTS
// ============================================================
package com.example.cti.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.function.Consumer;

@Slf4j
@Service
public class TtsService {

    @Value("${naver.clova.client-id}")
    private String clientId;

    @Value("${naver.clova.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String CLOVA_TTS_URL =
        "https://naveropenapi.apigw.ntruss.com/tts-premium/v1/tts";

    public void synthesize(String text, Consumer<byte[]> onComplete) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("X-NCP-APIGW-API-KEY-ID", clientId);
            headers.set("X-NCP-APIGW-API-KEY", clientSecret);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("speaker", "nara");      // 화자: nara (여성), vara (남성)
            params.add("volume", "0");           // 볼륨 (-5 ~ 5)
            params.add("speed", "0");            // 속도 (-5 ~ 5)
            params.add("pitch", "0");            // 높낮이 (-5 ~ 5)
            params.add("format", "wav");
            params.add("text", text);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                CLOVA_TTS_URL, HttpMethod.POST,
                new HttpEntity<>(params, headers), byte[].class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                log.info("[TTS] 생성 완료: {}bytes", response.getBody().length);
                onComplete.accept(response.getBody());
            }

        } catch (Exception e) {
            log.error("[TTS] 오류", e);
        }
    }
}


// ============================================================
// application.yml 설정
// ============================================================
/*
spring:
  application:
    name: cti-ai-pipeline

server:
  port: 8080

anthropic:
  api:
    key: ${ANTHROPIC_API_KEY}

naver:
  clova:
    client-id: ${NAVER_CLIENT_ID}
    client-secret: ${NAVER_CLIENT_SECRET}

google:
  cloud:
    credentials:
      location: classpath:google-credentials.json

logging:
  level:
    com.example.cti: DEBUG
*/


// ============================================================
// build.gradle 의존성
// ============================================================
/*
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-websocket'
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // Google STT
    implementation 'com.google.cloud:google-cloud-speech:4.0.0'

    // JSON
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
*/
