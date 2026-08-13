package com.xxx.ragdoc.infrastructure.mq;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描持久化 PENDING 账本并重投 MQ，覆盖首次发送失败和 Broker 消息丢失。消费端 lease 保证幂等。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.parser", name = "mode", havingValue = "async")
public class ParseTaskOutboxRelay {
    private final ParseTaskRepository repository;
    private final ParseTaskProducer producer;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Value("${rag.parser.delivery-lease-seconds:30}")
    private long deliveryLeaseSeconds = 30;

    @org.springframework.beans.factory.annotation.Value("${rag.parser.outbox-max-attempts:8}")
    private int maxDeliveryAttempts = 8;

    @org.springframework.beans.factory.annotation.Value("${rag.parser.outbox-max-backoff-seconds:900}")
    private long maxBackoffSeconds = 900;

    @Scheduled(fixedDelayString = "${rag.parser.outbox-relay-ms:30000}")
    public void relay() {
        Instant now = Instant.now(clock);
        var due =
                repository.claimDueForDelivery(
                        relayId(), now, now.plusSeconds(deliveryLeaseSeconds), 100);
        int sent = 0;
        for (var task : due) {
            boolean success = producer.send(task, new TraceId("outbox-" + task.id()));
            if (success) {
                repository.markDeliverySucceeded(task.id(), Instant.now(clock));
                sent++;
            } else {
                now = Instant.now(clock);
                long backoff = exponentialBackoffSeconds(task.deliveryAttempts());
                repository.markDeliveryFailed(
                        task.id(), now.plusSeconds(backoff), "relay MQ send failed", now,
                        maxDeliveryAttempts);
                if (task.deliveryAttempts() + 1 >= maxDeliveryAttempts) {
                    log.error("parse_task.outbox_dead task_id={} attempts={}",
                            task.id(), task.deliveryAttempts() + 1);
                }
            }
        }
        if (!due.isEmpty()) log.info("parse_task.outbox_relay due={}, sent={}", due.size(), sent);
    }

    private long exponentialBackoffSeconds(int completedAttempts) {
        int exponent = Math.max(0, Math.min(completedAttempts, 20));
        long candidate = 5L << exponent;
        return Math.min(candidate, Math.max(5, maxBackoffSeconds));
    }

    private static String relayId() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName()
                    + ":"
                    + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "unknown:" + ProcessHandle.current().pid();
        }
    }
}
