package com.xxx.ragdoc.application.chat;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.chat.port.RerankClient.RerankCandidate;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.domain.document.Chunk;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * V2 召回用例: query → embed → (① hybrid dense+BM25 RRF | dense-only) → ② MySQL 回查 chunk → ③ reranker
 * 精排(可选) → 组装 Citation。
 *
 * <p>架构师备注:
 *
 * <ul>
 *   <li>本类不感知 LLM, 只产 {@link RetrieveResult}(含 citation 列表), 由 ChatService 决定是否进 LLM 调用。
 *   <li>V2-A/B: dense-only。V2-C: dense + BM25 RRF。V2-第三段: 加 BGE-Reranker-v2-m3 cross-encoder 精排。
 *   <li>第③段 feature flag 控制: {@code rag.rerank.enabled}(默认 false); 开启时 hybrid 召回扩到
 *       candidatePool(~20) 条, 喂 cross-encoder, 取 topN(~5)。rerank 失败自动降级到 hybrid 序(不抛错)。
 *   <li>V3: {@link VectorStore.MetadataFilter} 元数据过滤(source/version/language)已透传到底层 search。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrieveService {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;
    // Optional 注入: reranker 可选, 缺它仍能跑(自动跳过第③段)。Spring 自动行为没适配, 这里用 required=true,
    // 但通过 RerankProperties.feature flag 决定是否实际调用, 二者任一缺失即视为不支持 rerank。
    private final RerankClient rerankClient;
    private final RerankProperties rerankProps;

    /**
     * V3-W3 加固: 启动期自检 + 打日志明示 reranker 实际配置。防止 .env / dev.yml / 环境变量多重覆盖 静默让 reranker base_url 错配,
     * 跑完一组 RAGAS 才发现全跑 hybrid fallback(P0 教训)。
     */
    @PostConstruct
    void logRerankConfig() {
        log.info(
                "retrieve.rerank_config enabled={}, base_url={}, model={}, candidate_pool={}, top_n={}",
                rerankProps.isEnabled(),
                rerankProps.getBaseUrl(),
                rerankProps.getModel(),
                rerankProps.getCandidatePool(),
                rerankProps.getTopN());
        if (rerankProps.isEnabled() && rerankProps.getBaseUrl() == null) {
            log.warn(
                    "retrieve.rerank_misconfig ❌ enabled=true 但 base_url 为空, 启动后所有 rerank 调用会 fail");
        }
    }

    /**
     * 执行召回。
     *
     * @param cmd 用户问题 + 可选 docId/topK/source/version/language
     * @return 召回结果(可能为空 items, 但召回操作本身成功)
     */
    public RetrieveResult retrieve(ChatCommand cmd) {
        // 用户 topK 是"最终想要几条"; 启用 reranker 时底层扩大到 candidatePool 条
        int userTopK = (cmd.topK() == null) ? 5 : cmd.topK();
        boolean rerankEnabled = rerankProps.isEnabled();
        int fetchK = rerankEnabled ? Math.max(userTopK, rerankProps.getCandidatePool()) : userTopK;

        // 1. query → embed(单条)
        EmbeddingResult queryEmbedding = embeddingClient.embed(cmd.query());

        // 2. ① + ② 召回(fetchK 条) + 元数据过滤
        VectorStore.MetadataFilter filter =
                new VectorStore.MetadataFilter(cmd.source(), cmd.version(), cmd.language());
        List<ScoredChunk> hits =
                vectorStore.search(
                        queryEmbedding,
                        cmd.query(),
                        cmd.docId(),
                        fetchK,
                        filter.isEmpty() ? null : filter);
        if (hits.isEmpty()) {
            log.info("retrieve.empty query_len={}, fetchK={}", cmd.query().length(), fetchK);
            return RetrieveResult.empty();
        }

        // 3. MySQL 回查 chunk 全文(批量 SQL 消除 N+1: 原 iteration findById 召回 N 条 = N 次查询)
        List<Long> hitIds = new ArrayList<>(hits.size());
        for (ScoredChunk hit : hits) {
            hitIds.add(hit.chunkId());
        }
        Map<Long, Chunk> chunkMap = new HashMap<>();
        for (Chunk c : chunkRepository.findByIdIn(hitIds)) {
            chunkMap.put(c.id(), c);
        }
        // 过滤掉查不到 chunk 元数据的 hit(保序)
        List<ScoredChunk> validHits =
                hits.stream().filter(h -> chunkMap.containsKey(h.chunkId())).toList();

        // 4. ③ 可选: cross-encoder reranker 精排, 取 topN(=userTopK)
        //    失败时降级到原 hybrid 序(不破坏主流程, 只 log)
        //    V3-W3: 加 candidates 数 + top1 hybrid score 进 log, 跑完 RAGAS 看分布判断 reranker 是否真提质
        List<ScoredChunk> finalHits = validHits;
        float top1HybridScore = validHits.isEmpty() ? 0f : validHits.get(0).score();
        if (rerankEnabled && validHits.size() > 1) {
            try {
                List<RerankCandidate> candidates = new ArrayList<>(validHits.size());
                for (ScoredChunk h : validHits) {
                    candidates.add(
                            new RerankCandidate(h.chunkId(), chunkMap.get(h.chunkId()).content()));
                }
                int topN = Math.min(userTopK, candidates.size());
                log.info(
                        "retrieve.rerank_start candidates={} topN={} top1_hybrid_score={}",
                        candidates.size(),
                        topN,
                        top1HybridScore);
                List<ScoredChunk> reranked = rerankClient.rerank(cmd.query(), candidates, topN);
                if (!reranked.isEmpty()) {
                    finalHits = reranked;
                    log.info(
                            "retrieve.rerank_applied candidates={}, final_n={}, top1_rerank_score={}, top1_hybrid_score={}",
                            candidates.size(),
                            reranked.size(),
                            reranked.get(0).score(),
                            top1HybridScore);
                } else {
                    log.warn("retrieve.rerank_empty fallback to hybrid");
                }
            } catch (Exception e) {
                log.warn(
                        "retrieve.rerank_failed fallback to hybrid, query_len={}, error={}",
                        cmd.query().length(),
                        e.getMessage());
                // finalHits 保持原 validHits 不变
            }
        } else if (rerankEnabled) {
            // 单条候选 rerank 无意义, 直接用
            finalHits = validHits;
        }

        // 截到 userTopK(reranker 路径已经在 rerank 里截了; 非 rerank 路径需要这一刀)
        if (finalHits.size() > userTopK) {
            finalHits = finalHits.subList(0, userTopK);
        }

        // 5. 组装 Citation(按 finalHits 顺序)
        //    P3-A Parent-Child 回链: 若命中 chunk 有 parentChunkId, 反查 parent 全文作 llmContext
        //    (child 短仅用于精检索; parent 长是完整段, 喂 LLM 信息充足 → context_recall ↑)
        //
        //    批量预拉 parent: 收集所有非空 parentChunkId, 一次 SQL 消除 parent 反链 N+1
        //    不过滤软删父 doc —— parent 与 child 同属一文档, 上面 validHits 已过滤过软删文档
        List<Long> parentIdsToFetch = new ArrayList<>();
        for (ScoredChunk hit : finalHits) {
            Chunk c = chunkMap.get(hit.chunkId());
            if (c != null && c.parentChunkId() != null) {
                parentIdsToFetch.add(c.parentChunkId());
            }
        }
        Map<Long, Chunk> parentMap = new HashMap<>();
        if (!parentIdsToFetch.isEmpty()) {
            for (Chunk p : chunkRepository.findByIdIn(parentIdsToFetch)) {
                parentMap.put(p.id(), p);
            }
        }

        List<Citation> citations = new ArrayList<>(finalHits.size());
        java.util.Set<Long> seenParents = new java.util.HashSet<>(); // 同 parent 去重(P3-A)
        for (ScoredChunk hit : finalHits) {
            Chunk c = chunkMap.get(hit.chunkId());
            if (c == null) continue;
            // snippet 永远取 child 自己(200字 给前端卡片精简)
            String snippet = truncate(c.content(), 200);
            // llmContext: P3-A 下, 优先 parent 全文(~2000字); flat 模式或无 parent 用 chunk 自己
            String llmContext = c.content();
            if (c.parentChunkId() != null) {
                Chunk parent = parentMap.get(c.parentChunkId());
                if (parent != null) {
                    // 同一 parent 已被前面 hit 命中过 → 跳过(cit 不重复喂相同 parent 喂 LLM)
                    if (seenParents.contains(parent.id())) continue;
                    seenParents.add(parent.id());
                    llmContext = parent.content();
                    // 引用 id 仍标 child(便于前端溯源到精确检索位置), 但 llmContext 是 parent
                    citations.add(
                            new Citation(
                                    c.id(),
                                    c.documentId(),
                                    c.page(),
                                    snippet,
                                    llmContext,
                                    hit.score(),
                                    c.sectionPath()));
                    continue;
                }
            }
            citations.add(
                    new Citation(
                            c.id(),
                            c.documentId(),
                            c.page(),
                            snippet,
                            llmContext,
                            hit.score(),
                            c.sectionPath()));
        }

        log.info(
                "retrieve.done fetchK={}, rerank={}, hits={}, topK={}",
                fetchK,
                rerankEnabled,
                citations.size(),
                userTopK);
        return new RetrieveResult(citations);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Citation 引用条目(与 {@code ChatResult.Citation} 同形, 但属 application 层 - RetrieveService 产出)。 */
    public record Citation(
            Long chunkId,
            Long docId,
            int page,
            String snippet,
            String llmContext,
            float score,
            List<String> sectionPath) {}

    /** 召回结果。items 空表示 NO_RECALL。 */
    public record RetrieveResult(List<Citation> items) {
        public static RetrieveResult empty() {
            return new RetrieveResult(List.of());
        }
    }
}
