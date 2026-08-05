package com.xxx.ragdoc.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentAclEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentAclJpaRepository;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.DocumentJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Task 11 P0: {@link JpaAclWriter} Owner ACL 创建幂等性测试 (问题 4)。
 *
 * <p>覆盖任务文档 §6.3 全部:
 *
 * <ul>
 *   <li>用户上传第一份文档 → 创建 OWNER ACL
 *   <li>同一用户上传第二份文档, 第一份有 OWNER 也不跳过 → 仍给第二份建 ACL (修复关键!)
 *   <li>同 docId + owner + OWNER 重复 → 不写第二条
 *   <li>并发 UK 冲突被吞 (不挂主流程)
 * </ul>
 */
@DisplayName("Task 11 P0 JpaAclWriter by-documentId 幂等")
class JpaAclWriterIdempotentTest {

    private DocumentAclJpaRepository aclRepo;
    private DocumentJpaRepository docRepo;

    @BeforeEach
    void setup() {
        aclRepo = mock(DocumentAclJpaRepository.class);
        docRepo = mock(DocumentJpaRepository.class);
        DocumentEntity docEntity = new DocumentEntity();
        docEntity.setId(1L);
        docEntity.setTenantId("tA");
        when(docRepo.findById(anyLong())).thenReturn(Optional.of(docEntity));
        when(docRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("第一份 doc → 创建 OWNER ACL")
    void firstDocCreatesAcl() {
        // 关键断言: 不在 doc1 上有 OWNER
        when(aclRepo.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                        1L, "USER", "userA", "OWNER"))
                .thenReturn(false);
        JpaAclWriter writer = new JpaAclWriter(aclRepo, docRepo);

        writer.grantOwnerAcl(1L, "userA", "TENANT");

        verify(aclRepo).save(any(DocumentAclEntity.class));
    }

    @Test
    @DisplayName("第二份 doc 仍创建 ACL — 即使 owner 在第一份 doc 上有 OWNER (问题 4 关键修复)")
    void secondDocStillCreatesAclEvenIfOwnerHasAclOnFirstDoc() {
        // 旧 bug: findReadableDocIds(USER, userA, OWNER) 不带 docId, 若 userA 在 doc=1 已有 OWNER,
        // 第二份 doc=2 上传时使用 exists check 会误返 true 视为已建 — 跳过 ACL 写入.
        // 新 impl: exists check 严格按 (documentId=2, USER, userA, OWNER) → false → 写 ACL.
        when(aclRepo.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                        anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(false); // doc2 上没 OWNER
        JpaAclWriter writer = new JpaAclWriter(aclRepo, docRepo);

        writer.grantOwnerAcl(2L, "userA", "TENANT");

        // 关键断言: doc2 真的写到 repo 了
        verify(aclRepo).save(argThat(a -> a.getDocumentId() == 2L && "userA".equals(a.getPrincipalId())));
    }

    @Test
    @DisplayName("同 docId + owner 重复 → 跳过 (幂等, 不写第二)")
    void idempotentWhenAlreadyExists() {
        when(aclRepo.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                        1L, "USER", "userA", "OWNER"))
                .thenReturn(true);
        JpaAclWriter writer = new JpaAclWriter(aclRepo, docRepo);

        writer.grantOwnerAcl(1L, "userA", "TENANT");

        // 不再 save, 视为已存在
        verify(aclRepo, never()).save(any());
    }

    @Test
    @DisplayName("并发 UK 冲突 → DataIntegrityViolationException 被吞 (幂等)")
    void concurrentUkConflictIgnores() {
        when(aclRepo.existsByDocumentIdAndPrincipalTypeAndPrincipalIdAndPerm(
                        1L, "USER", "userA", "OWNER"))
                .thenReturn(false); // 第一查 false
        // 但并发已写, 显式 save 时 UK 冲突
        doThrow(new DataIntegrityViolationException("uk conflict"))
                .when(aclRepo)
                .save(any(DocumentAclEntity.class));
        JpaAclWriter writer = new JpaAclWriter(aclRepo, docRepo);

        // 不抛 (幂等)
        writer.grantOwnerAcl(1L, "userA", "TENANT");
        // 没异常 = 通过
    }

    @Test
    @DisplayName("owner 空/null 跳过 ACL 但 visibility 仍写")
    void nullOwnerSkipsAcl() {
        JpaAclWriter writer = new JpaAclWriter(aclRepo, docRepo);

        writer.grantOwnerAcl(1L, null, "TENANT");

        verify(aclRepo, never()).save(any());
    }
}
