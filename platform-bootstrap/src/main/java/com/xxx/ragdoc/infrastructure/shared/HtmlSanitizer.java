package com.xxx.ragdoc.infrastructure.shared;

import org.apache.commons.text.StringEscapeUtils;

/**
 * HTML 转义工具(防 XSS)。
 *
 * <p>由 FeedbackService 调用: corrected_answer / comment 在持久化前转义。 见 docs/architecture/security.md
 * §输出编码 + ADR-0003 配套。
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {}

    /** HTML4 转义: &lt; &gt; &amp; &quot; 等。 null / 空串安全返回原值。 */
    public static String escape(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return StringEscapeUtils.escapeHtml4(input);
    }
}
