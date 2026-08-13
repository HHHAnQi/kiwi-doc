package com.xxx.ragdoc.application.chat.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentCheckpointRepository {
    record Checkpoint(
            String runId,
            long checkpointVersion,
            String completedStepId,
            AgentUsage usage,
            AgentBudgetReservation reservation,
            List<String> evidenceIds,
            Instant createdAt) {}

    Checkpoint save(Checkpoint checkpoint);

    Optional<Checkpoint> findLatest(String runId);
}
