package com.xxx.ragdoc.infrastructure.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.document.ParsingTrigger;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.shared.ContentHash;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissingParseTaskReconcileJobTest {
    @Test
    void recreatesMissingTaskForOldUploadedDocument() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DocumentRepository repository = mock(DocumentRepository.class);
        ParsingTrigger trigger = mock(ParsingTrigger.class);
        Document document =
                Document.newUploaded(
                        new ContentHash("a".repeat(64)),
                        "guide.pdf",
                        "application/pdf",
                        10,
                        "tenant-a");
        document.assignId(new com.xxx.ragdoc.domain.shared.DocumentId(42L));
        when(repository.findUploadedWithoutParseTask(now.minusSeconds(60), 100))
                .thenReturn(List.of(document));

        new MissingParseTaskReconcileJob(repository, trigger, clock).reconcile();

        verify(trigger).trigger(42L);
    }
}
