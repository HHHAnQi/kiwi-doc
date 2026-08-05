package com.xxx.ragdoc.application.chat.evidence;

import java.util.List;

/**
 * 一次 chat 检索过程的真实证据三段快照 (PR-1)。
 *
 * <p>把 RetrieveService 实际跑出的三段结果保存下来, 让评测与 Trace 严格基于 Chat 实际 Context, 不再"再调一次 {@code /retrieve}"
 * —— 避免评测 Context 与 Chat Context 分离 (EMS-PR1 硬约束)。
 *
 * <h2>三段语义</h2>
 *
 * <ul>
 *   <li>{@link #initialRetrieval()} — 向量召回 (Dense / Hybrid RRF) 后, rerank 前的原始序。
 *   <li>{@link #postRerank()} — Reranker 精排后 (或失败回退到原序) 的截断结果, 直接对应 finalHits。
 *   <li>{@link #finalContext()} — 经过 parent/child 解析 + 同 parent 去重后, 真正喂给 LLM 的 Context 映射。 一条
 *       finalContext Evidence = 一条可被 Citation 引用的命中文本; Citation 可经 chunkId 映射回此处。
 * </ul>
 *
 * <h2>不变量</h2>
 *
 * <ul>
 *   <li>无权 Evidence 不允许出现在任何一段 (RetrieveService 已通过 AccessScope / ALWAYS_FALSE 截断)。
 *   <li>{@code finalContext} 必须与 ChatService 实际计入 LLM Context 的条目数严格一致。
 *   <li>同 contentHash 的 Evidence 在 {@code finalContext} 阶段被去重。
 * </ul>
 *
 * <p>无召回 (NO_RECALL / EMPTY_KB) 时三段均为空列表; 端口实现据此判断是否需要持久化快照。
 */
public record EvidenceSnapshot(
        List<Evidence> initialRetrieval,
        List<Evidence> postRerank,
        List<Evidence> finalContext,
        String rerankState) {

    public static EvidenceSnapshot empty() {
        return new EvidenceSnapshot(List.of(), List.of(), List.of(), "not_enabled");
    }

    public EvidenceSnapshot {
        initialRetrieval = initialRetrieval == null ? List.of() : List.copyOf(initialRetrieval);
        postRerank = postRerank == null ? List.of() : List.copyOf(postRerank);
        finalContext = finalContext == null ? List.of() : List.copyOf(finalContext);
        rerankState = rerankState == null ? "not_enabled" : rerankState;
    }
}
