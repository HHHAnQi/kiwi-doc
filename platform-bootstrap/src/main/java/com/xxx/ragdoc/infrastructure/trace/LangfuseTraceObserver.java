package com.xxx.ragdoc.infrastructure.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.port.TraceObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link TraceObserver} 的 Langfuse HTTP 实现(V3-W3, DoD-5).
 *
 * <p>调用 Langfuse ingestion REST API: POST {base}/api/public/ingestion with HTTP Basic Auth(public +
 * secret). Body 形式: {@code { "batch": [ {id, type, ...event}, ... ] }},type 可为
 * "trace-create"/"observation-create".
 *
 * <p>异步策略: 所有 trace API 写入内存 buffer, scheduled executor 每 flush-interval-ms 批量发, 不阻塞 chat 主路径.
 *
 * <p>只在 langfuse.enabled=true 时装配; chat-app ChatService 注入 TraceObserver 接口, Spring 自动选 NoOp 或
 * Langfuse 实现, 调用方代码一致.
 *
 * <p>设计取舍 — 不引 langfuse-java sdk:
 *
 * <ul>
 *   <li>SDK 版本/maven 坐标不可控(项目依赖卫生)
 *   <li>ingestion API 简单, 自己写 HTTP ~150 行可覆盖 80% 用例
 *   <li>No-op / 异步 flush 自控度高
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
public class LangfuseTraceObserver implements TraceObserver {

    private final LangfuseProperties props;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** trace buffer: traceId → list of pending events. await flush. */
    private final ConcurrentHashMap<String, List<Map<String, Object>>> buffer =
            new ConcurrentHashMap<>();

    /** 已结束 trace 的元数据, endTrace 时合并 event 创 trace-create 一并 flush. */
    private final ConcurrentHashMap<String, Map<String, Object>> traces = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private RestClient httpClient;

    @PostConstruct
    public void init() {
        if (props.getPublicKey() == null || props.getPublicKey().isBlank()) {
            log.warn("langfuse.enabled=true 但 public_key 空, 上报会 401, 建议填 LANGFUSE_PUBLIC_KEY");
        }
        String basic = props.getPublicKey() + ":" + props.getSecretKey();
        String encoded = Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));

        httpClient =
                RestClient.builder()
                        .baseUrl(props.getBaseUrl())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .build();

        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "langfuse-flush");
                            t.setDaemon(true);
                            return t;
                        });
        scheduler.scheduleWithFixedDelay(
                this::flush,
                props.getFlushIntervalMs(),
                props.getFlushIntervalMs(),
                TimeUnit.MILLISECONDS);
        log.info(
                "langfuse observer 启用 base={} flush_interval={}ms",
                props.getBaseUrl(),
                props.getFlushIntervalMs());
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            // 关闭前最后 flush 一次
            try {
                flush();
            } catch (Exception e) {
                log.debug("langfuse final flush failed on shutdown: {}", e.getMessage());
            }
            scheduler.shutdownNow();
        }
    }

    @Override
    public String startTrace(String chatTraceId, String userId, Map<String, Object> metadata) {
        // trace-create event 入 traces 池, 等 endTrace 一起 flush
        Map<String, Object> traceBody = new LinkedHashMap<>();
        traceBody.put("id", chatTraceId);
        traceBody.put("name", "chat");
        traceBody.put("userId", userId);
        if (metadata != null && !metadata.isEmpty()) {
            traceBody.put("metadata", metadata);
        }
        traces.put(chatTraceId, traceBody);
        buffer.putIfAbsent(chatTraceId, new ArrayList<>());
        return chatTraceId;
    }

    @Override
    public void observe(
            String traceId,
            ObservationType type,
            String name,
            Object input,
            Object output,
            long durationMs,
            Map<String, Object> metadata) {
        if (!traces.containsKey(traceId)) {
            // trace 未 startTrace, drop (不主动起, 防止孤儿 observation)
            return;
        }
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("id", UUID.randomUUID().toString());
        obs.put("traceId", traceId);
        obs.put("name", name == null ? type.name().toLowerCase() : name);
        obs.put("type", "SPAN"); // Langfuse SPAN 子事件(NOTICE/EVENT/SPAN)
        obs.put("startTime", Instant.now(clock).toString());
        if (input != null) obs.put("input", input);
        if (output != null) obs.put("output", output);
        if (durationMs >= 0)
            obs.put("endTime", Instant.now(clock).plusMillis(durationMs).toString());
        if (metadata != null && !metadata.isEmpty()) obs.put("metadata", metadata);

        List<Map<String, Object>> pending = buffer.computeIfAbsent(traceId, k -> new ArrayList<>());
        pending.add(wrap("observation-create", obs));

        // Phase 1.E: 阈值 flush — 单 trace observation 累积 ≥ flushBatchSize 时立刻 drain,
        // 不等 定时周期(/= SSE 流式 query 用 Langfuse UI 看进度, first_token/retrieve 提前可见)。
        if (props.getFlushBatchSize() > 0 && pending.size() >= props.getFlushBatchSize()) {
            flushTraceIncremental(traceId);
        }
    }

    @Override
    public void endTrace(String traceId, Map<String, Object> finalMetadata) {
        Map<String, Object> trace = traces.remove(traceId);
        List<Map<String, Object>> observations = buffer.remove(traceId);
        if (trace == null) {
            log.debug("langfuse.endTrace unknown traceId={}", traceId);
            return;
        }
        if (finalMetadata != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existing =
                    (Map<String, Object>) trace.getOrDefault("metadata", new LinkedHashMap<>());
            existing.putAll(finalMetadata);
            trace.put("metadata", existing);
        }

        // 拼 batch: 1 个 trace-create + N 个 observation-create。
        // trace-create 用 traceId body.id, Langfuse 按 id upsert — 之前 flush 的 patch 不会重复创建。
        List<Map<String, Object>> batch = new ArrayList<>();
        batch.add(wrap("trace-create", trace));
        if (observations != null) batch.addAll(observations);

        send(batch);
    }

    /**
     * Phase 1.E: 把某 trace 当前 buffer 内积压的 observation 提前 flush 一次。
     *
     * <p>包含一个 trace-create(body.id = traceId)做 server side upsert, 保证后续 observation 不会因 trace 不存在 被
     * drop。 已结束的 trace(endTrace 已 remove)不应进此路径 — 调用方应先 check traces.containsKey。
     */
    private void flushTraceIncremental(String traceId) {
        Map<String, Object> trace = traces.get(traceId);
        if (trace == null) return;
        List<Map<String, Object>> pending = buffer.remove(traceId);
        // 立刻 putIfAbsent 一个空 list — 同时触发的 observe() 会拿到新 list, 不丢事件
        buffer.putIfAbsent(traceId, new ArrayList<>());
        if (pending == null || pending.isEmpty()) return;
        List<Map<String, Object>> batch = new ArrayList<>(pending.size() + 1);
        batch.add(wrap("trace-create", trace));
        batch.addAll(pending);
        send(batch);
        log.debug("langfuse.incremental_flush trace_id={}, size={}", traceId, pending.size());
    }

    /**
     * 定时 flush(由 @PostConstruct 的 scheduler 周 期触发)。
     *
     * <p>endTrace 已立即 send 自己的 batch, 不依赖此; 周期 flush 主要服务于长 流式 chat: 在 endTrace 之前, 让 buffer 内的
     * retrieve/first_token observation 提前可见到 Langfuse UI。阈值 ≤0 时退化为 noop, 由 endTrace 兜底完整性。
     */
    private void flush() {
        if (props.getFlushBatchSize() <= 0) return;
        // 遍历 traces 内所有未结束 trace, 符合阈值则 drain
        for (String traceId : traces.keySet()) {
            List<Map<String, Object>> pending = buffer.get(traceId);
            if (pending != null && pending.size() >= props.getFlushBatchSize()) {
                flushTraceIncremental(traceId);
            }
        }
    }

    /** 包装为 ingestion batch 单 event 项: {id, type, body}. */
    private Map<String, Object> wrap(String type, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("body", body);
        return event;
    }

    /** 发送一个 batch 到 ingestion, 失败静默 log 不抛. */
    private void send(List<Map<String, Object>> batch) {
        if (batch.isEmpty()) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("batch", batch);
            httpClient
                    .post()
                    .uri("/api/public/ingestion")
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("langfuse.sent batch_size={}", batch.size());
        } catch (Exception e) {
            log.warn("langfuse.send_failed size={} err={}", batch.size(), e.getMessage());
        }
    }
}
