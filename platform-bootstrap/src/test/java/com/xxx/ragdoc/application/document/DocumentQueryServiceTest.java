package com.xxx.ragdoc.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.application.document.query.DocumentDetail;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.document.DocumentStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** DocumentQueryService 单测 - 纯读路径校验。 */
@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceTest {

    @Mock private DocumentRepository documentRepository;

    @InjectMocks private DocumentQueryService queryService;

    @Test
    void list_ReturnsPageFromRepositoryWithStatusAndKeyword() {
        // given
        com.xxx.ragdoc.application.document.query.DocumentSummary sample =
                new com.xxx.ragdoc.application.document.query.DocumentSummary(
                        1L, "sentinel.pdf", DocumentStatus.READY, 100L, 5, null, null);
        Page<com.xxx.ragdoc.application.document.query.DocumentSummary> stub =
                new PageImpl<>(List.of(sample));
        when(documentRepository.list(eq(DocumentStatus.READY), eq("sentinel"), any()))
                .thenReturn(stub);

        // when
        Page<com.xxx.ragdoc.application.document.query.DocumentSummary> result =
                queryService.list(DocumentStatus.READY, "sentinel", PageRequest.of(0, 10));

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).docId()).isEqualTo(1L);
    }

    @Test
    void getDetail_ReturnsDetailIfFound() {
        DocumentDetail detail =
                new DocumentDetail(
                        1L,
                        "f.pdf",
                        "application/pdf",
                        DocumentStatus.READY,
                        100,
                        5,
                        0,
                        null,
                        null,
                        null);
        when(documentRepository.findDetailById(1L)).thenReturn(Optional.of(detail));

        DocumentDetail result = queryService.getDetail(1L);

        assertThat(result.docId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void getDetail_ThrowsNotFoundIfMissing() {
        when(documentRepository.findDetailById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getDetail(999L))
                .isInstanceOf(NotFoundException.class);
    }
}
