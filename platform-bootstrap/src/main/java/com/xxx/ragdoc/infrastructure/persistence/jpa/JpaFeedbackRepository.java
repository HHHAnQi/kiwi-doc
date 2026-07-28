package com.xxx.ragdoc.infrastructure.persistence.jpa;

import com.xxx.ragdoc.application.feedback.port.FeedbackRepository;
import com.xxx.ragdoc.domain.feedback.Feedback;
import com.xxx.ragdoc.domain.feedback.Rating;
import com.xxx.ragdoc.infrastructure.persistence.jpa.entity.FeedbackEntity;
import com.xxx.ragdoc.infrastructure.persistence.jpa.repository.FeedbackJpaRepository;
import com.xxx.ragdoc.infrastructure.shared.HtmlSanitizer;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** {@link FeedbackRepository} 端口的 JPA 适配实现。 在此层负责 HTML 转义(防 XSS), domain 层保留原始语义。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JpaFeedbackRepository implements FeedbackRepository {

    private final FeedbackJpaRepository jpa;

    @Override
    public Feedback save(Feedback feedback, String escapedCorrected, String escapedComment) {
        // 兜底: 即使上层忘了转义, 这里也再转一次(null 安全)
        String safeCorrected = HtmlSanitizer.escape(feedback.correctedAnswer());
        String safeComment = HtmlSanitizer.escape(feedback.comment());
        FeedbackEntity e = FeedbackMapper.toNewEntity(feedback, safeCorrected, safeComment);
        FeedbackEntity saved = jpa.save(e);
        feedback.assignId(saved.getId());
        return toDomain(saved);
    }

    @Override
    public boolean existsByTraceId(String traceId) {
        return jpa.existsByTraceId(traceId);
    }

    @Override
    public Page<Feedback> list(Rating rating, Pageable pageable) {
        Page<FeedbackEntity> page =
                rating == null
                        ? jpa.findAllByOrderByCreatedAtDesc(pageable)
                        : jpa.findByRatingOrderByCreatedAtDesc(rating.dbValue(), pageable);
        return page.map(JpaFeedbackRepository::toDomain);
    }

    @Override
    public Optional<Feedback> findById(Long id) {
        return jpa.findById(id).map(JpaFeedbackRepository::toDomain);
    }

    private static Feedback toDomain(FeedbackEntity e) {
        return FeedbackMapper.toDomain(e);
    }
}
