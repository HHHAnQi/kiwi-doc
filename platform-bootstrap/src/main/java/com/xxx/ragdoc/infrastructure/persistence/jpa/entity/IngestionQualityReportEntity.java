package com.xxx.ragdoc.infrastructure.persistence.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ingestion_quality_reports")
public class IngestionQualityReportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "document_id", nullable = false) private Long documentId;
    @Column(nullable = false, length = 32) private String stage;
    @Column(nullable = false) private Boolean passed;
    @Column(nullable = false) private Double score;
    @Column(name = "reasons", columnDefinition = "JSON") private String reasons;
    @Column(name = "chunk_count", nullable = false) private Integer chunkCount;
    @Column(name = "embedding_count", nullable = false) private Integer embeddingCount;
    @Column(name = "redaction_count", nullable = false) private Integer redactionCount;
    @Column(name = "parser_version", nullable = false, length = 32) private String parserVersion;
    @Column(name = "chunker_version", nullable = false, length = 32) private String chunkerVersion;
    @Column(name = "embedding_version", nullable = false, length = 64) private String embeddingVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public void setDocumentId(Long v) { documentId = v; }
    public void setStage(String v) { stage = v; }
    public void setPassed(Boolean v) { passed = v; }
    public void setScore(Double v) { score = v; }
    public void setReasons(String v) { reasons = v; }
    public void setChunkCount(Integer v) { chunkCount = v; }
    public void setEmbeddingCount(Integer v) { embeddingCount = v; }
    public void setRedactionCount(Integer v) { redactionCount = v; }
    public void setParserVersion(String v) { parserVersion = v; }
    public void setChunkerVersion(String v) { chunkerVersion = v; }
    public void setEmbeddingVersion(String v) { embeddingVersion = v; }
}
