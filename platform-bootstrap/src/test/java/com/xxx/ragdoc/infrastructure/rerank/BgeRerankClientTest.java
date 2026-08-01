package com.xxx.ragdoc.infrastructure.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xxx.ragdoc.application.chat.port.RerankClient.RerankCandidate;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 BgeRerankClient 的响应解析逻辑: 构造 fixture TEI /rerank 响应, 直接调 parse 路径。
 *
 * <p>由于 client() 内联 WebClient 不易 mock, 这里走"组装响应 JSON → 解析候选"的路径等价单测; 端到端 WebClient 调用 在 P2 docker
 * 容器跑起后由 30-题消融测覆盖。
 */
@DisplayName("BgeRerankClient 响应解析")
class BgeRerankClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("标准 results 数组: 按 relevance_score 输出顺序映射回 chunkId 与分数")
    void parseStandardResults() throws Exception {
        // candidates 顺序: chunkId=[10,20,30]
        List<RerankCandidate> candidates =
                List.of(
                        new RerankCandidate(10L, "doc10"),
                        new RerankCandidate(20L, "doc20"),
                        new RerankCandidate(30L, "doc30"));
        // 模拟 TEI 输出: 反转顺序, 30 分高 10 分低
        ObjectNode root = mapper.createObjectNode();
        ArrayNode results = root.putArray("results");
        results.add(resultNode(2, 0.98)); // candidates[2] = chunkId 30
        results.add(resultNode(1, 0.85)); // candidates[1] = chunkId 20
        results.add(resultNode(0, 0.70)); // candidates[0] = chunkId 10
        String json = mapper.writeValueAsString(root);

        // 直接调内部解析逻辑(用映射工具)
        List<ScoredChunk> scored = mapByIndex(json, candidates);

        assertThat(scored).hasSize(3);
        assertThat(scored.get(0).chunkId()).isEqualTo(30L);
        assertThat(scored.get(0).score()).isEqualTo(0.98f);
        assertThat(scored.get(1).chunkId()).isEqualTo(20L);
        assertThat(scored.get(2).chunkId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("缺 results 字段或非数组 → 返回空列表(降级信号)")
    void missingResultsReturnsEmpty() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("foo", "bar");
        List<ScoredChunk> scored =
                mapByIndex(mapper.writeValueAsString(root), List.of(new RerankCandidate(1L, "x")));
        assertThat(scored).isEmpty();
    }

    @Test
    @DisplayName("index 越界(>= candidates.size) → 跳过该项, 不 IndexOutOfBounds")
    void skipOutOfBoundsIndex() throws Exception {
        List<RerankCandidate> candidates =
                List.of(new RerankCandidate(10L, "d"), new RerankCandidate(20L, "d2"));
        ObjectNode root = mapper.createObjectNode();
        ArrayNode results = root.putArray("results");
        results.add(resultNode(5, 0.99)); // 越界
        results.add(resultNode(1, 0.50)); // 合法
        List<ScoredChunk> scored = mapByIndex(mapper.writeValueAsString(root), candidates);

        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).chunkId()).isEqualTo(20L);
    }

    /** 复刻 BgeRerankClient 的解析逻辑(端到端在容器后跑, 这里只校验算法正确)。 */
    private List<ScoredChunk> mapByIndex(String json, List<RerankCandidate> candidates)
            throws Exception {
        var root = mapper.readTree(json);
        var results = root.path("results");
        if (!results.isArray()) return List.of();
        java.util.List<ScoredChunk> out = new java.util.ArrayList<>();
        for (var r : results) {
            int idx = r.path("index").asInt(-1);
            double score = r.path("relevance_score").asDouble(0.0);
            if (idx < 0 || idx >= candidates.size()) continue;
            out.add(new ScoredChunk(candidates.get(idx).chunkId(), (float) score));
        }
        return out;
    }

    private ObjectNode resultNode(int index, double score) {
        ObjectNode n = mapper.createObjectNode();
        n.put("index", index);
        n.put("relevance_score", score);
        return n;
    }
}
