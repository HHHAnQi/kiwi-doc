package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.PrincipalEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * principal 表 Spring Data JPA 仓库 (V9 RAG-Perm-001)。
 *
 * <p>仅暴露 token 查询; tenant/user 维度的查询交给将来 admin 后台扩展。AuthFilter 通过 {@link #findByToken(String)} 解析
 * Bearer token → PrincipalEntity。
 */
@Repository
public interface PrincipalJpaRepository extends JpaRepository<PrincipalEntity, String> {

    Optional<PrincipalEntity> findByToken(String token);
}
