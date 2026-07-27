package com.xxx.ragdoc.infrastructure.parse;

import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.domain.document.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V1 解析触发器桩实现:同步空走一遍状态机,标记 PARSING → 仍 PARSING(留待 V2 接 Tika)。
 *
 * <p>V2 替换为:Apache Tika 抽内容 + 切片 + BGE-M3 向量 + Milvus 写入,
 * 状态在 try/catch 末尾迁至 READY 或 FAILED。
 *
 * <p>V3 替换为:发 DocumentParsedRequest 到 RocketMQ,
 * 由独立 parser-service 消费,trigger 立即返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StubParsingTrigger implements ParsingTrigger {

    private final DocumentRepository documentRepository;

    @Override
    public void trigger(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document 不存在: " + documentId));
        try {
            doc.startParsing();
            documentRepository.save(doc);
            log.info("parse.triggered doc_id={} (V1 stub, 未真正解析)", documentId);
        } catch (IllegalStateException e) {
            log.warn("parse.trigger_skipped doc_id={}, reason={}", documentId, e.getMessage());
        }
    }
}
