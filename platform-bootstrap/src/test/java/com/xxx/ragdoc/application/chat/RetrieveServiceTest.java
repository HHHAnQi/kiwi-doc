package com.xxx.ragdoc.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.command.ChatCommand;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.application.chat.port.RerankClient;
import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
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
            RetrieveService svc = new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics());

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
            RetrieveService svc = new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics());

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
            RetrieveService svc = new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics());

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
            RetrieveService svc = new RetrieveService(emb, vs, cr, rr, rp, newRagdocMetrics());

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
}
