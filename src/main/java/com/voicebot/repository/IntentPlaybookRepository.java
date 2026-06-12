package com.voicebot.repository;

import com.voicebot.domain.IntentPlaybook;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface IntentPlaybookRepository extends MongoRepository<IntentPlaybook, String> {
    Optional<IntentPlaybook> findByIntent(String intent);
}
