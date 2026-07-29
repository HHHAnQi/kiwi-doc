package com.xxx.ragdoc.infrastructure.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class BgeM3EmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebClient client() {
        return WebClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public List<EmbeddingResult> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return callEmbedding(texts);
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
        // OpenAI 兼容: input 接受数组
        ArrayNode inputsNode = body.putArray("input");
        for (String input : inputs) {
            inputsNode.add(input);
        }

        try {
            String respJson =
                    client().post()
                            .uri("/v1/embeddings")
                            .header("Content-Type", "application/json")
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofMillis(props.getTimeoutMs()))
                            .block();
            return parseResponse(respJson, inputs.size());
        } catch (Exception e) {
            log.error(
                    "embedding.call_failed batch_size={}, error={}", inputs.size(), e.getMessage());
            throw new IllegalStateException("Embedding 服务调用失败: " + e.getMessage(), e);
        }
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
