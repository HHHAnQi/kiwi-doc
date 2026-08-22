package com.xxx.ragdoc.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.application.auth.AuthContext;
import com.xxx.ragdoc.application.auth.PermissionResolverPort;
import com.xxx.ragdoc.application.document.port.DocumentRepository;
import com.xxx.ragdoc.common.exception.NotFoundException;
import com.xxx.ragdoc.domain.auth.Principal;
import com.xxx.ragdoc.domain.document.Document;
import com.xxx.ragdoc.domain.shared.ContentHash;
import com.xxx.ragdoc.domain.shared.DocumentId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 11 P0: {@link DocumentAccessGuard} 跨租户 + ACL 越权测试。
 *
 * <p>覆盖任务文档 §6.4 跨租户场景 + ACL 越权场景 (memory mock, 不依赖 DB):
 *
 * <ul>
 *   <li>A1 可读自己的 PRIVATE doc
 *   <li>A2 无 ACL 不能读 A1 私有 doc → 404
 *   <li>B1 跨租户访问 A1 doc → 404
 *   <li>role:admin 在本租户内 → 通过
 *   <li>role:admin 跨租户 → 404
 *   <li>READ ACL 用户 → 通过 requireRead, 但 requireWrite/requireOwner 失败
 *   <li>OWNER ACL 用户 → 通过 requireOwner
 *   <li>不存在 docId → 404 (与无权一同, 防枚举)
 * </ul>
 */
@DisplayName("Task 11 P0 DocumentAccessGuard 跨租户/ACL 隔离")
class DocumentAccessGuardCrossTenantTest {

    private DocumentRepository documentRepository;
    private PermissionResolverPort permissionResolver;
    private DocumentAccessGuard guard;

    @BeforeEach
    void setup() {
        documentRepository = mock(DocumentRepository.class);
        permissionResolver = mock(PermissionResolverPort.class);
        guard = new DocumentAccessGuard(documentRepository, permissionResolver);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    private void loginAs(String tenant, String user, boolean admin) {
        AuthContext.set(
                new Principal(
                        tenant,
                        user,
                        admin
                                ? Set.of("role:default", "role:user", "role:admin")
                                : Set.of("role:default", "role:user"),
                        "tok-" + user));
    }

    private Document makeDoc(long id, String tenant) {
        Document d =
                Document.newUploaded(
                        new ContentHash(
                                "0000000000000000000000000000000000000000000000000000000000000000"),
                        "f.pdf",
                        "application/pdf",
                        1L,
                        tenant);
        d.assignId(new DocumentId(id));
        d.startParsing();
        return d;
    }

    @Test
    @DisplayName("A1 admin 用户可访问本租户任意 doc (requireRead 通过)")
    void adminCanReadOwnTenant() {
        loginAs("tenantA", "userA1", true);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

        Document result = guard.requireRead(100L);
        assertThat(result.id().value()).isEqualTo(100L);
    }

    @Test
    @DisplayName("role:admin 跨租户访问 → 404 (本租户 admin 不跨租户)")
    void adminCrossTenantBlocked() {
        loginAs("tenantB", "userB1", true);
        Document doc = makeDoc(100, "tenantA"); // doc 属 tenantA
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> guard.requireRead(100L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("A2 无 ACL 不能读 A1 visibility=TENANT 文档 (本租户默认可见) → 通过")
    void sameTenantNonPrivateAutoRead() {
        // 设计: visibility ∈ {TENANT, PUBLIC} 在本租户内自动 READ。无 ACL 也能读。
        // 这是 PUBLIC=本租户内公开 语义的直接体现 (Task 11 决策)
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(documentRepository.findVisibilityById(100L)).thenReturn(Optional.of("TENANT"));

        assertThat(guard.requireRead(100L).id().value()).isEqualTo(100L);
    }

    @Test
    @DisplayName("B1 跨租户访问 A1 文档 → 404 (即使 visibility=TENANT)")
    void crossTenantBlockedEvenThoughTenantVisible() {
        loginAs("tenantB", "userB1", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> guard.requireRead(100L)).isInstanceOf(NotFoundException.class);
        verify(permissionResolver, never()).hasExplicitAcl(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("B1 跨租户访问 A1 doc → 404 (即使 ACL 命中也无意义, tenant 先于 ACL)")
    void crossTenantBlockedBeforeAcl() {
        loginAs("tenantB", "userB1", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> guard.requireRead(100L)).isInstanceOf(NotFoundException.class);
        // ACL 在 tenant 检查之前不应被查 (本任务设计 — tenant 不符直接 404)
        verify(permissionResolver, never()).hasExplicitAcl(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("用户有 USER+READ ACL → requireRead 通过")
    void readAclAllowsRequireRead() {
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(permissionResolver.hasExplicitAcl(eq(100L), any(), eq("READ"))).thenReturn(true);

        assertThat(guard.requireRead(100L).id().value()).isEqualTo(100L);
    }

    @Test
    @DisplayName("同租户 visibility=TENANT → 自动 READ (无需 ACL)")
    void readAclNotNeedForTenantVisible() {
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(documentRepository.findVisibilityById(100L)).thenReturn(Optional.of("TENANT"));

        assertThat(guard.requireRead(100L).id().value()).isEqualTo(100L);
        // 没调 ACL (TENANT visibility 自带 READ)
        verify(permissionResolver, never()).hasExplicitAcl(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("READ ACL 用户 → requireRead 通过, 但 requireWrite 失败 → 404")
    void readAclNotEnoughForWrite() {
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(permissionResolver.hasExplicitAcl(eq(100L), any(), eq("WRITE"))).thenReturn(false);
        when(permissionResolver.hasExplicitAcl(eq(100L), any(), eq("OWNER"))).thenReturn(false);

        // requireWrite 失败 (visibility 默认 TENANT 走 READ 路径, 但 WRITE 路径不走 visibility)
        assertThatThrownBy(() -> guard.requireWrite(100L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("OWNER ACL → requireOwner 通过 (能删)")
    void ownerAclAllowsRequireOwner() {
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(permissionResolver.hasExplicitAcl(eq(100L), any(), eq("OWNER"))).thenReturn(true);

        assertThat(guard.requireOwner(100L).id().value()).isEqualTo(100L);
    }

    @Test
    @DisplayName("同租户 visibility=PRIVATE 且无 ACL → 404 (P0 修复: 原硬编码 TENANT 使 PRIVATE 失效)")
    void privateDocBlockedWithoutAcl() {
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(documentRepository.findVisibilityById(100L)).thenReturn(Optional.of("PRIVATE"));
        when(permissionResolver.hasExplicitAcl(eq(100L), any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> guard.requireRead(100L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("同租户 visibility=PRIVATE + READ ACL → 通过 (显式授权仍有效)")
    void privateDocReadableWithAcl() {
        loginAs("tenantA", "userA2", false);
        Document doc = makeDoc(100, "tenantA");
        when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
        when(documentRepository.findVisibilityById(100L)).thenReturn(Optional.of("PRIVATE"));
        when(permissionResolver.hasExplicitAcl(eq(100L), any(), eq("READ"))).thenReturn(true);

        assertThat(guard.requireRead(100L).id().value()).isEqualTo(100L);
    }

    @Test
    @DisplayName("不存在 docId → 404 (与无权同返防枚举)")
    void nonExistentReturns404() {
        loginAs("tenantA", "userA1", false);
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireRead(999L)).isInstanceOf(NotFoundException.class);
    }
}
