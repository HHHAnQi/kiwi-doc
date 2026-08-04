package com.xxx.ragdoc.infrastructure.scheduler;

import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.DocumentManageService;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MilvusDeleteSweeper 单测: 验证周期触发会拉 pending=true 文档并委派给 DocumentManageService.attemptMilvusDelete。
 * 不依赖 Spring @Scheduled 调度, 直接调方法体, 验证 happy + empty + 单条失败不阻断其他条目 三种场景。
 */
@DisplayName("MilvusDeleteSweeper")
class MilvusDeleteSweeperTest {

    @Test
    @DisplayName("无 pending 文档 → 不调 attemptMilvusDelete, 不打 info 日志")
    void emptyPendingDoesNothing() {
        DocumentRepository repo = mock(DocumentRepository.class);
        when(repo.findDocsPendingMilvusDelete(anyInt())).thenReturn(List.of());
        DocumentManageService svc = mock(DocumentManageService.class);

        new MilvusDeleteSweeper(repo, svc).sweepPendingDeletes();

        verifyNoInteractions(svc);
    }

    @Test
    @DisplayName("有 pending 文档 → 逐条委派 attemptMilvusDelete")
    void iteratesPendingDocs() {
        DocumentRepository repo = mock(DocumentRepository.class);
        Document d1 = docWithId(101L), d2 = docWithId(102L);
        when(repo.findDocsPendingMilvusDelete(anyInt())).thenReturn(List.of(d1, d2));
        DocumentManageService svc = mock(DocumentManageService.class);

        new MilvusDeleteSweeper(repo, svc).sweepPendingDeletes();

        verify(svc).attemptMilvusDelete(d1);
        verify(svc).attemptMilvusDelete(d2);
    }

    @Test
    @DisplayName("单条 attemptMilvusDelete 抛异常 → 不阻断其他条目继续 sweep")
    void singleFailureDoesNotAbortBatch() {
        DocumentRepository repo = mock(DocumentRepository.class);
        Document d1 = docWithId(101L), d2 = docWithId(102L);
        when(repo.findDocsPendingMilvusDelete(anyInt())).thenReturn(List.of(d1, d2));
        DocumentManageService svc = mock(DocumentManageService.class);
        // 第一条抛异常 (理论不会发生, attemptMilvusDelete 内部已 catch; 测试防御外层 sweep 也兜底)
        doThrow(new RuntimeException("unexpected")).when(svc).attemptMilvusDelete(d1);

        new MilvusDeleteSweeper(repo, svc).sweepPendingDeletes();

        verify(svc).attemptMilvusDelete(d1);
        verify(svc).attemptMilvusDelete(d2);
    }

    @Test
    @DisplayName("repo.fetch 抛异常 → sweeper 不挂死 (吞异常, 等下个周期)")
    void fetchFailureSwallowed() {
        DocumentRepository repo = mock(DocumentRepository.class);
        when(repo.findDocsPendingMilvusDelete(anyInt()))
                .thenThrow(new RuntimeException("db unavailable"));
        DocumentManageService svc = mock(DocumentManageService.class);

        // 不应抛: scheduled 任务异常会终止调度线程, 必须吞
        new MilvusDeleteSweeper(repo, svc).sweepPendingDeletes();

        verifyNoInteractions(svc);
    }

    /** 构造一个有 id 的 document mock, 避免 sweeper catch 块里 doc.id().value() NPE。 */
    private static Document docWithId(long id) {
        Document d = mock(Document.class);
        when(d.id()).thenReturn(new DocumentId(id));
        return d;
    }
}
