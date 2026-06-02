package com.voicebot.repository;

import com.voicebot.domain.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {
    List<CallRecord> findByCallId(String callId);
}
