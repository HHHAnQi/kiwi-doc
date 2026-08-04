package com.xxx.ragdoc.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 3 / P3-2: 共享 {@link TransactionTemplate} Bean。
 *
 * <p>用途: DocumentManageService / DocumentUploadService 都用编程式短事务避免把 Milvus / parse 等慢调用
 * 包进 @Transactional 持锁过久 (P3-A 重灌死锁根因)。两个 service 之前各自 new TransactionTemplate(txManager),
 * 测试时不易替换 → 提到 Bean 让测试可注入 mock 或自实现的同步版本。
 *
 * <p>统一 REQUIRES_NEW: softDelete 短事务不应被外层 (若有) @Transactional 牵连; upload 同理。
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate shortTx(PlatformTransactionManager txManager) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }
}
