package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.command.ChatStreamEvent;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.sufficiency.RequirementCoverage;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * PR-7c.3a / EMS-PR7 §2.1 + §7: 通用 Evidence-grounded Answer Composer 接口。
 *
 * <p>与 PR-6c 的 {@code ComparisonAnswerComposer} 区分: PR-7c MULTI_HOP 跑 Both-side 后用单 LLM 调用
 * 把所有授权 Evidence + Requirement 结构化组装为最终答案。ComparisonWorkflow 仍用自己的 Composer。
 *
 * <p>只允许:
 *
 * <ul>
 *   <li>仅一次 LLM 调用 (Revision §7.4 — answer model calls ≤ 1 per Run)
 *   <li>使用 Guard 通过的最终 Evidence
 *   <li>按 Requirement 组织证据, 不让模型自己重新分类
 *   <li>每个关键结论附 Citation [Evidence:ID]
 * </ul>
 *
 * <p>不接受:
 *
 * <ul>
 *   <li>Planner Response rationale
 *   <li>Sufficiency 模型自由文本
 *   <li>Agent Transcript / Budget / 异常 / Repository 状态
 *   <li>无权 Evidence
 *   <li>跨阶段 Buffer 已生成草稿重新调用
 * </ul>
 */
public interface EvidenceGroundedAnswerComposer {

    GroundedAnswer compose(GroundedAnswerRequest request) throws Exception;

    Flux<ChatStreamEvent> stream(GroundedAnswerRequest request);

    /** 单次回答请求 (Revision §7.1 Prompt 内容清单)。 */
    record GroundedAnswerRequest(
            String originalQuery,
            List<EvidenceRequirement> requirements,
            List<RequirementCoverage> coverage,
            List<Evidence> evidence,
            /** 安全 metadata: tenantId 仅用于 trace + skip okward injection; 不进 Prompt。 */
            String tenantId,
            String runId) {

        public GroundedAnswerRequest {
            if (originalQuery == null) originalQuery = "";
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            if (tenantId == null) tenantId = "";
            if (runId == null) runId = "";
        }
    }

    /** Composer 返回 (text + 用到的 evidence ids — Citation 仅来自最终 Evidence)。 */
    record GroundedAnswer(
            String text,
            List<String> usedEvidenceIds) {

        public GroundedAnswer {
            if (text == null) text = "";
            usedEvidenceIds = usedEvidenceIds == null ? List.of() : List.copyOf(usedEvidenceIds);
        }
    }
}
