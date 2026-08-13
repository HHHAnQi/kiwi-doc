package com.xxx.ragdoc.parser.infrastructure;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VisibilityTimeoutSchedulerTest {

    @Test
    void reaperUsesTheSameUtcInstantForAtomicExecutionAndDeliveryRecovery() {
        ParseTaskRepository repository = Mockito.mock(ParseTaskRepository.class);
        Instant now = Instant.parse("2026-08-13T08:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        when(repository.reapExpiredRunning(now)).thenReturn(1);

        new VisibilityTimeoutScheduler(repository, clock).reapExpiredRunningTasks();

        verify(repository).reapExpiredRunning(now);
    }
}
