package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-6b.2: {@link EvidenceAccumulator} 单测 (重点 — per-Run 隔离不在此处直接测, 由 Factory 单测。 这里测 dedup / ACL /
 * 限制 / 稳定顺序 / flush only IDs)。
 */
@DisplayName("EvidenceAccumulator - PR-6b.2 三级去重 + ACL + 限制 + 稳定序")
class EvidenceAccumulatorTest {

    private Evidence ev(String tenant, long docId, long chunkId, String content, double score) {
        return Evidence.of(
                tenant, docId, chunkId, "v1", content, score, null, "semantic_search", Map.of());
    }

    @Test
    @DisplayName("相同 evidenceId 去重 (同 tenant/doc/chunk/content → 同 sha256)")
    void dedupByEvidenceId() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 20, 4000);
        Evidence e1 = ev("tA", 1L, 10L, "hello", 0.9);
        Evidence e2 = ev("tA", 1L, 10L, "hello", 0.9); // 完全一致 → 同 evidenceId
        boolean a1 = acc.accept(0, 0, e1);
        boolean a2 = acc.accept(1, 0, e2);
        assertThat(a1).isTrue();
        assertThat(a2).isFalse(); // dedup
        assertThat(acc.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("相同 (tenant,doc,ver,chunk,contentHash) 但 retrievalScore 不同 → 仍 dedup")
    void dedupByProvenanceEvenWithDifferentScore() {
        // 注意: evidenceId = sha256(tenant|doc|chunk|contentHash), 同 4 元组 → 同 ID
        // 这里只是证明 provenance 一致就 dedup, 不依赖 score
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 20, 4000);
        Evidence e1 = ev("tA", 1L, 10L, "same content", 0.95);
        Evidence e2 = ev("tA", 1L, 10L, "same content", 0.5);
        acc.accept(0, 0, e1);
        boolean r = acc.accept(1, 0, e2);
        assertThat(r).isFalse();
        assertThat(acc.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("同正文不同 document → 保留多来源 (不丢)")
    void sameContentDifferentDocKept() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 20, 4000);
        Evidence e1 = ev("tA", 1L, 10L, "duplicate text", 0.9);
        Evidence e2 = ev("tA", 2L, 20L, "duplicate text", 0.8); // 不同 doc 不同 chunk
        acc.accept(0, 0, e1);
        acc.accept(1, 0, e2);
        assertThat(acc.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("tenant 不一致 → ACL 终检丢弃")
    void tenantMismatchRejected() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 20, 4000);
        Evidence wrong = ev("tB", 1L, 10L, "leaked", 0.9);
        boolean r = acc.accept(0, 0, wrong);
        assertThat(r).isFalse();
        assertThat(acc.size()).isZero();
    }

    @Test
    @DisplayName("maxEvidence 数量上限截断")
    void maxEvidenceTruncates() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 2, 99999);
        acc.accept(0, 0, ev("tA", 1L, 10L, "a", 0.9));
        acc.accept(1, 0, ev("tA", 2L, 20L, "b", 0.8));
        acc.accept(2, 0, ev("tA", 3L, 30L, "c", 0.7));
        assertThat(acc.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("maxEvidenceTokens 上限截断 (按稳定顺序累积到 limit 后截止)")
    void maxTokensTruncates() {
        // 5 个 CJK 字符 = 5 token; limit=8 → 留下两个 evidence 共 10 token, 实际只能留 1 个
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 99, 8);
        acc.accept(0, 0, ev("tA", 1L, 10L, "一二三四五", 0.9));
        acc.accept(1, 0, ev("tA", 2L, 20L, "六七八九十", 0.8));
        // 第 1 条 5 token ≤ 8 留; 第 2 条 cumulative 5+5=10 > 8 → 截
        assertThat(acc.size()).isEqualTo(1);
        assertThat(acc.snapshot().get(0).content()).isEqualTo("一二三四五");
    }

    @Test
    @DisplayName("稳定顺序: stepSequence → resultIndex → retrievalScore desc → evidenceId asc")
    void stableOrder() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 99, 99999);
        // 同 step 同 result 不同 score
        acc.accept(0, 0, ev("tA", 1L, 10L, "low", 0.3));
        acc.accept(0, 1, ev("tA", 2L, 20L, "high", 0.9));
        acc.accept(0, 0, ev("tA", 3L, 30L, "mid", 0.5));
        List<Evidence> snap = acc.snapshot();
        // 期望: step0 result0 score0.5 / step0 result0 score0.3 / step0 result1 score0.9
        assertThat(snap.get(0).content()).isEqualTo("mid"); // result=0, score 0.5 > 0.3
        assertThat(snap.get(1).content()).isEqualTo("low"); // result=0, score 0.3 last
        assertThat(snap.get(2).content()).isEqualTo("high"); // result=1
    }

    @Test
    @DisplayName("snapshot 返回不可变 view 之外的 copy")
    void snapshotIsCopy() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 99, 99999);
        acc.accept(0, 0, ev("tA", 1L, 10L, "x", 0.5));
        List<Evidence> snap1 = acc.snapshot();
        acc.accept(1, 0, ev("tA", 2L, 20L, "y", 0.6));
        // 第二次 snapshot 反映新数据, 不影响第一次 snapshot
        assertThat(snap1).hasSize(1);
        assertThat(acc.snapshot()).hasSize(2);
    }

    @Test
    @DisplayName("toIdsWithCount 仅返回 IDs, 不含 content / documentId / chunkId")
    void flushIdsOnly() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 99, 99999);
        acc.accept(0, 0, ev("tA", 1L, 10L, "secret content", 0.9));
        List<String> ids = acc.toIdsWithCount();
        assertThat(ids).hasSize(1);
        assertThat(ids.get(0)).matches("[0-9a-f]{64}"); // sha256 hex
        // verify 字符串不含'content' /docId /chunkId
        assertThat(ids.get(0)).doesNotContain("secret content");
    }

    @Test
    @DisplayName("空快照返回空 list, 不报错")
    void emptySnapshot() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 99, 99999);
        assertThat(acc.snapshot()).isEmpty();
        assertThat(acc.toIdsWithCount()).isEmpty();
        assertThat(acc.size()).isZero();
    }

    @Test
    @DisplayName("describeLimits 标注 ESTIMATED_NOT_PRECISE")
    void describeLimitsMarksEstimate() {
        EvidenceAccumulator acc = new EvidenceAccumulator("tA", 5, 1000);
        var d = acc.describeLimits();
        assertThat(d.get("tokenEstimatorPrecision")).isEqualTo(TokenEstimator.PRECISION_LABEL);
        assertThat(d.get("maxEvidence")).isEqualTo(5);
        assertThat(d.get("maxEvidenceTokens")).isEqualTo(1000);
    }
}
