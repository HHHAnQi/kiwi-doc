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

    // ─── Phase 2.A experimental flags (2026-08-03) ─────────────────
    // 默认 OFF, Phase 2.A 实证 "整体不通过判据" 但代码保留, 待将来严谨 A/B 时开。
    // 见 eval/PHASE2A_REPORT.md + eval/EVAL_BASELINE_CERT.md。

    /**
     * Phase 2.A Upgrade A1: 放宽 prompt 第5条 "完全无关就答无" 判定。
     * OFF=baseline 行为(严格拒答, faithfulness 数字更稳); ON=放宽判定让 code-only / 弱相关 ctx
     * 也能引出答案(refusal_rate -2.5~6pp, 但 RAGAS context_recall 长度耦合 -24pp)。
     */
    private boolean promptRelaxRefusal = false;

    /**
     * Phase 2.A Upgrade A2: Lost-in-the-Middle context 重排 (Liu et al. 2023)。
     * OFF=按 score 自然顺序喂 LLM; ON=最高分放 context 头, 次高分放尾, 其余交替中段。
     * 单独贡献未验证(Phase 2.A 与 A1 同跑), 待 Phase 2.B 单独 A/B。
     */
    private boolean litmReorder = false;
}
