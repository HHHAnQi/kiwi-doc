package com.xxx.ragdoc.application.chat.agent;

import java.time.Instant;
import java.util.List;

/** PR-6a.2: agent_step 表的应用层映射 record。 */
public record AgentStepRecord(
        String runId,
        String stepId,
        int stepSequence,
        String toolName,
        String toolVersion,
        String callId,
        String inputHash,
        AgentStepStatus status,
        int resultCount,
        List<String> evidenceIds,
        Long latencyMs,
        String errorCode,
        boolean retryable,
        boolean replayed,
        boolean deduplicated,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public AgentStepRecord {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName");
        if (status == null) status = AgentStepStatus.PENDING;
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
