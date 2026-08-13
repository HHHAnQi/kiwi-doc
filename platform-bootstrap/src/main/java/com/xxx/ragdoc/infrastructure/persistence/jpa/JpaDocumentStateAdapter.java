package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.document.port.DocumentStatePort;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentJpaRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 4 / V10 DocLifecycle: {@link DocumentStatePort} 的 JPA 实现。
 *
 * <p>每个 mark 方法走 {@code PROPAGATION_REQUIRES_NEW} 短事务, 立刻把 status + lastStateChangeAt 写库, 让
 * reconcile job 能识别 in-flight 阶段; 失败 (非法迁移 / DB 错误) 抛出, 由调用方决策 markFailed。
 *
 * <p>放 infrastructure 层 (合法引用 DocumentJpaRepository), application 层管道只看 port。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaDocumentStateAdapter implements DocumentStatePort {

    private final DocumentJpaRepository documentJpaRepository;
    private final DocumentMapper documentMapper; // 复用同一个 mapper util
    private final PlatformTransactionManager txManager;

    /** 短事务: 不传播外层管道事务, 让中间态立刻对其它会话可见。 */
    private final TransactionTemplate shortTx() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    @Override
    public void markChunked(Long documentId, List<Chunk> chunks) {
        shortTx()
                .executeWithoutResult(
                        status -> {
                            DocumentEntity entity =
                                    documentJpaRepository
                                            .findById(documentId)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "Document 不存在: " + documentId));
                            Document d = documentMapper.toDomain(entity);
                            d.markChunked(chunks);
                            documentMapper.toEntity(d, entity);
                            documentJpaRepository.save(entity);
                            log.debug(
                                    "doc_state.mark_chunked doc_id={}, status=CHUNKED", documentId);
                        });
    }

    @Override
    public void markEmbedding(Long documentId) {
        transition(documentId, Document::markEmbedding, "EMBEDDING");
    }

    @Override
    public void markIndexing(Long documentId) {
        transition(documentId, Document::markIndexing, "INDEXING");
    }

    @Override
    public void markIndexed(Long documentId) {
        transition(documentId, Document::markIndexed, "INDEXED");
    }

    /** 通用无参状态机推进。 */
    private void transition(
            Long documentId, java.util.function.Consumer<Document> mutator, String label) {
        shortTx()
                .executeWithoutResult(
                        status -> {
                            DocumentEntity entity =
                                    documentJpaRepository
                                            .findById(documentId)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "Document 不存在: " + documentId));
                            Document d = documentMapper.toDomain(entity);
                            mutator.accept(d);
                            documentMapper.toEntity(d, entity);
                            documentJpaRepository.save(entity);
                            log.debug(
                                    "doc_state.transition doc_id={}, status={}", documentId, label);
                        });
    }
}
