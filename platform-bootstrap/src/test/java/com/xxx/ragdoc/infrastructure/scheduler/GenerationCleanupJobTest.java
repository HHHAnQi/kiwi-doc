package com.xxx.ragdoc.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.port.ChunkRepository;
import com.xxx.ragdoc.application.document.port.GenerationCleanupRepository;
import com.xxx.ragdoc.application.document.port.VectorStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationCleanupJobTest {
    private final GenerationCleanupRepository repository = mock(GenerationCleanupRepository.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ChunkRepository chunkRepository = mock(ChunkRepository.class);
    private final GenerationCleanupJob job =
            new GenerationCleanupJob(repository, vectorStore, chunkRepository);

    @Test
    void deletesVectorBeforeChunkAndMarksDone() {
        GenerationCleanupRepository.Task task = new GenerationCleanupRepository.Task(1L, 10L, 2, 0);
        when(repository.findDue(any(Instant.class), anyInt())).thenReturn(List.of(task));
        when(repository.claim(eq(1L), any(Instant.class), any(Instant.class))).thenReturn(true);

        job.sweep();

        var order = inOrder(vectorStore, chunkRepository, repository);
        order.verify(vectorStore).deleteByDocumentIdAndGeneration(10L, 2);
        order.verify(chunkRepository).deleteByDocumentIdAndGeneration(10L, 2);
        order.verify(repository).markDone(1L);
    }

    @Test
    void failureIsRetriedWithoutDeletingMysqlChunks() {
        GenerationCleanupRepository.Task task = new GenerationCleanupRepository.Task(2L, 20L, 3, 1);
        when(repository.findDue(any(Instant.class), anyInt())).thenReturn(List.of(task));
        when(repository.claim(eq(2L), any(Instant.class), any(Instant.class))).thenReturn(true);
        doThrow(new IllegalStateException("milvus unavailable"))
                .when(vectorStore)
                .deleteByDocumentIdAndGeneration(20L, 3);

        job.sweep();

        verify(chunkRepository, never()).deleteByDocumentIdAndGeneration(anyLong(), anyInt());
        verify(repository)
                .markRetry(
                        eq(2L),
                        eq(2),
                        any(Instant.class),
                        contains("milvus unavailable"),
                        eq(false));
        verify(repository, never()).markDone(anyLong());
    }
}
