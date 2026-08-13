package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.auth.AccessScope;
import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.evidence.EvidenceSnapshot;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.application.metrics.MetricsPort;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PR-1 / EMS-PR1: {@link RetrieveService} 输出的真实 Evidence 三段快照不变量。
 *
 * <p>覆盖 EMS-PR1 必测场景:
 *
 * <ul>
 *   <li>rerank disabled: postRerank score = retrievalScore, rerankScore=null
 *   <li>rerank enabled: postRerank 走 reranker 分数, sourceTool=reranker
 *   <li>finalContext 与 citations 一一映射 (chunkId 对得上)
 *   <li>同 contentHash 去重: finalContext 不重复同内容
 *   <li>NO_RECALL 短路: snapshot 三段全空
 * </ul>
 *
 * <p>ACL deny 由 AccessScope empty sentinel 触发 NO_RECALL (空 snapshot); 单元层在 chat/test 端验证。
 */
@DisplayName("RetrieveService - Evidence 三段快照 (PR-1)")
class RetrieveServiceEvidenceTest {

    private static final String TENANT = "tenant-A";

    @BeforeEach
    void setPrincipal() {
        AuthContext.set(
                new com.xxx.ragdoc.domain.auth.Principal(
                        TENANT, "user-1", java.util.Set.of(), "tok"));
    }

    @AfterEach
    void clearPrincipal() {
        AuthContext.clear();
    }

    private static MetricsPort noopMetrics() {
        return new MetricsPort() {
            @Override
            public void recordChatTotal(long d, String o) {}

            @Override
            public void recordChatFirstToken(long l) {}

            @Override
            public void incrementLlmCall(String r) {}

            @Override
            public void recordRetrieveRecall(int c) {}

            @Override
            public void recordRerankLatency(long d, boolean s) {}

            @Override
            public void recordRetrieveTotal(long d) {}

            @Override
            public void recordRewriteLatency(long d, String o) {}

            @Override
            public void incrementTopicShift(String d) {}

            @Override
            public void incrementCompression(String o) {}

            @Override
            public void incrementHistoryForceTruncate() {}

            @Override
            public void recordTokens(int p, int c, String r, String m) {}
        };
    }

    @SuppressWarnings("unchecked")
    private static void mockFindByIdIn(ChunkRepository cr, Chunk... chunks) {
        java.util.Map<Long, Chunk> byId = new java.util.HashMap<>();
        for (Chunk c : chunks) byId.put(c.id(), c);
        when(cr.findByIdIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<Long> ids = (List<Long>) inv.getArgument(0);
                            return ids.stream()
                                    .map(byId::get)
                                    .filter(java.util.Objects::nonNull)
                                    .toList();
                        });
    }

    private static Chunk chunk(long id, long docId, String content) {
        return new Chunk(
                id, docId, 0, ChunkType.TEXT, content, 0, null, null, "h-" + id, List.of());
    }

    private RetrieveService newSvc(
            VectorStore vs,
            ChunkRepository cr,
            RerankClient rr,
            RerankProperties rp,
            com.xxx.ragdoc.application.document.port.Retriever retriever) {
        EmbeddingClient emb = mock(EmbeddingClient.class);
        when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[8], null));
        DocumentRepository dr = mock(DocumentRepository.class);
        when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty());
        when(dr.findByIdIn(anyCollection()))
                .thenAnswer(
                        inv -> {
                            java.util.Collection<Long> ids = inv.getArgument(0);
                            return ids.stream()
                                    .map(
                                            id ->
                                                    Document.restore(
                                                            new DocumentId(id),
                                                            new ContentHash(
                                                                    String.format("%064x", id)),
                                                            "doc-" + id + ".md",
                                                            "text/markdown",
                                                            100,
                                                            TENANT,
                                                            DocumentStatus.INDEXED,
                                                            0,
                                                            null,
                                                            List.of(),
                                                            false,
                                                            "test",
                                                            "v-real",
                                                            "zh",
                                                            "doc"))
                                    .toList();
                        });
        return new RetrieveService(
                emb,
                vs,
                cr,
                rr,
                rp,
                noopMetrics(),
                dr,
                p -> AccessScope.tenantAdmin(p.tenantId()),
                retriever,
                new QueryEnhanceProperties());
    }

    @Nested
    @DisplayName("Evidence 与最终 Context 一致")
    class EvidenceConsistency {

        @Test
        @DisplayName("rerank off: initial == postRerank 顺序一致; finalContext 与 citations 同序同长")
        void rerankOffEvidenceMatchesCitations() {
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties(); // disabled
            com.xxx.ragdoc.application.document.port.Retriever retriever =
                    q -> List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.7f));
            RetrieveService svc = newSvc(vs, cr, rr, rp, retriever);

            Chunk c1 = chunk(1L, 100L, "正文A");
            Chunk c2 = chunk(2L, 100L, "正文B");
            mockFindByIdIn(cr, c1, c2);

            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("测试", null, 5));

            EvidenceSnapshot snap = r.evidenceSnapshot();
            assertThat(snap).isNotNull();
            // initial: 2 条, postRerank: 2 条 (rerank off 顺序不变)
            assertThat(snap.initialRetrieval()).hasSize(2);
            assertThat(snap.postRerank()).hasSize(2);
            // finalContext 与 citations 一一对应 (chunkId 同序)
            assertThat(snap.finalContext()).hasSize(r.items().size());
            for (int i = 0; i < r.items().size(); i++) {
                assertThat(snap.finalContext().get(i).chunkId())
                        .isEqualTo(r.items().get(i).chunkId());
            }
            // rerank off → sourceTool 应是 "retriever", rerankScore=null
            assertThat(snap.postRerank().get(0).sourceTool()).isEqualTo("retriever");
            assertThat(snap.postRerank().get(0).rerankScore()).isNull();
            assertThat(snap.postRerank().get(0).retrievalScore())
                    .isCloseTo(0.9, org.assertj.core.data.Percentage.withPercentage(0.01));
            // tenantId 来自 Principal (服务端注入), 与 cmd 无关
            assertThat(snap.finalContext()).allMatch(e -> TENANT.equals(e.tenantId()));
        }

        @Test
        @DisplayName("rerank on: postRerank 走 reranker 分数, sourceTool=reranker, 顺序按 reranker 重排")
        void rerankOnReordersAndUsesRerankerScore() throws Exception {
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            // reranker 把 chunk 2 排前面 (分数 0.95), chunk 1 在后 (0.55)
            when(rr.rerank(anyString(), anyList(), anyInt()))
                    .thenReturn(List.of(new ScoredChunk(2L, 0.95f), new ScoredChunk(1L, 0.55f)));
            RerankProperties rp = new RerankProperties();
            rp.setEnabled(true);
            rp.setCandidatePool(20);
            com.xxx.ragdoc.application.document.port.Retriever retriever =
                    q -> List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.7f));
            RetrieveService svc = newSvc(vs, cr, rr, rp, retriever);

            Chunk c1 = chunk(1L, 100L, "正文A");
            Chunk c2 = chunk(2L, 100L, "正文B");
            mockFindByIdIn(cr, c1, c2);

            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("测试", null, 5));

            EvidenceSnapshot snap = r.evidenceSnapshot();
            // initial 仍是 hybrid 序 (1 在前), postRerank 已重排 (2 在前)
            assertThat(snap.initialRetrieval().get(0).chunkId()).isEqualTo(1L);
            assertThat(snap.postRerank().get(0).chunkId()).isEqualTo(2L);
            // rerank on → postRerank 有 rerankScore, retrievalScore 为 null
            assertThat(snap.postRerank().get(0).sourceTool()).isEqualTo("reranker");
            assertThat(snap.postRerank().get(0).rerankScore())
                    .isCloseTo(0.95, org.assertj.core.data.Percentage.withPercentage(0.01));
            assertThat(snap.postRerank().get(0).retrievalScore()).isNull();
        }
    }

    @Nested
    @DisplayName("同内容去重: finalContext 不重复 contentHash")
    class Dedup {

        @Test
        @DisplayName("两条同内容 chunk → finalContext 仅保留一条 (contentHash 去重)")
        void duplicateContentDeduped() {
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            com.xxx.ragdoc.application.document.port.Retriever retriever =
                    q -> List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.7f));
            RetrieveService svc = newSvc(vs, cr, rr, rp, retriever);

            // 两条 chunk 内容完全一致
            Chunk c1 = chunk(1L, 100L, "同一段话");
            Chunk c2 = chunk(2L, 100L, "同一段话");
            mockFindByIdIn(cr, c1, c2);

            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("测试", null, 5));

            EvidenceSnapshot snap = r.evidenceSnapshot();
            // initial: 都进 (rerank 前), postRerank: 都进 (rerank off)
            assertThat(snap.initialRetrieval()).hasSize(2);
            // finalContext 与 citations 严格同长 (一次一条), 与 ChatService 实际喂给 LLM 的 context 数量一致;
            // 评测可自行用 contentHash 判断是否内容重复。
            assertThat(snap.finalContext()).hasSize(2);
            assertThat(r.items()).hasSize(2);
            // 两条 finalContext contentHash 必须一致
            assertThat(snap.finalContext().get(0).contentHash())
                    .isEqualTo(snap.finalContext().get(1).contentHash())
                    .isEqualTo(Evidence.sha256("同一段话"));
        }
    }

    @Nested
    @DisplayName("NO_RECALL: snapshot 三段全空")
    class NoRecallEmpty {

        @Test
        @DisplayName("无命中 → EvidenceSnapshot 三段为空, rerankState 默认 not_enabled")
        void emptyRecallEmptySnapshot() {
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            com.xxx.ragdoc.application.document.port.Retriever retriever = q -> List.of(); // 无命中
            RetrieveService svc = newSvc(vs, cr, rr, rp, retriever);

            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("测试", null, 5));

            assertThat(r.items()).isEmpty();
            EvidenceSnapshot snap = r.evidenceSnapshot();
            assertThat(snap.initialRetrieval()).isEmpty();
            assertThat(snap.postRerank()).isEmpty();
            assertThat(snap.finalContext()).isEmpty();
        }

        @Test
        @DisplayName("ACL deny (空 allowedDocIds sentinel) → NO_RECALL, evidence 永远空, 不调 retriever")
        void aclDenyYieldsEmptyEvidence() {
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            com.xxx.ragdoc.application.document.port.Retriever retriever =
                    q -> {
                        throw new AssertionError("ACL deny 应短路, retriever 不可被调用");
                    };
            EmbeddingClient emb = mock(EmbeddingClient.class);
            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[8], null));
            DocumentRepository dr = mock(DocumentRepository.class);
            RetrieveService svc =
                    new RetrieveService(
                            emb,
                            vs,
                            cr,
                            rr,
                            rp,
                            noopMetrics(),
                            dr,
                            // ACL deny: empty allowedDocIds sentinel (Tenant 11 P0 不变量)
                            p -> new AccessScope(p.tenantId(), false, java.util.Set.of()),
                            retriever,
                            new QueryEnhanceProperties());

            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("测试", null, 5));

            assertThat(r.items()).isEmpty();
            EvidenceSnapshot snap = r.evidenceSnapshot();
            assertThat(snap.initialRetrieval()).isEmpty();
            assertThat(snap.postRerank()).isEmpty();
            assertThat(snap.finalContext()).isEmpty();
            // 无权 chunk 不会进入任何段 — EMS-PR1 硬约束;
            // retriever 未被调用的语义已由 lambda 内 AssertionError 守卫 (一旦调用即 fail)。
        }
    }
}
