package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.document.ingestion.IngestionQualityGate;
import com.xxx.ragdoc.application.document.ingestion.IngestionQualityReportPort;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.IngestionQualityReportEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.IngestionQualityReportJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JpaIngestionQualityReportAdapter implements IngestionQualityReportPort {
    private final IngestionQualityReportJpaRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long documentId,
            String stage,
            IngestionQualityGate.Report report,
            String parserVersion,
            String chunkerVersion,
            String embeddingVersion) {
        IngestionQualityReportEntity entity = new IngestionQualityReportEntity();
        entity.setDocumentId(documentId);
        entity.setStage(stage);
        entity.setPassed(report.passed());
        entity.setScore(report.score());
        try {
            entity.setReasons(objectMapper.writeValueAsString(report.reasons()));
        } catch (Exception e) {
            entity.setReasons("[]");
        }
        entity.setChunkCount(report.chunkCount());
        entity.setEmbeddingCount(report.embeddingCount());
        entity.setRedactionCount(report.redactionCount());
        entity.setParserVersion(parserVersion);
        entity.setChunkerVersion(chunkerVersion);
        entity.setEmbeddingVersion(embeddingVersion);
        repository.save(entity);
    }
}
