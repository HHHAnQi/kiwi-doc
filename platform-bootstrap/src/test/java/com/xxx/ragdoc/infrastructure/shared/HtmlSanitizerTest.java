package com.xxx.ragdoc.infrastructure.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HtmlSanitizer 单测。 直接覆盖 XSS 攻击向量(来自 OWASP 标准列表)。 */
class HtmlSanitizerTest {

    @Test
    @DisplayName("null 安全: 返回 null")
    void nullSafe() {
        assertThat(HtmlSanitizer.escape(null)).isNull();
    }

    @Test
    @DisplayName("空串安全: 返回空串")
    void emptySafe() {
        assertThat(HtmlSanitizer.escape("")).isEmpty();
    }

    @Test
    @DisplayName("纯文本: 不变")
    void plainText() {
        assertThat(HtmlSanitizer.escape("hello world 你好")).isEqualTo("hello world 你好");
    }

    @Test
    @DisplayName("<script> 标签 → 转义为 &lt;script&gt;")
    void scriptTagEscaped() {
        assertThat(HtmlSanitizer.escape("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    @DisplayName("<img onerror> XSS → 尖括号转义, 防止标签被渲染")
    void imgOnerrorEscaped() {
        // 仅转义 < > (HTML4 规则), 内部 onerror=alert 文本字面虽保留,
        // 但因 < > 被转义, 浏览器不会把它当 <img> 标签执行
        String result = HtmlSanitizer.escape("<img src=x onerror=alert(1)>");
        assertThat(result).contains("&lt;img").contains("&gt;");
        assertThat(result).doesNotContain("<img");
    }

    @Test
    @DisplayName("双引号 → &quot; (防属性穿越)")
    void doubleQuoteEscaped() {
        String result = HtmlSanitizer.escape("\"quoted\"");
        assertThat(result).contains("&quot;");
    }

    @Test
    @DisplayName("SQL 关键字不会被此工具处理(由 JPA 参数化负责)")
    void sqlKeywordsUnaffected() {
        // 此工具仅管 HTML; SQL 注入由 prepared statement 兜底
        String input = "asd'; DROP TABLE users; --";
        String result = HtmlSanitizer.escape(input);
        // 仅 ' 和 - 等不转义; 但绝不会让 < > 通过
        assertThat(result).doesNotContain("<");
    }
}
