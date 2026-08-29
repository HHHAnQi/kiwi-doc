package com.xxx.ragdoc.application.chat.agent;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRunRecoveryJobTest {

    @Test
    void staleNonTerminalRunIsSafelyTerminatedByCas() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        Instant now = Instant.parse("2026-08-13T00:10:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AgentRunRecord run =
                new AgentRunRecord(
                        "run-1",
                        "req-1",
                        "tenant-a",
                        "user-a",
                        "PLANNED_AGENT",
                        AgentRunStatus.EXECUTING,
                        "plan-1",
                        "v1",
                        "hash",
                        "{}",
                        AgentBudget.pr6Default(),
                        AgentBudgetReservation.zero(),
                        AgentUsage.zero(),
                        List.of(),
                        0,
                        null,
                        null,
                        null,
                        null,
                        "LIVE",
                        now.minusSeconds(600),
                        now.minusSeconds(600),
                        7);
        when(repository.findStaleNonTerminal(now.minusSeconds(300), 100)).thenReturn(List.of(run));
        when(repository.transition(
                        eq("run-1"),
                        eq(7L),
                        eq(Set.of(AgentRunStatus.EXECUTING)),
                        eq(AgentRunStatus.SYSTEM_FAILED),
                        eq("RECOVERY_STALE_RUN"),
                        eq(run.usage()),
                        eq(run.reservation())))
                .thenReturn(true);

        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentCheckpointRepository checkpointRepository = mock(AgentCheckpointRepository.class);
        when(checkpointRepository.findLatest("run-1")).thenReturn(java.util.Optional.empty());
        new AgentRunRecoveryJob(repository, stepRepository, checkpointRepository, clock).recover();

        verify(repository)
                .transition(
                        "run-1",
                        7L,
                        Set.of(AgentRunStatus.EXECUTING),
                        AgentRunStatus.SYSTEM_FAILED,
                        "RECOVERY_STALE_RUN",
                        run.usage(),
                        run.reservation());
    }

    @Test
    void recoverableRunWithoutResumeExecutorIsStillSafelyTerminated() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentStepRepository steps = mock(AgentStepRepository.class);
        AgentCheckpointRepository checkpoints = mock(AgentCheckpointRepository.class);
        Instant now = Instant.parse("2026-08-13T00:10:00Z");
        AgentRunRecord run =
                new AgentRunRecord(
                        "run-2",
                        "req-2",
                        "tenant-a",
                        "user-a",
                        "PLANNED_AGENT",
                        AgentRunStatus.EXECUTING,
                        "plan-1",
                        "v1",
                        "hash",
                        "{}",
                        AgentBudget.pr6Default(),
                        AgentBudgetReservation.zero(),
                        AgentUsage.zero(),
                        List.of(),
                        0,
                        null,
                        null,
                        null,
                        null,
                        "LIVE",
                        now.minusSeconds(600),
                        now.minusSeconds(600),
                        2);
        AgentStepRecord step =
                new AgentStepRecord(
                        "run-2",
                        "step-2",
                        0,
                        "semantic_search",
                        "v1",
                        null,
                        "hash",
                        "idem",
                        true,
                        AgentStepStatus.PENDING,
                        0,
                        List.of(),
                        null,
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        now,
                        now,
                        0);
        var checkpoint =
                new AgentCheckpointRepository.Checkpoint(
                        "run-2",
                        1,
                        null,
                        AgentUsage.zero(),
                        AgentBudgetReservation.zero(),
                        List.of(),
                        now.minusSeconds(600));
        when(repository.findStaleNonTerminal(now.minusSeconds(300), 100)).thenReturn(List.of(run));
        when(steps.findByRunId("run-2")).thenReturn(List.of(step));
        when(checkpoints.findLatest("run-2")).thenReturn(java.util.Optional.of(checkpoint));

        new AgentRunRecoveryJob(repository, steps, checkpoints, Clock.fixed(now, ZoneOffset.UTC))
                .recover();

        verify(repository)
                .transition(
                        "run-2",
                        2L,
                        Set.of(AgentRunStatus.EXECUTING),
                        AgentRunStatus.SYSTEM_FAILED,
                        "RECOVERY_RESUME_NOT_WIRED",
                        run.usage(),
                        run.reservation());
    }
}
