package com.xxx.ragdoc.infrastructure.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.chat.RerankProperties;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@link RerankClient} 的 BGE-Reranker-v2-m3 实现。
 *
 * <p>调 text-embeddings-inference 的 /rerank 端点(标准 Jina/Cohere 兼容协议):
 *
 * <pre>
 * POST /rerank
 * {
 *   "query": "Sentinel 是什么",
 *   "documents": ["doc1 文本", "doc2 文本", ...],
 *   "top_n": 5
 * }
 *
 * 响应:
 * {
 *   "results": [
 *     {"index": 2, "relevance_score": 0.98},  // ← candidates[2] 排第一
 *     {"index": 0, "relevance_score": 0.84},
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>关键点:
 *
 * <ul>
 *   <li>documents 顺序需与 candidates 严格对齐(results[].index 是 documents 下标)
 *   <li>输出 ScoredChunk 用 rerank score 覆盖原 hybrid score, 让下游拿到统一的"精排后序"
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BgeRerankClient implements RerankClient {

    private final RerankProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebClient client() {
        return WebClient.builder().baseUrl(props.getBaseUrl()).build();
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<RerankCandidate> candidates, int topN)
            throws Exception {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 1. 构造请求体: query + documents[] + top_n
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        ArrayNode docsNode = body.putArray("documents");
        for (RerankCandidate c : candidates) {
            // 截断超长文本(TEI 单 doc 限 ~8192 token, 这儿保守 4000 字符)
            String text = c.text();
            if (text != null && text.length() > 4000) {
                text = text.substring(0, 4000);
            }
            docsNode.add(text == null ? "" : text);
        }
        body.put("top_n", Math.min(topN, candidates.size()));

        // 2. POST /rerank
        String respJson;
        try {
            respJson =
                    client().post()
                            .uri("/rerank")
                            .header("Content-Type", "application/json")
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofMillis(props.getTimeoutMs()))
                            .block();
        } catch (Exception e) {
            log.error(
                    "rerank.call_failed query_len={}, candidates={}, error={}",
                    query.length(),
                    candidates.size(),
                    e.getMessage());
            throw e;
        }

        // 3. 解析 results: [{index, relevance_score}], 按 reranker 输出顺序映射回 chunkId
        List<ScoredChunk> scored = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(respJson);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                log.warn("rerank.response_no_results body={}", respJson);
                return List.of();
            }
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(0.0);
                if (idx < 0 || idx >= candidates.size()) {
                    continue;
                }
                scored.add(new ScoredChunk(candidates.get(idx).chunkId(), (float) score));
            }
        } catch (Exception e) {
            log.error("rerank.parse_failed error={}", e.getMessage());
            throw new IllegalStateException("Reranker 响应解析失败", e);
        }

        log.info(
                "rerank.done query_len={}, candidates={}, returned={}, top1_score={}",
                query.length(),
                candidates.size(),
                scored.size(),
                scored.isEmpty() ? 0f : scored.get(0).score());
        return scored;
    }
}
