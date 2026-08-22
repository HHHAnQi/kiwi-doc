package com.xxx.ragdoc.application.chat.planner;

import com.xxx.ragdoc.application.chat.tool.SearchInput;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PR-7a / EMS-PR7 §4.5: 模板/规则 Planner — <b>确定性</b>, 不调 LLM。
 *
 * <p>用途:
 *
 * <ul>
 *   <li>model-disabled 默认实现
 *   <li>单元测试与 PR-5 Harness Replay 的稳定 fixture 来源
 *   <li>固定可解释的多跳 Plan 生成规则
 * </ul>
 *
 * <p>规则 (PR-7a 第一版):
 *
 * <ul>
 *   <li>每个 {@link EvidenceRequirement} 生成 <b>一个</b>对应的 {@link PlannedToolStep}
 *   <li>required Requirement 在前, 可选在后; 按 RequirementType 决定 Tool:
 *       <ul>
 *         <li>{@code FACT} / {@code ENTITY_ATTRIBUTE} / {@code TEMPORAL} (有 entities 或 version
 *             filter) → {@code metadata_search(v1)} 携带 SearchFilters
 *         <li>{@code RELATION} / {@code FOLLOW_UP_ENTITY} → {@code semantic_search(v1)} (一般概念)
 *         <li>{@code COMPARISON_SIDE} PR-7a 直走 semantic_search (本应优先走 PR-6c 固定工作流, 但作为兜底)
 *       </ul>
 *   <li>{@code dependsOn} 仅对 {@code FOLLOW_UP_ENTITY} 生效: 依赖前一个 (前置) Requirement 对应 Step
 *   <li>避免重复 Tool signature: 同一 (toolName, version, normalizedInput) 多次出现时跳过
 *   <li>Plan 大小受 {@link PlannerRequest#remainingBudget()} 步数上限 + {@code maxPlanSteps} 双重限制
 *   <li>Replan (replanIndex>0): 仅生成 {@code currentCoverage.uncoveredRequirementIds} 对应 Step
 * </ul>
 *
 * <p>返回<b>稳定</b> stepId: {@code plan-step-{N}} / {@code replan-{replanIndex}-step-{N}}。
 */
@Slf4j
// P1 修复(装配冲突): model-enabled=false(默认) 时的底层 planner, 与 ModelPlannerProvider 互斥。
@Component("basePlannerProvider")
@ConditionalOnProperty(
        prefix = "rag.agent.planner",
        name = "model-enabled",
        havingValue = "false",
        matchIfMissing = true)
@RequiredArgsConstructor
public class RuleTemplatePlannerProvider implements PlannerProvider {

    private final PlannerProperties properties;

    @Override
    public PlannerResponse plan(PlannerRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        int planStepsCap =
                Math.min(properties.getMaxPlanSteps(), request.remainingBudget().remainingSteps());
        if (planStepsCap <= 0) {
            return zeroStepPlan(request, "BUDGET_ZERO");
        }

        // Replan 仅处理 uncovered
        List<String> targets =
                request.replanIndex() == 0
                        ? request.requirements().stream()
                                .filter(r -> r.required() || isOptionalUseful(r))
                                .map(EvidenceRequirement::requirementId)
                                .toList()
                        : List.copyOf(request.currentCoverage().uncoveredRequirementIds());

        List<PlannedToolStep> steps = new ArrayList<>();
        List<String> coveredReq = new ArrayList<>();
        java.util.Set<String> seenSignatures = new java.util.HashSet<>();
        // 已完成 step 的 signature 全部排除, 防止 Replan 重复
        for (CompletedStepSummary s : request.completedSteps()) {
            seenSignatures.add(s.toolSignatureHash());
        }

        int n = 0;
        for (EvidenceRequirement req : request.requirements()) {
            if (!targets.contains(req.requirementId())) continue;
            if (steps.size() >= planStepsCap) break;
            PlannedToolStep step = buildStep(request, req, steps.size(), n);
            if (step == null) continue;
            String sig =
                    signature(
                            step.toolName(),
                            step.toolVersion(),
                            step.input() == null ? "" : step.input().normalizedForDedup());
            if (!seenSignatures.add(sig)) {
                log.info(
                        "planner.rule.skipping_duplicate_signature step={} req={}",
                        step.stepId(),
                        req.requirementId());
                continue;
            }
            steps.add(step);
            coveredReq.add(req.requirementId());
            n++;
        }

        String reasonCode =
                request.replanIndex() == 0
                        ? PlannerResponse.INITIAL_MULTI_HOP_PLAN
                        : PlannerResponse.MISSING_REQUIREMENT_RECOVERY;
        return new PlannerResponse(
                request.replanIndex() == 0
                        ? "rule-plan-" + safeId(request.runId())
                        : "rule-replan-" + request.replanIndex() + "-" + safeId(request.runId()),
                "v1",
                steps,
                coveredReq,
                reasonCode);
    }

    private boolean isOptionalUseful(EvidenceRequirement r) {
        // PR-7a v1: optional 也允许进入 Plan, 但 Sufficiency 不强制; 留 v2 收紧
        return true;
    }

    private PlannedToolStep buildStep(
            PlannerRequest request, EvidenceRequirement req, int stepSeq, int subOrdinal) {
        boolean metadata =
                req.type() == RequirementType.FACT
                        || req.type() == RequirementType.ENTITY_ATTRIBUTE
                        || req.type() == RequirementType.TEMPORAL;
        boolean useMetadata =
                metadata
                        && (!req.targetEntities().isEmpty()
                                || req.expectedFilters() != null
                                        && !req.expectedFilters().isEmpty());
        String stepId =
                (request.replanIndex() == 0
                                ? "plan-step-"
                                : "replan-" + request.replanIndex() + "-step-")
                        + stepSeq;
        String initialTool = useMetadata ? "metadata_search" : "semantic_search";

        // 验证 tool 在 allowedTools 内; 不在就 fallback semantic_search; 仍不在则 skip
        boolean metadataAllowed =
                request.allowedTools().stream().anyMatch(t -> t.name().equals("metadata_search"));
        boolean semanticAllowed =
                request.allowedTools().stream().anyMatch(t -> t.name().equals("semantic_search"));
        String chosenTool;
        boolean chosenMetadata;
        if ((useMetadata && metadataAllowed)
                || initialTool.equals("semantic_search") && !metadataAllowed && semanticAllowed) {
            chosenTool =
                    useMetadata && metadataAllowed
                            ? "metadata_search"
                            : (metadataAllowed ? "metadata_search" : "semantic_search");
            chosenMetadata = chosenTool.equals("metadata_search");
        } else if (semanticAllowed) {
            chosenTool = "semantic_search";
            chosenMetadata = false;
        } else {
            log.info(
                    "planner.rule.tool_not_allowed — skip (allowed={})",
                    request.allowedTools().stream().map(PlannerToolDescriptor::name).toList());
            return null;
        }
        String toolName = chosenTool;
        useMetadata = chosenMetadata;
        String toolVer = "v1";

        String q = request.normalizedQuery() + " " + req.description();
        String version =
                firstNonBlank(
                        stringFilter(req.expectedFilters(), "version"),
                        stringFilter(request.filters(), "version"));
        String source =
                firstNonBlank(
                        stringFilter(req.expectedFilters(), "source"),
                        stringFilter(request.filters(), "source"));
        ToolInput input =
                new SearchInput(q.trim(), 5, new SearchInput.SearchFilters(source, version, null));

        List<String> deps =
                req.type() == RequirementType.FOLLOW_UP_ENTITY && subOrdinal > 0
                        ? List.of(
                                (request.replanIndex() == 0
                                                ? "plan-step-"
                                                : "replan-" + request.replanIndex() + "-step-")
                                        + (stepSeq - 1))
                        : List.of();

        return new PlannedToolStep(
                stepId,
                toolName,
                toolVer,
                input,
                deps,
                List.of(req.requirementId()),
                "Evidence for: " + truncate(req.description(), 60),
                req.required());
    }

    private PlannerResponse zeroStepPlan(PlannerRequest request, String reason) {
        return new PlannerResponse(
                "rule-empty-" + safeId(request.runId()), "v1", List.of(), List.of(), reason);
    }

    private static String signature(String toolName, String toolVersion, String normalizedInput) {
        return toolName + "|" + toolVersion + "|" + normalizedInput;
    }

    private static String stringFilter(java.util.Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String safeId(String id) {
        return id == null ? "id" : (id.length() > 12 ? id.substring(0, 12) : id);
    }
}
