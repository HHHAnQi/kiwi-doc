package com.xxx.ragdoc.application.chat.comparison;

import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator;
import com.xxx.ragdoc.application.chat.agent.AgentRunRecord;
import com.xxx.ragdoc.application.chat.agent.AgentRunRepository;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-6c / EMS-PR6c §10: ComparisonWorkflow 把 ready-to-answer 的 Run 推到 ANSWERED (或明确失败终态)。
 *
 * <p><b>关键 CAS 竞态处理 (§10.4)</b>:
 *
 * <ol>
 *   <li>answer composer 成功完成后, 用 Coordinator.transitionRun CAS READY_TO_ANSWER → ANSWERED
 *       (reasonCode=EVIDENCE_GROUNDED_ANSWER)
 *   <li>CAS 失败需 reload run:
 *       <ul>
 *         <li>已 ANSWERED → 幂等: 不返回成功 answer, 让 caller 决策
 *         <li>已 CANCELLED / TIMED_OUT → 不返回成功 answer, 上抛或返回 FinalizeOutcome.conflict
 *         <li>其它状态 → 返回 FinalizeOutcome.conflict
 *       </ul>
 *   <li>Composer 抛异常 → 转 SYSTEM_FAILED + reasonCode=COMPARISON_ANSWER_COMPOSER_FAILED (不允许伪装成功)
 *   <li>Composer 流式 timeout → TIMED_OUT + reasonCode=COMPARISON_ANSWER_TIMEOUT (上层 Reactor timeout
 *       触发)
 *   <li>用户取消 → CANCELLED + reasonCode=USER_CANCELLED
 * </ol>
 *
 * <p>不覆盖已存在终态 (Revision §10.4.2)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonRunFinalizer {

    private final AgentRunRepository runRepository;
    private final AgentPersistenceCoordinator coordinator;

    /** 终态判别结果 (对应 ChatResult 装配的不同 stateHint + verification 行为)。 */
    public sealed interface FinalizeOutcome
            permits FinalizeOutcome.Answered,
                    FinalizeOutcome.Conflict,
                    FinalizeOutcome.ComposerFailed,
                    FinalizeOutcome.TimedOut,
                    FinalizeOutcome.Cancelled {

        /** ANSWERED CAS 成功。 */
        record Answered(long newVersion) implements FinalizeOutcome {}

        /** CAS 失败, 但 Run 已不是 READY_TO_ANSWER。 */
        record Conflict(AgentRunStatus current) implements FinalizeOutcome {}

        /** Composer 抛异常; Run 已被转 SYSTEM_FAILED。 */
        record ComposerFailed(String reason) implements FinalizeOutcome {}

        /** Timeout; Run 已被转 TIMED_OUT。 */
        record TimedOut() implements FinalizeOutcome {}

        /** 用户取消; Run 已 CANCELLED。 */
        record Cancelled() implements FinalizeOutcome {}
    }

    /**
     * 在 Comparison Composer 成功后调用 — 用 CAS READY_TO_ANSWER → ANSWERED。
     *
     * @param runId AgentRunRecord.runId()
     * @param readyVersion READY_TO_ANSWER 状态时 run.version()
     */
    public FinalizeOutcome finalizeAnswered(String runId, long readyVersion) {
        boolean ok =
                coordinator.transitionRun(
                        runId,
                        readyVersion,
                        Set.of(AgentRunStatus.READY_TO_ANSWER),
                        AgentRunStatus.ANSWERED,
                        "EVIDENCE_GROUNDED_ANSWER",
                        /* usage/reservation: passed untouched, 仍是最后 settle 后的状态 */
                        currentUsageOrZero(runId, readyVersion),
                        currentReservationOrZero(runId, readyVersion));
        if (ok) {
            return new FinalizeOutcome.Answered(readyVersion + 1);
        }
        // reload 判别
        Optional<AgentRunRecord> reloaded = runRepository.findByRunId(runId);
        if (reloaded.isEmpty()) {
            log.warn("comparison.finalize reload 找不到 run={}", runId);
            return new FinalizeOutcome.Conflict(null);
        }
        AgentRunStatus current = reloaded.get().status();
        if (current == AgentRunStatus.ANSWERED) {
            // 幂等完成 — 但当前 caller 无法区分双写; 仍返回 Conflict 让上层决策
            return new FinalizeOutcome.Conflict(AgentRunStatus.ANSWERED);
        }
        if (current == AgentRunStatus.CANCELLED) {
            return new FinalizeOutcome.Cancelled();
        }
        if (current == AgentRunStatus.TIMED_OUT) {
            return new FinalizeOutcome.TimedOut();
        }
        return new FinalizeOutcome.Conflict(current);
    }

    /** composer 抛异常时调用, 返回 SYSTEM_FAILED 转换的成功与否 (调用方据此返回结构化失败)。 */
    public boolean markComposerFailed(String runId, long readyVersion) {
        return coordinator.transitionRun(
                runId,
                readyVersion,
                Set.of(AgentRunStatus.READY_TO_ANSWER),
                AgentRunStatus.SYSTEM_FAILED,
                "COMPARISON_ANSWER_COMPOSER_FAILED",
                currentUsageOrZero(runId, readyVersion),
                currentReservationOrZero(runId, readyVersion));
    }

    boolean markTimedOut(String runId, long version, Set<AgentRunStatus> expected) {
        return coordinator.transitionRun(
                runId,
                version,
                expected,
                AgentRunStatus.TIMED_OUT,
                "COMPARISON_ANSWER_TIMEOUT",
                currentUsageOrZero(runId, version),
                currentReservationOrZero(runId, version));
    }

    boolean markCancelled(String runId, long version, Set<AgentRunStatus> expected) {
        return coordinator.transitionRun(
                runId,
                version,
                expected,
                AgentRunStatus.CANCELLED,
                "USER_CANCELLED",
                currentUsageOrZero(runId, version),
                currentReservationOrZero(runId, version));
    }

    private AgentUsage currentUsageOrZero(String runId, long version) {
        return runRepository
                .findByRunId(runId)
                .map(AgentRunRecord::usage)
                .orElse(AgentUsage.zero());
    }

    private AgentBudgetReservation currentReservationOrZero(String runId, long version) {
        return runRepository
                .findByRunId(runId)
                .map(AgentRunRecord::reservation)
                .orElse(AgentBudgetReservation.zero());
    }
}
