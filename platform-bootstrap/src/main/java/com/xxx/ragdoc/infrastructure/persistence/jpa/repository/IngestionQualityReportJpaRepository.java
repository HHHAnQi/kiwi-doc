package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.IngestionQualityReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionQualityReportJpaRepository
        extends JpaRepository<IngestionQualityReportEntity, Long> {}
