package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ChatTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatTraceJpaRepository extends JpaRepository<ChatTraceEntity, String> {

    /** feedback 软引用合法性校验(ADR-0003)。 */
    boolean existsByTraceId(String traceId);
}
