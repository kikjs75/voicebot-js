package com.voicebot.service.llm;

import com.voicebot.domain.IntentPlaybook;
import com.voicebot.repository.IntentPlaybookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@Profile("real")
@RequiredArgsConstructor
public class PlaybookService {

    private final IntentPlaybookRepository repository;

    public Optional<IntentPlaybook> findByIntent(String intent) {
        long start = System.currentTimeMillis();
        Optional<IntentPlaybook> result = repository.findByIntent(intent);
        log.info("[PLAYBOOK] intent={} hit={} elapsed={}ms",
                intent, result.isPresent(), System.currentTimeMillis() - start);
        return result;
    }
}
