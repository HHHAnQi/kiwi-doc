package com.xxx.ragdoc.infrastructure.security;

import com.xxx.ragdoc.application.document.SecurityScannerProperties;
import com.xxx.ragdoc.application.document.security.ScanResult;
import com.xxx.ragdoc.application.document.security.ScanResult.Threat;
import com.xxx.ragdoc.application.document.security.ScanResult.ThreatType;
import com.xxx.ragdoc.application.document.security.port.SecurityScannerPort;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 8: {@link SecurityScannerPort} 的正则规则实现 — 检测文档中可能的 prompt injection。
 *
 * <p><b>任务文档要求检测</b>:
 *
 * <ol>
 *   <li>{@code "Ignore previous instructions"} 类 → {@link ThreatType#IGNORE_PREVIOUS}
 *   <li>{@code "system prompt"} / 系统提示泄露 → {@link ThreatType#SYSTEM_PROMPT_LEAK}
 *   <li>{@code "<tool_call>"} / 工具调用注入 → {@link ThreatType#TOOL_CALLING}
 * </ol>
 *
 * <p>额外加:
 *
 * <ul>
 *   <li>角色劫持 ("you are now" / "act as") → {@link ThreatType#ROLE_HIJACK}
 *   <li>零宽/控制字符混淆 → {@link ThreatType#ENCODING_OBFUSCATION}
 * </ul>
 *
 * <p>判定逻辑:
 *
 * <ul>
 *   <li>0 命中 → CLEAN
 *   <li>命中数 < {@code maliciousThreshold} → SUSPICIOUS (低风险/单一可疑)
 *   <li>命中数 ≥ threshold → MALICIOUS (多模式共振, 高置信)
 * </ul>
 *
 * <p>设计选择 (不调 LLM 做 judge):
 *
 * <ul>
 *   <li>低成本 + 确定性: 解析每条 doc 跑 5 个 pattern, μs 级
 *   <li>防 LLM-as-judge 自身被越狱: 攻击者可让 judge LLM "判恶意为洁净"
 *   <li>正则是第一道 (Task 7 citation verifier 二道, defense-in-depth)
 * </ul>
 *
 * <p>Bean 装配: 不用 {@code @ConditionalOnProperty} — 总是装配让 TikaParsingTrigger 构造稳定, 内部 {@code
 * properties.isEnabled()} 决定是否真扫 (false 时直接返 CLEAN)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegexSecurityScanner implements SecurityScannerPort {

    private final SecurityScannerProperties properties;

    // 大小写不敏感 + unicode (中文/英文双语)
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Ignore previous / disregard prior / 忽略上面指令。 */
    private static final List<Pattern> IGNORE_PREVIOUS_PATTERNS =
            List.of(
                    Pattern.compile(
                            "ignore\\s+(the\\s+|all\\s+)?previous\\s+(instructions?|prompt)",
                            FLAGS),
                    Pattern.compile(
                            "disregard\\s+(all\\s+|the\\s+)?(prior|previous)\\s+(instructions?|prompt)?",
                            FLAGS),
                    Pattern.compile(
                            "forget\\s+(all\\s+|your\\s+)?(previous\\s+)?(instructions?|prompt)",
                            FLAGS),
                    // 中文: 忽略上面/之前/之前的 + 0~5 字 + 指令/提示/规则
                    Pattern.compile("忽略(上面|之前|前面|此前).{0,5}(指令|提示|规则|prompt)", FLAGS),
                    Pattern.compile("无视(上面|之前|前面|此前).{0,5}(指令|提示|规则)", FLAGS));

    /** System prompt / reveal / 你的系统提示。 */
    private static final List<Pattern> SYSTEM_PROMPT_PATTERNS =
            List.of(
                    Pattern.compile(
                            "(reveal|show|print|repeat|leak)\\s+.*(system\\s+)?(prompt|instruction|message)",
                            FLAGS),
                    Pattern.compile("system\\s+prompt", FLAGS),
                    Pattern.compile("你(的)?(系统|原始)?(提示|prompt|指令).{0,10}(是|是什么|是什么?|是什么\\?)", FLAGS),
                    Pattern.compile("(输出|展示|打印)你(的)?(系统|初始|原始)?(提示|prompt)", FLAGS));

    /** Tool calling injection — 各模型 marker。 */
    private static final List<Pattern> TOOL_CALLING_PATTERNS =
            List.of(
                    Pattern.compile("<tool_call>", FLAGS),
                    Pattern.compile("<\\|tool_calls\\|>", FLAGS),
                    Pattern.compile("<\\|start_header_id\\|>assistant<\\|end_header_id\\|>", FLAGS),
                    Pattern.compile("<function_call>", FLAGS),
                    // 中文变种: 调用工具 X / 执行函数 Y
                    Pattern.compile("请?(调用|执行|触发)(某个|这个)?(工具|函数|function)\\s*[:：]?", FLAGS));

    /** Role hijack — 角色劫持。 */
    private static final List<Pattern> ROLE_HIJACK_PATTERNS =
            List.of(
                    Pattern.compile("you\\s+are\\s+now\\s+", FLAGS),
                    Pattern.compile("your\\s+new\\s+role\\s+is\\s+", FLAGS),
                    Pattern.compile("(act|pretend)\\s+as\\s+(if\\s+you\\s+are\\s+)?", FLAGS),
                    Pattern.compile("from\\s+now\\s+on\\s*,?\\s+you\\s+are", FLAGS),
                    // 中文: 从现在起你是 / 假装你是 / 请扮演
                    Pattern.compile("(从现在起|接下来|此后)你(是|扮演|应当)", FLAGS),
                    Pattern.compile("(假装|扮演|模拟)(你是|一个|一名)", FLAGS));

    /** Encoding obfuscation — 零宽 / BOM / 控制字符。 */
    private static final List<Pattern> ENCODING_PATTERNS =
            List.of(
                    // U+200B (zero-width space), U+200C, U+200D, U+2060 (word joiner), U+FEFF (BOM)
                    Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]"),
                    // 控制字符 C1 (U+0080-U+009F) — 在文本里通常表示 zip/二进制 leak
                    Pattern.compile("[\\u0080-\\u009F]"));

    @Override
    public ScanResult scan(String text, Long documentId) {
        if (text == null || text.isBlank()) return ScanResult.clean();
        if (!properties.isEnabled()) {
            // 主开关关 — 直接返 CLEAN (老行为兼容)
            return ScanResult.clean();
        }
        List<Threat> threats = new ArrayList<>();
        collectThreats(threats, text, ThreatType.IGNORE_PREVIOUS, IGNORE_PREVIOUS_PATTERNS);
        collectThreats(threats, text, ThreatType.SYSTEM_PROMPT_LEAK, SYSTEM_PROMPT_PATTERNS);
        collectThreats(threats, text, ThreatType.TOOL_CALLING, TOOL_CALLING_PATTERNS);
        collectThreats(threats, text, ThreatType.ROLE_HIJACK, ROLE_HIJACK_PATTERNS);
        collectThreats(threats, text, ThreatType.ENCODING_OBFUSCATION, ENCODING_PATTERNS);

        if (threats.isEmpty()) {
            return ScanResult.clean();
        }
        int threshold = Math.max(1, properties.getMaliciousThreshold());
        ScanResult r =
                threats.size() >= threshold
                        ? ScanResult.malicious(threats)
                        : ScanResult.suspicious(threats);
        log.warn(
                "security.scan doc_id={}, outcome={}, n_threats={}, summary={}",
                documentId,
                r.outcome(),
                threats.size(),
                r.summary());
        return r;
    }

    /** 把模式命中 collect 进 threats (每种类型独立 entry, position 头次出现位置). */
    private static void collectThreats(
            List<Threat> threats, String text, ThreatType type, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                int start = m.start();
                int end = Math.min(text.length(), m.end() + 30); // excerpt 截到匹配末 + 30 char
                String excerpt =
                        text.substring(Math.max(0, start), end).replaceAll("\\s+", " ").trim();
                threats.add(new Threat(type, p.pattern(), start, truncate(excerpt, 60)));
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // 仅给 test 内部 helper
    static List<Pattern> patternsFor(ThreatType t) {
        return switch (t) {
            case IGNORE_PREVIOUS -> IGNORE_PREVIOUS_PATTERNS;
            case SYSTEM_PROMPT_LEAK -> SYSTEM_PROMPT_PATTERNS;
            case TOOL_CALLING -> TOOL_CALLING_PATTERNS;
            case ROLE_HIJACK -> ROLE_HIJACK_PATTERNS;
            case ENCODING_OBFUSCATION -> ENCODING_PATTERNS;
        };
    }

    /** 仅给 test 用的 helper: 数命中数。 */
    int countMatches(String text) {
        if (text == null || text.isBlank()) return 0;
        int n = 0;
        for (ThreatType t : ThreatType.values()) {
            for (Pattern p : patternsFor(t)) {
                if (p.matcher(text).find()) n++;
            }
        }
        return n;
    }
}
