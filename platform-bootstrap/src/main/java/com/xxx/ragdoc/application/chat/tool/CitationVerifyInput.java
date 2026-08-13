package com.xxx.ragdoc.application.chat.tool;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * PR-4 / EMS-PR4: citation_verify Tool 的输入。
 *
 * <p>规则约束 (与 LlmCitationVerifier 现状对齐):
 *
 * <ul>
 *   <li>{@code claim} 必填 (≤ 2000 字符; 长则上游截断)
 *   <li>{@code evidences} 必填 (非空; 调用方必须已 ACL 过滤; Tool 不自行扩检索范围)
 *   <li><b>禁止</b> 在 input 携带 tenantId — 由 ToolExecutionContext 派生
 * </ul>
 */
public record CitationVerifyInput(
        @NotBlank(message = "claim 不能为空") @Size(max = 2000, message = "claim 上限 2000 字符")
                String claim,
        List<Evidence> evidences)
        implements ToolInput {

    public CitationVerifyInput {
        if (claim == null || claim.isBlank()) {
            throw new IllegalArgumentException("claim 不能为空");
        }
        if (evidences == null || evidences.isEmpty()) {
            throw new IllegalArgumentException("evidences 不能为空 (调用方必须先 ACL 过滤再传)");
        }
        evidences = List.copyOf(evidences);
    }

    @Override
    public String normalizedForDedup() {
        StringBuilder sb = new StringBuilder("verify|");
        sb.append("claim=").append(claim.trim().toLowerCase()).append('|');
        sb.append("n=").append(evidences.size()).append('|');
        // evidence id 集合按字典序排 (顺序无关)
        evidences.stream()
                .map(Evidence::evidenceId)
                .sorted()
                .forEach(id -> sb.append(id).append(','));
        return sb.toString();
    }
}
