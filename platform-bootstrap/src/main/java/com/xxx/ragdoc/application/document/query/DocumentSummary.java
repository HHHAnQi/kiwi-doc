package com.xxx.ragdoc.application.document.query;

import com.xxx.ragdoc.domain.document.DocumentStatus;
import java.time.Instant;

/**
 * 文档列表项(轻量, 不含 chunk_count)。
 *
 * <p>列表分页时摘要返回 + 单条详情时用 {@link DocumentDetail}。
 *
 * @param chunkCount 关联 chunks 统计; V1 parsing stub 始终为 0
 * @param source V3 业务元数据: 来源组件(dubbo/nacos/seata/rocketmq/sentinel), 缺省 'unknown'
 * @param version V3 业务元数据: 版本号, 可空
 * @param language V3 业务元数据: 语言(zh/en), 缺省 'zh'
 * @param docType V3 业务元数据: 文档类型(doc/blog/release-notes/spec/demo), 缺省 'doc'
 * @param isDefault P3-1: 是否为同 source 的默认版本; RetrieveService 在用户没传 version 时按此过滤
 * @param pendingMilvusDelete P3-2: 软删后 Milvus 向量是否待清理; sweeper 周期收敛
 */
public record DocumentSummary(
        Long docId,
        String originalFilename,
        DocumentStatus status,
        long sizeBytes,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt,
        String source,
        String version,
        String language,
        String docType,
        boolean isDefault,
        boolean pendingMilvusDelete) {}
