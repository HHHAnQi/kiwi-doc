package com.xxx.ragdoc.infrastructure.parse;

import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.port.ParseTaskRepository;
import com.xxx.ragdoc.domain.document.*;
import com.xxx.ragdoc.domain.shared.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AsyncParsingTriggerGenerationTest {
    @Test
    void duplicateInflightRebuildDoesNotAllocateAnotherGeneration() {
        DocumentRepository documents = mock(DocumentRepository.class);
        ParseTaskRepository tasks = mock(ParseTaskRepository.class);
        Document doc =
                Document.newUploaded(
                        new ContentHash("a".repeat(64)),
                        "guide.pdf",
                        "application/pdf",
                        1,
                        "tenant-a");
        doc.assignId(new DocumentId(9L));
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        ParseTask existing =
                new ParseTask(
                        7L,
                        9L,
                        2,
                        ParseTask.TriggerType.REBUILD,
                        6L,
                        "a".repeat(64),
                        ParseTaskStatus.RUNNING,
                        0,
                        3,
                        0,
                        0,
                        null,
                        null,
                        List.of(),
                        now,
                        "worker",
                        ParseTask.DeliveryStatus.SENT,
                        0,
                        now,
                        null,
                        now,
                        now);
        when(documents.findById(9L)).thenReturn(Optional.of(doc));
        when(tasks.findByDocumentId(9L)).thenReturn(Optional.of(existing));

        new AsyncParsingTrigger(documents, tasks, Clock.fixed(now, ZoneOffset.UTC)).rebuild(9L);

        verify(tasks, never()).nextGeneration(anyLong());
        verify(tasks, never()).save(any());
    }
}
