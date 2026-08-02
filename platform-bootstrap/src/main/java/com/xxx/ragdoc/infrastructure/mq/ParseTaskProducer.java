package com.xxx.ragdoc.infrastructure.mq;

import com.xxx.ragdoc.domain.document.ParseTask;
import com.xxx.ragdoc.domain.shared.TraceId;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * V3 parser-service 拆分 — chat-app 端 MQ 生产者(spec §2.2 / §4.1 第 5 步).
 *
 * <p>发 topic {@code parse-task-submit}, parser-service {@code @RocketMQMessageListener} 消费.
 *
 * <p>仅在 {@code rag.parser.mode=async} 时启用({@link ConditionalOnProperty}); sync 路径下不会注入 本 bean, 也不需要
 * RocketMQ broker 在跑, 平滑迁移保护(dev 默认 sync, 生产切 async).
 *
 * <p>失败处理: producer.send 抛异常 → 由上层 DocumentUploadService 捕获, 不回滚 documents/parse_tasks INSERT(已
 * commit), 改为: parse_tasks 仍 PENDING, 由 V3 Commit 3 心跳 job 兜底重投或人工 retry. 此处对 sendResult 没有同步等
 * broker ack, RocketMQ 默认同步发送(send-message-timeout=5s).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.parser", name = "mode", havingValue = "async")
public class ParseTaskProducer {

    /** topic 名固定, spec §2.2 契约. */
    public static final String TOPIC = "parse-task-submit";

    private final RocketMQTemplate rocketMQTemplate;
    private final Clock clock;

    /**
     * 发送一条 parse-task-submit 消息(供 DocumentUploadService 异步路径调用).
     *
     * @param task 已 INSERT 的 PENDING task(含 id)
     * @param traceId 上游 trace, 透传到 mq header 让 parser-service 日志能串起调用链
     */
    public void send(ParseTask task, TraceId traceId) {
        ParseTaskSubmitMessage payload =
                new ParseTaskSubmitMessage(
                        task.id(), task.documentId(), task.contentHash(), Instant.now(clock));

        org.springframework.messaging.Message<ParseTaskSubmitMessage> message =
                MessageBuilder.withPayload(payload).setHeader("traceId", traceId.value()).build();

        try {
            rocketMQTemplate.syncSend(TOPIC, message);
            log.info(
                    "parse_task.mq_sent topic={}, task_id={}, doc_id={}, trace_id={}",
                    TOPIC,
                    task.id(),
                    task.documentId(),
                    traceId.value());
        } catch (Exception ex) {
            // 不抛, 不回滚 parse_tasks 行(已 commit). 心跳 job 后续会用 visible_at ≤ now 兜底重投.
            // TODO V3 Commit 3: 接 RocketMQ producer DLQ / 落 fallback 表
            log.error(
                    "parse_task.mq_send_failed topic={}, task_id={}, trace_id={}, err={}",
                    TOPIC,
                    task.id(),
                    traceId.value(),
                    ex.getMessage(),
                    ex);
        }
    }
}
