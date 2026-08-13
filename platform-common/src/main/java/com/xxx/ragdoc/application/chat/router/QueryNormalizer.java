package com.xxx.ragdoc.application.chat.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR-3 / EMS-PR3: 把用户原始 query 规范化为 Router 入参。
 *
 * <p>本类<b>不进入 LLM</b>, 仅做安全可解释的字符清理 + 正则 entity 抽取:
 *
 * <ul>
 *   <li>多空白合并 / 首尾 trim / 全角转半角数字
 *   <li>抽取版本号 / 错误码 / 年份+季度 / 产品名清单(知识库范围白名单)
 *   <li>保留原始 query (供 LLM prompt 用), normalizedQuery 单独一份 (供 Router + retrieval 用)
 * </ul>
 *
 * <p>硬约束: <b>不引入</b>仓库外事实 (不能在 normalize 阶段填知识库不存在的版本号或概念)。
 */
public final class QueryNormalizer {

    private QueryNormalizer() {}

    // ── 正则 ──────────────────────────────────────────────

    /**
     * v1.0 / v2.3.1 / V2 / 2.0 / 1.x — 版本号。
     *
     * <p>必须满足以下之一才算版本号 (避免误伤 "Q1" / "100 元" / 单纯年份):
     *
     * <ul>
     *   <li>{@code v} / {@code V} 前缀 + 数字 + 0..3 段点号分隔 (v1, V2.3.1)
     *   <li>无前缀但至少含一个 {@code .} (1.0, 2.5.1)
     *   <li>{@code 数字.x} 形式 (1.x, 2.x)
     * </ul>
     */
    static final Pattern VERSION_PATTERN =
            Pattern.compile(
                    "\\b(?:[vV]\\d+(?:\\.\\d+){0,3}|\\d+\\.\\d+(?:\\.\\d+){0,2}|\\d+\\.x)\\b|\\b([vV]\\d+)\\b");

    /** 10086 / 5002 等数字错误码 (3-5 位纯数字), 防误命中 "100 元" 限定 3-5 位。 */
    static final Pattern NUMERIC_ERROR_CODE_PATTERN = Pattern.compile("\\b\\d{3,5}\\b");

    /** AUTH_EXPIRED / RUNTIME_ERROR / SYS_AUTH_FAILED 等大写+下划线命名错误码。 */
    static final Pattern NAMED_ERROR_CODE_PATTERN =
            Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+){1,}\\b");

    /** 2023 / 2024 / 2025 — 年份。 */
    static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    /** 2025 Q1 / Q3 / 第一季度等 — 季度。 */
    static final Pattern QUARTER_PATTERN = Pattern.compile("\\bQ([1-4])\\b|[第第]([1-4])季度");

    /** 知识库常见产品名白名单 (用于 entity whitelist 抽取); 命中即归一化为大写键。 加新条目需在数据集里有评测依据, 不允许凭直觉扩。 */
    static final List<String> PRODUCT_WHITELIST =
            List.of(
                    "Spring Boot",
                    "Spring Cloud",
                    "Spring Cloud Alibaba",
                    "Dubbo",
                    "Nacos",
                    "Seata",
                    "RocketMQ",
                    "Sentinel",
                    "Gateway",
                    "OpenFeign",
                    "RestTemplate",
                    "Hystrix",
                    "Ribbon",
                    "LoadBalancer",
                    "Eureka",
                    "Consul",
                    "Zookeeper",
                    "OkHttp",
                    "TCC",
                    "AT");

    // ── 入口 ─────────────────────────────────────────────

    public record NormalizedQuery(
            String original,
            String normalized,
            List<String> versions,
            List<String> errorCodes,
            List<String> years,
            List<String> quarters,
            List<String> mentionedProducts,
            Map<String, Object> asRouterFilters) {

        public boolean hasAnyEntity() {
            return !versions.isEmpty()
                    || !errorCodes.isEmpty()
                    || !years.isEmpty()
                    || !quarters.isEmpty()
                    || !mentionedProducts.isEmpty();
        }
    }

    public static NormalizedQuery normalize(String raw) {
        if (raw == null) {
            return new NormalizedQuery(
                    "", "", List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        String normalized = normalizeWhitespaces(raw);

        List<String> versions = dedupMatches(VERSION_PATTERN.matcher(normalized));
        List<String> years = dedupMatches(YEAR_PATTERN.matcher(normalized));
        // 错误码前先剔除年份(2025 看起来像 4 位数字错误码): 年份优先级 > 错误码
        List<String> rawNumCodes = dedupMatches(NUMERIC_ERROR_CODE_PATTERN.matcher(normalized));
        java.util.Set<String> yearSet = new java.util.HashSet<>(years);
        List<String> errorCodes = new ArrayList<>();
        for (String c : rawNumCodes) {
            if (!yearSet.contains(c) && !versions.contains(c)) {
                errorCodes.add(c);
            }
        }
        // 大写命名错误码合并 (与数字错误码不冲突)
        for (String name : dedupMatches(NAMED_ERROR_CODE_PATTERN.matcher(normalized))) {
            if (!errorCodes.contains(name)) errorCodes.add(name);
        }
        List<String> quarters = new ArrayList<>();
        Matcher qm = QUARTER_PATTERN.matcher(normalized);
        while (qm.find()) {
            String q = qm.group(1) != null ? qm.group(1) : qm.group(2);
            if (q != null) quarters.add("Q" + q);
        }
        List<String> products = extractProducts(normalized);

        Map<String, Object> filters = new LinkedHashMap<>();
        if (!versions.isEmpty()) filters.put("versions", versions);
        if (!errorCodes.isEmpty()) filters.put("errorCodes", errorCodes);
        if (!years.isEmpty()) filters.put("years", years);
        if (!quarters.isEmpty()) filters.put("quarters", quarters);
        if (!products.isEmpty()) filters.put("products", products);

        return new NormalizedQuery(
                raw, normalized, versions, errorCodes, years, quarters, products, filters);
    }

    // ── 辅助 ─────────────────────────────────────────────

    static String normalizeWhitespaces(String raw) {
        // 全角数字/字母转半角, 让 v１．０ 识别成 v1.0
        String s = raw;
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 0xFF10 && c <= 0xFF19) chars[i] = (char) (c - 0xFF10 + '0');
            else if (c >= 0xFF21 && c <= 0xFF3A) chars[i] = (char) (c - 0xFF21 + 'A');
            else if (c >= 0xFF41 && c <= 0xFF5A) chars[i] = (char) (c - 0xFF41 + 'a');
        }
        s = new String(chars);
        // NRT/CR 合并成单空白
        return s.trim().replaceAll("\\s+", " ");
    }

    private static List<String> dedupMatches(Matcher m) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            String g = m.group();
            if (seen.add(g)) out.add(g);
        }
        return out;
    }

    private static List<String> merge(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String x : b) if (!out.contains(x)) out.add(x);
        return out;
    }

    private static List<String> extractProducts(String normalized) {
        List<String> hits = new ArrayList<>();
        for (String p : PRODUCT_WHITELIST) {
            if (normalized.toLowerCase().contains(p.toLowerCase())) {
                hits.add(p);
            }
        }
        return hits;
    }
}
