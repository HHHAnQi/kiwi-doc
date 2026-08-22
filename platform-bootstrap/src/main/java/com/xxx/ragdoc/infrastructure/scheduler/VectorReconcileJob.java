package com.xxx.ragdoc.infrastructure.scheduler;

import com.xxx.ragdoc.application.document.DocumentManageService;
import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Task 4 / V10 DocLifecycle: 每日 reconcile job — 保证 Document.status 与 Milvus 向量一致。
 *
 * <p>两类不一致自动修复:
 *
 * <ol>
 *   <li><b>向量丢失</b>: documents.status=INDEXED 但 Milvus 无对应向量 (历史事故 / Milvus 重启丢数据) → 触发
 *       parsingTrigger 重处理, 让 chunks + Milvus 向量重建
 *   <li><b>状态卡死</b>: 文档卡在 PARSING/CHUNKED/EMBEDDING/INDEXING 中间态超过阈值 (parser 实例崩溃 / 网络 hang 住没抛异常)
 *       → markFailed + retry, 让 reconcile 拉起重处理
 * </ol>
 *
 * <p>安全约束:
 *
 * <ul>
 *   <li>retry 上限保护: retryCount >= 3 的卡死文档仅 markFailed 等人工, 不再自动重试 (避免毒丸消息循环)
 *   <li>每条 per-item try/catch: 单条失败不杀整批, 下轮 reconcile 仍会拾起
 *   <li>默认每日 03:00 跑 (业务低谷); initialDelay 60s 让启动后跑一次暖场
 *   <li>批大小默认 50 防单次扫全库压垮 Milvus (countByDocumentId 是逐条 RPC, 不能粗暴放大)
 * </ul>
 */
@Slf4j
@Component
public class VectorReconcileJob {

    private static final String SEP = " ╵ ";

    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final ParsingTrigger parsingTrigger;
    private final DocumentManageService documentManageService;

    @Value("${rag.reconcile.stuck-threshold-minutes:30}")
    private int stuckThresholdMinutes;

    @Value("${rag.reconcile.batch-size:50}")
    private int batchSize;

    public VectorReconcileJob(
            DocumentRepository documentRepository,
            VectorStore vectorStore,
            ParsingTrigger parsingTrigger,
            DocumentManageService documentManageService) {
        this.documentRepository = documentRepository;
        this.vectorStore = vectorStore;
        this.parsingTrigger = parsingTrigger;
        this.documentManageService = documentManageService;
    }

    /**
     * 每日 03:00 执行 + 启动 60s 后暖场一次。
     *
     * <p>用户可改 {@code rag.reconcile.cron} 覆盖。fixedDelay/cron 二选一, cron 更贴合 "每日扫描" 语义。
     */
    // P1 修复: Spring 6 不支持 cron + initialDelay 组合(启动直接 fail)。
// 原 initialDelay=60s 的意图是躲开启动期资源初始化, 改由方法内部 try/catch 兜底(DB/向量库
// 未就绪时记 warn 等下轮, scheduler 本身不挂)。
@Scheduled(cron = "${rag.reconcile.cron:0 0 3 * * *}")
    public void reconcile() {
        log.info("reconcile.start");
        try {
            reconcileMissingVectors();
        } catch (Exception e) {
            // 任一阶段抛不让另一阶段停摆
            log.error("reconcile.missing_vectors_phase_failed: {}", e.getMessage(), e);
        }
        try {
            reconcileStuckDocuments();
        } catch (Exception e) {
            log.error("reconcile.stuck_phase_failed: {}", e.getMessage(), e);
        }
        log.info("reconcile.done");
    }

    /** INDEXED 但当前 generation 在 Milvus 完全不存在 → rebuild。高频任务只做存在性探测。 */
    void reconcileMissingVectors() {
        List<Document> indexed = documentRepository.findIndexed(batchSize);
        if (indexed.isEmpty()) {
            log.info("reconcile.missing_vectors no_indexed_docs skip");
            return;
        }
        int missing = 0;
        int fixed = 0;
        for (Document d : indexed) {
            try {
                int presence =
                        vectorStore.vectorPresence(d.id().value(), d.activeGeneration());
                if (presence == 0) {
                    missing++;
                    log.warn(
                            "reconcile.vector_generation_missing doc_id={}, generation={}, last_state={}, trigger_rebuild",
                            d.id().value(),
                            d.activeGeneration(),
                            d.lastStateChangeAt());
                    parsingTrigger.rebuild(d.id().value());
                    fixed++;
                } else if (presence < 0) {
                    log.debug(
                            "reconcile.vector_presence_unavailable doc_id={}, skip",
                            d.id().value());
                }
            } catch (Exception e) {
                log.error(
                        "reconcile.missing_vector_fix_failed doc_id={}, error={}",
                        d.id().value(),
                        e.getMessage());
            }
        }
        log.info(
                "reconcile.missing_vectors_phase indexed={}, missing={}, triggered={}",
                indexed.size(),
                missing,
                fixed);
    }

    /** in-flight 中间态超时 → markFailed + retry (不应超过 retry 上限)。 */
    void reconcileStuckDocuments() {
        List<Document> stuck =
                documentRepository.findStuckInPipeline(stuckThresholdMinutes, batchSize);
        if (stuck.isEmpty()) {
            log.info("reconcile.stuck no_inflight_overdue skip");
            return;
        }
        int retried = 0;
        int pendingManual = 0;
        for (Document d : stuck) {
            try {
                String reason =
                        "状态机卡死 in " + d.status() + SEP + "last_change=" + d.lastStateChangeAt();
                // 先尝试 markFailed 把状态推进到 FAILED (in-flight → FAILED 合法); 已 FAILED 时跳过
                boolean justMarked = false;
                if (d.status() != DocumentStatus.FAILED) {
                    try {
                        d.markFailed(reason);
                        justMarked = true;
                    } catch (IllegalStateException markFailedEx) {
                        log.debug(
                                "reconcile.mark_failed_illegal doc_id={}, status={}, skip",
                                d.id().value(),
                                d.status());
                    }
                }
                if (d.canRetry()) {
                    // 流程: markFailed 已在 try 块上方完成 → retry() (域方法, FAILED→PARSING + retryCount++)
                    // → save 持久化新状态 → parsingTrigger 真正重跑管道。
                    // 不调 DocumentManageService.retry (它会重新 findById, 跟刚 save 的 doc 状态竞态);
                    // 在 reconcile 内存聚合根上一次性完成 + 一次 save 简化事务边界。
                    d.retry();
                    documentRepository.save(d);
                    parsingTrigger.trigger(d.id().value());
                    retried++;
                    log.warn(
                            "reconcile.stuck_retry doc_id={}, status={}, retry_count_after={}",
                            d.id().value(),
                            d.status(),
                            d.retryCount());
                } else {
                    // retryCount 已达上限 (V10=3): 标 FAILED 留待运维。justMarked=true 时已带 reason 不重复 mark。
                    if (!justMarked) {
                        // 已 FAILED 状态再命中 reconcile — 只更新 errorMessage 提示人工介入
                        // (状态机不允许 FAILED→FAILED, 这里仅更新 message 字段; 但 markFailed 强制 transitionTo
                        // 会抛 — 改为直接 set errorMessage 通过新增方法; 简化做法: 跳过 message 更新, 仅 save)
                        log.warn(
                                "reconcile.already_failed_no_retry doc_id={}, retryCount={}",
                                d.id().value(),
                                d.retryCount());
                    }
                    documentRepository.save(d);
                    pendingManual++;
                    log.error(
                            "reconcile.stuck_manual_required doc_id={}, retryCount={}, status={}",
                            d.id().value(),
                            d.retryCount(),
                            d.status());
                }
            } catch (Exception e) {
                log.error(
                        "reconcile.stuck_fix_failed doc_id={}, error={}",
                        d.id().value(),
                        e.getMessage());
            }
        }
        log.info(
                "reconcile.stuck_phase stuck={}, retried={}, pending_manual={}",
                stuck.size(),
                retried,
                pendingManual);
    }

    // for test (包级可见, 测试用)
    static DocumentStatus[] inflightStatuses() {
        return new DocumentStatus[] {
            DocumentStatus.PARSING,
            DocumentStatus.CHUNKED,
            DocumentStatus.EMBEDDING,
            DocumentStatus.INDEXING
        };
    }
}
