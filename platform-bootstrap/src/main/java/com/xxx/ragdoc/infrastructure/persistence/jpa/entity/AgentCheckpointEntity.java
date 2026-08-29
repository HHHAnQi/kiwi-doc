package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "agent_checkpoint",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_agent_checkpoint_version",
                        columnNames = {"run_id", "checkpoint_version"}))
public class AgentCheckpointEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "checkpoint_version", nullable = false)
    private Long checkpointVersion;

    @Column(name = "completed_step_id", length = 64)
    private String completedStepId;

    @Column(name = "usage_json", nullable = false, columnDefinition = "JSON")
    private String usageJson;

    @Column(name = "reservation_json", nullable = false, columnDefinition = "JSON")
    private String reservationJson;

    @Column(name = "evidence_ids_json", columnDefinition = "JSON")
    private String evidenceIdsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getCheckpointVersion() {
        return checkpointVersion;
    }

    public void setCheckpointVersion(Long value) {
        this.checkpointVersion = value;
    }

    public String getCompletedStepId() {
        return completedStepId;
    }

    public void setCompletedStepId(String value) {
        this.completedStepId = value;
    }

    public String getUsageJson() {
        return usageJson;
    }

    public void setUsageJson(String value) {
        this.usageJson = value;
    }

    public String getReservationJson() {
        return reservationJson;
    }

    public void setReservationJson(String value) {
        this.reservationJson = value;
    }

    public String getEvidenceIdsJson() {
        return evidenceIdsJson;
    }

    public void setEvidenceIdsJson(String value) {
        this.evidenceIdsJson = value;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant value) {
        this.createdAt = value;
    }
}
