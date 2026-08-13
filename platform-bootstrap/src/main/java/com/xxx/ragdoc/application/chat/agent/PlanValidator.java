package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.tool.AgentTool;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import com.xxx.ragdoc.application.chat.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PR-6a / EMS-PR6 §4-5: DeterministicExecutionPlan 校验器。
 *
 * <p>校验维度:
 *
 * <ol>
 *   <li>Plan 基础 (id/version/steps/数量上限)
 *   <li>StepId 安全字符 + 唯一 (大小写无关) + banned 词
 *   <li>Tool name/version 精确匹配 Registry + allowlist
 *   <li>Tool Input 类型与 Tool 契约一致 + banned identity 字段扫描
 *   <li>dependsOn 指向真实 Step + 无自依 / 无环 / 无重复依赖
 *   <li>稳定拓扑序 (Kahn + 原序 tie-break)
 * </ol>
 *
 * <p>不修改计划; 一次返回所有错误 (调用方决策)。非法时不返回部分拓扑序。
 */
@org.springframework.stereotype.Component
public class PlanValidator {

    /** stepId 安全字符: 仅 [a-zA-Z0-9_-] 长度 ≤ 64, 首字母字母。 */
    static final Pattern SAFE_STEP_ID = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$");

    /** banned identity/security 字段名 (与 ToolExecutor BANNED 同步)。 */
    static final Set<String> BANNED_INPUT_FIELDS =
            Set.of(
                    "tenantid",
                    "userid",
                    "principal",
                    "rawprincipal",
                    "raw_principal",
                    "role",
                    "adminoverride",
                    "admin_override",
                    "permissionscope",
                    "permission_scope",
                    "permissionscopeversion",
                    "authorization",
                    "authorizationheader",
                    "token",
                    "rawtoken",
                    "raw_token",
                    "apikey",
                    "api_key",
                    "cookie",
                    "connectionstring",
                    "connection_string",
                    "password",
                    "secret");

    /** 测试/CI 时可为 null, 让 PlanValidator 不做 ToolRegistry 存在性校验。 */
    private final ToolRegistry toolRegistry;

    public PlanValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 校验 + 拓扑排序。
     *
     * @param plan 待校验计划 (非空)
     * @param policy 执行策略 (含 allowedTools + budget.maxSteps)
     */
    public PlanValidationResult validate(
            DeterministicExecutionPlan plan, AgentExecutionPolicy policy) {
        List<PlanValidationResult.PlanValidationError> errors = new ArrayList<>();

        // 1. Plan 基础
        if (plan.planId() == null || plan.planId().isBlank()) {
            errors.add(err("PLAN_ID_MISSING", null, "planId 为空"));
        }
        if (plan.planVersion() == null || plan.planVersion().isBlank()) {
            errors.add(err("PLAN_VERSION_MISSING", null, "planVersion 为空"));
        }
        if (plan.steps().isEmpty()) {
            errors.add(err("PLAN_EMPTY", null, "steps 为空"));
            return invalid(errors);
        }
        if (plan.steps().size() > policy.budget().maxSteps()) {
            errors.add(
                    err(
                            "PLAN_TOO_MANY_STEPS",
                            null,
                            "steps="
                                    + plan.steps().size()
                                    + " > maxSteps="
                                    + policy.budget().maxSteps()));
        }

        // 2. StepId 唯一 + 安全 + banned
        Map<String, AgentToolStep> byId = new LinkedHashMap<>(); // 保留原序
        Set<String> lowerCaseSeen = new HashSet<>();
        for (AgentToolStep step : plan.steps()) {
            String sid = step.stepId();
            if (sid == null || sid.isBlank()) {
                errors.add(err("STEP_ID_MISSING", null, "stepId 为空"));
                continue;
            }
            if (!SAFE_STEP_ID.matcher(sid).matches()) {
                errors.add(err("BANNED_STEP_ID", sid, "stepId 含非法字符或过长"));
                continue;
            }
            String lc = sid.toLowerCase();
            if (!lowerCaseSeen.add(lc)) {
                errors.add(err("DUPLICATE_STEP_ID", sid, "stepId 重复 (大小写无关)"));
                continue;
            }
            if (containsBannedToken(sid)) {
                errors.add(err("BANNED_STEP_ID", sid, "stepId 含敏感词"));
                continue;
            }
            byId.put(sid, step);
        }
        if (byId.isEmpty()) return invalid(errors);

        // 3. Tool name/version 存在 + allowlist + input 类型 + banned input 字段
        for (AgentToolStep step : byId.values()) {
            String tn = step.toolName();
            String tv = step.toolVersion();
            if (tn == null || tn.isBlank()) {
                errors.add(err("TOOL_NAME_MISSING", step.stepId(), "toolName 为空"));
                continue;
            }
            if (tv == null || tv.isBlank()) {
                errors.add(err("TOOL_VERSION_MISSING", step.stepId(), "toolVersion 为空"));
                continue;
            }
            if (!policy.allowedTools().contains(tn)) {
                errors.add(err("TOOL_NOT_ALLOWED", step.stepId(), "tool=" + tn + " 不在 allowlist"));
                continue;
            }
            if (toolRegistry != null) {
                AgentTool<? extends ToolInput, ?> tool;
                try {
                    tool = toolRegistry.getByName(tn);
                } catch (Exception e) {
                    errors.add(err("TOOL_NOT_FOUND", step.stepId(), "tool=" + tn + " 未注册"));
                    continue;
                }
                if (!tool.descriptor().version().equals(tv)) {
                    errors.add(
                            err(
                                    "TOOL_VERSION_MISMATCH",
                                    step.stepId(),
                                    "tool="
                                            + tn
                                            + " 期望 version="
                                            + tv
                                            + " 实际="
                                            + tool.descriptor().version()));
                    continue;
                }
                if (!tool.inputType().isInstance(step.input())) {
                    errors.add(
                            err(
                                    "INPUT_TYPE_MISMATCH",
                                    step.stepId(),
                                    "tool="
                                            + tn
                                            + " 期望 input="
                                            + tool.inputType().getSimpleName()
                                            + " 实际="
                                            + (step.input() == null
                                                    ? "null"
                                                    : step.input().getClass().getSimpleName())));
                    continue;
                }
            }
            // Banned 字段扫描 (toString 大小写不敏感)
            String inputStr = step.input() == null ? "" : step.input().toString().toLowerCase();
            for (String banned : BANNED_INPUT_FIELDS) {
                if (inputStr.contains(banned + "=") || inputStr.contains(banned + " :")) {
                    errors.add(err("BANNED_INPUT_FIELD", step.stepId(), "input 含敏感字段"));
                    break;
                }
            }
        }

        // 4. dependsOn 真实 + 无自依 + 无环 + 不重复
        for (AgentToolStep step : byId.values()) {
            Set<String> seenDeps = new HashSet<>();
            for (String dep : step.dependsOn()) {
                if (dep == null || dep.isBlank()) {
                    errors.add(err("DEPENDENCY_NOT_FOUND", step.stepId(), "dependsOn 含空值"));
                    continue;
                }
                if (!seenDeps.add(dep)) {
                    errors.add(err("DUPLICATE_DEPENDENCY", step.stepId(), "dependsOn 重复: " + dep));
                }
                if (!byId.containsKey(dep)) {
                    errors.add(
                            err("DEPENDENCY_NOT_FOUND", step.stepId(), "依赖的 stepId 不存在: " + dep));
                }
                if (dep.equals(step.stepId())) {
                    errors.add(err("SELF_DEPENDENCY", step.stepId(), "依赖自身"));
                }
            }
        }

        // 5. 环检测 + 拓扑排序 (Kahn + 原序 tie-break → 稳定)
        List<String> topo = topologicalSort(byId);
        if (topo == null) {
            errors.add(err("CYCLIC_DEPENDENCY", null, "检测到循环依赖"));
        }

        if (!errors.isEmpty()) return invalid(errors);
        return new PlanValidationResult(true, List.of(), topo);
    }

    // ─── 内部 ────────────────────────────────────────────

    static List<String> topologicalSort(Map<String, AgentToolStep> byId) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (String sid : byId.keySet()) {
            inDegree.put(sid, 0);
            children.put(sid, new ArrayList<>());
        }
        for (AgentToolStep step : byId.values()) {
            for (String dep : step.dependsOn()) {
                if (!byId.containsKey(dep) || dep.equals(step.stepId())) continue; // 已被前段记录
                inDegree.merge(step.stepId(), 1, Integer::sum);
                children.get(dep).add(step.stepId());
            }
        }
        Queue<String> queue = new LinkedList<>();
        for (String sid : byId.keySet()) { // 原序入队
            if (inDegree.get(sid) == 0) queue.add(sid);
        }
        List<String> sorted = new ArrayList<>(byId.size());
        while (!queue.isEmpty()) {
            String sid = queue.poll();
            sorted.add(sid);
            for (String child : children.get(sid)) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) queue.add(child);
            }
        }
        return sorted.size() == byId.size() ? sorted : null;
    }

    private static PlanValidationResult.PlanValidationError err(
            String code, String stepId, String msg) {
        return new PlanValidationResult.PlanValidationError(code, stepId, msg);
    }

    private static PlanValidationResult invalid(
            List<PlanValidationResult.PlanValidationError> errors) {
        return new PlanValidationResult(false, List.copyOf(errors), List.of());
    }

    private static boolean containsBannedToken(String value) {
        String lc = value.toLowerCase();
        for (String b : BANNED_INPUT_FIELDS) if (lc.contains(b)) return true;
        return false;
    }
}
