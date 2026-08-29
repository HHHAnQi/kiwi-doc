package com.xxx.ragdoc.application.document.chunking;

/**
 * 切片模式配置(P3-A Parent-Child feature flag)。
 *
 * <p>配置前缀: {@code rag.chunking.*}。配置见 application-*.yml。
 *
 * <p>切换:
 *
 * <ul>
 *   <li>{@code flat}(默认): 同质 token-based 切片(V2 原始路径)。
 *   <li>{@code parent_child}: Parent-Child 两层切片(P3-A, 参考 LlamaIndex HierarchicalNodeParser)。
 * </ul>
 *
 * <p>切换通过环境变量 {@code RAG_CHUNKING_MODE=parent_child} 或 application.yml。
 *
 * <p>V3: 下沉到 platform-common 共享层(parser-service 同样需要切片配置)。
 */
@lombok.Data
@org.springframework.stereotype.Component
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "rag.chunking")
public class ChunkingProperties {

    /** 切片模式: flat(默认) / parent_child。 */
    private Mode mode = Mode.FLAT;

    /**
     * Contextual Retrieval(P1): embed 输入前是否拼接确定性上下文前缀(来源+文档标题+章节路径)。
     *
     * <p>见 {@link ContextualEmbeddingPrefix}。只改 embedding 输入, 不动 chunk 原文/哈希/BM25 文本。 默认 true; 关闭 =
     * baseline 行为(评测消融用)。
     */
    private boolean contextualPrefixEnabled = true;

    /** 前缀长度上限(字符), 防 section_path 极深时前缀反客为主。 */
    private int contextualPrefixMaxChars = 120;

    public enum Mode {
        FLAT,
        PARENT_CHILD
    }
}
