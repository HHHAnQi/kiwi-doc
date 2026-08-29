package com.xxx.ragdoc.parser.application;

import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * parse_tasks 状态迁移守护层(spec §3.3 invariant)。
 *
 * <p>所有 parse_tasks 表的状态变化必须经本类 — 直接调 repository.update 绕过守护视为违规。 本类不持业务事务, 事务边界由调用方
 * (ParseTaskConsumer / 心跳 job) 决定; 但本类保证: 非法迁移抛 {@link IllegalStateTransition}, 不静默成功。
 *
 * <h2>迁移表(spec §3.3)</h2>
 *
 * <table>
 *   <tr><th>from</th><th>to</th><th>触发</th><th>守卫</th></tr>
 *   <tr><td>PENDING</td><td>RUNNING</td><td>worker pull(leaseNextPending 已做完)</td><td>visible_at ≤ now</td></tr>
 *   <tr><td>RUNNING</td><td>PARSED</td><td>解析成功</td><td>chunks_written &gt; 0</td></tr>
 *   <tr><td>RUNNING</td><td>FAILED</td><td>异常</td><td>retry_count++</td></tr>
 *   <tr><td>FAILED</td><td>PENDING</td><td>重试</td><td>retry_count &lt; max_retries + visible_at push +60s</td></tr>
 *   <tr><td>FAILED</td><td>CANCELLED</td><td>中毒</td><td>retry_count ≥ max_retries</td></tr>
 * </table>
 *
 * <p>PENDING→RUNNING 的 CAS 由 {@link ParseTaskRepository#leaseNextPending} 在 SQL 层完成, 本类的 {@link
 * #markParsed} / {@link #markFailed} 都假设当前 task 已 RUNNING。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseTaskService {

    private final ParseTaskRepository repository;
    private final Clock clock;

    /** FAILED → PENDING 的 redelivery delay(秒)。spec §3.3: 60s。 */
    @Value("${rag.parser.retry-delay-seconds:60}")
    private long retryDelaySeconds;

    /**
     * 解析成功: RUNNING → PARSED(spec §3.3 第 4 行)。
     *
     * <p>守卫: chunks_written &gt; 0(空切片视为语义失败, 拒绝进入终态)。
     */
    public ParseTask markParsed(ParseTask task) {
        ensure(task.status() == ParseTaskStatus.RUNNING, task);
        if (task.chunksWritten() <= 0) {
            throw new IllegalStateTransition("PARSED 守卫失败: chunks_written<=0 task_id=" + task.id());
        }
        ParseTask updated =
                task.withExecutionState(
                        ParseTaskStatus.PARSED,
                        task.retryCount(),
                        task.chunksWritten(),
                        task.chunkSeqOffset(),
                        null,
                        null,
                        task.attempts(),
                        task.visibleAt(),
                        task.leasedBy(),
                        Instant.now(clock));
        repository.update(updated);
        log.info(
                "parse_task.parsed task_id={}, doc_id={}, chunks={}",
                task.id(),
                task.documentId(),
                task.chunksWritten());
        return updated;
    }

    /**
     * 解析失败: RUNNING → FAILED → 自动判定回 PENDING(可重试) 或 CANCELLED(DLQ 终态)。
     *
     * <p>spec §3.3 第 5-7 行; 调用方一次调本方法即可, 内部根据 retry_count vs max_retries 决定下一态。
     */
    public ParseTask markFailed(ParseTask task, Throwable cause) {
        ensure(task.status() == ParseTaskStatus.RUNNING, task);

        int newRetry = task.retryCount() + 1;
        String errorClass = cause.getClass().getName();
        String errorMessage =
                truncate(cause.getMessage() == null ? "null" : cause.getMessage(), 500);

        // attempts 追加本次记录
        var newAttempts = new ArrayList<>(task.attempts() == null ? List.of() : task.attempts());
        newAttempts.add(new ParseTask.Attempt(Instant.now(clock), 0L, errorClass, errorMessage));

        boolean dead = newRetry >= task.maxRetries();
        // 可重试失败直接回 PENDING 并延迟可见；持久化 OutboxRelay 到点后重投。
        // 旧实现停在 FAILED，但没有任何组件把 FAILED 重新变为 PENDING，任务会永久丢失。
        ParseTaskStatus nextStatus = dead ? ParseTaskStatus.CANCELLED : ParseTaskStatus.PENDING;
        Instant nextVisibleAt =
                dead ? task.visibleAt() : Instant.now(clock).plusSeconds(retryDelaySeconds);

        ParseTask updated =
                task.withExecutionState(
                        nextStatus,
                        newRetry,
                        task.chunksWritten(),
                        task.chunkSeqOffset(),
                        errorMessage,
                        errorClass,
                        List.copyOf(newAttempts),
                        nextVisibleAt,
                        task.leasedBy(),
                        Instant.now(clock));
        repository.update(updated);

        if (dead) {
            log.warn(
                    "parse_task.cancelled(DLQ) task_id={}, doc_id={}, retry={}/{}",
                    task.id(),
                    task.documentId(),
                    newRetry,
                    task.maxRetries());
        } else {
            log.warn(
                    "parse_task.requeued_with_delay task_id={}, doc_id={}, retry={}/{}, err={}",
                    task.id(),
                    task.documentId(),
                    newRetry,
                    task.maxRetries(),
                    errorClass);
        }
        return updated;
    }

    /**
     * 续点 flush: RUNNING 内部 chunks_written / chunk_seq_offset 的 in-place 更新(状态不变)。
     *
     * <p>spec §8 Commit 2: 每 10 chunks flush 一次, 中断重启可读到该值。
     */
    public ParseTask checkpoint(ParseTask task, int chunksWritten, int chunkSeqOffset) {
        ensure(task.status() == ParseTaskStatus.RUNNING, task);
        ParseTask updated =
                task.withExecutionState(
                        task.status(),
                        task.retryCount(),
                        chunksWritten,
                        chunkSeqOffset,
                        task.errorMessage(),
                        task.errorClass(),
                        task.attempts(),
                        task.visibleAt(),
                        task.leasedBy(),
                        Instant.now(clock));
        repository.update(updated);
        log.debug(
                "parse_task.checkpoint task_id={}, chunks_written={}, seq_offset={}",
                task.id(),
                chunksWritten,
                chunkSeqOffset);
        return updated;
    }

    /**
     * FAILED → PENDING 的早触发(不等心跳 job) — 可选, parser-service 用心跳主导, 本方法保留给手工 retry endpoint。 守卫: 当前必须
     * FAILED 且 retry_count &lt; max_retries。
     */
    public ParseTask requeueFromFailed(ParseTask task) {
        ensure(task.status() == ParseTaskStatus.FAILED, task);
        if (task.retryCount() >= task.maxRetries()) {
            throw new IllegalStateTransition(
                    "requeue 守卫失败: retry_count>=max_retries task_id=" + task.id());
        }
        ParseTask updated =
                task.withExecutionState(
                        ParseTaskStatus.PENDING,
                        task.retryCount(),
                        task.chunksWritten(),
                        task.chunkSeqOffset(),
                        task.errorMessage(),
                        task.errorClass(),
                        task.attempts(),
                        Instant.now(clock),
                        null,
                        Instant.now(clock));
        repository.update(updated);
        log.info("parse_task.requeued task_id={}", task.id());
        return updated;
    }

    /** 调用方应先做完 boolean 守卫(compute 出 ensurePass=true), 传入 task 仅作错误信息。 */
    private void ensure(boolean ensurePass, ParseTask task) {
        if (!ensurePass) {
            throw new IllegalStateTransition("非法迁移: task_id=" + task.id() + " 当前=" + task.status());
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "unknown" : (s.length() > max ? s.substring(0, max) : s);
    }

    /** 状态迁移非法异常(开发期 invariant 违反, 不应被生产流量触发)。 */
    public static final class IllegalStateTransition extends RuntimeException {
        public IllegalStateTransition(String msg) {
            super(msg);
        }
    }
}
