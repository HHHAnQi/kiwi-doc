package com.xxx.ragdoc.infrastructure.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Embedding 服务配置(text-embeddings-inference BGE-M3)。 配置见 application-*.yml 的 embedding.* 块。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    /** BGE-M3 服务地址(text-embeddings-inference 容器暴露的 OpenAI 兼容 /v1)。 */
    private String baseUrl;

    /** 模型 ID(BGE-M3 固定 BAAI/bge-m3)。 */
    private String model;

    /** 超时(ms)。 */
    private int timeoutMs;
}
