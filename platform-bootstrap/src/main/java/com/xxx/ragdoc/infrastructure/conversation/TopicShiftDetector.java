package com.xxx.ragdoc.infrastructure.conversation;

import com.xxx.ragdoc.application.chat.EmbeddingResult;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext;
import com.xxx.ragdoc.application.chat.conversation.ConversationContext.Turn;
import com.xxx.ragdoc.application.chat.port.EmbeddingClient;
import com.xxx.ragdoc.infrastructure.metrics.RagdocMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 多轮话题切换检测器, ADR-0011 §5。
 *
 * <p>原理: 如果 currQuery 与 ctx.recentTurns.last() 的 userQuery 在 BGE-M3 向量空间里相似度低于阈值
 * (默认 0.5, BGE-M3 中文经验值; Anthropic 2024.06 英文报告阈值 0.7), 视为话题切换。
 *
 * <p>触发后行为 (ChatService C5 接):
 *
 * <ul>
 *   <li>不强制清 history (用户可能要回头聊)
 *   <li>下次 rewrite 时跳过 history 参与 — 防 rewrite LLM 把老话题和当前混合指代错乱
 *   <li>rollingSummary 仍可作 LLM prompt 的弱远期背景
 * </ul>
 *
 * <p>异常 fallback: embedding 调用失败 / 向量维度不匹配 → silent log + 返回 false
 * (detector 失败不挂 chat, ChatService 视为"无 shift"按正常多轮走)。
 *
 * <p>{@code @ConditionalOnProperty} 让此 Bean 仅在 {@code rag.conversation.topic-shift-detect=true}
 * 时注入。C5 起 ChatService 用 @Autowired(required=false) 拿到, null 时直接视为 false。
 *
 * @author Phase 1 / C5 (ADR-0011)
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "rag.conversation",
        name = "topic-shift-detect",
        havingValue = "true")
@RequiredArgsConstructor
public class TopicShiftDetector {

    private final EmbeddingClient embeddingClient;
    private final ConversationProperties props;
    private final RagdocMetrics metrics;

    /**
     * 判定 currQuery 是否相对 ctx 末 turn 是话题切换。
     *
     * <p>逻辑:
     *
     * <ol>
     *   <li>ctx / recentTurns 为空 → false (第 1 turn 不可能是 shift)
     *   <li>调 embeddingClient.embed(currQuery) + embed(lastQuery) 拿两个 dense 向量
     *   <li>算 cosine, 小于阈值 → true
     * </ol>
     */
    public boolean isTopicShift(String currQuery, ConversationContext ctx) {
        if (ctx == null || ctx.recentTurns() == null || ctx.recentTurns().isEmpty()) {
            return false; // 第 1 turn 无对比基线
        }
        Turn lastTurn = ctx.recentTurns().get(ctx.recentTurns().size() - 1);
        String lastQuery = lastTurn.userQuery();

        try {
            EmbeddingResult currEmb = embeddingClient.embed(currQuery);
            EmbeddingResult lastEmb = embeddingClient.embed(lastQuery);
            if (currEmb == null || lastEmb == null) {
                log.warn("topic_shift.embed_null fallback to no-shift");
                metrics.incrementTopicShift("embed_null");
                return false;
            }
            float[] a = currEmb.denseVector();
            float[] b = lastEmb.denseVector();
            if (a == null
                    || b == null
                    || a.length == 0
                    || a.length != b.length) {
                // 维度不等 = embedding 异常 (BGE-M3 dim 恒 1024),
                // 不让 cosine 算出 0 误判为 shift, 直接走异常 fallback
                throw new IllegalStateException(
                        "embed dim mismatch: " + (a == null ? 0 : a.length) + " vs "
                                + (b == null ? 0 : b.length));
            }
            double sim = cosine(a, b);
            boolean shift = sim < props.getTopicShiftThreshold();
            metrics.incrementTopicShift(shift ? "detected" : "not_detected");
            if (shift) {
                log.info("topic_shift.detected sim={} < threshold={}", sim, props.getTopicShiftThreshold());
            }
            return shift;
        } catch (Exception e) {
            // detector 不挂 chat — embed 失败 / 网络异常 / dim 不等 → 视为无 shift, 让 rewrite 正常跑
            log.warn(
                    "topic_shift.detect_failed fallback to no-shift, reason={}",
                    e.getMessage());
            metrics.incrementTopicShift("detect_failed");
            return false;
        }
    }

    /**
     * cosine similarity.
     *
     * <p>调用方必须保证 a/b 非空且维度相等 (本类的 isTopicShift 已校验); 本方法只做 dot / norm 计算。
     * 若 a/b 为零向量 (norm=0) 返回 0, 极少情况 (几乎不可能, 唯一可能 dim 全 0 的极少 embedding model)。
     */
    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
