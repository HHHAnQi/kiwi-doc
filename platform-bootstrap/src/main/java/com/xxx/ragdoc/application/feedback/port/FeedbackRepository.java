package com.xxx.ragdoc.application.feedback.port;

import com.xxx.ragdoc.domain.feedback.Feedback;
import com.xxx.ragdoc.domain.feedback.Rating;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Feedback 仓储端口。 实现见 {@code JpaFeedbackRepository}(infra 层)。
 *
 * <p>{@link Pageable}/{@link Page} 是分页元信息 DTO(SPR-10280 起 Spring 一直把它们定位为 framework-neutral 的
 * interface), 不算污染 application 层。
 */
public interface FeedbackRepository {

    Feedback save(Feedback feedback, String escapedCorrectedAnswer, String escapedComment);

    boolean existsByTraceId(String traceId);

    /**
     * 分页查询列表(管理员)。
     *
     * @param rating null = 全部; 否则按 rating 过滤
     */
    Page<Feedback> list(Rating rating, Pageable pageable);

    Optional<Feedback> findById(Long id);
}
