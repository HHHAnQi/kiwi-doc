package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * chat 业务配置: 友好文案 + 阈值。 放在 application 层(非 infrastructure), 是因为它表达业务语义而非技术配置。 V2 真实问答接入后,
 * min-score-threshold 才真正生效。
 *
 * <p>见 docs/features/error-and-empty-states/spec.md §配置项。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class ChatMessages {

    /** V2: 召回 score 低于此阈值视为 NO_RECALL */
    private double minScoreThreshold = 0.3;

    private String emptyKbMessage = "知识库中还没有任何文档, 请先上传一份 PDF/Markdown/HTML 文档再提问。";

    private String noRecallMessage = "未在知识库中找到相关信息, 请尝试改写问题或上传更相关的文档。";

    /** V2+ 生效: LLM 调用失败时的兜底文案前缀 */
    private String llmDegradedMessage = "模型暂不可用, 请稍后重试。如有紧急问题, 请点反馈并提供 trace_id: ";

    /**
     * Task 7: Citation Verification FAIL (REFUSE 模式) 时的拒答文案。
     *
     * <p>参数化: 阈值通过 {@code String.format} 注入 (%.2f)。
     */
    private String verifierRefusalTemplate =
            "本次答案未通过引用核验 (NLI score < %.2f), 已自动拒答以避免幻觉。请尝试改写问题或提供更多上下文。";

    /** Task 7: 拒答文案构造器。 */
    public String verifierRefusal(double scoreThreshold) {
        return String.format(verifierRefusalTemplate, scoreThreshold);
    }

    // ─── Phase 2.A experimental flags (2026-08-03) ─────────────────
    // 默认 OFF, Phase 2.A 实证 "整体不通过判据" 但代码保留, 待将来严谨 A/B 时开。
    // 见 eval/PHASE2A_REPORT.md + eval/EVAL_BASELINE_CERT.md。

    /**
     * Phase 2.A Upgrade A1: 放宽 prompt 第5条 "完全无关就答无" 判定。 OFF=baseline 行为(严格拒答, faithfulness 数字更稳);
     * ON=放宽判定让 code-only / 弱相关 ctx 也能引出答案(refusal_rate -2.5~6pp, 但 RAGAS context_recall 长度耦合
     * -24pp)。
     */
    private boolean promptRelaxRefusal = false;

    /**
     * Phase 2.A Upgrade A2: Lost-in-the-Middle context 重排 (Liu et al. 2023)。 OFF=按 score 自然顺序喂 LLM;
     * ON=最高分放 context 头, 次高分放尾, 其余交替中段。 单独贡献未验证(Phase 2.A 与 A1 同跑), 待 Phase 2.B 单独 A/B。
     */
    private boolean litmReorder = false;

    // ─── Phase 2.B / P2-2: PromptV2 (citation 强迫 + grounding 收紧) ───────────
    // Baseline prompt 业内 -0.86 faithfulness / 0.67 precision (eval/EVAL_BASELINE_CERT.md);
    // V2 加强: (a) 强制每个事实 [n] citation, (b) '片段不存在就不能说' 规则, (c) 收紧 fallback 触发条件。
    // Holdout gate = eval/holdout.jsonl 80 条 (生产冷启动 = 0 baseline 对照), 比较 faithfulness ≥ +5pp。
    // flag 默认 OFF; eval 跑留一 ≮ baseline 才开。

    /**
     * Phase 2.B / P2-2: V2 prompt 启用 flag。优先级: promptV2 > promptRelaxRefusal > baseline。 V2 启用时
     * promptRelaxRefusal 不生效 (V2 ≠ relaxed, 是更严格而非更宽松)。
     */
    private boolean promptV2 = false;

    /**
     * Phase 2.B / P2-2: V2 是否强制要求 LLM 在每个事实后面标 citation [n]。 ON=任何非通用陈述都要有 [n]; OFF=只关键事实。default
     * ON, 可经此 flag 关闭做 A/B。
     */
    private boolean promptV2Citation = true;
}
