package com.voicebot.service.stt;

import reactor.core.publisher.Flux;

public interface SttService {

    // 오디오 스트림 → 인식 결과 스트림 (final:true 가 확정 텍스트)
    Flux<SttResult> recognize(Flux<byte[]> audioStream, String callId);

    record SttResult(String text, boolean isFinal) {}
}
