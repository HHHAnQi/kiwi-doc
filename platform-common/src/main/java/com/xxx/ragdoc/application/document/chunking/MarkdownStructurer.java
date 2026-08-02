package com.xxx.ragdoc.application.document.chunking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构感知预处理器(Q3-A + Q3-B)。
 *
 * <p>TextCleaner 在切片前已统一清洗, 但它把行级 markdown 标记(``` ``` ```, |table|, #)的字面符号去掉, 让下游 ChunkingService
 * 看到的就是段落流, 失去了"哪些行属于同一个 code block / table"的结构信息。 这导致 {@link ChunkingService#splitIntoChildren}
 * 用句号/换行一刀切, 会粗暴切断 code block 中间的多行 SQL/YAML, 也切断 markdown table 中间的多行表体。
 *
 * <p>本类在 TextCleaner <b>之前</b> 介入, 把原文重排为「结构化块列表」, 每个块标注类型 + section_path:
 *
 * <ul>
 *   <li>{@link BlockType#CODE} — 来自单个 ```...``` 围起来的代码块(语言标记保留为单独 token, 内容整段为一块)
 *   <li>{@link BlockType#TABLE} — 连续以 | 开头的行(至少 2 行)整体为一块
 *   <li>{@link BlockType#TEXT} — 普通段落(原样保留, 由 TextCleaner 后续清洗)
 * </ul>
 *
 * <p>每个块自带 {@link StructuredBlock#sectionPath()} — 它所属的 markdown heading 路径栈(如 ["Dubbo", "异步调用"]),
 * 这是 Q3-B 的核心: 在 TextCleaner 清掉 `#` 之前把 heading 抽走, 让 chunk 能溯源到章节。
 *
 * <p>ChunkingService 拿到 List&lt;StructuredBlock&gt; 后, 切片逻辑改为: 块级别边界永不被切断 (code/table 是原子); TEXT
 * 块内部仍可按句号/字数细切。
 *
 * <p>故意不加 markup 处理(callout / admonition / Hugo shortcode) — 那些已被 TextCleaner 清洗掉, 无结构价值。
 *
 * <h2>为什么不在 TextCleaner 里做</h2>
 *
 * 单一职责: TextCleaner 是 string→string 的清洗函数, 已经有 10 条规则 + 顺序敏感, 再嵌入结构解析 会让它的契约("\$input
 * 清洗文本")和输入输出类型都失控。结构化是不同概念层的关注点, 独立成类。
 */
public final class MarkdownStructurer {

    private MarkdownStructurer() {}

    /** 匹配 markdown heading 行: `^#{1,6}\s+标题文字`。不支持 ATLP 备用语法(下划线 H1/H2) 因 SCA corpus 不用。 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    /**
     * 把原始 markdown 文本切成有序的结构化块。
     *
     * @param raw 原始全文(未经过 TextCleaner)
     * @return 结构化块列表(空入参返回空列表)
     */
    public static List<StructuredBlock> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<StructuredBlock> blocks = new ArrayList<>();
        String[] lines = raw.split("\n", -1);

        // Q3-B: heading 路径栈。遇 #/##/.../#(n) 标题行入栈, 维护"当前所在章节"。
        // ## foo → pop 直到 size < 2, push foo; ### bar → pop 直到 size < 3, push bar。
        Deque<HeadingEntry> headingStack = new ArrayDeque<>();

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // Q3-B: 先识别 heading 行(优先于 code fence —— `# foo` 在 code 内不算 heading)
            // 但 heading 行若在 code block 内, 上面 line 88 之前已分别走 CODE 分支, 不会到这。
            Matcher hm = HEADING_PATTERN.matcher(line);
            if (hm.matches()) {
                int level = hm.group(1).length(); // # 个数
                String title = hm.group(2).trim();
                pushHeading(headingStack, level, title);
                i++;
                continue;
            }

            // 1. 代码块: ```lang 开始, ``` 结束(允许末尾空白)
            if (isFenceOpen(line)) {
                StringBuilder code = new StringBuilder();
                int startLang = i;
                // 跳过 fence 行本身, 收集内容直到 fence 关闭
                i++;
                while (i < lines.length && !isFenceClose(lines[i])) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                // i 指向关闭 fence(若文件结尾未闭合, i 已 = lines.length)
                if (i < lines.length) i++; // 跳过关闭 fence
                String content = code.toString();
                // 空 code block 也要保留(可能是占位/示例), 但罕见; 空 content 时跳过
                if (!content.isBlank()) {
                    blocks.add(
                            new StructuredBlock(
                                    content,
                                    BlockType.CODE,
                                    startLang,
                                    snapshotPath(headingStack)));
                }
                continue;
            }

            // 2. Table: 连续以 | 开头的行(含分隔行 |---|), 至少 2 行才算 table
            if (line.trim().startsWith("|")) {
                int startIdx = i;
                StringBuilder tbl = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    tbl.append(lines[i]).append('\n');
                    i++;
                }
                // 至少 header + separator / 2 行才认为是 table
                if (i - startIdx >= 2) {
                    blocks.add(
                            new StructuredBlock(
                                    tbl.toString(),
                                    BlockType.TABLE,
                                    startIdx,
                                    snapshotPath(headingStack)));
                } else {
                    // 单行 |foo 也归 TEXT(避免误判 YAML front matter 之类)
                    blocks.add(
                            new StructuredBlock(
                                    tbl.toString(),
                                    BlockType.TEXT,
                                    startIdx,
                                    snapshotPath(headingStack)));
                }
                continue;
            }

            // 3. 普通文本行: 一直累积到遇到下一个 fence / table 起点 / heading 行
            int startIdx = i;
            StringBuilder text = new StringBuilder();
            while (i < lines.length
                    && !isFenceOpen(lines[i])
                    && !lines[i].trim().startsWith("|")
                    && !HEADING_PATTERN.matcher(lines[i]).matches()) {
                text.append(lines[i]).append('\n');
                i++;
            }
            String content = text.toString();
            if (!content.isBlank()) {
                blocks.add(
                        new StructuredBlock(
                                content, BlockType.TEXT, startIdx, snapshotPath(headingStack)));
            }
        }

        return blocks;
    }

    /**
     * 维护 heading 栈层级: pop 直到栈顶 level &lt; 传入 level, 然后压入新 heading。
     *
     * <p>例: 栈=[h1: Dubbo] → push(h2, 异步调用) → 栈=[h1: Dubbo, h2: 异步调用]; 再 push(h2, 编排) → 先 pop h2:
     * 异步调用 → 栈=[h1: Dubbo, h2: 编排]。
     */
    private static void pushHeading(Deque<HeadingEntry> stack, int level, String title) {
        while (!stack.isEmpty() && stack.peek().level >= level) {
            stack.pop();
        }
        stack.push(new HeadingEntry(level, title));
    }

    /** 把栈逆序拍快照(栈顶为最深层 → 反转得到 [h1, h2, h3] 自然顺序)。 */
    private static List<String> snapshotPath(Deque<HeadingEntry> stack) {
        if (stack.isEmpty()) return List.of();
        List<String> reversed = new ArrayList<>(stack.size());
        for (HeadingEntry e : stack) reversed.add(e.title);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    /** ```java / ```bash / ``` 行(行首允许最多 3 个空格的 indent)。 */
    private static boolean isFenceOpen(String line) {
        String t = line.stripLeading();
        // 三反引号 + 可选语言标记, 行尾允许空白
        return t.startsWith("```");
    }

    /** ``` 单独成行或行尾只有可选空白的视为关闭 fence。 */
    private static boolean isFenceClose(String line) {
        String t = line.strip();
        return t.equals("```") || t.startsWith("```");
    }

    /** heading 栈内部条目: level + 标题文本。 */
    private record HeadingEntry(int level, String title) {}

    /**
     * 结构化块: 内容 + 类型 + 原文行号(用于诚实标注 chunk 来源, 0-based) + section_path。
     *
     * @param sectionPath 该块所属的 markdown heading 路径栈(如 [Dubbo, 异步调用]); 空 list = 无 heading 上下文
     */
    public record StructuredBlock(
            String content, BlockType type, int sourceLine, List<String> sectionPath) {}

    /** 块类型。CODE/TABLE 在 ChunkingService 中作为原子单元不被切断。 */
    public enum BlockType {
        TEXT,
        CODE,
        TABLE
    }
}
