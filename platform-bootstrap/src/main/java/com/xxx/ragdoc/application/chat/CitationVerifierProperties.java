package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Task 7: Citation Verification 配置 (NLI-based, 走 fallback LLM)。
 *
 * <p>沿用 {@link RerankProperties} / {@code QueryEnhanceProperties} 模式 (enabled 主开关 +
 * 子配置)。默认全部关闭, 改 {@code RAG_CITATION_VERIFIER_ENABLED=true} 开启。
 *
 * <p>行为选型 (任务文档: 重新生成 / 拒答):
 *
 * <ul>
 *   <li>{@code REFUSE}: score < threshold → 改 hint=VERIFY_FAILED, answer=拒答模板
 *   <li>{@code REGENERATE}: score < threshold → 重新调 LLM (最多 maxRegenerateAttempts 次)
 *   <li>{@code WARN_ONLY}: 仅记录, 不改 hint (灰度实验)
 * </ul>
 *
 * <p>启用前必备: fallback LLM route 已配 (走便宜 LLM Judge, 同 Tasks 5/6 NLI 评判风格)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.citation-verifier")
public class CitationVerifierProperties {

    /** 主开关, 默认 false。 */
    private boolean enabled = false;

    /** NLI score 通过门槛, [0,1]。default 0.5。 */
    private double scoreThreshold = 0.5;

    /** 验证失败时动作。 */
    private OnFail onFail = OnFail.REFUSE;

    /** REGENERATE 模式下最多重生成次数。default 1 (即 1 次 regenerate 尝试)。 */
    private int maxRegenerateAttempts = 1;

    /** LLM 超时, 毫秒。default 8s — verifier LLM call 不能挂主 chat。 */
    private int timeoutMs = 8000;

    public enum OnFail {
        REFUSE,
        REGENERATE,
        WARN_ONLY
    }
}
