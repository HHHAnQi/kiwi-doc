package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.application.document.query.DocumentSummary;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Document 仓储端口(domain/application 只认此接口,具体实现藏于 infrastructure)。 实现见 {@code
 * JpaDocumentRepository}(infra 层)。
 *
 * <p>命名约定:端口接口放 application 包;adapter 实现放 infrastructure 包。 这是六边形 / DDD 标准 DIP 结构。
 */
public interface DocumentRepository {

    /** 保存(新建 + 状态更新共用)。 V1 简化:不做显式 save + update 区分,JPA merge 由实现层处理。 */
    Document save(Document document);

    /** 按内容 hash 查(用于幂等判断,V1 单租户)。 */
    Optional<Document> findByContentHash(ContentHash hash);

    /** 按 id 查(默认仅查未软删,V4 多租户化后强制带 tenantId)。 */
    Optional<Document> findById(Long id);

    /**
     * 按状态统计未软删文档数量。
     *
     * <p>chat V1 stub 用此判断 EMPTY_KB: {@code countByStatus(READY) == 0}。
     */
    long countByStatus(DocumentStatus status);

    /**
     * 分页查询(可选 status 过滤 + 文件名模糊搜索)。
     *
     * @param status null = 不限状态
     * @param keyword null/空 = 不模糊搜索
     */
    Page<DocumentSummary> list(DocumentStatus status, String keyword, Pageable pageable);

    /** 按 id 加载详情(含 chunk_count 关联统计, 不含 chunk 内容)。 */
    Optional<DocumentDetail> findDetailById(Long id);

    /**
     * Phase 3 / P3-1 (修正版 Phase 3): 按业务 source 找当前默认版本 (is_default=true, READY, 未软删)。
     *
     * <p>用途: RetrieveService 在用户没显式传 version 时 fallback 找 default 版本过滤, 避免跨版本混查
     * (Spring Boot 2 javax vs Spring Boot 3 jakarta).
     *
     * <p>不变量: 同 source + READY + !deleted 最多 1 条 is_default=true.
     * 实现参见 {@code JpaDocumentRepository.findDefaultReadyBySource}.
     *
     * @return 不存在 default 版本 (新 source 未上传 / 全软删) 返 empty, 调用方降级全库检索。
     */
    Optional<Document> findDefaultReadyBySource(String source);

    /**
     * Phase 3 / P3-1: source 下是否已存在任意 default 文档 (任意状态, 排除软删)。
     *
     * <p>DocumentUploadService 在新建 doc 时调用: 若 source 已有 default (即使非 READY) 则不抢占;
     * 反之 (source 首次上传 / 老 default 被软删) 则把新 doc 标 isDefault=true。
     *
     * <p>不限定 status 是因为 parsingTrigger 还未跑完 (doc 还在 UPLOADED 状态), 用 READY 过滤会误判抢 default;
     * 真正检索时由 {@link #findDefaultReadyBySource} 二次过滤 READY 状态来兜底。
     */
    boolean existsDefaultBySource(String source);

    /**
     * Phase 3 / P3-2: 拉取 pending_milvus_delete=true 的文档 (限制条数, 升序排 id 防跨周期重复同一条)。
     *
     * <p>MilvusDeleteSweeper 定时 (默认 60s) 调用, 重试软删时未能同步删除 Milvus 向量的文档。
     * 成功删除后由 sweeper 调 {@link #save} 清 pending 标记; 仍失败则保持 pending 等下轮。
     *
     * @param limit 单次拉取上限 (sweeper 自管速率, 防止单批过大)
     */
    java.util.List<Document> findDocsPendingMilvusDelete(int limit);

    /**
     * Task 4: 拿所有 INDEXED 且未软删的文档 (用于 reconcile 查 Milvus 向量是否丢失)。
     *
     * <p>reconcile job 用: foreach 调 {@code vectorStore.countByDocumentId(docId)} == 0 →
     * trigger 重处理。
     *
     * @param limit 单次拉取上限
     */
    java.util.List<Document> findIndexed(int limit);

    /**
     * Task 4: 拉卡在 in-flight 中间态 (PARSING/CHUNKED/EMBEDDING/INDEXING) 且
     * lastStateChangeAt 超过阈值的文档 — 由 reconcile 标记 FAILED + 触发重处理。
     *
     * @param thresholdMinutes 阈值分钟数; 此前没动的 in-flight 文档视为卡死
     * @param limit 单次拉取上限
     */
    java.util.List<Document> findStuckInPipeline(int thresholdMinutes, int limit);
}
