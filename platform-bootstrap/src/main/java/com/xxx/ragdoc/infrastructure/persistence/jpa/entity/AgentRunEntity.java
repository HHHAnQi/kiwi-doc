package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * PR-6a.2: agent_run 表 JPA Entity。
 *
 * <p>JSON 字段以 String 存储 (与 ChatTraceEntity.evidenceSnapshot 同风格); 反序列化由 {@code
 * AgentRunRepositoryImpl} 用 ObjectMapper 完成。
 *
 * <p>乐观锁: 手动 CAS {@code @Modifying @Query}, 不用 {@code @Version} annotation。
 */
@Entity
@Table(name = "agent_run")
public class AgentRunEntity {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "strategy", nullable = false, length = 32)
    private String strategy;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    @Column(name = "plan_version", nullable = false, length = 32)
    private String planVersion;

    @Column(name = "plan_hash", nullable = false, length = 64)
    private String planHash;

    @Column(name = "plan_json", nullable = false, columnDefinition = "JSON")
    private String planJson;

    @Column(name = "budget_json", nullable = false, columnDefinition = "JSON")
    private String budgetJson;

    @Column(name = "reservation_json", nullable = false, columnDefinition = "JSON")
    private String reservationJson;

    @Column(name = "usage_json", nullable = false, columnDefinition = "JSON")
    private String usageJson;

    @Column(name = "evidence_ids_json", columnDefinition = "JSON")
    private String evidenceIdsJson;

    @Column(name = "evidence_count", nullable = false)
    private Integer evidenceCount = 0;

    @Column(name = "terminal_reason_code", length = 64)
    private String terminalReasonCode;

    /** P2-D5(A): 过程决策摘要 — 写入后不被终态覆盖(语义见 V24 迁移注释)。 */
    @Column(name = "decision_summary", length = 64)
    private String decisionSummary;

    @Column(name = "router_version", length = 64)
    private String routerVersion;

    @Column(name = "toolset_version", length = 64)
    private String toolsetVersion;

    @Column(name = "index_version", length = 64)
    private String indexVersion;

    @Column(name = "harness_mode", nullable = false, length = 16)
    private String harnessMode;

    @Column(name = "owner_id", length = 96)
    private String ownerId;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "resume_count", nullable = false)
    private Integer resumeCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // --- getters/setters --- //

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(String planVersion) {
        this.planVersion = planVersion;
    }

    public String getPlanHash() {
        return planHash;
    }

    public void setPlanHash(String planHash) {
        this.planHash = planHash;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public String getBudgetJson() {
        return budgetJson;
    }

    public void setBudgetJson(String budgetJson) {
        this.budgetJson = budgetJson;
    }

    public String getReservationJson() {
        return reservationJson;
    }

    public void setReservationJson(String reservationJson) {
        this.reservationJson = reservationJson;
    }

    public String getUsageJson() {
        return usageJson;
    }

    public void setUsageJson(String usageJson) {
        this.usageJson = usageJson;
    }

    public String getEvidenceIdsJson() {
        return evidenceIdsJson;
    }

    public void setEvidenceIdsJson(String evidenceIdsJson) {
        this.evidenceIdsJson = evidenceIdsJson;
    }

    public Integer getEvidenceCount() {
        return evidenceCount;
    }

    public void setEvidenceCount(Integer evidenceCount) {
        this.evidenceCount = evidenceCount;
    }

    public String getTerminalReasonCode() {
        return terminalReasonCode;
    }

    public void setTerminalReasonCode(String terminalReasonCode) {
        this.terminalReasonCode = terminalReasonCode;
    }

    public String getDecisionSummary() {
        return decisionSummary;
    }

    public void setDecisionSummary(String decisionSummary) {
        this.decisionSummary = decisionSummary;
    }

    public String getRouterVersion() {
        return routerVersion;
    }

    public void setRouterVersion(String routerVersion) {
        this.routerVersion = routerVersion;
    }

    public String getToolsetVersion() {
        return toolsetVersion;
    }

    public void setToolsetVersion(String toolsetVersion) {
        this.toolsetVersion = toolsetVersion;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(String indexVersion) {
        this.indexVersion = indexVersion;
    }

    public String getHarnessMode() {
        return harnessMode;
    }

    public void setHarnessMode(String harnessMode) {
        this.harnessMode = harnessMode;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Integer getResumeCount() {
        return resumeCount;
    }

    public void setResumeCount(Integer resumeCount) {
        this.resumeCount = resumeCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
