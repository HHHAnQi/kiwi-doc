package com.xxx.ragdoc.application.chat.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.agent.AgentRunResult;
import com.xxx.ragdoc.application.chat.agent.AgentBudgetReservation;
import com.xxx.ragdoc.application.chat.agent.AgentRunStatus;
import com.xxx.ragdoc.application.chat.agent.AgentUsage;
import com.xxx.ragdoc.application.chat.comparison.ComparisonEvidencePartitioner.PartitionResult;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-6c / EMS-PR6c §14.2: {@link ComparisonEvidencePartitioner} 单测。
 */
@DisplayName("ComparisonEvidencePartitioner - PR-6c.1 left/right 分组 + 安全检查")
class ComparisonEvidencePartitionerTest {

    private ComparisonEvidencePartitioner partitioner;
    private Map<String, ComparisonTarget> targets;

    @BeforeEach
    void setup() {
        partitioner = new ComparisonEvidencePartitioner();
        targets = Map.of(
                ComparisonPlanFactory.LEFT_STEP_ID, ComparisonTarget.of("v1", "v1"),
                ComparisonPlanFactory.RIGHT_STEP_ID, ComparisonTarget.of("v2", "v2"));
    }

    private Evidence ev(String tenant, String side /* LEFT/RIGHT */) {
        Map<String, Object> md = Map.of("comparisonSide", side, "sourceStepId",
                side.equals("LEFT") ? ComparisonPlanFactory.LEFT_STEP_ID
                        : ComparisonPlanFactory.RIGHT_STEP_ID);
        return Evidence.of(tenant, side.equals("LEFT") ? 1L : 2L, side.equals("LEFT") ? 10L : 20L,
                "v1", "content-" + side, side.equals("LEFT") ? 0.9 : 0.8, null, "metadata_search", md);
    }

    private AgentRunResult resultWith(List<Evidence> evs) {
        return new AgentRunResult("r1", "req-1", AgentRunStatus.READY_TO_ANSWER,
                evs, AgentUsage.zero(), AgentBudgetReservation.zero(),
                evs.size(), 0, 0, 0, "EVIDENCE_READY",
                3L,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("正常: 左右各两条 Evidence 正确分组")
    void happyPartition() {
        AgentRunResult r = resultWith(List.of(
                ev("tA", "LEFT"), ev("tA", "LEFT"),
                ev("tA", "RIGHT"), ev("tA", "RIGHT")));
        PartitionResult pr = partitioner.partition(r, "tA", targets);
        assertThat(pr.valid()).isTrue();
        assertThat(pr.evidenceSet().leftEvidence()).hasSize(2);
        assertThat(pr.evidenceSet().rightEvidence()).hasSize(2);
    }

    @Test
    @DisplayName("一侧为空 + 另一侧非空 → 不允许掩蔽 (返回 left=empty / right=非空, 由上层 SC §7.3 拒绝)")
    void oneSideEmpty() {
        AgentRunResult r = resultWith(List.of(ev("tA", "RIGHT")));
        PartitionResult pr = partitioner.partition(r, "tA", targets);
        assertThat(pr.valid()).isTrue();
        assertThat(pr.evidenceSet().leftEvidence()).isEmpty();
        assertThat(pr.evidenceSet().rightEvidence()).hasSize(1);
    }

    @Test
    @DisplayName("两侧都空 → result valid=true, left/right 都 0 (调用方拒答 NO_EVIDENCE)")
    void bothEmpty() {
        AgentRunResult r = resultWith(List.of());
        PartitionResult pr = partitioner.partition(r, "tA", targets);
        assertThat(pr.valid()).isTrue();
        assertThat(pr.evidenceSet().total()).isZero();
    }

    @Test
    @DisplayName("Evidence metadata 缺 sourceStepId / comparisonSide → fail-closed")
    void evidenceMissingSource() {
        Evidence orphan = Evidence.of("tA", 1L, 10L, "v1", "x", 0.9, null, "metadata_search", Map.of());
        PartitionResult pr = partitioner.partition(resultWith(List.of(orphan)), "tA", targets);
        assertThat(pr.valid()).isFalse();
        assertThat(pr.failure()).isEqualTo(
                ComparisonEvidencePartitioner.PartitionFailure.NO_SOURCE_STEP_ON_SOME_EVIDENCE);
    }

    @Test
    @DisplayName("Evidence tenant 不一致 → fail-closed TENANT_MISMATCH (Revision §6 双保险)")
    void tenantMismatch() {
        PartitionResult pr = partitioner.partition(
                resultWith(List.of(ev("tB", "LEFT"))), "tA", targets);
        assertThat(pr.valid()).isFalse();
        assertThat(pr.failure()).isEqualTo(
                ComparisonEvidencePartitioner.PartitionFailure.TENANT_MISMATCH);
    }

    @Test
    @DisplayName("Evidence sourceStepId 不在 compare-left/right 范围 → UNKNOWN_SOURCE_STEP")
    void unknownSourceStep() {
        Evidence bug = Evidence.of("tA", 1L, 10L, "v1", "x", 0.9, null, "metadata_search",
                Map.of("sourceStepId", "compare-middle"));
        PartitionResult pr = partitioner.partition(resultWith(List.of(bug)), "tA", targets);
        assertThat(pr.valid()).isFalse();
        assertThat(pr.failure()).isEqualTo(
                ComparisonEvidencePartitioner.PartitionFailure.UNKNOWN_SOURCE_STEP);
    }

    @Test
    @DisplayName("稳定排序: 左侧按 retrievalScore desc → evidenceId asc")
    void stableOrder() {
        Evidence l1 = Evidence.of("tA", 1L, 10L, "v1", "high", 0.95, null, "metadata_search",
                Map.of("comparisonSide", "LEFT"));
        Evidence l2 = Evidence.of("tA", 1L, 11L, "v1", "low", 0.3, null, "metadata_search",
                Map.of("comparisonSide", "LEFT"));
        PartitionResult pr = partitioner.partition(
                resultWith(List.of(l1, l2)), "tA", targets);
        assertThat(pr.evidenceSet().leftEvidence().get(0).content()).isEqualTo("high");
        assertThat(pr.evidenceSet().leftEvidence().get(1).content()).isEqualTo("low");
    }
}
