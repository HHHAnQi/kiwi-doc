package com.xxx.ragdoc.infrastructure.parse;

import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.document.ParseTaskStatus;
import com.xxx.ragdoc.domain.shared.TraceId;
import com.xxx.ragdoc.infrastructure.mq.ParseTaskProducer;
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
 * <p>trace_id 取自当前线程(TraceIdFilter 注入的 MDC); 若 MDC 没值则用 documentId 编一个最小占位 trace。
 * 不强行依赖 TraceIdContext —— chat-app 复用 chat 已有的 trace 管线即可。
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
    private final ParseTaskProducer producer;
    private final Clock clock;

    @Override
    public void trigger(Long documentId) {
        Document doc =
                documentRepository
                        .findById(documentId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Document 不存在: " + documentId));
        Instant now = Instant.now(clock);
        ParseTask pending =
                new ParseTask(
                        null,
                        documentId,
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
                        now,
                        now);

        ParseTask saved;
        try {
            saved = parseTaskRepository.save(pending);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 同 content_hash 已存在 = 上传幂等命中了同 hash 旧 doc; 走 findByContentHash 回查原 task,
            // 若是终态重新入队, 否则让原 task 继续跑(不动)。
            ParseTask exist =
                    parseTaskRepository
                            .findByContentHash(doc.contentHash().value())
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
                        new ParseTask(
                                exist.id(),
                                exist.documentId(),
                                exist.contentHash(),
                                ParseTaskStatus.PENDING,
                                exist.retryCount(),
                                exist.maxRetries(),
                                0,
                                0,
                                null,
                                null,
                                exist.attempts(),
                                now,
                                null,
                                exist.createdAt(),
                                now);
                parseTaskRepository.update(saved);
            } else {
                // 非终态说明另一进程在跑, 重发消息兜底(parser-service 消费时 lease 抢占保证只一份)
                saved = exist;
            }
        }
        // spec §4.1 step 4-5: 切到 transient UPLOADED → PARSING 状态机迁移由 parser-service 跑完 markReady
        // chat-app 在创建 PENDING task 时不动 doc.status(UPLOADED), 等 parser-service 完成后再 markReady
        producer.send(saved, new TraceId(String.valueOf(documentId)));
        log.info(
                "async_parse.queued task_id={}, doc_id={}, status={}",
                saved.id(),
                documentId,
                saved.status());
    }
}
