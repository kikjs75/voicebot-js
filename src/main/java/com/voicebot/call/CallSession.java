package com.voicebot.call;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallSession implements Serializable {

    private String callId;

    @Builder.Default
    private List<Map<String, String>> messages = new ArrayList<>();

    private String state;
    private long startedAt;
}
