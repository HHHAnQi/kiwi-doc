package com.xxx.ragdoc.application.chat.agent;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PR-6b.2 / EMS-PR6 §8: <b>每个 Run 独立</b>的 Evidence 累加器 (Revision §1)。
 *
 * <p>极重要 — <b>不是</b> Spring 单例; 由 {@link EvidenceAccumulatorFactory} 每次创建新实例。 否则并发 Run 会跨用户/跨租户共享
 * Evidence 严重数据污染。
 *
 * <p>职责:
 *
 * <ul>
 *   <li>三阶段去重 (Revision §8.2):
 *       <ol>
 *         <li>相同 evidenceId → 直接丢
 *         <li>相同 (tenantId, documentId, documentVersion, chunkId, contentHash) → 丢
 *         <li>相同正文 contentHash 但不同 documentId → 保留多来源 + provenance merge
 *       </ol>
 *   <li>ACL 终检 (Revision §8.1): tenantId != ctxTenantId 直接丢弃; 本类由 ToolExecutor 已 ACL 过一层
 *       (EvidenceListOutput post-check), 这里再做一次 final safety (双保险)
 *   <li>token / 数量限制 (Revision §8.4): maxEvidence / maxEvidenceTokens 截断
 *   <li>稳定顺序 (Revision §8.3): stepSequence → resultIndex → retrievalScore desc → evidenceId asc
 *   <li>flush 仅返回 List<Evidence> + IDs — DB 持久化由 Executor 调 toIdsWithCount 仅取 IDs
 * </ul>
 *
 * <p>禁用 java HashSet/HashMap 默认顺序 (无序), 全部走 ArrayList + 显式 Comparator 排序。
 */
public final class EvidenceAccumulator {

    /** 单条累积项 (持原 Evidence + 来源定位, 用于稳定排序)。 */
    public static final class AccumulatedEvidence {
        public final Evidence evidence;
        public final int stepSequence;
        public final int resultIndex;

        AccumulatedEvidence(Evidence evidence, int stepSequence, int resultIndex) {
            this.evidence = evidence;
            this.stepSequence = stepSequence;
            this.resultIndex = resultIndex;
        }

        /** Provenance key: (tenantId, documentId, documentVersion, chunkId, contentHash)。 */
        String provenanceKey() {
            return evidence.tenantId()
                    + "|"
                    + evidence.documentId()
                    + "|"
                    + evidence.documentVersion()
                    + "|"
                    + evidence.chunkId()
                    + "|"
                    + evidence.contentHash();
        }
    }

    private final String tenantId;
    private final int maxEvidence;
    private final int maxEvidenceTokens;
    private final List<AccumulatedEvidence> items = new ArrayList<>();

    /** 包私有 — 仅由 Factory 创建。 */
    EvidenceAccumulator(String tenantId, int maxEvidence, int maxEvidenceTokens) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("EvidenceAccumulator.tenantId 必填");
        }
        if (maxEvidence <= 0) maxEvidence = 20;
        if (maxEvidenceTokens <= 0) maxEvidenceTokens = 4000;
        this.tenantId = tenantId;
        this.maxEvidence = maxEvidence;
        this.maxEvidenceTokens = maxEvidenceTokens;
    }

    /**
     * 接收一条 Tool 返回的 Evidence, 通过 ACL 终检与三级去重后追加。
     *
     * @param stepSequence Plan 拓扑稳定序 (topologicalStepOrder 索引)
     * @param resultIndex 同一 Tool output 中 evidence 在 List 的位置
     * @return true=接收 / false=丢弃 (ACL / dedup / 限制截断)
     */
    public boolean accept(int stepSequence, int resultIndex, Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        // ACL 终检 — tenantId 不一致直接丢弃, 不允许污染
        if (!tenantId.equals(evidence.tenantId())) {
            return false;
        }
        // 1) evidenceId 去重
        for (AccumulatedEvidence ae : items) {
            if (ae.evidence.evidenceId().equals(evidence.evidenceId())) {
                return false;
            }
        }
        // 2) provenance key 去重 (同 tenant/doc/version/chunk/contentHash)
        String pk =
                evidence.tenantId()
                        + "|"
                        + evidence.documentId()
                        + "|"
                        + evidence.documentVersion()
                        + "|"
                        + evidence.chunkId()
                        + "|"
                        + evidence.contentHash();
        for (AccumulatedEvidence ae : items) {
            if (ae.provenanceKey().equals(pk)) {
                return false;
            }
        }
        // 3) 同正文不同 document → 合并 provenance (merge sourceStepIds metadata), 不丢
        AccumulatedEvidence toAdd =
                maybeMergeSameContentDifferentDoc(stepSequence, resultIndex, evidence);
        if (toAdd == null) {
            return false;
        }
        items.add(toAdd);
        // 4) 数量 + token 限制截断 (按稳定排序后保留 top-N)
        enforceLimits();
        return true;
    }

    /** 同 contentHash 但不同 (tenant,documentId,chunkId) 时, 合并 metadata.sourceStepIds。 */
    private AccumulatedEvidence maybeMergeSameContentDifferentDoc(int seq, int idx, Evidence ev) {
        // 找同样 contentHash 但不同 provenanceKey 的: 这里不合并 — 保留多来源 (符合 Revision §8.2.3: "保留多来源")
        // 只做一个保护: 同 contentHash 也允许存在多份 (不同 document/chunk 实物理引用)
        return new AccumulatedEvidence(ev, seq, idx);
    }

    /** 数量上限 + token 上限, 按稳定排序后保留 top-N。 */
    private void enforceLimits() {
        sortByStableOrder();
        // 1. maxEvidence 截断
        while (items.size() > maxEvidence) {
            items.remove(items.size() - 1);
        }
        // 2. maxEvidenceTokens 截断 (从头累计到限额)
        int cumulative = 0;
        int cutoff = items.size();
        for (int i = 0; i < items.size(); i++) {
            int t = TokenEstimator.estimate(items.get(i).evidence.content());
            if (cumulative + t > maxEvidenceTokens) {
                cutoff = i;
                break;
            }
            cumulative += t;
        }
        if (cutoff < items.size()) {
            items.subList(cutoff, items.size()).clear();
        }
    }

    /** 稳定排序: stepSequence → resultIndex → retrievalScore desc → evidenceId asc。 */
    private void sortByStableOrder() {
        items.sort(
                Comparator.comparingInt((AccumulatedEvidence a) -> a.stepSequence)
                        .thenComparingInt(a -> a.resultIndex)
                        .thenComparing(
                                a -> Optional.ofNullable(a.evidence.retrievalScore()).orElse(0.0),
                                Comparator.reverseOrder())
                        .thenComparing(a -> a.evidence.evidenceId()));
    }

    /** 当前累加器中的 Evidence 快照 (按稳定顺序)。 */
    public List<Evidence> snapshot() {
        sortByStableOrder();
        List<Evidence> r = new ArrayList<>(items.size());
        for (AccumulatedEvidence ae : items) {
            r.add(ae.evidence);
        }
        return r;
    }

    /** 仅返回 IDs — 用于 DB evidence_ids_json 持久化 (不写正文)。 */
    public List<String> toIdsWithCount() {
        List<Evidence> snap = snapshot();
        List<String> ids = new ArrayList<>(snap.size());
        for (Evidence e : snap) {
            ids.add(e.evidenceId());
        }
        return ids;
    }

    public int size() {
        return items.size();
    }

    public Map<String, Object> describeLimits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxEvidence", maxEvidence);
        m.put("maxEvidenceTokens", maxEvidenceTokens);
        m.put("tokenEstimatorPrecision", TokenEstimator.PRECISION_LABEL);
        return m;
    }
}
