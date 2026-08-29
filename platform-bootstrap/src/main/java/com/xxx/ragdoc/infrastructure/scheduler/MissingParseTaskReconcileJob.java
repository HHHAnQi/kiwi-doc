package com.xxx.ragdoc.infrastructure.scheduler;

import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 补偿 Document 已提交、但进程在 ParseTask 建账前崩溃的窗口。仅异步解析模式启用。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.parser", name = "mode", havingValue = "async")
public class MissingParseTaskReconcileJob {
    private final DocumentRepository documentRepository;
    private final ParsingTrigger parsingTrigger;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${rag.parser.missing-task-reconcile-ms:60000}")
    public void reconcile() {
        Instant olderThan = Instant.now(clock).minusSeconds(60);
        var documents = documentRepository.findUploadedWithoutParseTask(olderThan, 100);
        int repaired = 0;
        for (var document : documents) {
            try {
                parsingTrigger.trigger(document.id().value());
                repaired++;
            } catch (Exception e) {
                log.error(
                        "parse_task.missing_reconcile_failed doc_id={}, error={}",
                        document.id().value(),
                        e.getMessage(),
                        e);
            }
        }
        if (!documents.isEmpty()) {
            log.info(
                    "parse_task.missing_reconcile candidates={}, repaired={}",
                    documents.size(),
                    repaired);
        }
    }
}
