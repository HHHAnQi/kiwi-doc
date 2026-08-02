package com.xxx.ragdoc.parser.infrastructure;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.infrastructure.mq.ParseTaskSubmitMessage;
import com.xxx.ragdoc.parser.application.ParseTaskService;
import com.xxx.ragdoc.parser.application.ParseWorker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 消费端(spec §4.1 step 6-12 + spec §8 Commit 2-3).
 *
 * <p>拿到 {@code parse-task-submit} 消息: lease 抢占 spec step 6 → 调 ParseWorker 跑 step 7-11 → 根据 success
 * / fail 调 ParseTaskService 迁终态。
 *
 * <p>关键设计(spec §3.3 + ADR-0009 D1):
 *
 * <ol>
 *   <li>topic / consumer-group 由 spec §2.2 契约定, 不允许漂移
 *   <li>ConsumeMode.CONCURRENTLY 允许多实例并行消费(单任务级幂等由 leaseNextPending 行锁保证)
 *   <li>本类抛异常 → RocketMQ 自动按 maxReconsumeTimes 重投, 但 task 状态已 FAILED/CANCELLED, 重投时
 *       findByContentHash + status check 会跳过已终态 — 不会重复跑
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "parse-task-submit",
        consumerGroup = "${rocketmq.consumer.group:parse-task-consumer-group}",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING)
public class ParseTaskConsumer implements RocketMQListener<ParseTaskSubmitMessage> {

    /** RUNNING lease 时长: 心跳 job 据此回收 zombie worker. 5 分钟覆盖最慢单文档解析(实测 2-3 min)。 */
    @Value("${rag.parser.lease-duration-minutes:5}")
    private long leaseDurationMinutes;

    private final ParseTaskRepository parseTaskRepository;
    private final DocumentRepository documentRepository;
    private final ParseWorker worker;
    private final ParseTaskService parseTaskService;
    private final Clock clock;

    @Override
    public void onMessage(ParseTaskSubmitMessage message) {
        Instant now = Instant.now(clock);
        String leasedBy = hostnamePid();

        // 幂等防线 1: 消息可能是 RocketMQ redelivery, 查最新 task 状态决定是否继续
        ParseTask latest =
                parseTaskRepository
                        .findById(message.taskId())
                        .orElseGet(
                                () -> {
                                    // 极罕见: parse_tasks 行被 V4 清表后 redelivery, 直接 ack 让 broker 不重投
                                    log.warn(
                                            "parse_task.missing id={} (msg redelivery after row purged?), ack silently",
                                            message.taskId());
                                    return null;
                                });
        if (latest == null) return; // RocketMQListener 不抛即 ack
        if (latest.status().isTerminal()) {
            log.info(
                    "parse_task.skip_terminal task_id={}, status={} (redelivery)",
                    latest.id(),
                    latest.status());
            return;
        }

        // step 6: lease(只在 PENDING 状态可抢). 若 latest 不是 PENDING(可能 RUNNING — 另 worker 在跑),
        // 跳过让原 worker 干完; FAILED 也跳过让心跳 retry 走.
        ParseTask leased = leaseTask(latest, leasedBy, now);
        if (leased == null) {
            log.info(
                    "parse_task.lease_skipped task_id={}, status={} (in-flight or retrying)",
                    latest.id(),
                    latest.status());
            return;
        }

        // step 7-11: 实际解析
        try {
            List<com.xxx.ragdoc.domain.document.Chunk> savedChunks = worker.execute(leased);
            int chunksWritten = savedChunks.size();
            // step 12a: success — 走状态机 PARSED + markReady document
            ParseTask parsed = parseTaskService.markParsed(withChunks(leased, chunksWritten));
            try {
                Document doc =
                        documentRepository
                                .findById(leased.documentId())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Document 不存在: " + leased.documentId()));
                doc.markReady(savedChunks);
                documentRepository.save(doc);
                log.info(
                        "parse_task.done task_id={}, doc_id={}, status={}, chunks={}",
                        leased.id(),
                        leased.documentId(),
                        parsed.status(),
                        chunksWritten);
            } catch (Exception e) {
                // parse_tasks 已 PARSED; doc.markReady 异常不致命(下次 chat 仍可读到 chunks),
                // 仅 log warn 不抛, 不让 broker 重投避免重复跑同 task.
                log.warn(
                        "parse_task.doc_markReady_failed task_id={}, err={}",
                        leased.id(),
                        e.getMessage());
            }
        } catch (Exception e) {
            // step 12b: failed — 走 ParseTaskService.markFailed, 自动判 retry_count vs max_retries
            // → FAILED/PENDING 或 CANCELLED(DLQ)
            try {
                parseTaskService.markFailed(leased, e);
                log.warn(
                        "parse_task.failed_handled task_id={}, err_class={}, err={}",
                        leased.id(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
            } catch (Exception inner) {
                // markFailed 自身失败(罕见, 通常为状态已不再是 RUNNING — 被心跳回收) 不抛, 防 broker 循环重投
                log.error(
                        "parse_task.markFailed_crashed task_id={}, err={}",
                        leased.id(),
                        inner.getMessage(),
                        inner);
            }
            // 不 rethrow: 本方法已记录 failed, 不要让 broker 再 redelivery(重复 markFailed retry_count++)
        }
    }

    /** 抢占一条 task(spec §3.3 PENDING→RUNNING). 不走 leaseNextPending, 因为 message 已带 taskId. */
    private ParseTask leaseTask(ParseTask task, String leasedBy, Instant now) {
        // 显式 select+update 双步(不分两步原子也不冲突, TASK id 已由 message 唯一定位, 其他 worker 拿同
        // msg id 才并发, RocketMQ CLUSTERING 保证同 message 一台消费)
        if (task.status().name().equals("PENDING") && !task.visibleAt().isAfter(now)) {
            Instant leaseUntil = now.plus(Duration.ofMinutes(leaseDurationMinutes));
            ParseTask leased =
                    new ParseTask(
                            task.id(),
                            task.documentId(),
                            task.contentHash(),
                            com.xxx.ragdoc.domain.document.ParseTaskStatus.RUNNING,
                            task.retryCount(),
                            task.maxRetries(),
                            task.chunksWritten(),
                            task.chunkSeqOffset(),
                            task.errorMessage(),
                            task.errorClass(),
                            task.attempts(),
                            leaseUntil,
                            leasedBy,
                            task.createdAt(),
                            now);
            try {
                parseTaskRepository.update(leased);
                return leased;
            } catch (DataIntegrityViolationException e) {
                log.debug("parse_task.lease_race_lost task_id={}", task.id());
                return null;
            }
        }
        return null;
    }

    /** 把 task 的 chunksWritten 字段置为 worker 返回的真实值, 供 markParsed 守卫检查. */
    private ParseTask withChunks(ParseTask task, int chunksWritten) {
        return new ParseTask(
                task.id(),
                task.documentId(),
                task.contentHash(),
                task.status(),
                task.retryCount(),
                task.maxRetries(),
                chunksWritten,
                task.chunkSeqOffset(),
                task.errorMessage(),
                task.errorClass(),
                task.attempts(),
                task.visibleAt(),
                task.leasedBy(),
                task.createdAt(),
                task.updatedAt());
    }

    private static String hostnamePid() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host + ":" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "unknown:" + ProcessHandle.current().pid();
        }
    }
}
