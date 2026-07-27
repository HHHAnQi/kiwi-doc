package com.xxx.ragdoc.application.document.port;

import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.shared.ContentHash;

import java.util.Optional;

/**
 * Document 仓储端口(domain/application 只认此接口,具体实现藏于 infrastructure)。
 * 实现见 {@code JpaDocumentRepository}(infra 层)。
 *
 * <p>命名约定:端口接口放 application 包;adapter 实现放 infrastructure 包。
 * 这是六边形 / DDD 标准 DIP 结构。
 */
public interface DocumentRepository {

    /**
     * 保存(新建 + 状态更新共用)。
     * V1 简化:不做显式 save + update 区分,JPA merge 由实现层处理。
     */
    Document save(Document document);

    /**
     * 按内容 hash 查(用于幂等判断,V1 单租户)。
     */
    Optional<Document> findByContentHash(ContentHash hash);

    /**
     * 按 id 查。
     */
    Optional<Document> findById(Long id);
}
