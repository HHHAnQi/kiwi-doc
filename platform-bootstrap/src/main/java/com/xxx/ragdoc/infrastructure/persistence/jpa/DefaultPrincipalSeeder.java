package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.PrincipalEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.PrincipalJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * P0 安全修复配套: dev/local/test profile 专用默认 principal 种子。
 *
 * <p>背景: V9 曾把公开默认 token (dev-token-change-me / admin-token-change-me) 无条件 seed 进 principal 表, V22
 * 迁移已删除; 但本地开发与 e2e 脚本 (scripts/*.sh, Makefile) 依赖这两个 token, 因此开发便利改为: <b>只在 dev/local/test profile
 * 下</b>启动时幂等补种, 生产 profile 该 bean 根本不加载。
 *
 * <p>比原 V9 方案强在: 默认 token 是否存在由 active profile 决定, 而不是由"跑过哪版迁移"决定 — 生产库从任何历史版本升级上来, V22 之后都不会再有公开默认
 * token。
 */
@Slf4j
@Component
@Profile({"dev", "local", "test"})
@RequiredArgsConstructor
public class DefaultPrincipalSeeder {

    private final PrincipalJpaRepository principalJpaRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        seedOne("dev-token-change-me", "dev", "role:default,role:user");
        seedOne("admin-token-change-me", "admin", "role:default,role:user,role:admin");
        log.warn(
                "⚠ DefaultPrincipalSeeder: 已在 dev profile 种入公开默认 token (dev/admin-token-change-me)。"
                        + "该 bean 带 @Profile({dev,local,test}), 生产环境不会加载; 生产请用强随机 token。");
    }

    private void seedOne(String token, String userId, String roles) {
        if (principalJpaRepository.findById(token).isPresent()) {
            return; // 幂等: 已存在(含手动改过密码的行)不覆盖
        }
        PrincipalEntity e = new PrincipalEntity();
        e.setToken(token);
        e.setTenantId("default");
        e.setUserId(userId);
        e.setRoles(roles);
        principalJpaRepository.save(e);
    }
}
