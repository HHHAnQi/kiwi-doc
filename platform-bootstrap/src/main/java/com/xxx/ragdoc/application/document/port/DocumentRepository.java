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
}
