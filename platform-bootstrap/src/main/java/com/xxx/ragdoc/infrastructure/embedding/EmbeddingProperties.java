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

    /**
     * 单次请求最大输入条数。TEI(及上游 nginx/reverse-proxy)对单请求 body 有上限(~2MB), 大文档 parent-child 重切后 child 数可能
     * 30+, 单次发全部 → 413 Payload Too Large (P3-A 全量重灌首批 197/200 失败的根因)。默认 8 按"平均 child ~400 字 × 8 =
     * 3.2KB × 还含 sparse 编码 + JSON overhead" 安全余量给到 2MB 内。
     */
    private int batchSize = 8;
}
