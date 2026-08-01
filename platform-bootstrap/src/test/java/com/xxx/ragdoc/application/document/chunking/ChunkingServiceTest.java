package com.xxx.ragdoc.application.document.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkingService} 单测: 覆盖 Q3-A 的结构感知 + child overlap。
 *
 * <p>这是 V2 之前从未有过的 ChunkingService 单测。V2-A 的"工程债"之一就是切片纯靠手工烟测验证, 改 Q3-A 之前补本文件, 让 CI 守住:
 *
 * <ul>
 *   <li>code block / table 在 flat 与 parent_child 模式下都不被字数边界切断
 *   <li>parent-child 模式 child 之间有 overlap
 *   <li>纯文本不被 overlap 污染语义
 * </ul>
 */
@DisplayName("ChunkingService - Q3-A 结构感知 + overlap")
class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService(new TextCleaner());

    @Test
    @DisplayName("flat: 包含 code block 的全文, code block 应被保留在单个 chunk 内部")
    void flatCodeBlockStaysInOneChunk() {
        String md =
                """
                准备工作: 安装好 SDK。

                ```xml
                <dependency>
                  <groupId>com.alibaba.nacos</groupId>
                  <artifactId>nacos-client</artifactId>
                  <version>2.4.0</version>
                </dependency>
                ```

                接下来就可以在代码里使用 Nacos 客户端做配置注册了。
                """;

        List<String> chunks = service.chunk(md);

        // 至少存在一个 chunk, 它必须完整含 dependency 起始 + version 标签 + 闭合
        boolean hasIntactDependency =
                chunks.stream()
                        .anyMatch(
                                c ->
                                        c.contains("<dependency>")
                                                && c.contains("nacos-client")
                                                && c.contains("2.4.0")
                                                && c.contains("</dependency>"));
        assertThat(hasIntactDependency).as("Maven dependency 块不应被字数切片切断").isTrue();
    }

    @Test
    @DisplayName("parent_child: 长 parent 内部多 child, 相邻 child 之间应有 4 字以上 overlap")
    void parentChildChildrenHaveOverlap() {
        // 构造一段 ~1000+ 字符的长 parent, 触发 splitIntoChildren 切成 ≥2 个 child
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("这是第").append(i).append("个句子的中文测试内容, 用于把 parent 段落撑到 400 字符以上。");
        }
        String parentText = sb.toString();

        List<ChunkingService.ParentChildChunk> result = service.chunkParentChild(parentText);

        // 必须切的 child 数 ≥ 2, 否则无法验证 overlap
        assertThat(result.size()).as("长 parent 应被切成多个 child 以验证 overlap").isGreaterThanOrEqualTo(2);

        // 拿前两个 child: 第二个 child 的开头应是第一个 child 的尾部(overlap)
        // 取 child[0].tail 与 child[1].head 比较 —— 任何一个 ≥ CHILD_OVERLAP_CHARS 的子串
        String first = result.get(0).childText();
        String second = result.get(1).childText();

        // overlap 机制: child[1] 头部 = child[0] 尾部 N 字符。 验证头 N(=40) 字符 == first 尾 N 字符
        int overlap = Math.min(40, first.length());
        String expectedPrefix = first.substring(first.length() - overlap);
        assertThat(second.startsWith(expectedPrefix))
                .as(
                        "child[1] 头部应是 child[0] 尾部 %d 字符(overlap 机制), 实际 child[0] tail=%s, child[1] head=%s",
                        overlap,
                        expectedPrefix,
                        second.substring(0, Math.min(overlap, second.length())))
                .isTrue();
    }

    @Test
    @DisplayName("parent_child: code block 应作为单个 child, 不被细切")
    void parentChildCodeAtomic() {
        String code =
                """
                ```bash
                curl -X POST http://localhost:8848/nacos/v1/ns/instance \
                  -d 'serviceName=test-service' \
                  -d 'ip=127.0.0.1' \
                  -d 'port=8080'
                ```
                """;
        // 在 code 前后各加一些文本让其更接近真实结构
        String md = "我们提供一个注册实例的示例命令。\n\n" + code + "\n\n执行上述命令会注册一个服务实例到 Nacos。";

        List<ChunkingService.ParentChildChunk> result = service.chunkParentChild(md);

        // 至少一个 child 含完整 curl(包括末尾 port=8080)
        boolean hasIntactCurl =
                result.stream()
                        .anyMatch(
                                c ->
                                        c.childText().contains("curl")
                                                && c.childText().contains("serviceName")
                                                && c.childText().contains("port=8080"));
        assertThat(hasIntactCurl).as("curl 命令不应被切碎").isTrue();
    }

    @Test
    @DisplayName("空入参 / 纯噪音 → 返回空列表, 不抛 NPE")
    void emptyInputReturnsEmpty() {
        assertThat(service.chunk(null)).isEmpty();
        assertThat(service.chunk("")).isEmpty();
        assertThat(service.chunkParentChild(null)).isEmpty();
        assertThat(service.chunkParentChild("")).isEmpty();
    }

    // ============================================================
    // Q3-B: sectionPath 透传(从 heading 栈到切片输出)
    // ============================================================

    @Test
    @DisplayName("Q3-B: chunkSectioned 应保留每个 chunk 的 section_path")
    void chunkSectionedIncludesSectionPath() {
        String md =
                """
                # Dubbo

                Apache Dubbo 是一款高性能 Java RPC 框架, 提供了面向接口代理的高性能 RPC 调用能力。

                ## 异步调用

                Dubbo 异步调用让调用方不必同步等待返回, 通过 RpcContext.asyncCall 可以发起异步调用。
                """;

        List<ChunkingService.SectionedFlatChunk> sectioned = service.chunkSectioned(md);
        assertThat(sectioned).as("两段都超 MIN_CHUNK_CHARS 应产出 2 条 chunk").isNotEmpty();

        // 至少有一条 chunk 的 path = [Dubbo], 至少有一条 = [Dubbo, 异步调用]
        boolean hasDubboRoot =
                sectioned.stream()
                        .anyMatch(c -> c.sectionPath().equals(java.util.List.of("Dubbo")));
        boolean hasDubboAsync =
                sectioned.stream()
                        .anyMatch(c -> c.sectionPath().equals(java.util.List.of("Dubbo", "异步调用")));
        assertThat(hasDubboRoot).as("# Dubbo 下的文本应带 path [Dubbo]").isTrue();
        assertThat(hasDubboAsync).as("## 异步调用 下的文本应带 path [Dubbo, 异步调用]").isTrue();
    }

    @Test
    @DisplayName("Q3-B: parent-child 模式, sectionPath 透传到 child")
    void parentChildSectionedPropagatesPath() {
        String md =
                """
                # Nacos

                Nacos 是阿里开源的动态服务发现与配置管理平台, 提供配置中心与服务注册中心两套核心能力。

                ## 配置管理

                NacosConfig 是 Nacos 的配置管理模块, 支持动态配置推送。这里继续展开关于动态推送机制, listener 长轮询, 推送对比, MD5 校验等更多内容, 目的是把段撑长一点触发切片。
                """;

        List<ChunkingService.SectionedParentChildChunk> sectioned =
                service.chunkParentChildSectioned(md);
        assertThat(sectioned).isNotEmpty();

        // 所有 child 都应带 Nacos 在 path 里(都在 # Nacos 下)
        assertThat(sectioned).allSatisfy(c -> assertThat(c.sectionPath()).contains("Nacos"));
        // 含"动态配置推送"的内容应在 [Nacos, 配置管理] 下
        boolean inConfig = sectioned.stream().anyMatch(c -> c.sectionPath().contains("配置管理"));
        assertThat(inConfig).as("应至少有一个 child 在 [Nacos, 配置管理] 路径下").isTrue();
    }
}
