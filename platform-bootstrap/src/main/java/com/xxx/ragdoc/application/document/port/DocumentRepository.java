package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.application.document.query.DocumentSummary;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import com.xxx.ragdoc.domain.shared.ContentHash;
import java.util.Optional;
import java.util.Set;
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

    /** 按租户 + 内容 hash 查询；幂等边界必须与数据库唯一约束一致。 */
    Optional<Document> findByContentHash(ContentHash hash, String tenantId);

    /** 按 id 查(默认仅查未软删,V4 多租户化后强制带 tenantId)。 */
    Optional<Document> findById(Long id);

    /**
     * 按 id 查 visibility(TENANT/PUBLIC/PRIVATE)。V9 加列但 Document 聚合未携带, 供 DocumentAccessGuard 做
     * PRIVATE 判定; 不存在返 empty, 调用方按保守默认处理。
     */
    default Optional<String> findVisibilityById(Long id) {
        return Optional.empty();
    }

    /** 批量按 id 回查文档，用于检索命中后的状态、租户和真实版本二次校验。 */
    java.util.List<Document> findByIdIn(java.util.Collection<Long> ids);

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

    /**
     * Task 11 / P0: 租户 + allowedDocumentIds 同时过滤的分页查询 — DB 层完成, 防内存过滤越权。
     *
     * @param tenantId 当前调用方 tenantId (必填)
     * @param allowedDocumentIds null = 本 tenant 全可见 (admin 哨兵); 非空 = 显式白名单
     * @param status null = 不限状态
     * @param keyword null/空 = 不模糊搜索
     */
    Page<DocumentSummary> listAccessible(
            String tenantId,
            Set<Long> allowedDocumentIds,
            DocumentStatus status,
            String keyword,
            Pageable pageable);

    /** 按 id 加载详情(含 chunk_count 关联统计, 不含 chunk 内容)。 */
    Optional<DocumentDetail> findDetailById(Long id);

    /**
     * Phase 3 / P3-1 (修正版 Phase 3): 按业务 source 找当前默认版本 (is_default=true, READY, 未软删)。
     *
     * <p>用途: RetrieveService 在用户没显式传 version 时 fallback 找 default 版本过滤, 避免跨版本混查 (Spring Boot 2
     * javax vs Spring Boot 3 jakarta).
     *
     * <p>不变量: 同 source + READY + !deleted 最多 1 条 is_default=true. 实现参见 {@code
     * JpaDocumentRepository.findDefaultReadyBySource}.
     *
     * @return 不存在 default 版本 (新 source 未上传 / 全软删) 返 empty, 调用方降级全库检索。
     */
    Optional<Document> findDefaultReadyBySource(String source);

    /**
     * Phase 3 / P3-1: source 下是否已存在任意 default 文档 (任意状态, 排除软删)。
     *
     * <p>DocumentUploadService 在新建 doc 时调用: 若 source 已有 default (即使非 READY) 则不抢占; 反之 (source 首次上传 /
     * 老 default 被软删) 则把新 doc 标 isDefault=true。
     *
     * <p>不限定 status 是因为 parsingTrigger 还未跑完 (doc 还在 UPLOADED 状态), 用 READY 过滤会误判抢 default; 真正检索时由
     * {@link #findDefaultReadyBySource} 二次过滤 READY 状态来兜底。
     */
    boolean existsDefaultBySource(String source);

    /** 同租户、同一逻辑文档是否已有未删除的当前版本。 */
    boolean existsCurrentByLogicalKey(String tenantId, String logicalDocumentKey);

    /** 加锁读取同一逻辑文档当前版本，用于并发安全地切换 current。 */
    Optional<Document> findCurrentByLogicalKeyForUpdate(String tenantId, String logicalDocumentKey);

    /** 返回租户内所有可检索的逻辑文档当前版本 id；source 可空。null 表示适配器暂不支持，调用方兼容旧实现。 */
    default Optional<java.util.Set<Long>> findCurrentIndexedIds(String tenantId, String source) {
        return Optional.empty();
    }

    /**
     * 解析当前检索范围内每个文档的在线 generation。Optional.empty 表示旧适配器不支持该能力； present(emptyMap) 表示该范围没有可检索文档，调用方应
     * fail closed。
     */
    default Optional<java.util.Map<Long, Integer>> findActiveGenerations(
            String tenantId,
            String source,
            String version,
            String language,
            java.util.Collection<Long> candidateDocumentIds) {
        return Optional.empty();
    }

    /**
     * Phase 3 / P3-2: 拉取 pending_milvus_delete=true 的文档 (限制条数, 升序排 id 防跨周期重复同一条)。
     *
     * <p>MilvusDeleteSweeper 定时 (默认 60s) 调用, 重试软删时未能同步删除 Milvus 向量的文档。 成功删除后由 sweeper 调 {@link
     * #save} 清 pending 标记; 仍失败则保持 pending 等下轮。
     *
     * @param limit 单次拉取上限 (sweeper 自管速率, 防止单批过大)
     */
    java.util.List<Document> findDocsPendingMilvusDelete(int limit);

    /**
     * Task 4: 拿所有 INDEXED 且未软删的文档 (用于 reconcile 查 Milvus 向量是否丢失)。
     *
     * <p>reconcile job 用当前 generation 的向量存在性探测；不存在时触发安全 rebuild。
     *
     * @param limit 单次拉取上限
     */
    java.util.List<Document> findIndexed(int limit);

    /**
     * Task 4: 拉卡在 in-flight 中间态 (PARSING/CHUNKED/EMBEDDING/INDEXING) 且 lastStateChangeAt 超过阈值的文档 —
     * 由 reconcile 标记 FAILED + 触发重处理。
     *
     * @param thresholdMinutes 阈值分钟数; 此前没动的 in-flight 文档视为卡死
     * @param limit 单次拉取上限
     */
    java.util.List<Document> findStuckInPipeline(int thresholdMinutes, int limit);

    /** 异步入库补偿：查找已上传超过阈值、但仍没有 parse_task 的文档。 */
    default java.util.List<Document> findUploadedWithoutParseTask(
            java.time.Instant olderThan, int limit) {
        return java.util.List.of();
    }
}
