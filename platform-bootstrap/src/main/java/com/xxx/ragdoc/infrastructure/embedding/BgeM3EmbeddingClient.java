package com.xxx.ragdoc.infrastructure.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * EmbeddingClient 实现: 调 text-embeddings-inference BGE-M3 服务的 OpenAI 兼容 /v1/embeddings。
 *
 * <p>BGE-M3 输出格式(关键):
 *
 * <pre>
 * {
 *   "data": [
 *     {
 *       "embedding": [...1024 维浮点...],         ← dense 向量
 *       "colonvectors_sparse": {                  ← 注意这个 key: text-embeddings-inference 的导出方式
 *         "indices": [123, 456, ...],
 *         "values":  [0.5, 0.3, ...]
 *       }
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>注意: text-embeddings-inference 输出的 sparse 字段名默认 "colonvectors_sparse"; 版本不同可能是
 * "sparse_vectors", 代码同时兼容两个 key。
 */
@Slf4j
@Component
public class BgeM3EmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;
    // Phase 3.A: 调用 BGE-M3 服务时走 CircuitBreaker(命名 instance "embedding"),
    // 失败率 ≥ 50% 自动熔断, 防 TEI/Ollama Embedding 长时间挂掉把整站 chat 拖死。
    private final CircuitBreaker circuitBreaker;

    /** P1: embed 并发闸(可选); props.maxConcurrent<=0 时不启用。 */
    private final java.util.concurrent.Semaphore concurrencyLimit;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BgeM3EmbeddingClient(EmbeddingProperties props, CircuitBreakerRegistry cbRegistry) {
        this.props = props;
        // cbRegistry 已由 application.yml resilience4j.circuitbreaker.instances.embedding 装载配置,
        // 这里仅按名取 instance。若配置缺失则按 registry default config 新建。
        this.circuitBreaker = cbRegistry.circuitBreaker("embedding");
        this.concurrencyLimit =
                props.getMaxConcurrent() > 0
                        ? new java.util.concurrent.Semaphore(props.getMaxConcurrent())
                        : null;
    }

    private WebClient client() {
        return WebClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public List<EmbeddingResult> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // 按 props.batchSize 分批调用避免 TEI 413: 单请求 body 上限 ~2MB,
        // 大 doc parent_child 模式下 child 数可能 30+ × 400 字 → 超 2MB 触发 413
        // (P3-A 全量重灌首批 197/200 失败根因)。
        int batchSize = Math.max(1, props.getBatchSize());
        if (texts.size() <= batchSize) {
            return callEmbedding(texts);
        }
        log.info(
                "embedding.batch_split total={}, batch_size={}, batches={}",
                texts.size(),
                batchSize,
                (texts.size() + batchSize - 1) / batchSize);
        List<EmbeddingResult> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> sub = texts.subList(i, end);
            all.addAll(callEmbedding(sub));
        }
        return all;
    }

    @Override
    public EmbeddingResult embed(String text) {
        List<EmbeddingResult> results = callEmbedding(List.of(text));
        return results.isEmpty() ? null : results.get(0);
    }

    private List<EmbeddingResult> callEmbedding(List<String> inputs) {
        // hfei cpu-1.5 同时支持:
        //   /embed           + {"inputs": [...]}    (TEI 原生, 返回纯 nested array)
        //   /v1/embeddings   + {"input":  [...]}    (OpenAI 兼容, 返回 data[].embedding)
        // 关键 bug: 之前用了 /v1/embeddings + 字段 inputs(复数) → 422/415 反序列化失败。
        // 标准是 input(单数)。这里用 OpenAI 兼容路径, parseResponse 现有逻辑能直接解析。
        ObjectNode body = objectMapper.createObjectNode();
        // P1(云端 embedding): 本地 TEI 忽略 model 字段, 云端(智谱等)必填
        if (props.getModel() != null && !props.getModel().isBlank()) {
            body.put("model", props.getModel());
        }
        if (props.getDimensions() > 0) {
            body.put("dimensions", props.getDimensions());
        }
        // OpenAI 兼容: input 接受数组
        ArrayNode inputsNode = body.putArray("input");
        for (String input : inputs) {
            inputsNode.add(input);
        }

        try {
            // P1: 串行/限流喂 TEI(信号量公平排队), 防并发排队导致的集体超时-熔断风暴
            if (concurrencyLimit != null) {
                try {
                    concurrencyLimit.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Embedding 调用被中断", ie);
                }
            }
            try {
                callEmbedWithGuard(inputs, body);
            } finally {
                if (concurrencyLimit != null) concurrencyLimit.release();
            }
            String respJson = lastResponse;
            return parseResponse(respJson, inputs.size());
        } catch (Exception e) {
            log.error(
                    "embedding.call_failed batch_size={}, error={}", inputs.size(), e.getMessage());
            throw new IllegalStateException("Embedding 服务调用失败: " + e.getMessage(), e);
        }
    }

    private volatile String lastResponse;

    private void callEmbedWithGuard(List<String> inputs, ObjectNode body) {
        // Phase 3.A: embedding CircuitBreaker 装饰。熔断态会直接抛 CallNotPermittedException,
        // 不进 HTTP 调用, 让上游 RetrieveService 知晓并形成降级链。
        lastResponse =
                circuitBreaker.executeSupplier(
                        () -> {
                            var request =
                                    client().post()
                                            .uri(embeddingsPath())
                                            .header("Content-Type", "application/json");
                            // P1(云端 embedding): 云 provider 需要 Bearer key; 本地 TEI 无需
                            if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
                                request =
                                        request.header(
                                                "Authorization", "Bearer " + props.getApiKey());
                            }
                            return request.bodyValue(body.toString())
                                    .retrieve()
                                    .bodyToMono(String.class)
                                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                                    .block();
                        });
    }

    /**
     * P1: base-url 已以版本段(/v1, /v4)结尾的 provider(智谱 paas/v4, OpenAI /v1)直接拼 /embeddings; 本地 TEI 裸
     * host(8082) 拼老路径 /v1/embeddings, 行为不变。
     */
    private String embeddingsPath() {
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        if (base.endsWith("/v1") || base.endsWith("/v4")) {
            return "/embeddings";
        }
        return "/v1/embeddings";
    }

    private List<EmbeddingResult> parseResponse(String json, int expectSize) {
        List<EmbeddingResult> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray()) {
                log.warn("embedding.response_no_data_array body={}", json);
                return results;
            }
            for (JsonNode item : dataArray) {
                // dense: 标准 OpenAI "embedding" 字段
                float[] dense = null;
                JsonNode embNode = item.path("embedding");
                if (embNode.isArray()) {
                    dense = new float[embNode.size()];
                    for (int i = 0; i < embNode.size(); i++) {
                        dense[i] = (float) embNode.get(i).asDouble();
                    }
                }
                // sparse: 兼容 text-embeddings-inference 的几个字段名
                Map<Integer, Float> sparse = parseSparse(item);

                results.add(new EmbeddingResult(dense, sparse));
            }
        } catch (Exception e) {
            log.error(
                    "embedding.parse_failed expect_size={}, error={}", expectSize, e.getMessage());
            throw new IllegalStateException("Embedding 响应解析失败", e);
        }
        return results;
    }

    /**
     * 解析 sparse: text-embeddings-inference 默认输出 "colonvectors_sparse", 部分版本 "sparse_vectors"; 兼容两个
     * key。
     */
    private static Map<Integer, Float> parseSparse(JsonNode item) {
        JsonNode sparseNode = item.path("colonvectors_sparse");
        if (sparseNode.isMissingNode() || sparseNode.isNull()) {
            sparseNode = item.path("sparse_vectors");
        }
        if (sparseNode.isMissingNode() || sparseNode.isNull()) {
            return new HashMap<>();
        }
        JsonNode indices = sparseNode.path("indices");
        JsonNode values = sparseNode.path("values");
        if (!indices.isArray() || !values.isArray()) {
            return new HashMap<>();
        }
        Map<Integer, Float> sparse = new HashMap<>();
        for (int i = 0; i < indices.size(); i++) {
            sparse.put(indices.get(i).asInt(), (float) values.get(i).asDouble());
        }
        return sparse;
    }
}
