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
 * P2-D3 修复后语义回归（D3_DEV_REGRESSION_SET）。
 *
 * <p>修复前(缺陷形态, 见 b572c68 探针): "证据非空+实体substring命中 → RULE_FULLY_COVERED" → 对不含答案事实的证据
 * false_sufficient=10/10(100%)。
 *
 * <p>修复后职责划分: Rule 只做三种确定性判定(NO_EVIDENCE / entity-filter mismatch / version-value conflict), 其余一律
 * UNDETERMINED 交 Model Judge 做语义充分性判定。 本集验证 Rule 层不再<b>构造性</b>产生 false sufficient — 语义正确性由独立
 * holdout (真实 LLM)与 Agent 级 T1-T4 验证, 本集不单独证明修复有效。
 */
@DisplayName("P2-D3 DEV回归集 — Rule只做确定性判定, 语义充分性一律defer")
class SufficiencySemanticProbeTest {

    private final RuleSufficiencyJudge judge = new RuleSufficiencyJudge();

    private static EvidenceRequirement nacosProtocol() {
        return new EvidenceRequirement(
                "REQ-1",
                "Nacos 使用什么一致性协议",
                RequirementType.ENTITY_ATTRIBUTE,
                true,
                List.of("Nacos"),
                Map.of());
    }

    private static Evidence ev(String id, String content) {
        return ev(id, content, "REQ-1");
    }

    private static Evidence ev(String id, String content, String reqId) {
        return Evidence.of(
                "tA",
                1L,
                10L,
                "v1",
                content,
                0.9,
                null,
                "semantic_search",
                Map.of("requirementIds", List.of(reqId)));
    }

    private SufficiencyDecision run(EvidenceRequirement req, List<Evidence> evs) {
        return judge.evaluate(
                new SufficiencyRequest(
                        "run-probe",
                        "Nacos 使用什么一致性协议",
                        List.of(req),
                        evs,
                        Set.of(),
                        Set.of(),
                        EvidenceCoverageSummary.empty(),
                        0,
                        true,
                        Map.of()));
    }

    // ─── A–E 机制探针(修复后) ───────────────────────────────────

    @Test
    @DisplayName("A: relevant+sufficient → Rule defer(UNDETERMINED), 语义判定归 Model")
    void probeA_relevantSufficient() {
        SufficiencyDecision d =
                run(nacosProtocol(), List.of(ev("e1", "Nacos 的持久化节点间采用 Raft 协议保证元数据一致性")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.coverage().get(0).reasonCode()).isEqualTo("RULE_DEFERS_SEMANTIC_TO_MODEL");
    }

    @Test
    @DisplayName("B: relevant但不含答案事实 → 同样 defer(修复前此处判 SUFFICIENT=FALSE)")
    void probeB_relevantButInsufficient() {
        SufficiencyDecision d =
                run(nacosProtocol(), List.of(ev("e1", "Nacos 支持服务发现与配置管理, 提供控制台和开放API")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.coverage().get(0).reasonCode()).isEqualTo("RULE_DEFERS_SEMANTIC_TO_MODEL");
    }

    @Test
    @DisplayName("C1: 完全无关证据(不含实体) → 确定性 NOT_COVERED (Rule 保留判定)")
    void probeC1_irrelevantNoEntity() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(ev("e1", "Dubbo 服务调用超时时间默认是 1000ms")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
        assertThat(d.coverage().get(0).reasonCode())
                .isEqualTo("EVIDENCE_ENTITY_OR_FILTER_MISMATCH");
    }

    @Test
    @DisplayName("C2: 无关证据但提及实体 → defer(修复前此处判 SUFFICIENT=FALSE)")
    void probeC2_irrelevantButEntityMentioned() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(ev("e1", "Nacos 控制台默认端口是 8848")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
    }

    @Test
    @DisplayName("D: 同一事实矛盾证据(无版本锁) → defer交Model(修复前漏检判SUFFICIENT)")
    void probeD_contradictory() {
        SufficiencyDecision d =
                run(
                        nacosProtocol(),
                        List.of(ev("e1", "Nacos 集群一致性协议是 Raft"), ev("e2", "Nacos 集群一致性协议是 Paxos")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.conflicts()).isEmpty(); // 事实级矛盾非Rule职责, 由Model语义判定
    }

    @Test
    @DisplayName("E: 无证据 → 确定性 INSUFFICIENT (Rule 保留判定)")
    void probeE_noEvidence() {
        SufficiencyDecision d = run(nacosProtocol(), List.of());
        assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
    }

    // ─── DEV 集: Rule 层 false-sufficient 构造性为零 ─────────────

    @Test
    @DisplayName("DEV集: 20对上 Rule 永不输出 SUFFICIENT(defer 100%, false-sufficient构造性=0)")
    void devRegressionNoConstructiveFalseSufficient() {
        List<String> sufficientContents =
                List.of(
                        "Nacos 的持久化节点间采用 Raft 协议保证元数据一致性",
                        "Nacos 集群元数据同步依赖 Raft 协议选举 leader",
                        "Nacos 的 Distro 协议负责临时实例的最终一致同步",
                        "Nacos 持久化实例使用 Raft 而临时实例使用 Distro 协议",
                        "Nacos 1.3 之后元数据一致性由 Raft 协议保障",
                        "Nacos 的 Distro 协议是 AP 型的最终一致性方案",
                        "Nacos 节点间通过 Raft 日志复制保持元数据一致",
                        "Nacos 临时服务列表由 Distro 协议做异步一致性同步",
                        "Nacos 的 Raft 实现负责持久化服务的强一致存储",
                        "Nacos 配置持久化与服务发现分别用 Raft 与 Distro 协议");
        List<String> insufficientContents =
                List.of(
                        "Nacos 提供了服务发现和动态配置管理能力",
                        "Nacos 支持命名空间隔离多租户配置",
                        "Nacos 控制台默认端口是 8848",
                        "Nacos 部署需要 JDK 8 以上环境",
                        "Nacos 控制台可以查看服务列表",
                        "Nacos 社区活跃度很高, 版本迭代快",
                        "Nacos 支持健康检查与服务权重设置",
                        "Nacos 可以作为 Dubbo 的注册中心",
                        "Nacos 的配置支持热更新和灰度发布",
                        "Nacos 集群建议至少部署三个节点");
        int ruleSufficientOutputs = 0;
        int deferred = 0;
        for (String c : sufficientContents) {
            if (run(nacosProtocol(), List.of(ev("e", c))).status() == SufficiencyStatus.SUFFICIENT)
                ruleSufficientOutputs++;
            deferred++;
        }
        for (String c : insufficientContents) {
            SufficiencyDecision d = run(nacosProtocol(), List.of(ev("e", c)));
            if (d.status() == SufficiencyStatus.SUFFICIENT) ruleSufficientOutputs++;
            if (d.status() == SufficiencyStatus.UNDETERMINED) deferred++;
        }
        System.out.printf(
                "[D3-DEV-REGRESSION] rule SUFFICIENT outputs=%d/20 (修复前 sufficient 命中20/20), "
                        + "deferred=%d/20%n",
                ruleSufficientOutputs, deferred);
        // 核心不变式: Rule 层不再有"终局充分性"判定 → false sufficient 构造性为零
        assertThat(ruleSufficientOutputs).isZero();
        assertThat(deferred).isEqualTo(20);
    }

    @Test
    @DisplayName("可达性: ENTITY_ATTRIBUTE 有实体命中证据 → UNDETERMINED → Model 可达(修复)")
    void reachabilityEntityAttribute() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(ev("e1", "Nacos 支持服务发现与配置管理")));
        assertThat(d.status())
                .as("修复前 ENTITY_ATTRIBUTE 直接终判 COVERED, Model 结构性不可达")
                .isEqualTo(SufficiencyStatus.UNDETERMINED);
    }
}
