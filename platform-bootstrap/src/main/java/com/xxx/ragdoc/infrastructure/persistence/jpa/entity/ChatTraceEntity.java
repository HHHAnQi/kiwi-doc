package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** chat_traces 表 JPA Entity。 仅存 hash + 长度 + state_hint, 不存原 query/answer, 防 PII 沉淀。 */
@Entity
@Table(name = "chat_traces")
public class ChatTraceEntity {

    @Id
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(name = "query_len", nullable = false)
    private Integer queryLen;

    @Column(name = "answer_len")
    private Integer answerLen;

    @Column(name = "state_hint", length = 32)
    private String stateHint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * PR-1 / EMS-PR1: 真实 Evidence 快照 (JSON), 序列化的 {@link
     * com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot} 三段证据。 NULL 表示未启用 / NO_RECALL /
     * EMPTY_KB 等无证据场景。
     */
    @Column(name = "evidence_snapshot", columnDefinition = "JSON")
    private String evidenceSnapshot;

    // ===== getters / setters =====

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getQueryHash() {
        return queryHash;
    }

    public void setQueryHash(String queryHash) {
        this.queryHash = queryHash;
    }

    public Integer getQueryLen() {
        return queryLen;
    }

    public void setQueryLen(Integer queryLen) {
        this.queryLen = queryLen;
    }

    public Integer getAnswerLen() {
        return answerLen;
    }

    public void setAnswerLen(Integer answerLen) {
        this.answerLen = answerLen;
    }

    public String getStateHint() {
        return stateHint;
    }

    public void setStateHint(String stateHint) {
        this.stateHint = stateHint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getEvidenceSnapshot() {
        return evidenceSnapshot;
    }

    public void setEvidenceSnapshot(String evidenceSnapshot) {
        this.evidenceSnapshot = evidenceSnapshot;
    }
}
