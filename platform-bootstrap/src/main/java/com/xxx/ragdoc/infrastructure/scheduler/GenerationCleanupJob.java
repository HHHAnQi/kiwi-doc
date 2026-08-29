package com.xxx.ragdoc.infrastructure.scheduler;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.GenerationCleanupRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 删除切换后已失效的 Milvus 向量和 MySQL chunks；失败可见、可退避、可重试。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationCleanupJob {
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 10;

    private final GenerationCleanupRepository repository;
    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;

    @Scheduled(
            fixedDelayString = "${rag.generation-cleanup.interval-ms:60000}",
            initialDelayString = "${rag.generation-cleanup.initial-delay-ms:30000}")
    public void sweep() {
        Instant now = Instant.now();
        for (GenerationCleanupRepository.Task task : repository.findDue(now, BATCH_SIZE)) {
            if (!repository.claim(task.id(), now, now.plus(Duration.ofMinutes(5)))) continue;
            try {
                // 先删派生索引，再删 SoT 的旧 chunk；两步均为幂等操作。
                vectorStore.deleteByDocumentIdAndGeneration(task.documentId(), task.generation());
                chunkRepository.deleteByDocumentIdAndGeneration(
                        task.documentId(), task.generation());
                repository.markDone(task.id());
            } catch (Exception e) {
                int attempts = task.attempts() + 1;
                long backoffSeconds = Math.min(3600L, 30L << Math.min(attempts - 1, 6));
                repository.markRetry(
                        task.id(),
                        attempts,
                        now.plusSeconds(backoffSeconds),
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        attempts >= MAX_ATTEMPTS);
                log.warn(
                        "generation_cleanup.failed job_id={}, doc_id={}, generation={}, attempts={}, error={}",
                        task.id(),
                        task.documentId(),
                        task.generation(),
                        attempts,
                        e.getMessage());
            }
        }
    }
}
