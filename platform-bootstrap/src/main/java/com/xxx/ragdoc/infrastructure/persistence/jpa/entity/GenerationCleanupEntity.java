package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "ingestion_generation_cleanup",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_generation_cleanup",
                        columnNames = {"document_id", "generation"}))
public class GenerationCleanupEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Integer generation;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long value) {
        documentId = value;
    }

    public Integer getGeneration() {
        return generation;
    }

    public void setGeneration(Integer value) {
        generation = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        status = value;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer value) {
        attempts = value;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant value) {
        nextAttemptAt = value;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant value) {
        leaseUntil = value;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String value) {
        lastError = value;
    }

    public void setUpdatedAt(Instant value) {
        updatedAt = value;
    }
}
