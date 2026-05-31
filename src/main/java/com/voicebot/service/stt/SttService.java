package com.voicebot.service.stt;

/**
 * STT(Speech-to-Text) 서비스 인터페이스.
 * 실제 구현: ClovaSpeechSttService
 * 시뮬레이터: SimulatorSttService
 */
public interface SttService {

    /**
     * 오디오 데이터를 텍스트로 변환한다.
     *
     * @param audioData 오디오 바이트 배열 (PCM 16kHz mono)
     * @param callId    콜 세션 ID (로깅용)
     * @return 인식된 텍스트
     */
    String recognize(byte[] audioData, String callId);
}
