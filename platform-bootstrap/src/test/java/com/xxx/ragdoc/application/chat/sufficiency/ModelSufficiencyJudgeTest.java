package com.xxx.ragdoc.application.chat.sufficiency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.evidence.Evidence;
import com.xxx.ragdoc.application.chat.planner.EvidenceCoverageSummary;
import com.xxx.ragdoc.application.chat.planner.EvidenceRequirement;
import com.xxx.ragdoc.application.chat.planner.RequirementType;
import com.xxx.ragdoc.application.chat.port.ChatClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** PR-7b: {@link ModelSufficiencyJudge} — False Sufficient 防护 + Provider 错误分类 + JSON 解析。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelSufficiencyJudge - PR-7b False Sufficient 防护 + Provider 错误")
class ModelSufficiencyJudgeTest {

    @Mock private ChatClient chatClient;
    private ModelSufficiencyJudge judge;

    @BeforeEach
    void setup() {
        judge = new ModelSufficiencyJudge(chatClient, new ObjectMapper(), new SufficiencyProperties());
    }

    private Evidence ev(String tenant, String reqId, String evidenceId) {
        Map<String, Object> md = new java.util.HashMap<>();
        md.put("requirementIds", List.of(reqId));
        return Evidence.of(tenant, 1L, 10L, "v2", "content-" + reqId, 0.9, null,
                "metadata_search", md);
    }

    private SufficiencyRequest request(List<EvidenceRequirement> reqs, List<Evidence> evs) {
        return new SufficiencyRequest("r1", "q", reqs, evs,
                Set.of(), Set.of(), EvidenceCoverageSummary.empty(), 0, true, Map.of());
    }

    private EvidenceRequirement req(String id, boolean required) {
        return new EvidenceRequirement(id, "d-" + id, RequirementType.RELATION, required,
                List.of(), Map.of());
    }

    @Test
    @DisplayName("False Sufficient 防护: 模型声称 COVERED + 但 evidenceIds 引用未知 evId → 降为 NOT_COVERED")
    void falseSufficientBlocked() throws Exception {
        Evidence realEvidence = ev("tA", "R1", null);
        // 模型声 COVERED 但引用一个不存在的 fake-evidenceId
        String json = "{\"coverage\":[{\"requirementId\":\"R1\",\"status\":\"COVERED\","
                + "\"evidenceIds\":[\"fake-ghost-id\"]}],\"globalConflicts\":[]}";
        when(chatClient.chat(anyString(), anyList())).thenReturn(json);

        SufficiencyDecision d = judge.evaluate(request(List.of(req("R1", true)),
                List.of(realEvidence)));
        // 降 NOT_COVERED + INSUFFICIENT
        assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.NOT_COVERED);
        assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
        assertThat(d.missingRequirementIds()).contains("R1");
    }

    @Test
    @DisplayName("模型声称 CONFLICTED 但 evidenceIds < 2 → 降为 NOT_COVERED")
    void conflictNeedsAtLeast2Evidence() throws Exception {
        Evidence realEvidence = ev("tA", "R1", null);
        String json = "{\"coverage\":[{\"requirementId\":\"R1\",\"status\":\"CONFLICTED\","
                + "\"evidenceIds\":[\"" + realEvidence.evidenceId() + "\"]}],\"globalConflicts\":[]}";
        when(chatClient.chat(anyString(), anyList())).thenReturn(json);

        SufficiencyDecision d = judge.evaluate(request(List.of(req("R1", true)),
                List.of(realEvidence)));
        assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.NOT_COVERED);
        assertThat(d.status()).isEqualTo(SufficiencyStatus.INSUFFICIENT);
    }

    @Test
    @DisplayName("合法 SUFFICIENT: 模型引用真实已存在 evidenceId")
    void legitimateSufficient() throws Exception {
        Evidence realEvidence = ev("tA", "R1", null);
        String json = "{\"coverage\":[{\"requirementId\":\"R1\",\"status\":\"COVERED\","
                + "\"evidenceIds\":[\"" + realEvidence.evidenceId() + "\"]}],\"globalConflicts\":[]}";
        when(chatClient.chat(anyString(), anyList())).thenReturn(json);

        SufficiencyDecision d = judge.evaluate(request(List.of(req("R1", true)),
                List.of(realEvidence)));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.SUFFICIENT);
        assertThat(d.coverage().get(0).status()).isEqualTo(CoverageStatus.COVERED);
        assertThat(d.source()).isEqualTo("MODEL");
    }

    @Test
    @DisplayName("模型 globalConflicts 非空 → CONFLICTED + REFUSE_CONFLICT")
    void modelConflict() throws Exception {
        Evidence a = ev("tA", "R1", null);
        Evidence b = ev("tA", "R1", null);
        String json = "{\"coverage\":[],\"globalConflicts\":[{\"requirementId\":\"R1\","
                + "\"evidenceIds\":[\"" + a.evidenceId() + "\",\"" + b.evidenceId() + "\"],"
                + "\"reason\":\"两边矛盾\"}]}";
        when(chatClient.chat(anyString(), anyList())).thenReturn(json);
        SufficiencyDecision d = judge.evaluate(
                request(List.of(req("R1", true)), List.of(a, b)));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.CONFLICTED);
        assertThat(d.action()).isEqualTo(RecommendedAction.REFUSE_CONFLICT);
    }

    @Test
    @DisplayName("ChatClient 抛 Exception → UNDETERMINED conservative")
    void providerErrorConservative() throws Exception {
        when(chatClient.chat(anyString(), anyList()))
                .thenThrow(new java.util.concurrent.TimeoutException("timeout"));
        SufficiencyDecision d = judge.evaluate(
                request(List.of(req("R1", true)), List.of(ev("tA", "R1", null))));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.reasonCode()).isEqualTo("MODEL_TIMEOUT");
    }

    @Test
    @DisplayName("模型输出非 JSON → UNDETERMINED conservative")
    void nonJsonConservative() throws Exception {
        when(chatClient.chat(anyString(), anyList())).thenReturn("not json at all");
        SufficiencyDecision d = judge.evaluate(
                request(List.of(req("R1", true)), List.of(ev("tA", "R1", null))));
        assertThat(d.status()).isEqualTo(SufficiencyStatus.UNDETERMINED);
        assertThat(d.reasonCode()).isEqualTo("MODEL_INVALID_JSON");
    }

    @Test
    @DisplayName("extractJson: fenced 与裸 JSON 都可提取")
    void extractJsonDefensive() {
        assertThat(ModelSufficiencyJudge.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(ModelSufficiencyJudge.extractJson("noise {\"a\":1} tail")).contains("\"a\":1");
    }
}
