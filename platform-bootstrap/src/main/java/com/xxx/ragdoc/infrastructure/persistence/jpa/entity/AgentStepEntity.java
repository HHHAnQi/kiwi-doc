package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** PR-6a.2: agent_step 表 JPA Entity。 */
@Entity
@Table(name = "agent_step")
public class AgentStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "step_id", nullable = false, length = 64)
    private String stepId;

    @Column(name = "step_sequence", nullable = false)
    private Integer stepSequence;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "tool_version", nullable = false, length = 32)
    private String toolVersion;

    @Column(name = "call_id", length = 64)
    private String callId;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "result_count", nullable = false)
    private Integer resultCount = 0;

    @Column(name = "evidence_ids_json", columnDefinition = "JSON")
    private String evidenceIdsJson;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "retryable", nullable = false)
    private Boolean retryable = false;

    @Column(name = "replayed", nullable = false)
    private Boolean replayed = false;

    @Column(name = "deduplicated", nullable = false)
    private Boolean deduplicated = false;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // --- getters/setters --- //

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public Integer getStepSequence() { return stepSequence; }
    public void setStepSequence(Integer stepSequence) { this.stepSequence = stepSequence; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolVersion() { return toolVersion; }
    public void setToolVersion(String toolVersion) { this.toolVersion = toolVersion; }

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }

    public String getInputHash() { return inputHash; }
    public void setInputHash(String inputHash) { this.inputHash = inputHash; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getResultCount() { return resultCount; }
    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }

    public String getEvidenceIdsJson() { return evidenceIdsJson; }
    public void setEvidenceIdsJson(String evidenceIdsJson) { this.evidenceIdsJson = evidenceIdsJson; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }

    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }

    public Boolean getDeduplicated() { return deduplicated; }
    public void setDeduplicated(Boolean deduplicated) { this.deduplicated = deduplicated; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
