package com.xxx.ragdoc.application.document.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownStructurer} 单测。
 *
 * <p>Q3-A 关键正确性: code block / table / 普通文本三类块的边界识别。这些 case 直接对应 corpus 实测出的切片 bug (代码被切断、表体被拆散)。
 */
@DisplayName("MarkdownStructurer - 结构感知预解析")
class MarkdownStructurerTest {

    @Test
    @DisplayName("单段文本应产出一个 TEXT 块")
    void singleParagraphBecomesOneTextBlock() {
        List<MarkdownStructurer.StructuredBlock> blocks =
                MarkdownStructurer.parse("这是一段普通文本。\n第二行继续。");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).type()).isEqualTo(MarkdownStructurer.BlockType.TEXT);
        assertThat(blocks.get(0).content()).contains("普通文本");
    }

    @Test
    @DisplayName("``` 围起的 code block 应作为单个 CODE 块整体保留(含中间换行)")
    void codeBlockIsAtomicUnit() {
        String md =
                """
                前置说明文字。

                ```java
                @Bean
                public Mongo mongo() {
                    return new MongoClient("localhost", 27017);
                }
                ```

                后置说明文字。
                """;

        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(md);

        // 至少 3 块: 前置 TEXT + CODE + 后置 TEXT
        assertThat(blocks.size()).isGreaterThanOrEqualTo(3);
        MarkdownStructurer.StructuredBlock code =
                blocks.stream()
                        .filter(b -> b.type() == MarkdownStructurer.BlockType.CODE)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("应至少有一个 CODE 块"));

        // code block 内容必须完整: 含 @Bean 注解 + 完整的方法签名 + 27017 端口
        assertThat(code.content()).contains("@Bean");
        assertThat(code.content()).contains("MongoClient");
        assertThat(code.content()).contains("27017");
        // 反引号本身不应进入 content(content 只含围栏内的代码体)
        assertThat(code.content()).doesNotContain("```");
    }

    @Test
    @DisplayName("连续 | 行应识别为 TABLE 块, 单行 | 不识别")
    void tableIsAtomicUnit() {
        String md =
                """
                配置项说明:

                | 名称 | 默认值 | 说明 |
                |---|---|---|
                | timeout | 3000 | 超时(ms) |
                | retries | 3 | 重试次数 |

                说明文字结束。
                """;

        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(md);
        MarkdownStructurer.StructuredBlock table =
                blocks.stream()
                        .filter(b -> b.type() == MarkdownStructurer.BlockType.TABLE)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("应识别出一个 TABLE 块"));

        // table 内容必须含分隔行 + 至少两行数据(完整未被切碎)
        assertThat(table.content()).contains("timeout");
        assertThat(table.content()).contains("retries");
        assertThat(table.content()).contains("|---|");
    }

    @Test
    @DisplayName("单行 |foo 应归为 TEXT 而不是 TABLE(防 YAML / 链接误判)")
    void singlePipeLineIsTextNotTable() {
        List<MarkdownStructurer.StructuredBlock> blocks =
                MarkdownStructurer.parse("看这个 link|label 的用法");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).type()).isEqualTo(MarkdownStructurer.BlockType.TEXT);
    }

    @Test
    @DisplayName("空入参 / 全空白应返回空列表")
    void emptyInputReturnsEmpty() {
        assertThat(MarkdownStructurer.parse(null)).isEmpty();
        assertThat(MarkdownStructurer.parse("")).isEmpty();
        assertThat(MarkdownStructurer.parse("   \n  \t  ")).isEmpty();
    }

    @Test
    @DisplayName("未闭合的 code block 应把剩余内容都放进 CODE 块(容错)")
    void unterminatedCodeBlockStillCaptured() {
        String md = "```python\nprint('hello')\nprint('world')"; // 无关闭 fence
        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(md);
        // 仍产出 CODE 块, 内容含两行
        assertThat(
                        blocks.stream()
                                .filter(b -> b.type() == MarkdownStructurer.BlockType.CODE)
                                .count())
                .isEqualTo(1);
        MarkdownStructurer.StructuredBlock code =
                blocks.stream()
                        .filter(b -> b.type() == MarkdownStructurer.BlockType.CODE)
                        .findFirst()
                        .orElseThrow();
        assertThat(code.content()).contains("print('hello')");
        assertThat(code.content()).contains("print('world')");
    }

    // ============================================================
    // Q3-B: section_path (heading 栈)
    // ============================================================

    @Test
    @DisplayName("Q3-B: heading 栈应被正确维护 — 每块带 section_path")
    void headingStackMaintainedPerBlock() {
        String md =
                """
                # Dubbo

                Dubbo 简介。

                ## 异步调用

                这里是异步调用的内容。

                ```java
                RpcContext.asyncCall(() -> {});
                ```

                ### 异步编程

                这是子章节。

                ## 编排

                另一个二级章节。
                """;

        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(md);

        // 第一个 TEXT 块("Dubbo 简介") 在 # Dubbo 下 → path = [Dubbo]
        MarkdownStructurer.StructuredBlock intro =
                blocks.stream()
                        .filter(
                                b ->
                                        b.type() == MarkdownStructurer.BlockType.TEXT
                                                && b.content().contains("Dubbo 简介"))
                        .findFirst()
                        .orElseThrow();
        assertThat(intro.sectionPath()).containsExactly("Dubbo");

        // CODE 块在 ## 异步调用 下 → path = [Dubbo, 异步调用]
        MarkdownStructurer.StructuredBlock code =
                blocks.stream()
                        .filter(b -> b.type() == MarkdownStructurer.BlockType.CODE)
                        .findFirst()
                        .orElseThrow();
        assertThat(code.sectionPath()).containsExactly("Dubbo", "异步调用");

        // 末段在 ## 编排 下 → path = [Dubbo, 编排] (验证 pop 同级别 heading)
        MarkdownStructurer.StructuredBlock orchestrate =
                blocks.stream()
                        .filter(
                                b ->
                                        b.type() == MarkdownStructurer.BlockType.TEXT
                                                && b.content().contains("另一个二级章节"))
                        .findFirst()
                        .orElseThrow();
        assertThat(orchestrate.sectionPath())
                .as("同级新 ## 应 pop 掉前一个 ## 与其下的 ###, 路径应为 [Dubbo, 编排]")
                .containsExactly("Dubbo", "编排");
    }

    @Test
    @DisplayName("Q3-B: 没有 heading 的文档, 所有块 section_path 为空 list")
    void noHeadingYieldsEmptyPath() {
        String md = "纯文本第一段。\n\n第二段。";
        List<MarkdownStructurer.StructuredBlock> blocks = MarkdownStructurer.parse(md);
        assertThat(blocks).isNotEmpty();
        assertThat(blocks).allSatisfy(b -> assertThat(b.sectionPath()).isEmpty());
    }
}
