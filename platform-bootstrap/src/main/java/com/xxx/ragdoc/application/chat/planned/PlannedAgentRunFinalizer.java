package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-7c.3b / EMS-PR7 §2.2: PlannedAgentPipeline 专用的统一 Run Finalizer。
 *
 * <p>承担<b>唯一</b>的 Run 终态 CAS 责任 — Pipeline 内 AgentRunPhaseExecutor / Sufficiency / Replan /
 * Composer 都不直接写 Run 终态, 全部委托给本 Finalizer。
 *
 * <p>CAS 失败处理 (Revision §2.2):
 *
 * <ul>
 *   <li>重读 Run 当前状态
 *   <li>已 ANSWERED → 视幂等 OK (不重复覆盖)
 *   <li>已 CANCELLED / TIMED_OUT → 优先于晚到的成功 (不覆盖)
 *   <li>其它 → Conflict 给上层
 * </ul>
 *
 * <p>调用 {@link AgentPersistenceCoordinator#transitionRun} + reload。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannedAgentRunFinalizer {

    private final AgentPersistenceCoordinator coordinator;

    public FinalizeOutcome finalize(
            String runId,
            long expectedVersion,
            Set<AgentRunStatus> expectedStatuses,
            AgentRunStatus target,
            String reasonCode,
            AgentUsage usage,
            AgentBudgetReservation reservation) {
        boolean ok =
                coordinator.transitionRun(
                        runId,
                        expectedVersion,
                        expectedStatuses,
                        target,
                        reasonCode,
                        usage,
                        reservation);
        if (ok) {
            return FinalizeOutcome.written(runId, expectedVersion + 1, target);
        }
        Optional<com.xxx.ragdoc.application.chat.agent.AgentRunRecord> reloaded =
                runRepositoryReload(runId);
        if (reloaded.isEmpty()) {
            log.warn("planned.finalizer.reload_missing run={}", runId);
            return FinalizeOutcome.conflict(null);
        }
        AgentRunStatus current = reloaded.get().status();
        if (current == AgentRunStatus.ANSWERED) {
            return FinalizeOutcome.alreadyAnsweredIdempotent(runId, reloaded.get().version());
        }
        if (current == AgentRunStatus.CANCELLED || current == AgentRunStatus.TIMED_OUT) {
            return FinalizeOutcome.alreadyTerminalWinner(runId, reloaded.get().version(), current);
        }
        return FinalizeOutcome.conflict(current);
    }

    private Optional<com.xxx.ragdoc.application.chat.agent.AgentRunRecord> runRepositoryReload(
            String runId) {
        // Use coordinator.transitionRun signature; reload via wrapper on AgentRunRepository exposed
        // by coordinator
        // PR-7c.3b: delegate to coordinator (already has findByRunId indirectly via
        // PersistenceCoordinator)
        try {
            return coordinator.reloadRun(runId);
        } catch (Exception e) {
            log.warn("planned.finalizer.reload_error run={} err={}", runId, e.toString());
            return Optional.empty();
        }
    }

    /** Finalizer 结果。 */
    public record FinalizeOutcome(
            String runId,
            long newVersion,
            boolean written,
            boolean idempotent,
            AgentRunStatus effectiveTerminal,
            AgentRunStatus conflict) {

        static FinalizeOutcome written(String runId, long newVersion, AgentRunStatus terminal) {
            return new FinalizeOutcome(runId, newVersion, true, false, terminal, null);
        }

        static FinalizeOutcome alreadyAnsweredIdempotent(String runId, long version) {
            return new FinalizeOutcome(runId, version, false, true, AgentRunStatus.ANSWERED, null);
        }

        static FinalizeOutcome alreadyTerminalWinner(
                String runId, long version, AgentRunStatus winner) {
            return new FinalizeOutcome(runId, version, false, false, winner, null);
        }

        static FinalizeOutcome conflict(AgentRunStatus current) {
            return new FinalizeOutcome(null, -1, false, false, null, current);
        }
    }
}
