package com.xxx.ragdoc.infrastructure.persistence.jpa.repository;

import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.FeedbackEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackJpaRepository extends JpaRepository<FeedbackEntity, Long> {

    /** 按 rating 分页查询(管理员列表)。 */
    Page<FeedbackEntity> findByRatingOrderByCreatedAtDesc(String rating, Pageable pageable);

    /** 默认列表(不限 rating)。 */
    Page<FeedbackEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 检查 trace_id 是否已有反馈(UNIQUE 约束应用层预校验)。 */
    boolean existsByTraceId(String traceId);
}
