package com.xxx.ragdoc.application.chat;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.conversation.EnhanceResult;
import com.xxx.ragdoc.application.chat.conversation.port.QueryProcessorPort;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.chat.port.RerankClient.RerankCandidate;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.Retriever;
import com.xxx.ragdoc.application.document.port.ReciprocalRankFusion;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.common.exception.ErrorCode;
import com.xxx.ragdoc.common.exception.InfraException;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.Document;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    // Phase 3.A: retrieve SLO 计量(retrieve_total_latency / recall_count / rerank_latency)
    // 架构债清理: 用 application 层端口 MetricsPort, 不直接持 infrastructure.RagdocMetrics。
    private final com.xxx.ragdoc.application.metrics.MetricsPort metrics;
    // P3-1: 查 default version 用于 fallback
    private final DocumentRepository documentRepository;
    // V9 RAG-Perm-001: 把 Principal 解析为可读 docId 白名单。null=admin/默认主体(不限制); 集合=显式白名单。
    private final PermissionResolverPort permissionResolver;
    // Task 5 / V11 Hybrid Retrieval: 检索端口(DENSE/HYBRID 路由 + RRF 融合)。
    // 替代直接 vectorStore.search — 让 AB 实验能 per-request override mode。
    private final Retriever retriever;
    // Task 6 / V12 Query Enhancement: 单轮 query rewrite + expansion 端口 (可选注入)。
    // 用 setter 注入 — {@code @ConditionalOnProperty enabled=true} 时 Bean 存在, 这里有值;
    // 默认 disabled 时 Bean 不装配, setter 不调用, 字段保持 null, retrieve 跳过 enhance。
    private QueryProcessorPort queryEnhancePort;
    // Task 6: properties 用 holders (default disabled, 仅查阅, 不影响 ConditionalOnMissingBean)。
    private final QueryEnhanceProperties queryEnhanceProps;

    /** Task 6: 注入 QueryProcessorPort — 仅在 rag.query-enhance.enabled=true 时由 Spring 调用。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setQueryEnhancePort(QueryProcessorPort queryEnhancePort) {
        this.queryEnhancePort = queryEnhancePort;
        if (queryEnhancePort != null) {
            log.info(
                    "RetrieveService.queryEnhancePort injected: {}",
                    queryEnhancePort.getClass().getName());
        }
    }

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
        return retrieve(cmd, null, null);
    }

    /**
     * Task 5 / V11: 重载支持 per-request mode override (AB 实验用)。
     *
     * @param cmd 用户问题 + 元数据过滤
     * @param mode 检索模式 null=走全局 {@code rag.retrieve.mode}; 否则强制 dense/hybrid
     */
    public RetrieveResult retrieve(ChatCommand cmd, Retriever.Mode mode) {
        return retrieve(cmd, mode, null);
    }

    /**
     * Task 5 + Task 6: 完整重载, 支持检索模式 + query 增强 per-request override。
     *
     * @param mode 检索模式 null=全局默认; dense/hybrid=override
     * @param enhance null=走全局 {@code rag.query-enhance.enabled}; true=强制 enhance; false=强制关闭
     */
    public RetrieveResult retrieve(ChatCommand cmd, Retriever.Mode mode, Boolean enhance) {
        long retrieveT0 = System.currentTimeMillis(); // Phase 3.A: retrieve_total_latency
        // 用户 topK 是"最终想要几条"; 启用 reranker 时底层扩大到 candidatePool 条
        int userTopK = (cmd.topK() == null) ? 5 : cmd.topK();
        boolean rerankEnabled = rerankProps.isEnabled();
        int fetchK = rerankEnabled ? Math.max(userTopK, rerankProps.getCandidatePool()) : userTopK;

        // Task 11 / P0: 用 AccessScope 严格区分 admin (本 tenant 全可见) vs 普通用户 (显式白名单)
        //   - admin (allowedDocumentIds=null)    → 不加 docId 子句, 只 filter tenant_id
        //   - 普通用户 (非空集)                  → filter tenant_id and document_id in [allowed]
        //   - 普通用户 (空集)                    → 立即 NO_RECALL 短路, 不再调 Milvus
        Principal principal = AuthContext.currentPrincipal();
        com.xxx.ragdoc.application.auth.AccessScope scope =
                permissionResolver.resolveAccessScope(principal);
        Set<Long> allowedDocIds = scope.allowedDocumentIds();
        if (!scope.isUnrestrictedWithinTenant()
                && allowedDocIds != null
                && allowedDocIds.isEmpty()) {
            log.info(
                    "retrieve.blocked_no_readable_doc user={}, tenant={}",
                    principal.userId(),
                    principal.tenantId());
            metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
            metrics.recordRetrieveRecall(0);
            return RetrieveResult.empty();
        }

        // Task 6 / V12 Query Enhancement: 在 embed 前可选 rewrite + expansion
        //   enhance 决策: per-request enhance==true → 强制; enhance==false → 强制关;
        //   enhance==null → 走全局 rag.query-enhance.enabled (默认 false)
        String effectiveQuery = cmd.query();
        List<String> retrievalQueries = List.of(cmd.query());
        if (shouldEnhance(enhance)) {
            if (queryEnhancePort == null) {
                // bean 未装配 (rag.query-enhance.enabled=false) → 静默降级 fallback
                log.debug("retrieve.query_enhance bean_absent, using original query");
            } else {
                EnhanceResult er = queryEnhancePort.enhance(cmd.query(), principal);
                effectiveQuery = er.primaryQuery();
                // 最多 = 主改写 + 原始 Query + N 条 expansion；LinkedHashSet 会自动合并相同主/原 Query。
                int maxQueries = Math.max(1, queryEnhanceProps.getMaxExpansionQueries() + 2);
                retrievalQueries = er.allQueries().stream().limit(maxQueries).toList();
                if (!"ok".equals(er.outcome())) {
                    log.info(
                            "retrieve.query_enhance_non_ok outcome={}, fallback_using_original={}, reason={}",
                            er.outcome(),
                            effectiveQuery.equals(cmd.query()),
                            er.errorMessage());
                }
            }
        }

        // 1. 解析召回过滤条件。未显式指定版本/文档时，只允许各逻辑文档的 current id，
        //    避免旧实现按 source 选一个全局默认版本而误伤同 source 下的其他文件。
        String effectiveVersion = cmd.version();
        boolean usedDefaultVersion = false;
        if (effectiveVersion == null && cmd.docId() == null) {
            Optional<java.util.Set<Long>> currentIdsResult =
                    documentRepository.findCurrentIndexedIds(principal.tenantId(), cmd.source());
            if (currentIdsResult.isPresent()) {
                java.util.Set<Long> currentIds = currentIdsResult.get();
                allowedDocIds =
                        allowedDocIds == null
                                ? currentIds
                                : allowedDocIds.stream()
                                        .filter(currentIds::contains)
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (allowedDocIds.isEmpty()) {
                    metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
                    metrics.recordRetrieveRecall(0);
                    return RetrieveResult.empty();
                }
            } else if (cmd.source() != null) {
                // 兼容自定义/旧版 DocumentRepository 适配器：尚不支持 current-id 查询时保留 V7 fallback。
                Optional<Document> defaultDoc =
                        documentRepository.findDefaultReadyBySource(cmd.source());
                if (defaultDoc.isPresent()) {
                    effectiveVersion = defaultDoc.get().version();
                    usedDefaultVersion = effectiveVersion != null;
                }
            }
        }
        java.util.Collection<Long> generationCandidates =
                cmd.docId() == null ? allowedDocIds : java.util.Set.of(cmd.docId());
        Optional<java.util.Map<Long, Integer>> activeGenerationsResult =
                documentRepository.findActiveGenerations(
                        principal.tenantId(),
                        cmd.source(),
                        effectiveVersion,
                        cmd.language(),
                        generationCandidates);
        if (activeGenerationsResult.isPresent() && activeGenerationsResult.get().isEmpty()) {
            metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
            metrics.recordRetrieveRecall(0);
            return RetrieveResult.empty();
        }
        VectorStore.MetadataFilter filter =
                new VectorStore.MetadataFilter(
                        cmd.source(),
                        effectiveVersion,
                        cmd.language(),
                        principal.tenantId(),
                        allowedDocIds,
                        activeGenerationsResult.orElse(null));
        // Task 5 起走 Retriever 接口 (DENSE/HYBRID 路由 + RRF 融合), 替代直接 vectorStore.search。
        // mode=null 让 Retriever 走全局 RetrieveProperties 默认 (老行为不变, 兼容向后)。
        // 2. 每个 Query 独立 Embedding + 检索，随后做 Query 级 RRF。所有分支复用完全相同的
        // Tenant/ACL/版本/generation 过滤条件；单个扩展失败不拖垮主链，全部失败才报基础设施错误。
        List<List<ScoredChunk>> queryRankings = new ArrayList<>();
        RuntimeException lastRetrievalFailure = null;
        for (int queryIndex = 0; queryIndex < retrievalQueries.size(); queryIndex++) {
            String retrievalQuery = retrievalQueries.get(queryIndex);
            try {
                EmbeddingResult queryEmbedding = embeddingClient.embed(retrievalQuery);
                Retriever.Query rq =
                        new Retriever.Query(
                                queryEmbedding,
                                retrievalQuery,
                                cmd.docId(),
                                fetchK,
                                filter.isEmpty() ? null : filter,
                                mode);
                queryRankings.add(retriever.search(rq));
            } catch (RuntimeException e) {
                lastRetrievalFailure = e;
                log.warn(
                        "retrieve.query_branch_failed query_index={}, total_queries={}, primary={}, error={}",
                        queryIndex,
                        retrievalQueries.size(),
                        queryIndex == 0,
                        e.getMessage());
            }
        }
        if (queryRankings.isEmpty()) {
            metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
            log.error(
                    "retrieve.infrastructure_failed query_len={}, fetchK={}, query_count={}, error={}",
                    cmd.query().length(),
                    fetchK,
                    retrievalQueries.size(),
                    lastRetrievalFailure == null ? "unknown" : lastRetrievalFailure.getMessage());
            throw new InfraException(
                    ErrorCode.RAG_RETRIEVAL_FAILED,
                    "检索基础设施调用失败",
                    lastRetrievalFailure);
        }
        List<ScoredChunk> hits =
                queryRankings.size() == 1
                        ? queryRankings.get(0)
                        : ReciprocalRankFusion.fuse(
                                queryRankings, queryEnhanceProps.getFusionRrfK(), fetchK);
        log.info(
                "retrieve.multi_query_done requested={}, succeeded={}, fused_hits={}",
                retrievalQueries.size(),
                queryRankings.size(),
                hits.size());
        if (hits.isEmpty()) {
            log.info("retrieve.empty query_len={}, fetchK={}", cmd.query().length(), fetchK);
            metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
            metrics.recordRetrieveRecall(0);
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

        // Milvus 是派生索引，命中后必须用 MySQL SoT 二次确认文档仍处于可检索状态且租户一致。
        Set<Long> hitDocumentIds =
                chunkMap.values().stream()
                        .map(Chunk::documentId)
                        .collect(java.util.stream.Collectors.toSet());
        Map<Long, Document> documentMap = new HashMap<>();
        for (Document d : documentRepository.findByIdIn(hitDocumentIds)) {
            if (d.id() != null) documentMap.put(d.id().value(), d);
        }

        // Milvus 是派生索引，不能把它的 ACL/状态过滤结果当作最终授权依据：逐条用 MySQL SoT 二次校验。
        List<ScoredChunk> validHits = new ArrayList<>(hits.size());
        int staleHitCount = 0;
        int securityRejectedHitCount = 0;
        for (ScoredChunk hit : hits) {
            Chunk chunk = chunkMap.get(hit.chunkId());
            Document document = chunk == null ? null : documentMap.get(chunk.documentId());
            if (chunk == null
                    || document == null
                    || document.isDeleted()
                    || document.status()
                            != com.xxx.ragdoc.domain.document.DocumentStatus.INDEXED
                    || chunk.generation() != document.activeGeneration()) {
                staleHitCount++;
                continue;
            }
            boolean tenantAllowed = principal.tenantId().equals(document.tenantId());
            boolean documentAllowed =
                    allowedDocIds == null || allowedDocIds.contains(chunk.documentId());
            if (!tenantAllowed || !documentAllowed) {
                securityRejectedHitCount++;
                continue;
            }
            validHits.add(hit);
        }
        if (staleHitCount > 0) {
            metrics.recordRetrieveStaleHit(staleHitCount);
            log.error(
                    "retrieve.stale_index_hit total_hits={}, stale_hits={}, tenant={}",
                    hits.size(),
                    staleHitCount,
                    principal.tenantId());
        }
        if (securityRejectedHitCount > 0) {
            metrics.recordRetrieveSecurityRejectedHit(securityRejectedHitCount);
            log.error(
                    "retrieve.security_rejected_index_hit total_hits={}, rejected_hits={}, tenant={}",
                    hits.size(),
                    securityRejectedHitCount,
                    principal.tenantId());
        }
        if (validHits.isEmpty() && staleHitCount > 0) {
            metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
            throw new InfraException(ErrorCode.RAG_RETRIEVAL_FAILED, "检索索引与元数据不一致，已拒绝使用陈旧结果");
        }
        if (validHits.isEmpty()) {
            metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
            metrics.recordRetrieveRecall(0);
            return RetrieveResult.empty();
        }

        // PR-1 / EMS-PR1: initialRetrieval 段证据 (rerank 前, 严格 validHits 序)
        //   - tenantId 来自 Principal (服务端注入, 不接受 caller 传)
        //   - 无 rerankScore; retrievalScore = hybrid/dense 分数
        //   - documentVersion 用本检索上下文解析出的 effectiveVersion (cmd.version 或 default fallback)
        final String tenantId = principal.tenantId();
        List<Evidence> initialEvidences =
                validHits.stream()
                        .map(
                                h -> {
                                    Chunk c = chunkMap.get(h.chunkId());
                                    Document d = documentMap.get(c.documentId());
                                    return Evidence.of(
                                            tenantId,
                                            c.documentId(),
                                            c.id(),
                                            d.version(),
                                            c.content(),
                                            (double) h.score(),
                                            null,
                                            "retriever",
                                            java.util.Map.of(
                                                    "page", c.page(),
                                                    "sectionPath", c.sectionPath()));
                                })
                        .toList();

        // 4. ③ 可选: cross-encoder reranker 精排, 取 topN(=userTopK)
        //    失败时降级到原 hybrid 序(不破坏主流程, 只 log)
        //    V3-W3: 加 candidates 数 + top1 hybrid score 进 log, 跑完 RAGAS 看分布判断 reranker 是否真提质
        //    Phase 1.E: 通过 rerankState 把分支信息回吐给 ChatService 做 Langfuse observation
        String rerankState = rerankEnabled ? "skipped" : "not_enabled";
        float top1RerankScore = 0f;
        List<ScoredChunk> finalHits = validHits;
        float top1HybridScore = validHits.isEmpty() ? 0f : validHits.get(0).score();
        if (rerankEnabled && validHits.size() > 1) {
            long rerankT0 = 0L; // Phase 3.A: rerank_latency metric 基准; 0 表示还没进入 call
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
                rerankT0 = System.currentTimeMillis();
                // 必须与召回使用同一个 effectiveQuery；否则开启 query rewrite 后会用旧问题精排新候选。
                List<ScoredChunk> reranked = rerankClient.rerank(effectiveQuery, candidates, topN);
                metrics.recordRerankLatency(System.currentTimeMillis() - rerankT0, true);
                if (!reranked.isEmpty()) {
                    finalHits = reranked;
                    rerankState = "applied";
                    top1RerankScore = reranked.get(0).score();
                    log.info(
                            "retrieve.rerank_applied candidates={}, final_n={}, top1_rerank_score={}, top1_hybrid_score={}",
                            candidates.size(),
                            reranked.size(),
                            top1RerankScore,
                            top1HybridScore);
                } else {
                    rerankState = "empty_fallback";
                    log.warn("retrieve.rerank_empty fallback to hybrid");
                }
            } catch (Exception e) {
                if (rerankT0 > 0) {
                    metrics.recordRerankLatency(System.currentTimeMillis() - rerankT0, false);
                }
                rerankState = "failed";
                log.warn(
                        "retrieve.rerank_failed fallback to hybrid, query_len={}, error={}",
                        cmd.query().length(),
                        e.getMessage());
                // finalHits 保持原 validHits 不变
            }
        } else if (rerankEnabled) {
            // 单条候选 rerank 无意义, 直接用
            rerankState = "skipped_single_candidate";
            finalHits = validHits;
        }

        // 截到 userTopK(reranker 路径已经在 rerank 里截了; 非 rerank 路径需要这一刀)
        if (finalHits.size() > userTopK) {
            finalHits = finalHits.subList(0, userTopK);
        }

        // PR-1 / EMS-PR1: postRerank 段证据 — 终序 finalHits (rerank 应用后或回退原序)
        //   - rerankState="applied" 时 retrievalScore 取自 finalHits 的 hybrid score,
        //     rerankScore 取该 hit 自带的 rerank score (rerankClient 返回)
        //   - 其它 rerankState 时 rerankScore=null, 取 finalHits 自身 score
        final boolean rerankApplied = "applied".equals(rerankState);
        final List<Evidence> postRerankEvidences =
                finalHits.stream()
                        .map(
                                h -> {
                                    Chunk c = chunkMap.get(h.chunkId());
                                    Document d = documentMap.get(c.documentId());
                                    Double rerankScore = rerankApplied ? (double) h.score() : null;
                                    Double retrievalScore =
                                            rerankApplied ? null : (double) h.score();
                                    return Evidence.of(
                                            tenantId,
                                            c.documentId(),
                                            c.id(),
                                            d.version(),
                                            c.content(),
                                            retrievalScore,
                                            rerankScore,
                                            rerankApplied ? "reranker" : "retriever",
                                            java.util.Map.of(
                                                    "page", c.page(),
                                                    "sectionPath", c.sectionPath()));
                                })
                        .toList();

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
        // PR-1 / EMS-PR1: finalContext 段证据 与 citations 严格同序同长 —— 这就是真正喂给 LLM 的 context
        // 映射, 让评测与 trace 与 Chat 实际 Context 完全一致 (EMS-PR1 硬约束)。
        //   - content 用 llmContext (parent 全文或 chunk 自身)
        //   - parent-child 模式按 seenParents 自然去重 (P3-A, 与 citations 同步)
        //   - 不在 flat 模式做 contentHash 去重: 否则 finalContext 与 ChatService 拼 context 数量对不上;
        //     评测可自行用 evidence.contentHash 判断重复。
        java.util.List<Evidence> finalContextEvidences =
                new java.util.ArrayList<>(finalHits.size());
        java.util.Set<Long> seenParents = new java.util.HashSet<>(); // 同 parent 去重(P3-A)
        for (ScoredChunk hit : finalHits) {
            Chunk c = chunkMap.get(hit.chunkId());
            if (c == null) continue;
            // snippet 永远取 child 自己(200字 给前端卡片精简)
            String snippet = truncate(c.content(), 200);
            // llmContext: P3-A 下, 优先 parent 全文(~2000字); flat 模式或无 parent 用 chunk 自己
            String llmContext = c.content();
            Chunk contextChunk = c; // 默认: 喂 LLM 的就是 child 自己
            if (c.parentChunkId() != null) {
                Chunk parent = parentMap.get(c.parentChunkId());
                if (parent != null) {
                    // 同一 parent 已被前面 hit 命中过 → 跳过(cit 不重复喂相同 parent 喂 LLM)
                    if (seenParents.contains(parent.id())) continue;
                    seenParents.add(parent.id());
                    llmContext = parent.content();
                    contextChunk = parent;
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
                    addFinalContextEvidence(
                            finalContextEvidences,
                            tenantId,
                            documentMap.get(c.documentId()).version(),
                            c,
                            contextChunk,
                            hit,
                            rerankApplied);
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
            addFinalContextEvidence(
                    finalContextEvidences,
                    tenantId,
                    documentMap.get(c.documentId()).version(),
                    c,
                    contextChunk,
                    hit,
                    rerankApplied);
        }

        log.info(
                "retrieve.done fetchK={}, rerank={}, hits={}, topK={}, rerank_state={}, default_version={}",
                fetchK,
                rerankEnabled,
                citations.size(),
                userTopK,
                rerankState,
                usedDefaultVersion ? effectiveVersion : "n/a");
        // Phase 3.A: retrieve SLO 计量(retrieve_total_latency + recall_count)
        metrics.recordRetrieveTotal(System.currentTimeMillis() - retrieveT0);
        metrics.recordRetrieveRecall(citations.size());
        EvidenceSnapshot snapshot =
                new EvidenceSnapshot(
                        initialEvidences, postRerankEvidences, finalContextEvidences, rerankState);
        return new RetrieveResult(
                citations, rerankState, top1HybridScore, top1RerankScore, snapshot);
    }

    /**
     * PR-1: 向 finalContext 段追加一条 Evidence, 与 citations 同序产出。 chunkId 标 childChunk.id() 维持与 Citation
     * 一致溯源键; content 用 contextChunk.content() (parent 全文或 chunk 自身)。
     */
    private static void addFinalContextEvidence(
            java.util.List<Evidence> sink,
            String tenantId,
            String docVersion,
            Chunk childChunk,
            Chunk contextChunk,
            ScoredChunk hit,
            boolean rerankApplied) {
        Double rerankScore = rerankApplied ? (double) hit.score() : null;
        Double retrievalScore = rerankApplied ? null : (double) hit.score();
        sink.add(
                Evidence.of(
                        tenantId,
                        childChunk.documentId(),
                        childChunk.id(),
                        docVersion,
                        contextChunk.content(),
                        retrievalScore,
                        rerankScore,
                        "context",
                        java.util.Map.of(
                                "page", childChunk.page(),
                                "sectionPath", childChunk.sectionPath())));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * Task 6: 是否执行 query enhance。
     *
     * <ul>
     *   <li>per-request enhance=true → 强制 (无视 props.enabled)
     *   <li>per-request enhance=false → 强制关
     *   <li>null → 跰全局 {@link QueryEnhanceProperties#isEnabled()}
     * </ul>
     */
    private boolean shouldEnhance(Boolean enhanceOverride) {
        if (enhanceOverride != null) return enhanceOverride;
        return queryEnhanceProps.isEnabled();
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

    /**
     * 召回结果。items 空表示 NO_RECALL。
     *
     * <p>PR-1 / EMS-PR1: 附带 {@link EvidenceSnapshot} 让评测/Trace 严格基于 Chat 实际 Context, 不再独立调 {@code
     * /retrieve}。所有老 4 参构造继续工作 (= empty snapshot, 不破坏现有调用方)。
     */
    public record RetrieveResult(
            List<Citation> items,
            String rerankState,
            float top1HybridScore,
            float top1RerankScore,
            EvidenceSnapshot evidenceSnapshot) {

        /** 老 4 参构造器 — 没有 Evidence 快照的场景 (空快照, 向后兼容测试与 A/B runner)。 */
        public RetrieveResult(
                List<Citation> items,
                String rerankState,
                float top1HybridScore,
                float top1RerankScore) {
            this(items, rerankState, top1HybridScore, top1RerankScore, EvidenceSnapshot.empty());
        }

        public static RetrieveResult empty() {
            return new RetrieveResult(List.of(), "not_enabled", 0f, 0f, EvidenceSnapshot.empty());
        }
    }
}
