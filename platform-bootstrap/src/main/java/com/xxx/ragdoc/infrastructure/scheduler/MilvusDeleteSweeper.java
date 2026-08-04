package com.xxx.ragdoc.infrastructure.scheduler;

import com.xxx.ragdoc.application.document.DocumentManageService;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.domain.document.Document;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 3 / P3-2 P0 fix: 软删文档的 Milvus 向量异步收敛 sweeper。
 *
 * <p>设计动机:
 *
 * <ul>
 *   <li>软删文档的 chunks 已在主流程同事务删除 (MySQL, 原子)。
 *   <li>Milvus 走 circuit breaker, 熔断态 / 超时会导致同步删除失败。失败时 documents.pending_milvus_delete=true;
 *       本 sweeper 周期扫描 pending=true 的文档, 通过 {@link DocumentManageService#attemptMilvusDelete}
 *       重试删除向量。
 *   <li>Milvus 通常秒级熔断半开恢复, 单批 20 个 60s 周期足够收敛。失败继续保留 pending, 无 backoff 复杂度。
 * </ul>
 *
 * <p>启用: {@code @EnableScheduling} 加在 RagDocApplication 上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusDeleteSweeper {

    /** 单批拉取上限; 太大会让单次 sweep 占 Milvus 较久, 太小会让堆积无法及时消化。 */
    private static final int BATCH_SIZE = 20;

    private final DocumentRepository documentRepository;
    private final DocumentManageService documentManageService;

    /**
     * fixedDelay=60s: 上次执行结束 60s 后再触发, 避开 Milvus 熔断态期间避免无效打。 initialDelay=30s:
     * 应用启动后 30s 才开跑, 给 flyway + Spring context 充分 warmup。
     */
    @Scheduled(fixedDelayString = "${rag.milvus-delete.sweep-interval-ms:60000}", initialDelayString = "30000")
    public void sweepPendingDeletes() {
        List<Document> pending;
        try {
            pending = documentRepository.findDocsPendingMilvusDelete(BATCH_SIZE);
        } catch (Exception e) {
            // DB 异常 (启动期 / 网络抖动) 不应让 @Scheduled 线程挂死。
            log.warn("milvus_delete_sweeper.fetch_failed error={}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return; // 无 pending 不打日志, 避免每 60s 一行噪音
        }
        log.info("milvus_delete_sweeper.start pending_count={}", pending.size());
        int success = 0;
        for (Document doc : pending) {
            try {
                if (documentManageService.attemptMilvusDelete(doc)) {
                    success++;
                }
            } catch (Exception e) {
                // 单条失败不影响其他条目; sweeper 下个周期会再拉到这条
                log.warn(
                        "milvus_delete_sweeper.single_fail doc_id={}, error={}",
                        doc.id().value(),
                        e.getMessage());
            }
        }
        log.info(
                "milvus_delete_sweeper.done attempted={}, success={}, still_pending_estimate={}",
                pending.size(),
                success,
                pending.size() - success);
    }
}
