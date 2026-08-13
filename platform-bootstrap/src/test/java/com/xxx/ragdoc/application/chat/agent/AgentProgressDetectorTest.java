package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-7c.2: {@link AgentProgressDetector} 进展判定单测。 */
@DisplayName("AgentProgressDetector - PR-7c.2 Phase 进展判定")
class AgentProgressDetectorTest {

    private AgentProgressDetector detector;

    @BeforeEach
    void setup() {
        detector = new AgentProgressDetector();
    }

    private Evidence ev(String tenant, String content) {
        return Evidence.of(
                tenant, 1L, 10L, "v1", content, 0.9, null, "semantic_search", java.util.Map.of());
    }

    @Test
    @DisplayName("新 Evidence ID 出现 → PROGRESS")
    void newEvidenceIdProgress() {
        AgentProgressDetector.Outcome r =
                detector.detect(
                        Set.of("ev1"),
                        List.of(ev("tA", "x")),
                        Set.of(),
                        List.of("R1"),
                        List.of("R1"));
        // newEvidence 持 ev2's sha256 (没用 ev1 input)
        assertThat(r).isEqualTo(AgentProgressDetector.Outcome.PROGRESS);
    }

    @Test
    @DisplayName("新 discoveredEntities (即使无新 evidence) → PROGRESS")
    void newEntitiesProgress() {
        AgentProgressDetector.Outcome r =
                detector.detect(
                        Set.of(), // prior empty
                        List.of(), // no new evidence
                        Set.of("newEntity"),
                        List.of("R1"),
                        List.of("R1"));
        // unexpected? 取消断言:
        // PR-7c.2 简化: newEntities 非空 → PROGRESS
        assertThat(r).isEqualTo(AgentProgressDetector.Outcome.PROGRESS);
    }

    @Test
    @DisplayName("Evidence 重复 (同 id 空 discoveredEntities missing 不减) → NO_PROGRESS")
    void noProgressWhenOnlyDuplicate() {
        Evidence dup = ev("tA", "x");
        // dup 已经在 prior (同 evidenceId = sha256(tenant|doc|chunk|contentHash))
        AgentProgressDetector.Outcome r =
                detector.detect(
                        Set.of(dup.evidenceId()),
                        List.of(dup),
                        Set.of(),
                        List.of("R1"),
                        List.of("R1"));
        assertThat(r).isEqualTo(AgentProgressDetector.Outcome.NO_PROGRESS);
    }

    @Test
    @DisplayName("missingRequirementIds 缩减 (新 evidence / entity 空也行) → PROGRESS")
    void fewerMissingProgress() {
        AgentProgressDetector.Outcome r =
                detector.detect(Set.of(), List.of(), Set.of(), List.of("R1", "R2"), List.of("R1"));
        assertThat(r).isEqualTo(AgentProgressDetector.Outcome.PROGRESS);
    }

    @Test
    @DisplayName("所有维度都无进展 → NO_PROGRESS")
    void allZerosNoProgress() {
        AgentProgressDetector.Outcome r =
                detector.detect(Set.of(), List.of(), Set.of(), List.of("R1"), List.of("R1"));
        assertThat(r).isEqualTo(AgentProgressDetector.Outcome.NO_PROGRESS);
    }
}
