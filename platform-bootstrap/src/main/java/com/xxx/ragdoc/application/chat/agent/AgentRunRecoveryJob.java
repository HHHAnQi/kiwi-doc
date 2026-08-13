package com.xxx.ragdoc.application.chat.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 重启恢复守护。第一阶段选择安全终止而非自动续跑：远程工具是否完成无法仅凭 Run/Step
 * 状态可靠判断，盲目续跑可能造成重复副作用。进入 SYSTEM_FAILED 后可按 runId 审计并由上层重试新 Run。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "rag.agent.recovery", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AgentRunRecoveryJob {

    private final AgentRunRepository repository;
    private final AgentStepRepository stepRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Value("${rag.agent.recovery.stale-seconds:300}")
    private long staleSeconds = 300;

    @org.springframework.beans.factory.annotation.Value("${rag.agent.recovery.batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelayString = "${rag.agent.recovery.scan-ms:60000}")
    public void recover() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofSeconds(Math.max(30, staleSeconds)));
        var stale = repository.findStaleNonTerminal(cutoff, batchSize);
        int recovered = 0;
        for (AgentRunRecord run : stale) {
            var checkpoint = checkpointRepository.findLatest(run.runId());
            boolean resumable = checkpoint.isPresent()
                    && stepRepository.findByRunId(run.runId()).stream()
                            .filter(s -> !s.status().isTerminal())
                            .allMatch(AgentStepRecord::recoverable);
            if (resumable) {
                // 当前只落地恢复资格与 checkpoint 底座；真正恢复执行器尚未接线。
                // 不能让候选永久悬挂，仍安全终止，但使用独立 reason 供后续重放/审计。
                log.warn("agent.recovery.resume_candidate run={} checkpoint={}",
                        run.runId(), checkpoint.get().checkpointVersion());
            }
            boolean won = repository.transition(
                    run.runId(), run.version(), Set.of(run.status()), AgentRunStatus.SYSTEM_FAILED,
                    resumable ? "RECOVERY_RESUME_NOT_WIRED" : "RECOVERY_STALE_RUN",
                    run.usage(), run.reservation());
            if (won) {
                recovered++;
                log.warn("agent.recovery.stale_run_terminated run={} status={} updated_at={}",
                        run.runId(), run.status(), run.updatedAt());
            }
        }
        if (recovered > 0) log.warn("agent.recovery.completed recovered={}", recovered);
    }
}
