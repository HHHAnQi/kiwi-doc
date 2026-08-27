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
 * P2-D3 语义有效性诊断探针 — 记录 RuleSufficiencyJudge 的<b>当前真实语义</b>。
 *
 * <p>本测试是缺陷文档, 不是正确性规范: 它断言的是"证据挂上即 COVERED"的现行行为。
 * 若未来修复语义判定, 本测试应随之为翻转为正确断言。
 *
 * <p>判定链(冻结): Requirement → Step.requirementIds → 执行期机械打标
 * (AgentRunPhaseExecutor.augmentMetadata) → RuleSufficiencyJudge 存在性检查
 * (entity substring + filter + 类型分流) → DispatchingSufficiencyJudge(Rule 优先) →
 * Guard → ReplanDecision。全程不读 evidence content 与 requirement 的语义相关性。
 */
@DisplayName("P2-D3 Sufficiency 语义探针 — 记录当前判定语义(false-sufficient by construction)")
class SufficiencySemanticProbeTest {

    private final RuleSufficiencyJudge judge = new RuleSufficiencyJudge();

    /** 与真实 extractor 产出同构: ENTITY_ATTRIBUTE + targetEntities=[Nacos]。 */
    private static EvidenceRequirement nacosProtocol() {
        return new EvidenceRequirement(
                "REQ-1", "Nacos 使用什么一致性协议", RequirementType.ENTITY_ATTRIBUTE,
                true, List.of("Nacos"), Map.of());
    }

    private static Evidence ev(String id, String content) {
        return ev(id, content, "REQ-1");
    }

    private static Evidence ev(String id, String content, String reqId) {
        return Evidence.of("tA", 1L, 10L, "v1", content, 0.9, null,
                "semantic_search", Map.of("requirementIds", List.of(reqId)));
    }

    private SufficiencyDecision run(EvidenceRequirement req, List<Evidence> evs) {
        return judge.evaluate(
                new SufficiencyRequest(
                        "run-probe", "Nacos 使用什么一致性协议", List.of(req), evs,
                        Set.of(), Set.of(), EvidenceCoverageSummary.empty(),
                        0, true, Map.of()));
    }

    // ─── A–E 最小反例集 ─────────────────────────────────────────

    @Test
    @DisplayName("A: relevant+sufficient → COVERED/SUFFICIENT (正确)")
    void probeA_relevantSufficient() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(
                ev("e1", "Nacos 的持久化节点间采用 Raft 协议保证元数据一致性")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.COVERED);
    }

    @Test
    @DisplayName("B: relevant但不含答案事实(只讲服务发现) → 仍 COVERED [FALSE SUFFICIENT]")
    void probeB_relevantButInsufficient() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(
                ev("e1", "Nacos 支持服务发现与配置管理, 提供控制台和开放API")));
        // 诊断结论: entity substring 命中即 COVERED, 不检查内容是否回答了问题
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        assertThat(d.coverage().get(0).reasonCode()).isEqualTo("RULE_FULLY_COVERED");
    }

    @Test
    @DisplayName("C1: 完全无关证据(不含实体) → NOT_COVERED (entity 过滤救回)")
    void probeC1_irrelevantNoEntity() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(
                ev("e1", "Dubbo 服务调用超时时间默认是 1000ms")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
        assertThat(d.coverage().get(0).reasonCode())
                .isEqualTo("EVIDENCE_ENTITY_OR_FILTER_MISMATCH");
    }

    @Test
    @DisplayName("C2: 无关证据但提及实体 → 仍 COVERED [FALSE SUFFICIENT]")
    void probeC2_irrelevantButEntityMentioned() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(
                ev("e1", "Nacos 控制台默认端口是 8848")));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
    }

    @Test
    @DisplayName("D: 同一事实矛盾证据(Raft vs Paxos) → 无版本锁 → 仍 COVERED [矛盾漏检]")
    void probeD_contradictory() {
        SufficiencyDecision d = run(nacosProtocol(), List.of(
                ev("e1", "Nacos 集群一致性协议是 Raft"),
                ev("e2", "Nacos 集群一致性协议是 Paxos")));
        // 冲突检测只识别 version-value mismatch — 事实级矛盾完全漏检
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        assertThat(d.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("E: 无证据 → INSUFFICIENT (正确)")
    void probeE_noEvidence() {
        SufficiencyDecision d = run(nacosProtocol(), List.of());
        assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
    }

    // ─── 20 对人工可核验 confusion 集(ground truth by construction) ───

    @Test
    @DisplayName("Confusion: 20对 ground-truth vs RuleJudge — 输出混淆矩阵")
    void confusionPattern() {
        // 10 对 sufficient: 内容含 Nacos + 一致性协议答案事实(全为同实体, 隔离语义变量)
        List<String> sufficientContents = List.of(
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
        // 10 对 insufficient: 均提及 Nacos(会被 tag 到 requirement)但不含协议答案
        List<String> insufficientContents = List.of(
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
        // 每对的实体要求: 用 Nacos requirement(问一致性协议)
        int truePositive = 0, falseSufficient = 0;
        for (String c : sufficientContents) {
            SufficiencyDecision d = run(nacosProtocol(), List.of(ev("e", c)));
            if (d.status() == SufficiencyStatus.SUFFICIENT) truePositive++;
            else falseSufficient++; // 不可能: 不含实体 → mismatch; 这里 sufficient 全含 Nacos
        }
        int falseSufficientRate10 = 0;
        for (String c : insufficientContents) {
            SufficiencyDecision d = run(nacosProtocol(), List.of(ev("e", c)));
            // ground truth: INSUFFICIENT; rule 若判 SUFFICIENT 即 false sufficient
            if (d.status() == SufficiencyStatus.SUFFICIENT) falseSufficientRate10++;
        }
        System.out.printf(
                "[D3-CONFUSION] rule judge: TP=%d/10, FALSE_SUFFICIENT=%d/10 "
                        + "(insufficient-but-entity-tagged 被判 SUFFICIENT)%n",
                truePositive, falseSufficientRate10);
        // 结构性断言: 不含答案事实但提及实体的证据 100% 被判 SUFFICIENT
        assertThat(falseSufficientRate10)
                .as("false sufficient on insufficient-but-tagged evidence")
                .isEqualTo(10);
        assertThat(truePositive).isEqualTo(10);
    }

    @Test
    @DisplayName("可达性: ENTITY_ATTRIBUTE 有实体命中证据 → Rule 直接终判, Model Judge 不被触达")
    void reachabilityEntityAttribute() {
        // DispatchingSufficiencyJudge: Rule 非 UNDETERMINED 即返回 — ENTITY_ATTRIBUTE
        // 的任何确定性结果(COVERED/NOT_COVERED)都不会触发 Model fallback。
        // RELATION/FOLLOW_UP 且有实体命中证据 → UNDETERMINED → Model 可达。
        EvidenceRequirement relation =
                new EvidenceRequirement(
                        "REQ-2", "因果/合成", RequirementType.RELATION,
                        true, List.of("Nacos"), Map.of());
        SufficiencyDecision d = run(relation, List.of(
                ev("e1", "Nacos 支持服务发现与配置管理", "REQ-2"))); // 无关内容, 但实体命中
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.coverage().get(0).reasonCode()).isEqualTo("RULE_CANNOT_VERIFY_SEMANTIC");
        // UNDETERMINED 是 Model Judge 的唯一入口 — 但只对 RELATION/FOLLOW_UP 开放
    }
}
