package com.xxx.ragdoc.application.document.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 文本清洗器(V2 版面净化降级实现)。
 *
 * <p>设计文档 README L10 要求的 "RAGFlow DeepDoc / PaddleOCR PP-Structure 版面解析" 在 V1 阶段 只接了 Tika(纯文本抽取),
 * 没剥离 Hugo/HTML/markdown 模板噪音。BM25 上线后这类高频噪音 token 被放大, 严重干扰召回。本类补这块缺口: **不替换 Tika, 只对 Tika
 * 抽出的文本做后处理清洗**。
 *
 * <p>清洗规则(顺序敏感, 先去整段噪音块再去行内标点):
 *
 * <ol>
 *   <li>Hugo shortcodes 整段去除: {@code {{% pageinfo %}} ... {{% /pageinfo %}}} (SCA
 *       文档大量使用这个标记表示"已废弃", 但这句话对 RAG 完全无信息)
 *   <li>Hugo 行内 shortcodes: {@code {{< ref >}} {{< tab >}} {{< /tab >}}}
 *   <li>Markdown 图片语法: {@code ![alt](https://...)} (alt 与 URL 都不是答案素材)
 *   <li>HTML 标签残留: {@code <br> <p> <a href="...">}
 *   <li>36 字符 UUID/traceId: {@code 1-217d7500-c19c-4bad-8508-27f30b9d8e21} (BM25 对这种高熵串敏感, 没语义价值)
 *   <li>裸 URL: {@code https://img.alicdn.com/...}(URL 路径常含 UUID, 是 BM25 噪音源)
 *   <li>Markdown 代码围栏开闭: {@code ```java ... ```} (保留代码内容, 只去掉围栏标记本身, 围栏后留空行让分词器断开)
 *   <li>Markdown 标题井号: {@code ## 标题} → {@code 标题} (去掉 #, 保留标题文本)
 *   <li>HTML 实体: {@code &lt; &gt; &amp;}
 *   <li>多余空白合并
 * </ol>
 *
 * <p>保守保留规则: **不删代码块内容、不删配置/参数名、不删版本号**。这些是真信息。
 *
 * <p>V3: 下沉到 platform-common 共享层(parser-service 复用同一清洗规则)。
 */
@Component
public class TextCleaner {

    // 顺序敏感的规则列表
    private final List<Pattern> rules = new ArrayList<>();

    public TextCleaner() {
        // 1. Hugo pageinfo 整段(SCA 文档废弃提示)
        rules.add(
                Pattern.compile(
                        "\\{\\{%[^%]*?pageinfo[^%]*?%\\}\\}[\\s\\S]*?\\{\\{%[^%]*?/pageinfo[^%]*?%\\}\\}",
                        Pattern.CASE_INSENSITIVE));
        // 2. Hugo 任一 shortcode 行内 {{< xxx >}} {{< /xxx >}} {{% xxx %}}
        rules.add(Pattern.compile("\\{\\{[<%][^>}]*?[>%]\\}\\}"));
        // 3. Markdown 图片 ![alt](url)
        rules.add(Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)"));
        // 4. HTML 标签 <xxx ...> </xxx> <xxx/>
        rules.add(Pattern.compile("<[^>]+>"));
        // 5. 36 字符或短形 UUID/traceId (xxxxx-xxxxx-xxxxx-xxxxx-xxxxxxxxxx, 中划线分隔, 至少 3 段)
        rules.add(Pattern.compile("[0-9a-fA-F]{4,}-[0-9a-fA-F]{4,}(?:-[0-9a-fA-F]+){2,}"));
        // 6. 裸 URL (http/https/ftp)
        rules.add(Pattern.compile("https?://[^\\s)\"'<>]+|ftp://[^\\s)\"'<>]+"));
    }

    /** 完整清洗。对全文生效, 适合 chunk 切片前调用。 */
    public String clean(String text) {
        if (text == null || text.isEmpty()) return "";

        String t = text;
        for (Pattern p : rules) {
            t = p.matcher(t).replaceAll(" ");
        }

        // 7. 代码围栏开闭标记(保留内容)。 ```java 或 ```bash 等语言标记整行去除。
        t = t.replaceAll("(?m)^```[^\\n]*$", "");

        // 8. Markdown 标题井号(保留标题文本)
        t = t.replaceAll("(?m)^#{1,6}\\s+", "");

        // 9. HTML 实体(常见 6 个)
        t =
                t.replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&nbsp;", " ")
                        .replace("&#39;", "'");

        // 10. 多余空白合并: 连续 3+ 换行压成 2, 连续空格压成 1
        t = t.replaceAll("\\n{3,}", "\n\n");
        t = t.replaceAll("[ \\t]{2,}", " ");

        // 首尾空白
        return t.strip();
    }
}
