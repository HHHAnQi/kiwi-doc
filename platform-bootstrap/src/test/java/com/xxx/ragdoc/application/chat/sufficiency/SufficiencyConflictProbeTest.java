package com.xxx.ragdoc.application.chat.sufficiency;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.RequirementType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2-D4 矛盾证据正确性审计 — Rule 层确定性探针。
 *
 * <p>问题: 同一 requirement 下相互冲突但表面相关的 evidence, Rule 层能否避免错误放行?
 * 结论(逐类断言): Rule 的确定性冲突识别<b>只覆盖 pinned-version 结构化场景</b>,
 * 全部 6 类事实级矛盾均 defer(UNDETERMINED)交 Model — 语义判定责任全部在 Model 侧。
 */
@DisplayName("P2-D4 矛盾证据 — Rule层探针(确定性冲突识别覆盖面)")
class SufficiencyConflictProbeTest {

    private final RuleSufficiencyJudge judge = new RuleSufficiencyJudge();

    private static EvidenceRequirement req(Map<String, Object> filters) {
        return new EvidenceRequirement(
                "REQ-1", "Nacos 使用什么一致性协议", RequirementType.ENTITY_ATTRIBUTE,
                true, List.of("Nacos"), filters);
    }

    private static Evidence ev(String content, String version) {
        return Evidence.of("tA", 1L, 10L, version, content, 0.9, null,
                "semantic_search", Map.of("requirementIds", List.of("REQ-1")));
    }

    private SufficiencyStatus run(EvidenceRequirement r, Evidence... evs) {
        return judge.evaluate(
                new SufficiencyRequest(
                        "run-d4", "q", List.of(r), List.of(evs), Set.of(), Set.of(),
                        EvidenceCoverageSummary.empty(), 0, true, Map.of())).status();
    }

    @Test
    @DisplayName("1. 同实体互斥事实(Raft vs Paxos) → Rule defer(非确定性识别)")
    void p1_mutuallyExclusive() {
        assertThat(run(req(Map.of()),
                ev("Nacos 集群一致性协议是 Raft", "v1"),
                ev("Nacos 集群一致性协议是 Paxos", "v1")))
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }

    @Test
    @DisplayName("2. 同属性不同值(端口8848 vs 9848) → Rule defer")
    void p2_samePropertyDiffValues() {
        assertThat(run(req(Map.of()),
                ev("Nacos 默认主端口是 8848", "v1"),
                ev("Nacos 默认主端口实际是 9848, 文档有误", "v1")))
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }

    @Test
    @DisplayName("3a. 时间性版本矛盾(内容级, 未锁版本) → Rule defer")
    void p3a_temporalContentConflict() {
        assertThat(run(req(Map.of()),
                ev("Nacos 2.0 起元数据一致性改用 Distro 协议", "v1"),
                ev("Nacos 2.0 之后元数据一致性仍是 Raft 协议", "v1")))
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }

    @Test
    @DisplayName("3b. pinned-version 结构化冲突 → Rule 确定性 CONFLICTED(唯一覆盖场景)")
    void p3b_pinnedVersionConflict() {
        assertThat(run(req(Map.of("version", "v2")),
                ev("Nacos 一致性协议说明 A", "v2"),
                ev("Nacos 一致性协议说明 B", "v3")))
                .isEqualTo(SufficiencyStatus.CONFLICTED);
    }

    @Test
    @DisplayName("4. 源A vs 源B 冲突(配置文档 vs FAQ) → Rule defer(不识别来源级矛盾)")
    void p4_sourceConflict() {
        assertThat(run(req(Map.of()),
                ev("[配置文档] Nacos 使用 Raft 协议", "v1"),
                ev("[官方FAQ] Nacos 使用 Paxos 协议", "v1")))
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }

    @Test
    @DisplayName("5. 一强一弱矛盾(详细权威 vs 简略模糊) → Rule defer")
    void p5_strongWeakConflict() {
        assertThat(run(req(Map.of()),
                ev("Nacos 持久化节点间通过 Raft 日志复制保持元数据强一致, 1.3版本引入", "v1"),
                ev("协议好像是Paxos", "v1")))
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }

    @Test
    @DisplayName("6. 阴性对照: 措辞不同但事实兼容 → 同样 defer(判定责任在Model)")
    void p6_negativeControlCompatible() {
        assertThat(run(req(Map.of()),
                ev("Nacos 持久化实例间通过 Raft 协议保证元数据一致性", "v1"),
                ev("Nacos 的元数据强一致依赖 Raft 共识算法", "v1")))
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }
}
