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
     * P1(云端 embedding 支持): Bearer api key; 空 = 不带 Authorization(本地 TEI 场景)。
     * 智谱/bigmodel.cn 等云 provider 必填。
     */
    private String apiKey;

    /**
     * P1(云端 embedding 支持): 向量维度(请求 body 的 dimensions 字段); 0 = 不传用模型默认。
     * 必须与 Milvus collection 的 dim 一致(本项目 1024)。
     */
    private int dimensions = 0;

    /**
     * P1 修复(索引吞吐): embed HTTP 并发上限(信号量), 0/负数=不限。
     * 背景: Rosetta amd64 模拟的 BGE-M3 吞吐极低, 多消费者并发压入会在 TEI 排队,
     * 总耗时超过 httpx timeout → 集体超时 → CB 开 → 任务重排风暴。
     * 本地 dev 建议 1(串行喂); GPU TEI 生产可保持 0。
     */
    private int maxConcurrent = 0;

    /**
     * 单次请求最大输入条数。TEI(及上游 nginx/reverse-proxy)对单请求 body 有上限(~2MB), 大文档 parent-child 重切后 child 数可能
     * 30+, 单次发全部 → 413 Payload Too Large (P3-A 全量重灌首批 197/200 失败的根因)。默认 8 按"平均 child ~400 字 × 8 =
     * 3.2KB × 还含 sparse 编码 + JSON overhead" 安全余量给到 2MB 内。
     */
    private int batchSize = 8;
}
