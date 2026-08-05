package com.xxx.ragdoc.infrastructure.milvus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.document.port.Retriever;
import com.xxx.ragdoc.application.document.port.VectorStore.ScoredChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 5: HybridRetriever + MilvusRetriever 单测。
 *
 * <p>聚焦融合 / 路由 逻辑 — 底层 DenseRetriever/SparseRetriever (依赖 MilvusVectorStore) 由 mock 替代。
 */
@DisplayName("Task 5 HybridRetriever + MilvusRetriever")
class HybridRetrieverTest {

    @Test
    @DisplayName("HybridRetriever: dense + sparse 拉宽到 candidatePool × topK, 再 RRF")
    void hybridFetchesWideCandidatePool() {
        DenseRetriever dense = mock(DenseRetriever.class);
        SparseRetriever sparse = mock(SparseRetriever.class);
        RRFFusioner rrf = new RRFFusioner();
        RetrieveProperties props = new RetrieveProperties();
        props.setCandidatePool(4);
        props.getRrf().setK(60);
        HybridRetriever hr = new HybridRetriever(dense, sparse, rrf, props);

        EmbeddingResult emb = new EmbeddingResult(new float[16], null);
        when(dense.search(eq(emb), any(), anyInt())).thenReturn(
                List.of(new ScoredChunk(1L, 0.9f), new ScoredChunk(2L, 0.8f)));
        when(sparse.search(anyString(), any(), anyInt())).thenReturn(
                List.of(new ScoredChunk(2L, 5.0f)));

        List<ScoredChunk> out = hr.search(emb, "query", "doc_id == 1", 5);

        // 验证: 底层都被 fetchN=5*4=20 拉过
        verify(dense).search(eq(emb), eq("doc_id == 1"), eq(20));
        verify(sparse).search(eq("query"), eq("doc_id == 1"), eq(20));
        // 输出 RRF 融合后 topK=5, chunk 2 双路命中排第一
        assertThat(out).isNotEmpty();
        assertThat(out.get(0).chunkId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("MilvusRetriever: mode=HYBRID 走 HybridRetriever, mode=DENSE 走 DenseRetriever")
    void milvusRetrieverRoutesByMode() {
        DenseRetriever dense = mock(DenseRetriever.class);
        HybridRetriever hybrid = mock(HybridRetriever.class);
        RetrieveProperties props = new RetrieveProperties();
        MilvusRetriever r = new MilvusRetriever(dense, hybrid, props);

        EmbeddingResult emb = new EmbeddingResult(new float[16], null);

        // mode=HYBRID per-request override
        Retriever.Query hybridQ =
                new Retriever.Query(emb, "q1", null, 5, null, Retriever.Mode.HYBRID);
        when(hybrid.search(any(), anyString(), any(), anyInt())).thenReturn(
                List.of(new ScoredChunk(99L, 0.5f)));
        r.search(hybridQ);
        verify(hybrid).search(any(), anyString(), any(), eq(5));
        verifyNoInteractions(dense);

        // mode=DENSE per-request override
        reset(dense, hybrid);
        Retriever.Query denseQ =
                new Retriever.Query(emb, "q2", null, 3, null, Retriever.Mode.DENSE);
        when(dense.search(any(), any(), anyInt())).thenReturn(List.of(new ScoredChunk(7L, 0.4f)));
        r.search(denseQ);
        verify(dense).search(any(), any(), eq(3));
        verifyNoInteractions(hybrid);
    }

    @Test
    @DisplayName("MilvusRetriever: mode=null 时走全局默认 (props.mode=DENSE)")
    void milvusRetrieverFallsBackToGlobalDefault() {
        DenseRetriever dense = mock(DenseRetriever.class);
        HybridRetriever hybrid = mock(HybridRetriever.class);
        RetrieveProperties props = new RetrieveProperties(); // 默认 DENSE
        MilvusRetriever r = new MilvusRetriever(dense, hybrid, props);

        EmbeddingResult emb = new EmbeddingResult(new float[16], null);
        Retriever.Query q = new Retriever.Query(emb, "q", null, 5, null, null);
        when(dense.search(any(), any(), anyInt())).thenReturn(List.of());

        r.search(q);

        verify(dense).search(any(), any(), eq(5));
        verifyNoInteractions(hybrid);
    }

    @Test
    @DisplayName("MilvusRetriever: 底层抛异常 → 返空列表不传播 (chat 主流程不被破坏)")
    void milvusRetrieverReturnsEmptyOnFailure() {
        DenseRetriever dense = mock(DenseRetriever.class);
        HybridRetriever hybrid = mock(HybridRetriever.class);
        RetrieveProperties props = new RetrieveProperties();
        MilvusRetriever r = new MilvusRetriever(dense, hybrid, props);

        EmbeddingResult emb = new EmbeddingResult(new float[16], null);
        Retriever.Query q =
                new Retriever.Query(emb, "q", null, 5, null, Retriever.Mode.DENSE);
        when(dense.search(any(), any(), anyInt())).thenThrow(new RuntimeException("Milvus down"));

        List<ScoredChunk> out = r.search(q);

        assertThat(out).isEmpty();
    }
}
