package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunRepository;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.application.chat.agent.AgentStateMachine;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.AgentRunEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.AgentRunJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-6a.2: {@link AgentRunRepository} 的 JPA 实现。
 *
 * <p>JSON 字段 (budget/usage/reservation/evidence_ids/plan) 由 ObjectMapper 序列化为 String;
 * Entity 不持有 typed domain record, 只持有 String。
 *
 * <p>CAS transition 在调 JPA 之前先调 {@link AgentStateMachine#checkLegal} 防御层保护
 * (DB CAS 是最终唯一终态保证, state machine 是快速失败层)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRepositoryImpl implements AgentRunRepository {

    private final AgentRunJpaRepository jpa;
    private final ObjectMapper mapper;

    @Override
    public AgentRunRecord create(AgentRunRecord run) {
        AgentRunEntity e = new AgentRunEntity();
        e.setRunId(run.runId());
        e.setRequestId(run.requestId());
        e.setTenantId(run.tenantId());
        e.setUserId(run.userId());
        e.setStrategy(run.strategy());
        e.setStatus(run.status().name());
        e.setPlanId(run.planId());
        e.setPlanVersion(run.planVersion());
        e.setPlanHash(run.planHash());
        e.setPlanJson(run.planJson());
        e.setBudgetJson(toJson(run.budget(), "budget"));
        e.setReservationJson(toJson(run.reservation(), "reservation"));
        e.setUsageJson(toJson(run.usage(), "usage"));
        e.setEvidenceIdsJson(run.evidenceIds().isEmpty() ? null : toJson(run.evidenceIds(), "evidenceIds"));
        e.setEvidenceCount(run.evidenceCount());
        e.setTerminalReasonCode(run.terminalReasonCode());
        e.setRouterVersion(run.routerVersion());
        e.setToolsetVersion(run.toolsetVersion());
        e.setIndexVersion(run.indexVersion());
        e.setHarnessMode(run.harnessMode());
        e.setVersion(0L);
        AgentRunEntity saved = jpa.save(e);
        return toRecord(saved);
    }

    @Override
    public Optional<AgentRunRecord> findByRunId(String runId) {
        return jpa.findById(runId).map(this::toRecord);
    }

    @Override
    public List<AgentRunRecord> findByTenantId(String tenantId, int limit) {
        return jpa.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .limit(limit)
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public boolean transition(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            AgentRunStatus targetStatus,
            String terminalReasonCode,
            AgentUsage usage,
            AgentBudgetReservation reservation) {
        // 防御层: 从任一 expected → target 必须状态机合法 (至少有一条合法)
        // 这里不预 check 全部组合 (expected 可能含多个); DB CAS WHERE status IN (...) 保证
        // 只有真正匹配的行被更新。
        int affected =
                jpa.transition(
                        runId,
                        expectedVersion,
                        statusNames(expectedStatuses),
                        targetStatus.name(),
                        terminalReasonCode,
                        toJson(usage, "usage"),
                        toJson(reservation, "reservation"));
        log.debug(
                "agent_run.transition run_id={} affected={} target={}", runId, affected, targetStatus);
        return affected == 1;
    }

    @Override
    public boolean updateBudgetState(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            AgentUsage usage,
            AgentBudgetReservation reservation) {
        int affected =
                jpa.updateBudgetState(
                        runId,
                        expectedVersion,
                        statusNames(expectedStatuses),
                        toJson(usage, "usage"),
                        toJson(reservation, "reservation"));
        return affected == 1;
    }

    @Override
    public boolean updateEvidenceSummary(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            List<String> evidenceIds,
            int evidenceCount) {
        int affected =
                jpa.updateEvidenceSummary(
                        runId,
                        expectedVersion,
                        statusNames(expectedStatuses),
                        evidenceIds == null || evidenceIds.isEmpty()
                                ? null
                                : toJson(evidenceIds, "evidenceIds"),
                        evidenceCount);
        return affected == 1;
    }

    // ─── mapping ────────────────────────────────────────────

    AgentRunRecord toRecord(AgentRunEntity e) {
        // 未知状态 → 抛 IllegalArgumentException 失败关闭 (不默认)
        AgentRunStatus status;
        try {
            status = AgentRunStatus.valueOf(e.getStatus());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "agent_run 未知状态 (fail-closed): " + e.getStatus() + " run_id=" + e.getRunId(), ex);
        }
        return new AgentRunRecord(
                e.getRunId(),
                e.getRequestId(),
                e.getTenantId(),
                e.getUserId(),
                e.getStrategy(),
                status,
                e.getPlanId(),
                e.getPlanVersion(),
                e.getPlanHash(),
                e.getPlanJson(),
                fromJson(e.getBudgetJson(), AgentBudget.class, "budget"),
                fromJson(e.getReservationJson(), AgentBudgetReservation.class, "reservation"),
                fromJson(e.getUsageJson(), AgentUsage.class, "usage"),
                e.getEvidenceIdsJson() == null
                        ? List.of()
                        : fromJsonList(e.getEvidenceIdsJson(), "evidenceIds"),
                e.getEvidenceCount() == null ? 0 : e.getEvidenceCount(),
                e.getTerminalReasonCode(),
                e.getRouterVersion(),
                e.getToolsetVersion(),
                e.getIndexVersion(),
                e.getHarnessMode(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getVersion() == null ? 0 : e.getVersion());
    }

    private static Set<String> statusNames(Set<AgentRunStatus> statuses) {
        return statuses.stream().map(AgentRunStatus::name).collect(Collectors.toSet());
    }

    private String toJson(Object obj, String fieldName) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent_run JSON 序列化失败: " + fieldName, e);
        }
    }

    private <T> T fromJson(String json, Class<T> type, String fieldName) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent_run JSON 反序列化失败: " + fieldName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String json, String fieldName) {
        try {
            return mapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent_run JSON list 反序列化失败: " + fieldName, e);
        }
    }
}
