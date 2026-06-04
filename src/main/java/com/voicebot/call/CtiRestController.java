package com.voicebot.call;

import com.voicebot.service.llm.LlmService;
import com.voicebot.service.tts.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/cti")
@RequiredArgsConstructor
public class CtiRestController {

    private final LlmService llmService;
    private final TtsService ttsService;

    @PostMapping("/llm/chat")
    public String chat(@RequestBody List<LlmService.Message> messages) {
        log.info("[CTI-REST] LLM 요청 messages={}", messages.size());
        return llmService.chat(messages, "CPP");
    }

    @PostMapping("/tts/synthesize")
    public byte[] synthesize(@RequestBody String text) {
        log.info("[CTI-REST] TTS 요청 text={}", text);
        return ttsService.synthesize(text, "CPP");
    }
}
