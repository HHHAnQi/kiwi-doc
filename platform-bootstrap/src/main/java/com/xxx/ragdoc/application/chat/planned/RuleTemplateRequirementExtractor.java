package com.xxx.ragdoc.application.chat.planned;

import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.RequirementType;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PR-7c.3b / EMS-PR7 §5: RequirementExtractor — 规则模板 (PR-7c v1, 不调 LLM)。
 *
 * <p>从 RouterDecision + 用户 query 抽取<b>稳定</b>的 {@link EvidenceRequirement} 列表, 用作 Planner
 * 的输入和 Sufficiency Judge 的真值锚。
 *
 * <p>规则 (v1, 确定性):
 *
 * <ul>
 *   <li>每个 entity 至少对应 1 个 ENTITY_ATTRIBUTE(若 router 给了 entities)
 *   <li>"为什么 ... 之后 ..." 等 MULTI_HOP 因果 → FACT
 *   <li>"比较 / 对比 X 和 Y" → 显式走 PR-6c 偏好固定工作流 (PlannedAgentPipeline 不接 COMPARISON intent)
 *   <li>未识别 → 至少 1 个 FACT (按 query 整句)
 * </ul>
 *
 * <p>稳定 requirementId = "REQ-{ordinal}" 全 Plan/Replan 引用唯一, 不随机。
 */
@Component
public class RuleTemplateRequirementExtractor {

    /**
     * @param decision Router 输出 (intent / entities / filters)
     * @param normalizedQuery 已规范化的 query 文本
     * @return 冻结的 Requirement 列表 (有序, id 稳定)
     */
    public RequirementExtractionResult extract(
            RouterDecision decision, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return RequirementExtractionResult.invalid("EMPTY_QUERY");
        }
        TaskIntent intent = decision != null ? decision.intent() : TaskIntent.FACT;
        List<EvidenceRequirement> reqs = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();

        // entity-based Requirement
        List<String> entities = decision != null && decision.entities() != null
                ? decision.entities() : List.of();
        int ordinal = 1;
        for (String e : entities) {
            if (e == null || e.isBlank()) continue;
            String id = "REQ-" + ordinal++;
            if (!usedIds.add(id)) continue;
            reqs.add(new EvidenceRequirement(
                    id,
                    "属性/事实关于: " + e,
                    RequirementType.ENTITY_ATTRIBUTE,
                    true,
                    List.of(e),
                    decision != null ? Map.copyOf(decision.filters()) : Map.of()));
        }

        // intent-specific Recommendation
        if (intent == TaskIntent.MULTI_HOP) {
            // 因果 / follow-up 至少 1 个 required
            reqs.add(new EvidenceRequirement(
                    nextId(usedIds),
                    "因果 / 时序关系或答案合成: " + truncate(normalizedQuery, 80),
                    RequirementType.RELATION,
                    true,
                    entities,
                    decision != null ? Map.copyOf(decision.filters()) : Map.of()));
        }

        // 兜底 FACT
        if (reqs.isEmpty()) {
            reqs.add(new EvidenceRequirement(
                    "REQ-1",
                    "主问题: " + truncate(normalizedQuery, 80),
                    RequirementType.FACT,
                    true,
                    List.of(),
                    Map.of()));
        }

        return RequirementExtractionResult.ok(List.copyOf(reqs));
    }

    private static String nextId(Set<String> used) {
        int n = 1;
        while (used.contains("REQ-" + n)) n++;
        String id = "REQ-" + n;
        used.add(id);
        return id;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    /** 输出。invalid 仅含 invalidReason。 */
    public record RequirementExtractionResult(
            boolean valid,
            String invalidReason,
            List<EvidenceRequirement> requirements) {

        public static RequirementExtractionResult invalid(String reason) {
            return new RequirementExtractionResult(false, reason, List.of());
        }

        public static RequirementExtractionResult ok(List<EvidenceRequirement> r) {
            return new RequirementExtractionResult(true, null, r);
        }
    }
}
