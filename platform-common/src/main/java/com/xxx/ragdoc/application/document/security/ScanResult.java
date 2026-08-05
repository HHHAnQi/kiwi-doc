package com.xxx.ragdoc.application.document.security;

import java.util.List;

/**
 * Task 8: Document Security Scanner 返回值 — 检测文档文本中可能的 prompt injection。
 *
 * <p>三类 {@link Outcome} 与「任务文档要求」对应:
 *
 * <ul>
 *   <li>{@code CLEAN}: 0 命中, 文本干净
 *   <li>{@code SUSPICIOUS}: 1-2 命中 (低风险误报或单一可疑模式), 推荐 TAG 但不阻
 *   <li>{@code MALICIOUS}: ≥3 命中 (多模式共振, 经典 injection 模式), 推荐阻断
 * </ul>
 *
 * <p>{@link Threat} 给每个命中详情, 让日志/前端 UI 明示攻击面。
 *
 * <p>用法示例:
 *
 * <pre>{@code
 * ScanResult r = scanner.scan(fullText, docId);
 * if (r.outcome() == ScanResult.Outcome.MALICIOUS && props.blockOnMalicious) {
 *     doc.markFailed("security_blocked: " + r.summary());
 *     throw new IllegalStateException("doc blocked by security scanner");
 * }
 * }</pre>
 */
public record ScanResult(Outcome outcome, List<Threat> threats, String summary) {

    public ScanResult {
        if (threats == null) threats = List.of();
        if (summary == null) summary = "";
    }

    /** 0 命中。 */
    public static ScanResult clean() {
        return new ScanResult(Outcome.CLEAN, List.of(), "no threats detected");
    }

    /** 一般可疑 (低风险)。 */
    public static ScanResult suspicious(List<Threat> threats) {
        List<Threat> t = threats == null ? List.of() : threats;
        return new ScanResult(Outcome.SUSPICIOUS, t, buildSummary("suspicious", t));
    }

    /** 多模式共振, 推荐阻断。 */
    public static ScanResult malicious(List<Threat> threats) {
        List<Threat> t = threats == null ? List.of() : threats;
        return new ScanResult(Outcome.MALICIOUS, t, buildSummary("malicious", t));
    }

    private static String buildSummary(String label, List<Threat> threats) {
        StringBuilder sb = new StringBuilder(label).append(": ");
        boolean first = true;
        for (Threat t : threats) {
            if (!first) sb.append(", ");
            sb.append(t.type());
            first = false;
        }
        return sb.toString();
    }

    public enum Outcome {
        CLEAN,
        SUSPICIOUS,
        MALICIOUS
    }

    /** 单个威胁命中详情。 */
    public record Threat(ThreatType type, String matchedPattern, int position, String excerpt) {

        public Threat {
            if (matchedPattern == null) matchedPattern = "";
            if (excerpt == null) excerpt = "";
        }
    }

    /**
     * 任务文档要求的检测种类。
     *
     * <ul>
     *   <li>{@link #IGNORE_PREVIOUS} — "Ignore previous instructions" 类
     *   <li>{@link #SYSTEM_PROMPT_LEAK} — "system prompt" / 要求泄露系统提示
     *   <li>{@link #TOOL_CALLING} — {@code <tool_call>}/function_call 类
     *   <li>{@link #ROLE_HIJACK} — "you are now" / "act as" 角色劫持
     *   <li>{@link #ENCODING_OBFUSCATION} — zero-width / 异常 unicode control 字符
     * </ul>
     */
    public enum ThreatType {
        IGNORE_PREVIOUS,
        SYSTEM_PROMPT_LEAK,
        TOOL_CALLING,
        ROLE_HIJACK,
        ENCODING_OBFUSCATION
    }
}
