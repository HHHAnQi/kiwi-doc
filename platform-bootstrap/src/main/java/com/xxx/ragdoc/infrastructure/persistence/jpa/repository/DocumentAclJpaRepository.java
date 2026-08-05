package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.DocumentAclEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * document_acl 表 Spring Data JPA 仓库 (V9 RAG-Perm-001)。
 *
 * <p>{@link #findReadableDocIds} 求 (principalType, principalId, perms) → granted docIds 集合;
 * 调用方 PermissionResolver 把 USER/ROLE/TENANT 三档各查一次取并集。
 */
@Repository
public interface DocumentAclJpaRepository extends JpaRepository<DocumentAclEntity, Long> {

    /**
     * 求 (principalType, principalId) 在 permsIn 集合内授予的 document_id 列表。
     *
     * @param principalType USER / ROLE / TENANT
     * @param principalId 对应的 id (user_id / role:xxx / tenant_id)
     * @param principalIdAlt 备选 id (主调对同一行允许 degenerate 别名, 暂未启用; 传同 principalId 即可)
     * @param permsIn 权限集合, 通常 ["READ","WRITE","OWNER"]
     */
    @Query(
            "SELECT DISTINCT a.documentId FROM DocumentAclEntity a "
                    + "WHERE a.principalType = :principalType "
                    + "AND a.principalId = :principalId "
                    + "AND a.perm IN :permsIn")
    List<Long> findReadableDocIds(
            @Param("principalType") String principalType,
            @Param("principalId") String principalId,
            @Param("permsIn") List<String> permsIn);
}
