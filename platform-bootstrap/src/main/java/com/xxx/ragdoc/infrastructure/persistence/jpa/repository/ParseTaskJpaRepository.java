package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.ParseTaskEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * parse_tasks 表 Spring Data JPA 仓库。
 *
 * <p>本接口只承担 CRDU; 状态迁移 invariant 由 application 层(ParseTaskService)守护。
 *
 * <p>{@link #leaseNextPending} 是 worker 抢占的核心原子操作: PESSIMISTIC_WRITE 行锁 + 限制单条 +
 * 状态转移(MOD sql, 一次 round-trip 完成 select+update)。
 */
@Repository
public interface ParseTaskJpaRepository extends JpaRepository<ParseTaskEntity, Long> {

    Optional<ParseTaskEntity> findByContentHash(String contentHash);

    Optional<ParseTaskEntity> findByDocumentId(Long documentId);

    /**
     * 原子 lease: 抢一条 PENDING + visible_at ≤ now 的 task, 转 RUNNING, 标 leasedBy + visibleAt 延长。
     *
     * <p>用法: 实现 service 层先 @Lock(PESSIMISTIC_WRITE) 查一条, 再 update — Spring Data JPA 不能直接
     * 在 Derived Query 上加锁+update, 这里只提供候选查找(加锁), update 走 {@link #markRunning}.
     * 组合语义见 JpaParseTaskRepository.leaseNextPending()。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT t FROM ParseTaskEntity t "
                    + "WHERE t.status = 'PENDING' AND t.visibleAt <= :now "
                    + "ORDER BY t.id ASC")
    List<ParseTaskEntity> findLeaseCandidates(@Param("now") Instant now);

    /**
     * 把候选 task 转 RUNNING(单条 update, 由 service 层在事务内串行: find → markRunning)。
     *
     * @return 受影响行数(1=抢成功, 0=被别的 worker 抢走)
     */
    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET "
                    + "t.status = 'RUNNING', "
                    + "t.leasedBy = :leasedBy, "
                    + "t.visibleAt = :leaseUntil, "
                    + "t.updatedAt = :now "
                    + "WHERE t.id = :id AND t.status = 'PENDING'")
    int markRunning(
            @Param("id") Long id,
            @Param("leasedBy") String leasedBy,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    /** 心跳 job: 把过期 RUNNING(visible_at < now) 回滚 PENDING, 清 leasedBy。 */
    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET "
                    + "t.status = 'PENDING', "
                    + "t.leasedBy = NULL, "
                    + "t.updatedAt = :now "
                    + "WHERE t.status = 'RUNNING' AND t.visibleAt < :now")
    int reapExpiredRunning(@Param("now") Instant now);
}
