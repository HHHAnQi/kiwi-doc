package com.xxx.ragdoc.domain.feedback;

import com.xxx.ragdoc.domain.shared.TraceId;
import java.time.Instant;
import java.util.Objects;

/**
 * Feedback 聚合根。
 *
 * <p>不变量(在工厂方法内强制):
 *
 * <ul>
 *   <li>{@link TraceId} 必填, 格式 [A-Za-z0-9_-]{1,64}
 *   <li>{@link Rating} 必填
 *   <li>{@code userId} 必填
 *   <li>correctedAnswer / comment 在领域层不做长度校验, 由 DTO 层 @Size 先拦
 * </ul>
 *
 * <p>V1 UNIQUE(trace_id): 一次 chat 仅一条 feedback, 重复由 DB 拒绝(FEEDBACK_EXISTS)。 V3 升级为 upsert 允许修改。
 *
 * <p>HTML 转义在 infra 层 Mapper 完成(domain 层保留原始内容, 不污染语义)。
 */
public class Feedback {

    private Long id;
    private final TraceId traceId;
    private final Rating rating;
    private final String correctedAnswer;
    private final String comment;
    private final String userId;
    private final Instant createdAt;

    private Feedback(
            Long id,
            TraceId traceId,
            Rating rating,
            String correctedAnswer,
            String comment,
            String userId,
            Instant createdAt) {
        this.id = id;
        this.traceId = Objects.requireNonNull(traceId, "traceId 不能为空");
        this.rating = Objects.requireNonNull(rating, "rating 不能为空");
        this.correctedAnswer = correctedAnswer;
        this.comment = comment;
        this.userId = Objects.requireNonNull(userId, "userId 不能为空");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    /** 新建(persistent 前调用, id 为 null)。 */
    public static Feedback newFeedback(
            TraceId traceId, Rating rating, String correctedAnswer, String comment, String userId) {
        return new Feedback(null, traceId, rating, correctedAnswer, comment, userId, null);
    }

    /** 持久化恢复(infra Mapper 用, 允许跨包)。 */
    public static Feedback restore(
            Long id,
            TraceId traceId,
            Rating rating,
            String correctedAnswer,
            String comment,
            String userId,
            Instant createdAt) {
        return new Feedback(id, traceId, rating, correctedAnswer, comment, userId, createdAt);
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("Feedback 已有 id, 不允许重新 assign");
        }
        this.id = Objects.requireNonNull(id);
    }

    public Long id() {
        return id;
    }

    public TraceId traceId() {
        return traceId;
    }

    public Rating rating() {
        return rating;
    }

    public String correctedAnswer() {
        return correctedAnswer;
    }

    public String comment() {
        return comment;
    }

    public String userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Feedback{id=" + id + ", traceId=" + traceId + ", rating=" + rating + '}';
    }
}
