package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentCheckpointEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCheckpointJpaRepository
        extends JpaRepository<AgentCheckpointEntity, Long> {
    Optional<AgentCheckpointEntity> findFirstByRunIdOrderByCheckpointVersionDesc(String runId);
}
