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
 * <p>{@link #leaseNextPending} 是 worker 抢占的核心原子操作: PESSIMISTIC_WRITE 行锁 + 限制单条 + 状态转移(MOD sql, 一次
 * round-trip 完成 select+update)。
 */
@Repository
public interface ParseTaskJpaRepository extends JpaRepository<ParseTaskEntity, Long> {

    Optional<ParseTaskEntity> findFirstByDocumentIdOrderByGenerationDesc(Long documentId);

    Optional<ParseTaskEntity> findByDocumentIdAndGeneration(Long documentId, Integer generation);

    @Query("SELECT COALESCE(MAX(t.generation), 0) FROM ParseTaskEntity t WHERE t.documentId=:documentId")
    int maxGeneration(@Param("documentId") Long documentId);

    /**
     * 原子 lease: 抢一条 PENDING + visible_at ≤ now 的 task, 转 RUNNING, 标 leasedBy + visibleAt 延长。
     *
     * <p>用法: 实现 service 层先 @Lock(PESSIMISTIC_WRITE) 查一条, 再 update — Spring Data JPA 不能直接 在 Derived
     * Query 上加锁+update, 这里只提供候选查找(加锁), update 走 {@link #markRunning}. 组合语义见
     * JpaParseTaskRepository.leaseNextPending()。
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

    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET t.status='RUNNING', t.leasedBy=:leasedBy, "
                    + "t.visibleAt=:leaseUntil, t.updatedAt=:now "
                    + "WHERE t.id=:id AND t.status='PENDING' AND t.visibleAt<=:now")
    int markRunningById(
            @Param("id") Long id,
            @Param("leasedBy") String leasedBy,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET t.deliveryStatus='SENT', t.lastDeliveredAt=:now, "
                    + "t.deliveryError=NULL, t.deliveryLeasedBy=NULL, t.deliveryLeaseUntil=NULL, "
                    + "t.updatedAt=:now WHERE t.id=:id AND t.deliveryStatus='SENDING'")
    int markDeliverySucceeded(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET t.deliveryStatus="
                    + "CASE WHEN t.deliveryAttempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END, "
                    + "t.deliveryAttempts=t.deliveryAttempts+1, t.nextDeliveryAt=:nextAttemptAt, "
                    + "t.deliveryError=:error, t.deliveryLeasedBy=NULL, t.deliveryLeaseUntil=NULL, "
                    + "t.updatedAt=:now WHERE t.id=:id AND t.deliveryStatus='SENDING'")
    int markDeliveryFailed(
            @Param("id") Long id,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("error") String error,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET t.deliveryStatus='PENDING', "
                    + "t.deliveryAttempts=0, t.nextDeliveryAt=:now, t.deliveryError=NULL, "
                    + "t.deliveryLeasedBy=NULL, t.deliveryLeaseUntil=NULL, t.updatedAt=:now "
                    + "WHERE t.id=:id AND t.status='PENDING' AND t.deliveryStatus='DEAD'")
    int replayDeadDelivery(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET t.deliveryStatus='PENDING', "
                    + "t.nextDeliveryAt=:nextAttemptAt, t.deliveryError=NULL, t.updatedAt=:now, "
                    + "t.deliveryLeasedBy=NULL, t.deliveryLeaseUntil=NULL "
                    + "WHERE t.id=:id")
    int resetDeliveryPending(
            @Param("id") Long id,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    /**
     * 心跳回收必须同时恢复执行与投递两个状态域。若只 RUNNING→PENDING 而保留 delivery=SENT，
     * Outbox Relay 永远不会再次看到该任务。
     */
    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET "
                    + "t.status = 'PENDING', "
                    + "t.leasedBy = NULL, "
                    + "t.visibleAt = :now, "
                    + "t.deliveryStatus = 'PENDING', "
                    + "t.nextDeliveryAt = :now, "
                    + "t.deliveryError = NULL, "
                    + "t.deliveryLeasedBy = NULL, "
                    + "t.deliveryLeaseUntil = NULL, "
                    + "t.updatedAt = :now "
                    + "WHERE t.status = 'RUNNING' AND t.visibleAt < :now")
    int reapExpiredRunning(@Param("now") Instant now);

    @Query("SELECT t FROM ParseTaskEntity t WHERE t.status = :status AND t.visibleAt <= :now ORDER BY t.id ASC")
    List<ParseTaskEntity> findDue(
            @Param("now") Instant now,
            @Param("status") String status,
            org.springframework.data.domain.Pageable pageable);

    @Query(
            "SELECT t FROM ParseTaskEntity t WHERE t.status='PENDING' "
                    + "AND t.deliveryStatus='PENDING' AND t.nextDeliveryAt<=:now ORDER BY t.id ASC")
    List<ParseTaskEntity> findDueDelivery(
            @Param("now") Instant now, org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT t FROM ParseTaskEntity t WHERE t.status='PENDING' AND ("
                    + "(t.deliveryStatus='PENDING' AND t.nextDeliveryAt<=:now) OR "
                    + "(t.deliveryStatus='SENDING' AND t.deliveryLeaseUntil<:now)) "
                    + "ORDER BY t.id ASC")
    List<ParseTaskEntity> findDeliveryClaimCandidates(
            @Param("now") Instant now, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query(
            "UPDATE ParseTaskEntity t SET t.deliveryStatus='SENDING', "
                    + "t.deliveryLeasedBy=:leasedBy, t.deliveryLeaseUntil=:leaseUntil, "
                    + "t.updatedAt=:now WHERE t.id=:id AND t.status='PENDING' AND ("
                    + "(t.deliveryStatus='PENDING' AND t.nextDeliveryAt<=:now) OR "
                    + "(t.deliveryStatus='SENDING' AND t.deliveryLeaseUntil<:now))")
    int claimDelivery(
            @Param("id") Long id,
            @Param("leasedBy") String leasedBy,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);
}
