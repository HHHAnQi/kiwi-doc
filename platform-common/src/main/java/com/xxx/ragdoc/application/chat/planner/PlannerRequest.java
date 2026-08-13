package com.xxx.ragdoc.application.chat.planner;

import com.xxx.ragdoc.application.chat.router.TaskIntent;
import java.util.List;
import java.util.Map;

/**
 * PR-7a / EMS-PR7 §4.2: Planner 输入。
 *
 * <p><b>硬约束</b> (Revision §4.2 + §5.2 数据最小化):
 *
 * <ul>
 *   <li><b>不</b>含 tenantId / userId / Principal / Token / Cookie / API key
 *   <li><b>不</b>含 internal exception / trace / DB 连接串
 *   <li><b>不</b>含无权 Evidence 或完整 Evidence 正文 (只持 coverage safe 摘要)
 *   <li><b>不</b>含会改变状态机的字段; Planner 无法直接 finalize Run / 改 budget
 *   <li>{@code replanIndex}=0 表示初始 Plan; >0 表示受控 Replan
 *   <li>所有 Tool input schema 由服务端 {@link PlannerToolDescriptor} 提供
 * </ul>
 */
public record PlannerRequest(
        String runId,
        String normalizedQuery,
        TaskIntent intent,
        List<String> entities,
        Map<String, Object> filters,
        List<EvidenceRequirement> requirements,
        EvidenceCoverageSummary currentCoverage,
        List<CompletedStepSummary> completedSteps,
        AgentBudgetView remainingBudget,
        List<PlannerToolDescriptor> allowedTools,
        int replanIndex) {

    public PlannerRequest {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId");
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            throw new IllegalArgumentException("normalizedQuery");
        }
        if (intent == null) intent = com.xxx.ragdoc.application.chat.router.TaskIntent.FACT;
        entities = entities == null ? List.of() : List.copyOf(entities);
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        currentCoverage =
                currentCoverage == null ? EvidenceCoverageSummary.empty() : currentCoverage;
        completedSteps = completedSteps == null ? List.of() : List.copyOf(completedSteps);
        if (remainingBudget == null) {
            throw new IllegalArgumentException("remainingBudget");
        }
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        if (replanIndex < 0) replanIndex = 0;
    }
}
