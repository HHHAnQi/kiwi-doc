package com.xxx.ragdoc.application.chat.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PR-3.2 / EMS-PR3: 规则优先生成的可解释 TaskRouter。
 *
 * <p><b>不调用任何 LLM</b>; 所有判定基于 QueryNormalizer 抽出的实体 + 关键词词表。可解释性优先于召回, 用于让 Broker 评测中能产出 reasonCode
 * / 混淆矩阵, 而不是黑盒。
 *
 * <h2>规则优先级 (PR-3 设计文档 §2, 严格顺序)</h2>
 *
 * <pre>
 *   1. UNANSWERABLE     → REFUSE            (out-of-scope / identity / injection / 越权请求)
 *   2. COMPARISON       → FIXED_WORKFLOW    (X 和 Y 对比 / 比较 X 与 Y / X vs Y; 双对象或双版本)
 *   3. MULTI_HOP        → FIXED_WORKFLOW    ("为什么 ... 之后 ..." 因果链)
 *   4. NUMERIC_OR_VERSION → TARGETED_RAG    (版本号 / 错误码 / 年份 / 季度 + 非比较语境)
 *   5. ENTITY_LOOKUP    → TARGETED_RAG      (产品 + 关键词 "在哪一节" "哪个章节" "哪份文档")
 *   6. SUMMARY          → CLASSIC_RAG       (总结 / 概括 / 概论 / 总结一下)
 *   7. FACT             → CLASSIC_RAG       (默认 / 兜底普通事实问题)
 * </pre>
 *
 * <p>如果两条规则都命中 (例如"比较 v1 与 v2 的差异"), 严格按优先级走 (COMPARISON 压 NUMERIC — 任务要求:"比较问题即使含版本号也应走
 * FIXED_WORKFLOW, 因为目标是 A/B 证据合并, 不是单一版本文档检索")。
 *
 * <h2>低置信度回退</h2>
 *
 * <p>当某条规则的判定条件"较弱"(例如 FACT 兜底 / SUMMARY 仅命中一个词 / 边界模糊) 时, 置信度会 &lt; {@link
 * #LOW_CONFIDENCE_THRESHOLD}。此时不会丢回去让 Agent 处理, 而是强制把 strategy 转回 {@link
 * ExecutionStrategy#CLASSIC_RAG} + reasonCode 追加 {@code _LOW_CONFIDENCE_FALLBACK}, intent 保留原判断。
 *
 * <p>注意: REFUSE 路径不参与低置信回退(UNANSWERABLE 一旦判定, 直接 REFUSE, 因为回退 Classic 也无意义)。
 */
public class RuleBasedTaskRouter implements TaskRouter {

    /** 低置信度阈值: < 0.7 → strategy 强制 CLASSIC_RAG 回退 (PR-3 §4)。 */
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.7;

    // ── 关键词词表 ───────────────────────────────────────
    private static final Pattern COMPARISON_PHRASE =
            Pattern.compile("比较|对比|的区别|的差异|哪个更好|哪一种|vs\\s|versus|哪个性能|相比");

    /** 比较类必须看得到至少两个比较对象; 仅含 "对比" 词是不够的。 */
    private static final Pattern COMPARISON_CONNECTOR =
            Pattern.compile(
                    "\\b\\S+\\s+(?:和|与|跟|以及|还是)\\s+\\S+|\\b\\S+\\s+vs\\.?\\s+\\S+",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTI_HOP_PHRASE =
            Pattern.compile("为什么.*之后|为什么.*升级.*后|为什么.*切换.*后|为什么.*引入.*后|为什么.*上线.*后");
    private static final Pattern WHY_PREFIX = Pattern.compile("^为什么|^为啥|^为何");

    private static final Pattern SUMMARY_PHRASE = Pattern.compile("总结|概括|概论|综述|概述|简述|概览|一览");
    private static final Pattern SUMMARY_VERB = Pattern.compile("(请|帮我)?\\s*(总结|概括|概述)\\s*(一下|下)?");

    private static final Pattern SECTION_LOOKUP_PHRASE =
            Pattern.compile("哪一节|哪个章节|哪份文档|在哪里|在哪个|哪一章|哪个文档|在第几|关于[^?？]{0,12}的(部分|章节|内容)");

    private static final List<String> OUT_OF_SCOPE_VERBS =
            List.of("登录", "转账", "修改数据库", "执行", "画一只", "画一只猫", "帮我画");
    private static final Pattern IDENTITY_QUESTION =
            Pattern.compile("^你是谁|^你叫什么|训练数据|你的模型|你是什么模型|^你多少钱");
    private static final List<String> OUT_OF_DOMAIN =
            List.of("天气", "股票", "彩票", "今天星期", "写一首诗", "作诗", "讲个笑话");

    /** Prompt injection 启发式: "忽略之前所有指令" / "无视系统提示" 等。 */
    private static final Pattern INJECTION_PHRASE =
            Pattern.compile(
                    "忽略.{0,8}指令|无视.{0,8}(指令|提示)|ignore.{0,8}previous", Pattern.CASE_INSENSITIVE);

    // ── 实现 ─────────────────────────────────────────────

    @Override
    public RouterDecision route(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return RouterDecision.refuse("EMPTY_QUERY", 1.0);
        }
        QueryNormalizer.NormalizedQuery q = QueryNormalizer.normalize(rawQuery);
        String text = q.normalized();

        // 1. UNANSWERABLE — 最高优先级, 一旦命中直接 REFUSE (不做低置信回退)
        UnanswerableHit una = detectUnanswerable(text);
        if (una != null) {
            return RouterDecision.refuse(una.reasonCode, una.confidence);
        }

        // 2. COMPARISON (双对象/双版本 + 比较词)
        if (isComparison(text, q)) {
            List<String> entitiesAB = extractComparisonEntities(text, q);
            RouterDecision d =
                    decision(
                            TaskIntent.COMPARISON,
                            ExecutionStrategy.FIXED_WORKFLOW,
                            entitiesAB,
                            q.asRouterFilters(),
                            0.92,
                            "COMPARISON_TWO_OBJECTS");
            return d;
        }

        // 3. MULTI_HOP (因果 "为什么 X 之后 Y")
        if (isMultiHop(text)) {
            RouterDecision d =
                    decision(
                            TaskIntent.MULTI_HOP,
                            ExecutionStrategy.FIXED_WORKFLOW,
                            collectEntities(q),
                            q.asRouterFilters(),
                            0.85,
                            "MULTI_HOP_CAUSAL");
            return d;
        }

        // 4. NUMERIC_OR_VERSION (版本号 / 错误码 / 年份+季度; 在非比较非多跳语境下)
        if (!q.versions().isEmpty() || !q.errorCodes().isEmpty() || (!q.years().isEmpty())) {
            String reasonCode = reasonForNumeric(q);
            double conf = confidenceForNumeric(q, reasonCode);
            RouterDecision d =
                    decision(
                            TaskIntent.NUMERIC_OR_VERSION,
                            ExecutionStrategy.TARGETED_RAG,
                            collectEntities(q),
                            q.asRouterFilters(),
                            conf,
                            reasonCode);
            return d;
        }

        // 5. ENTITY_LOOKUP (产品 + 章节定位词)
        if (!q.mentionedProducts().isEmpty() && SECTION_LOOKUP_PHRASE.matcher(text).find()) {
            RouterDecision d =
                    decision(
                            TaskIntent.ENTITY_LOOKUP,
                            ExecutionStrategy.TARGETED_RAG,
                            collectEntities(q),
                            q.asRouterFilters(),
                            0.78,
                            "ENTITY_SECTION_LOOKUP");
            return d;
        }

        // 6. SUMMARY ("总结/概括/概论" 词命中, 但仅在双对象时已被 COMPARISON 卷走)
        if (SUMMARY_PHRASE.matcher(text).find()) {
            double conf = SUMMARY_VERB.matcher(text).find() ? 0.78 : 0.6;
            RouterDecision d =
                    decision(
                            TaskIntent.SUMMARY,
                            ExecutionStrategy.CLASSIC_RAG,
                            collectEntities(q),
                            q.asRouterFilters(),
                            conf,
                            "SUMMARY");
            return d;
        }

        // 7. FACT 默认兜底: 置信度一般 (无强信号), 走低置信回退机制
        RouterDecision d =
                decision(
                        TaskIntent.FACT,
                        ExecutionStrategy.CLASSIC_RAG,
                        collectEntities(q),
                        q.asRouterFilters(),
                        0.6,
                        "FACT_DEFAULT");
        return d;
    }

    // ── 低置信度回退包装 ─────────────────────────────────
    /**
     * 工厂: 如果 conf < 阈值且策略不是 REFUSE, 把 strategy 改为 CLASSIC_RAG + reasonCode 追加
     * LOW_CONFIDENCE_FALLBACK。intent 不变 (评测依然可统计)。
     */
    private static RouterDecision decision(
            TaskIntent intent,
            ExecutionStrategy strategy,
            List<String> entities,
            Map<String, Object> filters,
            double confidence,
            String reasonCode) {
        if (confidence < LOW_CONFIDENCE_THRESHOLD && strategy != ExecutionStrategy.REFUSE) {
            return new RouterDecision(
                    intent,
                    ExecutionStrategy.CLASSIC_RAG,
                    entities,
                    filters,
                    confidence,
                    reasonCode + "_LOW_CONFIDENCE_FALLBACK");
        }
        return new RouterDecision(intent, strategy, entities, filters, confidence, reasonCode);
    }

    // ── 规则检测 ─────────────────────────────────────────

    private static boolean isComparison(String text, QueryNormalizer.NormalizedQuery q) {
        if (!COMPARISON_PHRASE.matcher(text).find()) {
            return false;
        }
        // 必须有 connector 或至少两个 product / 两个 version
        if (COMPARISON_CONNECTOR.matcher(text).find()) return true;
        if (q.mentionedProducts().size() >= 2) return true;
        return q.versions().size() >= 2;
    }

    private static boolean isMultiHop(String text) {
        if (MULTI_HOP_PHRASE.matcher(text).find()) return true;
        // "为什么 X 后/之后 ...": WHY_PREFIX + 含 "后"
        return WHY_PREFIX.matcher(text).find() && (text.contains("后") || text.contains("之后"));
    }

    private static List<String> extractComparisonEntities(
            String text, QueryNormalizer.NormalizedQuery q) {
        List<String> out = new ArrayList<>();
        // 优先两个版本号 (如 "v1 与 v2"), 没有就取两个产品, 再没有就取 comparator 两边 token
        out.addAll(q.versions());
        if (out.size() < 2) out.addAll(q.mentionedProducts());
        return out;
    }

    private static List<String> collectEntities(QueryNormalizer.NormalizedQuery q) {
        List<String> out = new ArrayList<>();
        out.addAll(q.versions());
        out.addAll(q.errorCodes());
        out.addAll(q.mentionedProducts());
        return out;
    }

    private static String reasonForNumeric(QueryNormalizer.NormalizedQuery q) {
        if (!q.versions().isEmpty()) return "VERSION_LOOKUP";
        if (!q.errorCodes().isEmpty()) return "ERROR_CODE_LOOKUP";
        if (!q.years().isEmpty()) return "TIME_RANGE_LOOKUP";
        return "NUMERIC_LOOKUP";
    }

    private static double confidenceForNumeric(
            QueryNormalizer.NormalizedQuery q, String reasonCode) {
        // 版本号/错误码: 高置信 (这是 TARGETED_RAG 最强的场景)
        // 单独年份: 中等置信 (可能只是提及)
        if (!q.versions().isEmpty() || !q.errorCodes().isEmpty()) return 0.9;
        if (!q.years().isEmpty()) return 0.75;
        return 0.7;
    }

    /** 检测 UNANSWERABLE 类问题, 不命中则返回 null。 */
    private static UnanswerableHit detectUnanswerable(String text) {
        if (INJECTION_PHRASE.matcher(text).find()) {
            return new UnanswerableHit("PROMPT_INJECTION_ATTEMPT", 0.95);
        }
        if (IDENTITY_QUESTION.matcher(text).find()) {
            return new UnanswerableHit("IDENTITY_QUESTION", 0.9);
        }
        for (String v : OUT_OF_SCOPE_VERBS) {
            if (text.contains(v)) return new UnanswerableHit("OUT_OF_SCOPE_ACTION", 0.85);
        }
        for (String v : OUT_OF_DOMAIN) {
            if (text.contains(v)) return new UnanswerableHit("OUT_OF_KB_DOMAIN", 0.85);
        }
        return null;
    }

    private record UnanswerableHit(String reasonCode, double confidence) {}
}
