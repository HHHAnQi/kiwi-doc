package com.xxx.ragdoc.application.chat;

/**
 * Embedding 用例出参: BGE-M3 同时输出 dense + sparse 向量。
 *
 * <p>BGE-M3 维度:
 *
 * <ul>
 *   <li>dense: 1024 维(语义召回)
 *   <li>sparse: 关键词向量(Map&lt;token_id, weight&gt;, 用于 BGE-M3 sparse 召回, 替代 BM25)
 * </ul>
 *
 * <p>注意: 这个 sparse 与 Milvus 2.5 内置 BM25 是两套机制, V2 用 BGE-M3 sparse(M3 原生能力)。
 */
public record EmbeddingResult(
        float[] denseVector, java.util.Map<Integer, Float> sparseVector // token_id → weight
        ) {}
