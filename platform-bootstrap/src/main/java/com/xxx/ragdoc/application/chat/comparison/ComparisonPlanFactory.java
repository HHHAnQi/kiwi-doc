package com.xxx.ragdoc.application.chat.comparison;

import com.xxx.ragdoc.application.chat.agent.AgentBudget;
import com.xxx.ragdoc.application.chat.agent.AgentExecutionPolicy;
import com.xxx.ragdoc.application.chat.agent.AgentToolStep;
import com.xxx.ragdoc.application.chat.agent.DeterministicExecutionPlan;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.tool.SearchInput;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-6c / EMS-PR6c §5: 把 Router 抽取的 <b>比较对象</b> 转为服务端确定性 {@link DeterministicExecutionPlan} +
 * {@link AgentExecutionPolicy}。
 *
 * <p>第一版只支持两个比较对象 (left + right); 多于两个 / 不足两个 → {@code valid=false} (不强行扩展为 N 路)。
 *
 * <p>Tool 选择规则 (§5.3, 不调 LLM):
 *
 * <ul>
 *   <li>filters 含 version/source/product/documentId 任一字段 → metadata_search (带结构化 filter)
 *   <li>否则 → semantic_search (query = 原始 question + label)
 *   <li>keyword_search 暂<b>不</b>自动启用 (留 PR-7 Router 显式标 错误码/API/精确版本时)
 * </ul>
 *
 * <p>客户端<b>不能</b>注入:
 *
 * <ul>
 *   <li>tenantId / userId / token (会被 PlanValidator + ToolExecutor 双重拦截)
 *   <li>budget / allowlist / maxSteps
 *   <li>直接 Tool 参数 (all Tool inputs 由本 Factory 服务端构造)
 * </ul>
 *
 * <p>预算 (§6) 固定服务端控制: maxSteps/ToolCalls 来自 {@link ComparisonExecutorProperties} (默认 2/2),
 * planner=0 / replan=0, allowlist 限定本 Plan 真实使用的 Tool 子集。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComparisonPlanFactory {

    public static final String PLAN_ID = "comparison-workflow";
    public static final String PLAN_VERSION = "v1";
    public static final String LEFT_STEP_ID = "compare-left";
    public static final String RIGHT_STEP_ID = "compare-right";

    private final ComparisonExecutorProperties props;

    /**
     * 构造 ComparisonPlan。失败返回 {@code valid=false, invalidReason=...}; 调用方决定是否兼容回退。
     *
     * @param originalQuery ChatCommand.query() 原文, 用于 Prompt 上下文
     * @param routerDecision Router 的 entities / filters
     * @param requestedFilters ChatCommand 自带的 filters (可能含 version/source/language); 服务端派生
     */
    public ComparisonPlanBuildResult build(
            String originalQuery,
            RouterDecision routerDecision,
            Map<String, Object> requestedFilters) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return ComparisonPlanBuildResult.invalid("EMPTY_QUERY");
        }
        // 1. 从 RouterDecision.entities / filters 抽取 left / right targets
        List<ComparisonTarget> targets = extractTargets(routerDecision, requestedFilters);
        if (targets.size() < 2) {
            return ComparisonPlanBuildResult.invalid("INSUFFICIENT_TARGETS");
        }
        if (targets.size() > 2) {
            return ComparisonPlanBuildResult.invalid("TOO_MANY_TARGETS_PR6C_V1_SUPPORTS_2_MAX");
        }
        ComparisonTarget left = targets.get(0);
        ComparisonTarget right = targets.get(1);
        if (left.normalizedValue().equalsIgnoreCase(right.normalizedValue())) {
            return ComparisonPlanBuildResult.invalid("DUPLICATE_TARGETS_NORMALIZED");
        }

        // 2. 选 Tool + 构造 Tool inputs (服务端强制)
        ComparisonToolChoice leftTool = pickTool(left);
        ComparisonToolChoice rightTool = pickTool(right);
        ToolInput leftInput = buildToolInput(originalQuery, left, leftTool);
        ToolInput rightInput = buildToolInput(originalQuery, right, rightTool);

        // 3. 构造两个 required AgentToolStep, 无相互依赖, 顺序 left → right
        AgentToolStep leftStep =
                new AgentToolStep(
                        LEFT_STEP_ID,
                        leftTool.toolName(),
                        leftTool.toolVersion(),
                        leftInput,
                        List.of(),
                        "Evidence about " + left.label(),
                        true);
        AgentToolStep rightStep =
                new AgentToolStep(
                        RIGHT_STEP_ID,
                        rightTool.toolName(),
                        rightTool.toolVersion(),
                        rightInput,
                        List.of(),
                        "Evidence about " + right.label(),
                        true);
        DeterministicExecutionPlan plan =
                new DeterministicExecutionPlan(PLAN_ID, PLAN_VERSION, List.of(leftStep, rightStep));

        // 4. 服务端固定 ExecutionPolicy
        AgentExecutionPolicy policy = buildPolicy(leftTool.toolName(), rightTool.toolName());

        return ComparisonPlanBuildResult.ok(plan, policy, left, right, leftTool, rightTool);
    }

    /** 从 RouterDecision 抽 left/right comparison targets。 */
    static List<ComparisonTarget> extractTargets(
            RouterDecision d, Map<String, Object> requestedFilters) {
        Map<String, Object> rf = requestedFilters == null ? Map.of() : requestedFilters;
        // entities 优先: PR-6c v1 只接受<b>正好</b> 2 个 entity (任何超出数都返回原始数量, 让 build() 拒绝)
        if (d != null && d.entities() != null && !d.entities().isEmpty()) {
            return d.entities().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> ComparisonTarget.of(s, normalize(s)))
                    .toList();
        }
        // version 优先于 product
        List<ComparisonTarget> out = new ArrayList<>();
        Map<String, Object> rfDecided = d != null ? d.filters() : null;
        List<String> versions = readStringList(rfDecided, "versions");
        if (versions.isEmpty()) versions = readStringList(rf, "version");
        if (versions.isEmpty()) versions = readStringList(rf, "versions");
        if (versions.size() >= 2) {
            Map<String, Object> filters = new LinkedHashMap<>();
            filters.put("version", versions.get(0));
            out.add(new ComparisonTarget(versions.get(0), normalize(versions.get(0)), filters));
            Map<String, Object> filters2 = new LinkedHashMap<>();
            filters2.put("version", versions.get(1));
            out.add(new ComparisonTarget(versions.get(1), normalize(versions.get(1)), filters2));
            return out;
        }
        List<String> products = readStringList(rfDecided, "products");
        if (products.isEmpty()) products = readStringList(rf, "product");
        if (products.isEmpty()) products = readStringList(rf, "products");
        if (products.size() >= 2) {
            out.add(
                    new ComparisonTarget(
                            products.get(0),
                            normalize(products.get(0)),
                            Map.of("source", products.get(0))));
            out.add(
                    new ComparisonTarget(
                            products.get(1),
                            normalize(products.get(1)),
                            Map.of("source", products.get(1))));
            return out;
        }
        return out;
    }

    private static void addIfNotBlank(List<ComparisonTarget> out, ComparisonTarget t) {
        if (t.normalizedValue() != null && !t.normalizedValue().isBlank()) out.add(t);
    }

    /** 决定每个 target 用哪个 Tool (§5.3, 不调 LLM; 确定性规则)。 */
    static ComparisonToolChoice pickTool(ComparisonTarget t) {
        Map<String, Object> f = t.filters();
        if (f != null
                && (f.containsKey("version")
                        || f.containsKey("product")
                        || f.containsKey("source")
                        || f.containsKey("documentId"))) {
            return ComparisonToolChoice.metadataSearchV1(
                    "metadata_search filter=" + describeFilters(f) + " for " + t.label());
        }
        return ComparisonToolChoice.semanticSearchV1(
                "semantic_search query=original + " + t.label());
    }

    /**
     * 构造 Tool 的 typed input; SearchInput 是 metadata_search / semantic_search / keyword_search 共用
     * schema。
     */
    static ToolInput buildToolInput(
            String originalQuery, ComparisonTarget target, ComparisonToolChoice choice) {
        Integer topK = 5;
        if (choice.toolName().equals("metadata_search")
                || choice.toolName().equals("semantic_search")) {
            String version = (String) target.filters().get("version");
            String source = (String) target.filters().get("source");
            SearchInput.SearchFilters filters =
                    new SearchInput.SearchFilters(source, version, null);
            String query = originalQuery + " " + target.label();
            return new SearchInput(query, topK, filters);
        }
        // keyword_search 第一版默认 fallback 到 search input without filters
        return new SearchInput(
                originalQuery + " " + target.label(), topK, SearchInput.SearchFilters.empty());
    }

    /** 服务端固定 ExecutionPolicy。allowlist 仅含本 Plan 实际需要的 Tool 子集 (§6)。 */
    AgentExecutionPolicy buildPolicy(String leftTool, String rightTool) {
        Set<String> allowlist = new java.util.LinkedHashSet<>(List.of(leftTool, rightTool));
        // Citation 校验单独调用 (在 AgentRun 之外), 不进 allowlist
        AgentBudget budget =
                new AgentBudget(
                        props.getMaxSteps(),
                        props.getMaxToolCalls(),
                        0,
                        0,
                        props.getMaxExecutionMillis(),
                        0,
                        0,
                        0,
                        java.math.BigDecimal.ZERO);
        return new AgentExecutionPolicy(
                budget,
                Instant.now().plusMillis(props.getMaxExecutionMillis()),
                allowlist,
                props.getMaxEvidence(),
                props.getMaxEvidenceTokens(),
                true /* continueOnEmptyResult=true 让两侧都跑 */,
                false /* 重试不自动 */,
                true /* failOnPermissionDenied */);
    }

    private static List<String> readStringList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        Object v = m.get(key);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        } else if (v instanceof String s && !s.isBlank()) {
            // 单值当 1 个; 比较需要 2 个, 直接 return 也罢
            out.add(s.trim());
        }
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String describeFilters(Map<String, Object> f) {
        return f.toString();
    }
}
