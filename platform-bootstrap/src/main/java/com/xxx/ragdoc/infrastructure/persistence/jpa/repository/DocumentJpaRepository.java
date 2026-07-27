package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, Long> {

    /**
     * 按内容 hash 查 V1 幂等查询。tenant_id 字段固定 default,V4 多租户化后扩展。
     */
    Optional<DocumentEntity> findByContentHashAndTenantId(String contentHash, String tenantId);
}
