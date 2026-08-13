package com.xxx.ragdoc.parser.application;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.GenerationCleanupRepository;
import com.xxx.ragdoc.domain.document.Chunk;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.document.ParseTask;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 同一 MySQL 事务内提交 Document INDEXED 与 ParseTask PARSED，避免真假双终态。 */
@Service
@RequiredArgsConstructor
public class ParseCompletionService {
    private final DocumentRepository documentRepository;
    private final ParseTaskService parseTaskService;
    private final GenerationCleanupRepository generationCleanupRepository;

    @Transactional
    public ParseTask complete(ParseTask leased, List<Chunk> savedChunks) {
        Document document = documentRepository.findById(leased.documentId())
                .orElseThrow(() -> new IllegalStateException("Document 不存在: " + leased.documentId()));
        if (leased.triggerType() == ParseTask.TriggerType.REBUILD) {
            int previousGeneration = document.activeGeneration();
            document.activateGeneration(leased.generation());
            if (previousGeneration != leased.generation()) {
                generationCleanupRepository.enqueue(leased.documentId(), previousGeneration);
            }
        } else {
            document.markChunked(savedChunks);
            document.markEmbedding();
            document.markIndexing();
            document.markIndexed();
            if (document.activeGeneration() != leased.generation()) {
                document.beginGenerationBuild(leased.generation());
                document.activateGeneration(leased.generation());
            }
        }
        documentRepository.save(document);
        return parseTaskService.markParsed(withChunks(leased, savedChunks.size()));
    }

    private static ParseTask withChunks(ParseTask task, int chunksWritten) {
        return task.withExecutionState(
                task.status(), task.retryCount(), chunksWritten, task.chunkSeqOffset(),
                task.errorMessage(), task.errorClass(), task.attempts(), task.visibleAt(),
                task.leasedBy(), task.updatedAt());
    }
}
