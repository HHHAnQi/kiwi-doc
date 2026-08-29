package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.*;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentCheckpointEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentCheckpointJpaRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentCheckpointRepositoryImpl implements AgentCheckpointRepository {
    private final AgentCheckpointJpaRepository jpa;
    private final ObjectMapper mapper;

    @Override
    public Checkpoint save(Checkpoint c) {
        AgentCheckpointEntity e = new AgentCheckpointEntity();
        e.setRunId(c.runId());
        e.setCheckpointVersion(c.checkpointVersion());
        e.setCompletedStepId(c.completedStepId());
        e.setUsageJson(write(c.usage()));
        e.setReservationJson(write(c.reservation()));
        e.setEvidenceIdsJson(c.evidenceIds().isEmpty() ? null : write(c.evidenceIds()));
        return map(jpa.save(e));
    }

    @Override
    public Optional<Checkpoint> findLatest(String runId) {
        return jpa.findFirstByRunIdOrderByCheckpointVersionDesc(runId).map(this::map);
    }

    private Checkpoint map(AgentCheckpointEntity e) {
        try {
            List<String> ids =
                    e.getEvidenceIdsJson() == null
                            ? List.of()
                            : mapper.readValue(
                                    e.getEvidenceIdsJson(), new TypeReference<List<String>>() {});
            return new Checkpoint(
                    e.getRunId(),
                    e.getCheckpointVersion(),
                    e.getCompletedStepId(),
                    mapper.readValue(e.getUsageJson(), AgentUsage.class),
                    mapper.readValue(e.getReservationJson(), AgentBudgetReservation.class),
                    ids,
                    e.getCreatedAt());
        } catch (Exception ex) {
            throw new IllegalStateException("agent checkpoint 反序列化失败", ex);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("agent checkpoint 序列化失败", ex);
        }
    }
}
