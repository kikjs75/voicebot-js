package com.voicebot.service.tts;

import com.google.auth.oauth2.GoogleCredentials;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@Profile("real-google")
@RequiredArgsConstructor
public class GoogleCloudTtsService implements TtsService {

    private static final String TTS_ENDPOINT =
            "https://texttospeech.googleapis.com/v1/text:synthesize";
    private static final String SCOPE =
            "https://www.googleapis.com/auth/cloud-platform";

    @Value("${voicebot.tts.google.credentials-path}")
    private String credentialsPath;

    @Value("${voicebot.tts.google.language-code:ko-KR}")
    private String languageCode;

    @Value("${voicebot.tts.google.voice-name:ko-KR-Neural2-A}")
    private String voiceName;

    @Value("${voicebot.tts.google.audio-encoding:LINEAR16}")
    private String audioEncoding;

    @Value("${voicebot.tts.google.sample-rate-hertz:8000}")
    private int sampleRateHertz;

    private final WebClient webClient;
    private GoogleCredentials credentials;

    @PostConstruct
    public void init() {
        try {
            credentials = GoogleCredentials
                    .fromStream(new FileInputStream(credentialsPath))
                    .createScoped(SCOPE);
            log.info("[TTS-GOOGLE] 서비스 계정 자격증명 로드 완료: {}", credentialsPath);
        } catch (IOException e) {
            throw new RuntimeException("[TTS-GOOGLE] 서비스 계정 JSON 키 로드 실패: " + credentialsPath, e);
        }
    }

    @Override
    public byte[] synthesize(String text, String callId) {
        log.debug("[TTS-GOOGLE] callId={} text={}", callId, text);

        String token = getAccessToken();

        Map<String, Object> requestBody = Map.of(
                "input", Map.of("text", text),
                "voice", Map.of(
                        "languageCode", languageCode,
                        "name", voiceName
                ),
                "audioConfig", Map.of(
                        "audioEncoding", audioEncoding,
                        "sampleRateHertz", sampleRateHertz
                )
        );

        Map<?, ?> response = webClient.post()
                .uri(TTS_ENDPOINT)
                .header("Authorization", "Bearer " + token)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        String audioContent = (String) response.get("audioContent");
        return Base64.getDecoder().decode(audioContent);
    }

    // 만료 체크 후 자동 갱신 (1시간 주기) — 별도 스케줄러 불필요
    private String getAccessToken() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new RuntimeException("[TTS-GOOGLE] Access Token 발급 실패", e);
        }
    }
}
