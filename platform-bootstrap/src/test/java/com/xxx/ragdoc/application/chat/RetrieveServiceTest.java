package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link RetrieveService} 单测: 覆盖 ①rerank disabled 走原 hybrid 序 ②enabled 走 rerank ③rerank 失败降级。 */
@DisplayName("RetrieveService - 第③段 reranker 集成")
class RetrieveServiceTest {

    /** Phase 3.A: 给 RetrieveService 注入一个真实(简单) MeterRegistry-backed metrics, mock 它没意义。 */
    private static com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics newRagdocMetrics() {
        return new com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** 工具: mock chunkRepository.findByIdIn 在被传入任一 ids 时, 走查表逐条返回。 */
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

    @Nested
    @DisplayName("rerank.enabled = false (默认)")
    class Disabled {

        @Test
        @DisplayName("应直接走 hybrid 序, 不调 rerankClient")
        void shouldSkipRerankWhenDisabled() {
            // given
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties(); // enabled=false 默认
            DocumentRepository dr = mock(DocumentRepository.class);
            when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty());
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(1L, 0.9f)));
            Chunk hit =
                    new Chunk(
                            1L,
                            1L,
                            0,
                            ChunkType.TEXT,
                            "正文",
                            0,
                            null,
                            null,
                            "h",
                            java.util.List.of());
            mockFindByIdIn(cr, hit);

            // when
            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("测试", null, null));

            // then: rerankClient 不应被调用
            verifyNoInteractions(rr);
            assertThat(r.items()).hasSize(1);
            assertThat(r.items().get(0).score()).isEqualTo(0.9f); // 沿用 hybrid 分数
        }
    }

    @Nested
    @DisplayName("rerank.enabled = true")
    class Enabled {

        @Test
        @DisplayName("应扩大召回 (candidatePool) 并调 rerankClient 重排, 替换为 reranker 分数")
        void shouldApplyRerankWhenEnabled() throws Exception {
            // given
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            rp.setEnabled(true);
            rp.setCandidatePool(20);
            DocumentRepository dr = mock(DocumentRepository.class);
            when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty());
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            // hybrid 召回 3 条(模拟), 顺序是 0.9 > 0.7 > 0.5
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(
                            List.of(
                                    new ScoredChunk(10L, 0.9f),
                                    new ScoredChunk(20L, 0.7f),
                                    new ScoredChunk(30L, 0.5f)));
            when(cr.findById(10L)).thenReturn(Optional.of(chunk(10L, "doc10")));
            when(cr.findById(20L)).thenReturn(Optional.of(chunk(20L, "doc20")));
            when(cr.findById(30L)).thenReturn(Optional.of(chunk(30L, "doc30")));
            // 改造后 RetrieveService 走批量 findByIdIn, mock 它返回这三条
            mockFindByIdIn(cr, chunk(10L, "doc10"), chunk(20L, "doc20"), chunk(30L, "doc30"));
            // reranker 把 30 反转排第一
            when(rr.rerank(anyString(), anyList(), anyInt()))
                    .thenReturn(
                            List.of(
                                    new ScoredChunk(30L, 0.98f),
                                    new ScoredChunk(10L, 0.85f),
                                    new ScoredChunk(20L, 0.70f)));

            // when
            RetrieveService.RetrieveResult r =
                    svc.retrieve(new ChatCommand("q", null, 5)); // topK=5 < candidatePool=20

            // then: vectorStore.search 应被以 candidatePool 大小调用
            verify(vs).search(any(), anyString(), any(), eq(20), any());
            // reranker 被调用, 结果按 reranker 序
            assertThat(r.items()).extracting("chunkId").containsExactly(30L, 10L, 20L);
            assertThat(r.items().get(0).score()).isEqualTo(0.98f); // reranker 分数替换 hybrid
        }

        @Test
        @DisplayName("rerank 调用失败时应降级到 hybrid 序, 不抛错")
        void shouldFallbackWhenRerankFails() throws Exception {
            // given
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            rp.setEnabled(true);
            DocumentRepository dr = mock(DocumentRepository.class);
            when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty());
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.5f)));
            when(cr.findById(1L)).thenReturn(Optional.of(chunk(1L, "d1")));
            when(cr.findById(2L)).thenReturn(Optional.of(chunk(2L, "d2")));
            mockFindByIdIn(cr, chunk(1L, "d1"), chunk(2L, "d2"));
            // rerank 抛异常
            when(rr.rerank(anyString(), anyList(), anyInt()))
                    .thenThrow(new RuntimeException("reranker down"));

            // when
            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("q", null, null));

            // then: 不抛错, 沿用 hybrid 序 + hybrid 分数
            assertThat(r.items()).extracting("chunkId").containsExactly(1L, 2L);
            assertThat(r.items().get(0).score()).isEqualTo(0.9f);
        }

        @Test
        @DisplayName("rerank 返回空时应降级到 hybrid 序")
        void shouldFallbackWhenRerankEmpty() throws Exception {
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            rp.setEnabled(true);
            DocumentRepository dr = mock(DocumentRepository.class);
            when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty());
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(7L, 0.9f)));
            when(cr.findById(7L)).thenReturn(Optional.of(chunk(7L, "d")));
            mockFindByIdIn(cr, chunk(7L, "d"));
            when(rr.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());

            RetrieveService.RetrieveResult r = svc.retrieve(new ChatCommand("q", null, null));
            // 单候选 rerank 返回空 → 走原序
            assertThat(r.items().get(0).chunkId()).isEqualTo(7L);
        }
    }

    private static Chunk chunk(long id, String content) {
        return new Chunk(
                id, 1L, 0, ChunkType.TEXT, content, 0, null, null, "h" + id, java.util.List.of());
    }

    /**
     * P3-1: 验证 default version fallback - 用户没传 version 但传了 source 时, RetrieveService 应该用 source 的
     * default version 过滤, 避免跨版本混查 (javax vs jakarta)。
     */
    @Nested
    @DisplayName("P3-1: default version fallback")
    class DefaultVersionFallback {

        /**
         * 构造一个最小可用的 mock Document (仅 version() 是 RetrieveService 真正使用的字段; 用反射避免
         * 触发 restore 工厂方法的必填字段约束)。
         */
        @SuppressWarnings("unchecked")
        private Document mockDocWithVersion(String version) {
            Document d = mock(Document.class);
            when(d.version()).thenReturn(version);
            return d;
        }

        @Test
        @DisplayName("source 已传 + version 未传 + source 有 default → 用 default version 过滤")
        void shouldUseDefaultVersionWhenSourceGivenButVersionMissing() {
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties(); // enabled=false
            DocumentRepository dr = mock(DocumentRepository.class);
            // source=dubbo 存在 default, version=3.x (Spring Boot 3 / jakarta)
            // 注意: mockDocWithVersion 内部也调 when(...), 必须先抽局部变量, 不能嵌套在 dr 的
            // when(...).thenReturn(Optional.of(...)) 参数里, 否则 Mockito 抛 UnfinishedStubbingException。
            Document defaultDoc = mockDocWithVersion("3.x");
            when(dr.findDefaultReadyBySource("dubbo")).thenReturn(Optional.of(defaultDoc));
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(1L, 0.9f)));
            mockFindByIdIn(cr, chunk(1L, "d"));

            // cmd 显式 source=dubbo, 不传 version
            ChatCommand cmd =
                    new ChatCommand("q", null, null, "dubbo", null, null);
            svc.retrieve(cmd);

            // 校验 vectorStore.search 时 MetadataFilter.version == "3.x" (从 default 兜来)
            org.mockito.ArgumentCaptor<VectorStore.MetadataFilter> filterCaptor =
                    org.mockito.ArgumentCaptor.forClass(VectorStore.MetadataFilter.class);
            verify(vs).search(any(), anyString(), any(), anyInt(), filterCaptor.capture());
            VectorStore.MetadataFilter used = filterCaptor.getValue();
            assertThat(used.version()).isEqualTo("3.x");
            assertThat(used.source()).isEqualTo("dubbo");
        }

        @Test
        @DisplayName("source 无 default doc → version 不过滤 (走全库)")
        void shouldNotFilterVersionWhenNoDefaultExists() {
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            DocumentRepository dr = mock(DocumentRepository.class);
            when(dr.findDefaultReadyBySource(any())).thenReturn(Optional.empty()); // 无 default
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(1L, 0.9f)));
            mockFindByIdIn(cr, chunk(1L, "d"));

            svc.retrieve(new ChatCommand("q", null, null, "dubbo", null, null));

            org.mockito.ArgumentCaptor<VectorStore.MetadataFilter> filterCaptor =
                    org.mockito.ArgumentCaptor.forClass(VectorStore.MetadataFilter.class);
            verify(vs).search(any(), anyString(), any(), anyInt(), filterCaptor.capture());
            // version=null → 不限
            assertThat(filterCaptor.getValue().version()).isNull();
        }

        @Test
        @DisplayName("用户显式传 version → 不查 default, 直接用 user version")
        void shouldSkipDefaultLookupWhenUserExplicitVersionGiven() {
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            DocumentRepository dr = mock(DocumentRepository.class);
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(1L, 0.9f)));
            mockFindByIdIn(cr, chunk(1L, "d"));

            // 用户显式传 version=2.x, 同时也传 source → 不应查 default
            svc.retrieve(new ChatCommand("q", null, null, "dubbo", "2.x", null));

            verify(dr, never()).findDefaultReadyBySource(any());
            org.mockito.ArgumentCaptor<VectorStore.MetadataFilter> filterCaptor =
                    org.mockito.ArgumentCaptor.forClass(VectorStore.MetadataFilter.class);
            verify(vs).search(any(), anyString(), any(), anyInt(), filterCaptor.capture());
            assertThat(filterCaptor.getValue().version()).isEqualTo("2.x");
        }

        @Test
        @DisplayName("source 未传 → 不查 default (走全库检索)")
        void shouldSkipDefaultLookupWhenSourceMissing() {
            EmbeddingClient emb = mock(EmbeddingClient.class);
            VectorStore vs = mock(VectorStore.class);
            ChunkRepository cr = mock(ChunkRepository.class);
            RerankClient rr = mock(RerankClient.class);
            RerankProperties rp = new RerankProperties();
            DocumentRepository dr = mock(DocumentRepository.class);
            RetrieveService svc =
                    new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics(), dr);

            when(emb.embed(any())).thenReturn(new EmbeddingResult(new float[1024], null));
            when(vs.search(any(), anyString(), any(), anyInt(), any()))
                    .thenReturn(List.of(new ScoredChunk(1L, 0.9f)));
            mockFindByIdIn(cr, chunk(1L, "d"));

            svc.retrieve(new ChatCommand("q", null, null)); // 无 source 无 version

            verify(dr, never()).findDefaultReadyBySource(any());
        }
    }
}
