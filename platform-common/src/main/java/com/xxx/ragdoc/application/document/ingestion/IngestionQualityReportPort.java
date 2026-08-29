package com.xxx.ragdoc.application.document.ingestion;

/** 质量报告持久化端口；失败报告同时构成脏数据隔离审计记录。 */
public interface IngestionQualityReportPort {
    void record(
            Long documentId,
            String stage,
            IngestionQualityGate.Report report,
            String parserVersion,
            String chunkerVersion,
            String embeddingVersion);
}
