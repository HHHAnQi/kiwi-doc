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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DocumentQueryServiceTest {

    @Mock private DocumentRepository documentRepository;
    // Task 11 P0: DocumentQueryService 现依赖 PermissionResolverPort + DocumentAccessGuard
    @Mock private com.xxx.ragdoc.application.auth.PermissionResolverPort permissionResolver;
    @Mock private DocumentAccessGuard accessGuard;

    @InjectMocks private DocumentQueryService queryService;

    @BeforeEach
    void setDefaultPrincipal() {
        com.xxx.ragdoc.application.auth.AuthContext.set(
                com.xxx.ragdoc.application.auth.AuthContext.DEFAULT_PRINCIPAL);
        // mock resolver 默认返空 scope, 让 listAccessible 走正常 path
        when(permissionResolver.resolveAccessScope(any()))
                .thenReturn(
                        com.xxx.ragdoc.application.auth.AccessScope.of(
                                "default", java.util.Set.of()));
    }

    @AfterEach
    void clearAuth() {
        com.xxx.ragdoc.application.auth.AuthContext.clear();
    }

    @Test
    void list_ReturnsPageFromRepositoryWithStatusAndKeyword() {
        // given
        com.xxx.ragdoc.application.document.query.DocumentSummary sample =
                new com.xxx.ragdoc.application.document.query.DocumentSummary(
                        1L,
                        "sentinel.pdf",
                        DocumentStatus.INDEXED,
                        100L,
                        5,
                        null,
                        null,
                        "sentinel",
                        null,
                        "zh",
                        "doc",
                        false, // isDefault (P3-1)
                        false); // pendingMilvusDelete (P3-2)
        Page<com.xxx.ragdoc.application.document.query.DocumentSummary> stub =
                new PageImpl<>(List.of(sample));
        // Task 11 P0: list 方法现调 listAccessible, 而非旧 list
        when(documentRepository.listAccessible(
                        eq("default"), any(), eq(DocumentStatus.INDEXED), eq("sentinel"), any()))
                .thenReturn(stub);

        // when
        Page<com.xxx.ragdoc.application.document.query.DocumentSummary> result =
                queryService.list(DocumentStatus.INDEXED, "sentinel", PageRequest.of(0, 10));

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
                        DocumentStatus.INDEXED,
                        100,
                        5,
                        0,
                        null,
                        null,
                        null,
                        "unknown",
                        null,
                        "zh",
                        "doc",
                        false, // isDefault (P3-1)
                        false); // pendingMilvusDelete (P3-2)
        when(documentRepository.findDetailById(1L)).thenReturn(Optional.of(detail));
        // Task 11 P0: getDetail 先走 accessGuard.requireRead (默认 mock 返 null 也行, 因为没 throw 即通过)
        when(accessGuard.requireRead(1L))
                .thenReturn(
                        com.xxx.ragdoc.domain.document.Document.newUploaded(
                                new com.xxx.ragdoc.domain.shared.ContentHash("a".repeat(64)),
                                "f",
                                "x",
                                1L,
                                "default"));

        DocumentDetail result = queryService.getDetail(1L);

        assertThat(result.docId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    void getDetail_ThrowsNotFoundIfMissing() {
        // accessGuard 自己内部加载 doc 99 → 找不到 → throw NotFoundException
        when(accessGuard.requireRead(999L))
                .thenThrow(
                        new com.xxx.ragdoc.common.exception.NotFoundException(
                                com.xxx.ragdoc.common.exception.ErrorCode.DOC_NOT_FOUND, "miss"));

        assertThatThrownBy(() -> queryService.getDetail(999L))
                .isInstanceOf(NotFoundException.class);
    }
}
