package com.voicebot.service.tts;

public interface TtsService {
    byte[] synthesize(String text, String callId);
}
