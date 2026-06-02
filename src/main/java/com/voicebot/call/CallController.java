package com.voicebot.call;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/call")
@RequiredArgsConstructor
public class CallController {

    private final CallHandler callHandler;

    @PostMapping(
            value = "/incoming",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = "audio/wav"
    )
    public ResponseEntity<byte[]> incoming(
            @RequestHeader("X-Call-Id") String callId,
            @RequestBody byte[] audioData
    ) {
        log.debug("[CALL] incoming callId={} audioSize={}bytes", callId, audioData.length);
        byte[] responseAudio = callHandler.process(audioData, callId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(responseAudio);
    }
}
