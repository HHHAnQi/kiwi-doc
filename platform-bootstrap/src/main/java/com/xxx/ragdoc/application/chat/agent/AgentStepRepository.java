package com.xxx.ragdoc.application.chat.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * PR-6a.2: agent_step 持久化 Port。
 *
 * <p>实现: {@code AgentStepRepositoryImpl} (infrastructure/persistence/jpa)。
 */
public interface AgentStepRepository {

    /** 创建新 Step。只接受 status=PENDING + version=0。重复 → DataIntegrityViolationException。 */
    AgentStepRecord create(AgentStepRecord step);

    Optional<AgentStepRecord> findByRunIdAndStepId(String runId, String stepId);

    /** 按 step_sequence ASC 排序。 */
    List<AgentStepRecord> findByRunId(String runId);

    /**
     * CAS Step 状态转换 + 字段更新。
     *
     * @param update 可选字段更新 (null 表示不更新对应字段)
     * @return true=成功 / false=冲突
     */
    boolean transition(
            String runId,
            String stepId,
            long expectedVersion,
            Set<AgentStepStatus> expectedStatuses,
            AgentStepStatus targetStatus,
            AgentStepUpdate update);

    /**
     * PR-7c.3c-2: 在同一 Run 内追加 Replan Steps (批量原子)。
     *
     * <p>每个 Step 的 status 必须 PENDING + version=0; step_sequence 必须在 Run 内全局唯一 (UNIQUE约束)。
     *
     * <p>实现应在同一短事务中完成全部 INSERT; 任一 UNIQUE 冲突或 FK 不存在 → 整体回滚 + 抛异常。
     */
    void appendAll(String runId, List<AgentStepRecord> steps);

    /** PR-6a.2: Step CAS UPDATE 时携带的可选字段更新。 */
    record AgentStepUpdate(
            String callId,
            int resultCount,
            List<String> evidenceIds,
            Long latencyMs,
            String errorCode,
            boolean retryable,
            boolean replayed,
            boolean deduplicated,
            Instant startedAt,
            Instant completedAt) {

        public AgentStepUpdate {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        public static AgentStepUpdate empty() {
            return new AgentStepUpdate(
                    null, 0, List.of(), null, null, false, false, false, null, null);
        }
    }
}
