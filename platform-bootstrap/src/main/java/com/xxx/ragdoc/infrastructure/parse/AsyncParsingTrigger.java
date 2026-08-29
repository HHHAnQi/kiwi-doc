package com.xxx.ragdoc.infrastructure.parse;

import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * V3-W1 parser-service 拆分 — async 实现(spec §2.3 / §4.1 第 4-5 步).
 *
 * <p>职责: 把同步链路换成 "INSERT parse_tasks PENDING + 发 parse-task-submit MQ message", 然后 202 立即返回。
 *
 * <p>实际解析 Tika→chunk→embed→Milvus 由 parser-service 异步消费完成。
 *
 * <p>对应 spec §8 Commit 2 chat-app 端改造: ParsingTrigger 端口加双实现, DocumentUploadService 不动。
 *
 * <p>trace_id 取自当前线程(TraceIdFilter 注入的 MDC); 若 MDC 没值则用 documentId 编一个最小占位 trace。 不强行依赖
 * TraceIdContext —— chat-app 复用 chat 已有的 trace 管线即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.parser", name = "mode", havingValue = "async")
public class AsyncParsingTrigger implements ParsingTrigger {

    /** 默认 max_retries 上限, 与 Flyway V5 DDL DEFAULT 3 对齐. */
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final DocumentRepository documentRepository;
    private final ParseTaskRepository parseTaskRepository;
    private final Clock clock;

    @Override
    public void trigger(Long documentId) {
        queue(documentId, false);
    }

    @Override
    public void rebuild(Long documentId) {
        queue(documentId, true);
    }

    private void queue(Long documentId, boolean rebuild) {
        Document doc =
                documentRepository
                        .findById(documentId)
                        .orElseThrow(
                                () -> new IllegalStateException("Document 不存在: " + documentId));
        Instant now = Instant.now(clock);
        ParseTask existing = parseTaskRepository.findByDocumentId(documentId).orElse(null);
        if (rebuild
                && existing != null
                && existing.triggerType() == ParseTask.TriggerType.REBUILD
                && !existing.status().isTerminal()) {
            log.info(
                    "async_rebuild.idempotent_inflight doc_id={}, task_id={}, generation={}",
                    documentId,
                    existing.id(),
                    existing.generation());
            return;
        }
        if (!rebuild && existing != null && !existing.status().isTerminal()) {
            log.info(
                    "async_parse.idempotent_inflight doc_id={}, task_id={}, generation={}",
                    documentId,
                    existing.id(),
                    existing.generation());
            return;
        }
        int generation =
                rebuild
                        ? parseTaskRepository.nextGeneration(documentId)
                        : existing == null ? 1 : existing.generation();
        ParseTask.TriggerType triggerType =
                rebuild
                        ? ParseTask.TriggerType.REBUILD
                        : existing == null
                                ? ParseTask.TriggerType.UPLOAD
                                : ParseTask.TriggerType.RETRY;
        ParseTask pending =
                new ParseTask(
                        null,
                        documentId,
                        generation,
                        triggerType,
                        rebuild && existing != null ? existing.id() : null,
                        doc.contentHash().value(),
                        ParseTaskStatus.PENDING,
                        0,
                        DEFAULT_MAX_RETRIES,
                        0,
                        0,
                        null,
                        null,
                        java.util.List.of(),
                        now,
                        null,
                        ParseTask.DeliveryStatus.PENDING,
                        0,
                        now,
                        null,
                        now,
                        now);

        ParseTask saved;
        try {
            saved = parseTaskRepository.save(pending);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 同 document+generation 冲突：并发重复请求必须落到同一个任务。
            ParseTask exist =
                    parseTaskRepository
                            .findByDocumentIdAndGeneration(documentId, generation)
                            .orElseThrow(() -> dup);
            log.info(
                    "async_parse.idempotent_hit doc_id={}, existing_task_id={}, status={}",
                    documentId,
                    exist.id(),
                    exist.status());
            if (exist.status().isTerminal()) {
                // 复用 ParseTaskService 的重入队逻辑走 parser-service 自己的 ParseTaskService, 这里 chat-app
                // 本地不持 ParseTaskService bean, 直接 update 一行复用(record 不可变, 用 new 复制)
                saved =
                        exist.withExecutionState(
                                ParseTaskStatus.PENDING,
                                exist.retryCount(),
                                0,
                                0,
                                null,
                                null,
                                exist.attempts(),
                                now,
                                null,
                                now);
                parseTaskRepository.update(saved);
            } else {
                // 非终态说明另一进程在跑, 重发消息兜底(parser-service 消费时 lease 抢占保证只一份)
                saved = exist;
            }
        }
        // 统一由 Relay 先抢 SENDING 租约再发送，避免直接发送与多实例 Relay 并发重复投递。
        // parse_tasks 已提交即表示可靠接单；Relay 周期默认 30s，可按生产延迟目标调小。
        log.info(
                "async_parse.queued task_id={}, doc_id={}, generation={}, trigger={}, status={}",
                saved.id(),
                documentId,
                saved.generation(),
                saved.triggerType(),
                saved.status());
    }
}
