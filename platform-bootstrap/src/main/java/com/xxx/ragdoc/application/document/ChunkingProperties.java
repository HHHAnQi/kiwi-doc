package com.xxx.ragdoc.application.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.chunking")
public class ChunkingProperties {

    /** 切片模式: flat(默认) / parent_child。 */
    private Mode mode = Mode.FLAT;

    public enum Mode {
        FLAT,
        PARENT_CHILD
    }
}
