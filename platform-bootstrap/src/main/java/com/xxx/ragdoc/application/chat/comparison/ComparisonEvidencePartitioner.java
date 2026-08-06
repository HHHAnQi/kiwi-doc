package com.xxx.ragdoc.application.chat.comparison;

import com.xxx.ragdoc.application.chat.agent.AgentRunResult;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * PR-6c / EMS-PR6c §7.2: 把 {@link AgentRunResult#evidence()} 按 comparison side (left/right) 分组。
 *
 * <p>核心断言 (Revision §6):
 *
 * <ul>
 *   <li>Evidence 缺 sourceStepId / comparisonSide metadata → fail-closed (无法证明来源)
 *   <li>单条 Evidence 如果被两侧 Tools 都返回 → metadata 自然区分, 我们只按 sourceStepId 划分
 *   <li>左右 Evidence 必须稳定排序 (按 retrievalScore desc → evidenceId asc)
 *   <li>不同 tenant Evidence 已被 EvidenceAccumulator 终检丢弃; 这里再校验一次 (双保险)
 * </ul>
 */
@Component
public class ComparisonEvidencePartitioner {

    /** 分组失败时给出具体原因; 调用方据此对齐 reasonCode。 */
    public enum PartitionFailure {
        NO_SOURCE_STEP_ON_SOME_EVIDENCE,
        UNKNOWN_SOURCE_STEP,
        TENANT_MISMATCH
    }

    public record PartitionResult(
            boolean valid,
            PartitionFailure failure,
            ComparisonEvidenceSet evidenceSet) {

        public static PartitionResult fail(PartitionFailure f) {
            return new PartitionResult(false, f, null);
        }

        public static PartitionResult ok(ComparisonEvidenceSet set) {
            return new PartitionResult(true, null, set);
        }
    }

    private static final String MD_COMPARISON_SIDE = "comparisonSide";

    /**
     * 把 runResult.evidence 按 metadata.comparisonSide 标记划分。
     *
     * @param expectedTenantId 服务端 Principal.tenantId; 一个 Evidence 与之不符 → fail-closed
     */
    public PartitionResult partition(
            AgentRunResult runResult,
            String expectedTenantId,
            Map<String, ComparisonTarget> stepIdToTargets) {
        if (runResult == null || runResult.evidence() == null || runResult.evidence().isEmpty()) {
            return PartitionResult.ok(new ComparisonEvidenceSet(
                    stepIdToTargets.getOrDefault(ComparisonPlanFactory.LEFT_STEP_ID,
                            ComparisonTarget.of("left", "left")),
                    List.of(),
                    stepIdToTargets.getOrDefault(ComparisonPlanFactory.RIGHT_STEP_ID,
                            ComparisonTarget.of("right", "right")),
                    List.of()));
        }

        Map<String, List<Evidence>> byStep = new HashMap<>();
        for (Evidence e : runResult.evidence()) {
            // 双保险: 终检 tenantId 一致
            if (!expectedTenantId.equals(e.tenantId())) {
                return PartitionResult.fail(PartitionFailure.TENANT_MISMATCH);
            }
            // 优先读 metadata.comparisonSide
            Object side = e.metadata() == null ? null : e.metadata().get(MD_COMPARISON_SIDE);
            String stepId;
            if (side instanceof String s) {
                stepId = sideToStepId(s);
            } else if (e.metadata() != null && e.metadata().get("sourceStepId") instanceof String si) {
                stepId = si;
            } else {
                return PartitionResult.fail(PartitionFailure.NO_SOURCE_STEP_ON_SOME_EVIDENCE);
            }
            if (stepId == null
                    || (!stepId.equals(ComparisonPlanFactory.LEFT_STEP_ID)
                            && !stepId.equals(ComparisonPlanFactory.RIGHT_STEP_ID))) {
                return PartitionResult.fail(PartitionFailure.UNKNOWN_SOURCE_STEP);
            }
            byStep.computeIfAbsent(stepId, k -> new ArrayList<>()).add(e);
        }

        List<Evidence> l = sortByScore(byStep.get(ComparisonPlanFactory.LEFT_STEP_ID));
        List<Evidence> r = sortByScore(byStep.get(ComparisonPlanFactory.RIGHT_STEP_ID));
        ComparisonTarget leftT = stepIdToTargets.getOrDefault(ComparisonPlanFactory.LEFT_STEP_ID,
                ComparisonTarget.of("left", "left"));
        ComparisonTarget rightT = stepIdToTargets.getOrDefault(ComparisonPlanFactory.RIGHT_STEP_ID,
                ComparisonTarget.of("right", "right"));
        return PartitionResult.ok(new ComparisonEvidenceSet(leftT, l, rightT, r));
    }

    private static String sideToStepId(String side) {
        if (side == null) return null;
        try {
            ComparisonSide cs = ComparisonSide.valueOf(side.toUpperCase(java.util.Locale.ROOT));
            return cs == ComparisonSide.LEFT
                    ? ComparisonPlanFactory.LEFT_STEP_ID : ComparisonPlanFactory.RIGHT_STEP_ID;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<Evidence> sortByScore(List<Evidence> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<Evidence> copy = new ArrayList<>(in);
        copy.sort(java.util.Comparator
                .comparing((Evidence e) -> e.retrievalScore() == null ? 0.0 : e.retrievalScore(),
                        java.util.Comparator.reverseOrder())
                .thenComparing(Evidence::evidenceId));
        return List.copyOf(copy);
    }

    /** 分组结果; Evidence 不含正文进 agent_run/agent_step。 */
    public record ComparisonEvidenceSet(
            ComparisonTarget leftTarget,
            List<Evidence> leftEvidence,
            ComparisonTarget rightTarget,
            List<Evidence> rightEvidence) {

        public ComparisonEvidenceSet {
            leftEvidence = leftEvidence == null ? List.of() : List.copyOf(leftEvidence);
            rightEvidence = rightEvidence == null ? List.of() : List.copyOf(rightEvidence);
        }

        public Map<String, List<Evidence>> asMap() {
            Map<String, List<Evidence>> m = new LinkedHashMap<>();
            m.put(ComparisonPlanFactory.LEFT_STEP_ID, leftEvidence);
            m.put(ComparisonPlanFactory.RIGHT_STEP_ID, rightEvidence);
            return m;
        }

        public int total() {
            return leftEvidence.size() + rightEvidence.size();
        }
    }
}
