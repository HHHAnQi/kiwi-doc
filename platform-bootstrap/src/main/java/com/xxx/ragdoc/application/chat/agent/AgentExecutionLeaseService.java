package com.xxx.ragdoc.application.chat.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentExecutionLeaseService {
    private final AgentRunRepository repository;
    private final Clock clock;

    public boolean claim(String runId, String ownerId, Duration ttl) {
        Instant now = Instant.now(clock);
        return repository.claimLease(runId, ownerId, now, now.plus(normalize(ttl)));
    }

    public boolean heartbeat(String runId, String ownerId, Duration ttl) {
        Instant now = Instant.now(clock);
        return repository.heartbeat(runId, ownerId, now, now.plus(normalize(ttl)));
    }

    public void release(String runId, String ownerId) {
        repository.releaseLease(runId, ownerId);
    }

    private static Duration normalize(Duration ttl) {
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(30) : ttl;
    }
}
