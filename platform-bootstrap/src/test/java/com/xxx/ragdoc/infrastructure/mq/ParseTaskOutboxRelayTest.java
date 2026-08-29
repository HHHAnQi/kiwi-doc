package com.xxx.ragdoc.infrastructure.mq;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParseTaskOutboxRelayTest {
    @Test
    void republishesDuePendingTasks() {
        ParseTaskRepository repository = mock(ParseTaskRepository.class);
        ParseTaskProducer producer = mock(ParseTaskProducer.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
        ParseTask task =
                new ParseTask(
                        7L,
                        9L,
                        "hash",
                        ParseTaskStatus.PENDING,
                        0,
                        3,
                        0,
                        0,
                        null,
                        null,
                        List.of(),
                        Instant.now(clock),
                        null,
                        Instant.now(clock),
                        Instant.now(clock));
        when(repository.claimDueForDelivery(
                        anyString(),
                        eq(Instant.now(clock)),
                        eq(Instant.now(clock).plusSeconds(30)),
                        eq(100)))
                .thenReturn(List.of(task));
        when(producer.send(eq(task), any())).thenReturn(true);
        new ParseTaskOutboxRelay(repository, producer, clock).relay();
        verify(producer).send(eq(task), any());
        verify(repository).markDeliverySucceeded(7L, Instant.now(clock));
    }

    @Test
    void backsOffWhenBrokerSendFails() {
        ParseTaskRepository repository = mock(ParseTaskRepository.class);
        ParseTaskProducer producer = mock(ParseTaskProducer.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
        ParseTask task =
                new ParseTask(
                        7L,
                        9L,
                        "hash",
                        ParseTaskStatus.PENDING,
                        0,
                        3,
                        0,
                        0,
                        null,
                        null,
                        List.of(),
                        Instant.now(clock),
                        null,
                        Instant.now(clock),
                        Instant.now(clock));
        when(repository.claimDueForDelivery(
                        anyString(),
                        eq(Instant.now(clock)),
                        eq(Instant.now(clock).plusSeconds(30)),
                        eq(100)))
                .thenReturn(List.of(task));
        when(producer.send(eq(task), any())).thenReturn(false);

        new ParseTaskOutboxRelay(repository, producer, clock).relay();

        verify(repository)
                .markDeliveryFailed(
                        7L,
                        Instant.now(clock).plusSeconds(5),
                        "relay MQ send failed",
                        Instant.now(clock),
                        8);
    }
}
