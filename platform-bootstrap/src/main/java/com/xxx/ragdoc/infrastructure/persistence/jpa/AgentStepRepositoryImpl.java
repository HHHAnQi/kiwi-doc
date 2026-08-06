package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentStepRecord;
import com.xxx.ragdoc.application.chat.agent.AgentStepRepository;
import com.xxx.ragdoc.application.chat.agent.AgentStepStatus;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentStepEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentStepJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** PR-6a.2: {@link AgentStepRepository} 的 JPA 实现。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStepRepositoryImpl implements AgentStepRepository {

    private final AgentStepJpaRepository jpa;
    private final ObjectMapper mapper;

    @Override
    public AgentStepRecord create(AgentStepRecord step) {
        if (step.status() != AgentStepStatus.PENDING) {
            throw new IllegalArgumentException("新建 Step 只能是 PENDING, 实际=" + step.status());
        }
        AgentStepEntity e = new AgentStepEntity();
        e.setRunId(step.runId());
        e.setStepId(step.stepId());
        e.setStepSequence(step.stepSequence());
        e.setToolName(step.toolName());
        e.setToolVersion(step.toolVersion());
        e.setCallId(step.callId());
        e.setInputHash(step.inputHash());
        e.setStatus(step.status().name());
        e.setResultCount(step.resultCount());
        e.setEvidenceIdsJson(
                step.evidenceIds().isEmpty() ? null : toJson(step.evidenceIds(), "evidenceIds"));
        e.setVersion(0L);
        AgentStepEntity saved = jpa.save(e);
        return toRecord(saved);
    }

    @Override
    public Optional<AgentStepRecord> findByRunIdAndStepId(String runId, String stepId) {
        return jpa.findByRunIdAndStepId(runId, stepId).map(this::toRecord);
    }

    @Override
    public List<AgentStepRecord> findByRunId(String runId) {
        return jpa.findByRunIdOrderByStepSequenceAsc(runId).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public boolean transition(
            String runId,
            String stepId,
            long expectedVersion,
            Set<AgentStepStatus> expectedStatuses,
            AgentStepStatus targetStatus,
            AgentStepUpdate update) {
        int affected =
                jpa.transition(
                        runId,
                        stepId,
                        expectedVersion,
                        expectedStatuses.stream().map(AgentStepStatus::name).collect(Collectors.toSet()),
                        targetStatus.name(),
                        update.callId(),
                        update.resultCount(),
                        update.evidenceIds() == null || update.evidenceIds().isEmpty()
                                ? null
                                : toJson(update.evidenceIds(), "evidenceIds"),
                        update.latencyMs(),
                        update.errorCode(),
                        update.retryable(),
                        update.replayed(),
                        update.deduplicated(),
                        update.startedAt(),
                        update.completedAt());
        log.debug("agent_step.transition run_id={} step_id={} affected={} target={}", runId, stepId, affected, targetStatus);
        return affected == 1;
    }

    // ─── mapping ────────────────────────────────────────────

    AgentStepRecord toRecord(AgentStepEntity e) {
        AgentStepStatus status;
        try {
            status = AgentStepStatus.valueOf(e.getStatus());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "agent_step 未知状态 (fail-closed): " + e.getStatus() + " run_id=" + e.getRunId()
                            + " step_id=" + e.getStepId(), ex);
        }
        return new AgentStepRecord(
                e.getRunId(),
                e.getStepId(),
                e.getStepSequence(),
                e.getToolName(),
                e.getToolVersion(),
                e.getCallId(),
                e.getInputHash(),
                status,
                e.getResultCount() == null ? 0 : e.getResultCount(),
                e.getEvidenceIdsJson() == null ? List.of() : fromJsonList(e.getEvidenceIdsJson()),
                e.getLatencyMs(),
                e.getErrorCode(),
                e.getRetryable() != null && e.getRetryable(),
                e.getReplayed() != null && e.getReplayed(),
                e.getDeduplicated() != null && e.getDeduplicated(),
                e.getStartedAt(),
                e.getCompletedAt(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion() == null ? 0 : e.getVersion());
    }

    private String toJson(Object obj, String fieldName) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent_step JSON 序列化失败: " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String json) {
        try {
            return mapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent_step evidence_ids 反序列化失败", e);
        }
    }
}
