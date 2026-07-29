package com.xxx.ragdoc.application.chat;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * V2-B 召回用例: query → BGE-M3 embed → Milvus dense ANN → MySQL 回查 chunk → 组装 Citation。
 *
 * <p>架构师备注:
 *
 * <ul>
 *   <li>本类不感知 LLM, 只产 {@link RetrieveResult}(含 citation 列表), 由 ChatService 决定是否进 LLM 调用。
 *   <li>V2-A sparse 字段是占位, 当前只能 dense-only。V2-C 切到 hfei /embed + /sparse 后, VectorStore.search
 *       内部升级为 hybrid + RRF, 本类无需修改。
 *   <li>score 阈值过滤: 当前 V2-B step 不直接拿 raw score(Milvus VectorStore.search 只返 chunk_id), 阈值已在 Milvus
 *       HNSW 内部隐式生效(ef/topK 控召回质量)。 ChatService 根据召回条数判 NO_RECALL。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrieveService {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;

    /**
     * 执行召回。
     *
     * @param cmd 用户问题 + 可选 docId + topK
     * @return 召回结果(可能为空 items, 但召回操作本身成功)
     */
    public RetrieveResult retrieve(ChatCommand cmd) {
        int topK = (cmd.topK() == null) ? 5 : cmd.topK();

        // 1. query → embed(单条, 复用 EmbeddingClient.embed)
        EmbeddingResult queryEmbedding = embeddingClient.embed(cmd.query());

        // 2. dense ANN(当前 sparse 占位, dense-only 路径)
        List<Long> chunkIds = vectorStore.search(queryEmbedding, cmd.docId(), topK);
        if (chunkIds.isEmpty()) {
            log.info("retrieve.empty query_len={}, topK={}", cmd.query().length(), topK);
            return RetrieveResult.empty();
        }

        // 3. 回查 MySQL chunks(保序、按 chunkIds 顺序, 这是 Milvus 给的相关性序)
        Map<Long, Chunk> chunkMap = new HashMap<>();
        for (Long id : chunkIds) {
            chunkRepository.findById(id).ifPresent(c -> chunkMap.put(id, c));
        }

        // 4. 组装 Citation(按 chunkIds 序, 跳过查不到的)
        List<Citation> citations = new ArrayList<>(chunkIds.size());
        for (Long id : chunkIds) {
            Chunk c = chunkMap.get(id);
            if (c == null) continue;
            // snippet 截前 200 字(防止 LLM prompt 过长, 也避免一次性把超大 chunk 塞回前端)
            String snippet = truncate(c.content(), 200);
            citations.add(new Citation(c.id(), c.documentId(), c.page(), snippet));
        }

        log.info("retrieve.done hits={}, topK={}", citations.size(), topK);
        return new RetrieveResult(citations);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Citation 引用条目(与 {@code ChatResult.Citation} 同形, 但属 application 层 - RetrieveService 产出)。 */
    public record Citation(Long chunkId, Long docId, int page, String snippet) {}

    /** 召回结果。items 空表示 NO_RECALL。 */
    public record RetrieveResult(List<Citation> items) {
        public static RetrieveResult empty() {
            return new RetrieveResult(List.of());
        }
    }
}
