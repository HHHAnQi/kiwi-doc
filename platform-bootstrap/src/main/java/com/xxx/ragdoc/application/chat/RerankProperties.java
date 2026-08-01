package com.xxx.ragdoc.application.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Reranker 配置(V2 第③段: BGE-Reranker-v2-m3 cross-encoder 精排)。
 *
 * <p>配置前缀: {@code rag.rerank.*}。配置见 application-*.yml。
 *
 * <p>放在 application 层(而非 infrastructure), 因为它只是配置数据值对象, 且 RetrieveService 需要直接读它 决策 feature flag;
 * 适配器层(infrastructure.rerank.BgeRerankClient) 也读同一份, 二者共享。
 *
 * <p>feature flag 设计:
 *
 * <ul>
 *   <li>{@code enabled=false}(默认): RetrieveService 不走第③段, 直接用 hybrid/dense top-K。
 *   <li>{@code enabled=true}: hybrid top-K(=candidatePool) → rerank → top-N(=topN)。
 * </ul>
 *
 * <p>切换: {@code application.yml} 设 {@code rag.rerank.enabled: true} 或环境变量 {@code
 * RAG_RERANK_ENABLED=true}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.rerank")
public class RerankProperties {

    /** 是否启用第③段 reranker。默认关闭, 需 docker 起 reranker 容器后才打开。 */
    private boolean enabled = false;

    /** BGE-Reranker-v2-m3 服务地址(text-embeddings-inference 容器的根 URL)。 */
    private String baseUrl = "http://localhost:8084";

    /** 模型 ID(BGE-Reranker-v2-m3 固定)。 */
    private String model = "BAAI/bge-reranker-v2-m3";

    /** 调用超时(ms)。Rosetta amd64 模拟下单对打分需 3-10s, 设 30s 安全。 */
    private int timeoutMs = 30000;

    /** 候选池大小: 从 hybrid 拉多少条喂给 reranker。 */
    private int candidatePool = 20;

    /** rerank 后最终保留的 top-N。 */
    private int topN = 5;
}
