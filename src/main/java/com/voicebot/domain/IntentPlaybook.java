package com.voicebot.domain;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Document(collection = "intent_playbook")
public class IntentPlaybook {

    @Id
    private String id;
    private String intent;
    private String response;
    private String action;
    private boolean escalate;
    private double confidenceThreshold;
}
