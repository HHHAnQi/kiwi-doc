package com.xxx.ragdoc.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.DocumentManageService;
import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.ChunkType;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 4: VectorReconcileJob 单测。
 *
 * <p>覆盖:
 *
 * <ol>
 *   <li>INDEXED 但 Milvus count=0 → 触发 parsingTrigger 重处理
 *   <li>卡 CHUNKED 超过阈值 → markFailed + retry
 *   <li>卡 INDEXING 但 retryCount 已达上限 → 不再 trigger, 只 markFailed 等人工
 *   <li>空队列 → no-op, 不调任何下游
 *   <li>单条 trigger 抛异常 → 不阻断其他条目
 * </ol>
 *
 * <p>未直接覆盖: 实际 Spring @Scheduled 触发 (由集成测试 / 烟测验证; 单测不依赖容器)
 */
@DisplayName("Task 4 VectorReconcileJob")
class VectorReconcileJobTest {

    @Test
    @DisplayName("INDEXED 但 Milvus 向量丢失 (count=0) → 触发 parsingTrigger 重处理")
    void missingVectorTriggersReindex() throws Exception {
        DocumentRepository repo = mock(DocumentRepository.class);
        VectorStore vs = mock(VectorStore.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        DocumentManageService svc = mock(DocumentManageService.class);

        Document indexed = indexedDoc(101L);
        when(repo.findIndexed(anyInt())).thenReturn(List.of(indexed));
        when(vs.countByDocumentId(101L)).thenReturn(0); // 0 = Milvus 无向量

        newJob(repo, vs, trigger, svc).reconcileMissingVectors();

        verify(trigger).trigger(101L);
    }

    @Test
    @DisplayName("INDEXED 且 Milvus count>0 (向量存在) → 不 trigger")
    void healthyIndexedDocNotTriggered() {
        DocumentRepository repo = mock(DocumentRepository.class);
        VectorStore vs = mock(VectorStore.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        DocumentManageService svc = mock(DocumentManageService.class);

        Document indexed = indexedDoc(101L);
        when(repo.findIndexed(anyInt())).thenReturn(List.of(indexed));
        when(vs.countByDocumentId(101L)).thenReturn(1);

        newJob(repo, vs, trigger, svc).reconcileMissingVectors();

        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("卡在 CHUNKED 超过阈值 → markFailed + retry + trigger 重跑")
    void stuckInFlightTriggersRetry() throws Exception {
        DocumentRepository repo = mock(DocumentRepository.class);
        VectorStore vs = mock(VectorStore.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        DocumentManageService svc = mock(DocumentManageService.class);

        // 构造一个卡在 CHUNKED 的 doc (canRetry()=true 因为 retryCount=0)
        Document stuck = chunkedStuckDoc(202L, 0);
        when(repo.findStuckInPipeline(anyInt(), anyInt())).thenReturn(List.of(stuck));

        newJob(repo, vs, trigger, svc).reconcileStuckDocuments();

        // markFailed + retry (内存聚合根) + save + parsingTrigger.trigger 四步
        verify(repo).save(any(Document.class));
        verify(trigger).trigger(202L);
        // manageService 不应被调 (单体内完成, 不走 svc.retry 防竞态)
        verifyNoInteractions(svc);
    }

    @Test
    @DisplayName("卡死但 retryCount>=3 (上限) → markFailed 但不调 trigger, 留待人工")
    void retryExhaustedNoRetry() {
        DocumentRepository repo = mock(DocumentRepository.class);
        VectorStore vs = mock(VectorStore.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        DocumentManageService svc = mock(DocumentManageService.class);

        // retryCount=3 已达上限 → canRetry()=false
        Document stuck = chunkedStuckDoc(303L, 3);
        when(repo.findStuckInPipeline(anyInt(), anyInt())).thenReturn(List.of(stuck));

        newJob(repo, vs, trigger, svc).reconcileStuckDocuments();

        verify(repo).save(any(Document.class)); // 仍 markFailed
        verifyNoInteractions(trigger); // 不再 trigger
    }

    @Test
    @DisplayName("空队列 → 不调任何下游")
    void emptyQueuesNoOp() {
        DocumentRepository repo = mock(DocumentRepository.class);
        VectorStore vs = mock(VectorStore.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        DocumentManageService svc = mock(DocumentManageService.class);

        when(repo.findIndexed(anyInt())).thenReturn(List.of());
        when(repo.findStuckInPipeline(anyInt(), anyInt())).thenReturn(List.of());

        VectorReconcileJob job = newJob(repo, vs, trigger, svc);
        job.reconcileMissingVectors();
        job.reconcileStuckDocuments();

        verifyNoInteractions(trigger);
        verifyNoInteractions(svc);
        verify(vs, never()).countByDocumentId(anyLong());
    }

    @Test
    @DisplayName("单条 parsingTrigger.trigger 抛异常 → 不阻断其他条目继续 reconcile")
    void singleFailureDoesNotAbortBatch() throws Exception {
        DocumentRepository repo = mock(DocumentRepository.class);
        VectorStore vs = mock(VectorStore.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        DocumentManageService svc = mock(DocumentManageService.class);

        Document d1 = indexedDoc(401L), d2 = indexedDoc(402L);
        when(repo.findIndexed(anyInt())).thenReturn(List.of(d1, d2));
        when(vs.countByDocumentId(anyLong())).thenReturn(0);
        // 第一条 trigger 抛异常
        doThrow(new RuntimeException("transient")).when(trigger).trigger(401L);

        newJob(repo, vs, trigger, svc).reconcileMissingVectors();

        verify(trigger).trigger(401L);
        verify(trigger).trigger(402L); // 仍被命中
    }

    // ===== 构造辅助 =====

    private static VectorReconcileJob newJob(
            DocumentRepository repo,
            VectorStore vs,
            ParsingTrigger trigger,
            DocumentManageService svc) {
        VectorReconcileJob job = new VectorReconcileJob(repo, vs, trigger, svc);
        // @Value 注入的字段在测试里手工塞
        try {
            Field t = VectorReconcileJob.class.getDeclaredField("stuckThresholdMinutes");
            t.setAccessible(true);
            t.setInt(job, 30);
            Field b = VectorReconcileJob.class.getDeclaredField("batchSize");
            b.setAccessible(true);
            b.setInt(job, 50);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("field set failed", e);
        }
        return job;
    }

    /** 构造已 INDEXED doc (装作 reconcile 从 DB 拉出的)。 */
    private static Document indexedDoc(long id) {
        Document d = parsedDoc(id);
        d.markChunked(sampleChunks(id));
        d.markEmbedding();
        d.markIndexing();
        d.markIndexed();
        return d;
    }

    /** 构造卡在 CHUNKED 的 doc, 指定 retryCount (模拟已重试次数)。 */
    private static Document chunkedStuckDoc(long id, int retryCount) {
        Document d = parsedDoc(id);
        d.markChunked(sampleChunks(id));
        // 通过反射把 retryCount 拉到指定值 — 模拟"已重试 N 次仍卡死"
        try {
            Field r = Document.class.getDeclaredField("retryCount");
            r.setAccessible(true);
            r.setInt(d, retryCount);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return d;
    }

    private static Document parsedDoc(long id) {
        Document d =
                Document.newUploaded(
                        new ContentHash(
                                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                        "x.pdf",
                        "application/pdf",
                        1L,
                        "default");
        d.assignId(new DocumentId(id));
        d.startParsing();
        return d;
    }

    private static List<Chunk> sampleChunks(long docId) {
        return List.of(
                new Chunk(
                        1L,
                        docId,
                        0,
                        ChunkType.TEXT,
                        "x",
                        0,
                        null,
                        null,
                        "h",
                        List.of()));
    }
}
