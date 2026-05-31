package com.voicebot.service.tts;

/**
 * TTS(Text-to-Speech) 서비스 인터페이스.
 * 실제 구현: ClovaVoiceTtsService
 * 시뮬레이터: SimulatorTtsService
 */
public interface TtsService {

    /**
     * 텍스트를 오디오 데이터로 변환한다.
     *
     * @param text   변환할 텍스트
     * @param callId 콜 세션 ID (로깅용)
     * @return 오디오 바이트 배열 (MP3)
     */
    byte[] synthesize(String text, String callId);
}
