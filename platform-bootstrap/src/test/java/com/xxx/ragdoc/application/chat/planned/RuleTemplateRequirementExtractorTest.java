package com.xxx.ragdoc.application.chat.planned;

import static org.assertj.core.api.Assertions.assertThat;

import com.xxx.ragdoc.application.chat.planner.RequirementType;
import com.xxx.ragdoc.application.chat.router.ExecutionStrategy;
import com.xxx.ragdoc.application.chat.router.RouterDecision;
import com.xxx.ragdoc.application.chat.router.TaskIntent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PR-7c.3b: {@link RuleTemplateRequirementExtractor} — 确定性 Requirements。 */
@DisplayName("RuleTemplateRequirementExtractor - PR-7c.3b 稳定 Requirements")
class RuleTemplateRequirementExtractorTest {

    private RuleTemplateRequirementExtractor ex;

    @BeforeEach
    void setup() {
        ex = new RuleTemplateRequirementExtractor();
    }

    private RouterDecision decision(TaskIntent intent, List<String> entities, Map<String, Object> filters) {
        return new RouterDecision(intent, ExecutionStrategy.FIXED_WORKFLOW,
                entities, filters, 0.9, "TEST");
    }

    @Test
    @DisplayName("MULTI_HOP + 2 entities → 2 ENTITY_ATTRIBUTE + 至少 1 RELATION, required=true, 稳定 ID")
    void multiHopEntities() {
        RuleTemplateRequirementExtractor.RequirementExtractionResult r =
                ex.extract(decision(TaskIntent.MULTI_HOP, List.of("v1", "v2"), Map.of()),
                        "为什么 X 之后 Y");
        assertThat(r.valid()).isTrue();
        assertThat(r.requirements()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(r.requirements().stream().allMatch(req -> req.required())).isTrue();
        // entity-related requirements at least 2
        long entityReqCount = r.requirements().stream()
                .filter(req -> req.type() == RequirementType.ENTITY_ATTRIBUTE).count();
        assertThat(entityReqCount).isEqualTo(2);
        // 至少一个 RELATION
        assertThat(r.requirements().stream().anyMatch(req -> req.type() == RequirementType.RELATION))
                .isTrue();
        // Stable ID
        assertThat(r.requirements().get(0).requirementId()).startsWith("REQ-");
    }

    @Test
    @DisplayName("无 entities + FACT intent → 至少 1 FACT (兜底)")
    void fallbackFact() {
        RuleTemplateRequirementExtractor.RequirementExtractionResult r =
                ex.extract(decision(TaskIntent.FACT, List.of(), Map.of()), "what is X");
        assertThat(r.valid()).isTrue();
        assertThat(r.requirements()).hasSize(1);
        assertThat(r.requirements().get(0).type()).isEqualTo(RequirementType.FACT);
    }

    @Test
    @DisplayName("空 query → invalid")
    void emptyQueryInvalid() {
        RuleTemplateRequirementExtractor.RequirementExtractionResult r =
                ex.extract(decision(TaskIntent.FACT, List.of(), Map.of()), "   ");
        assertThat(r.valid()).isFalse();
        assertThat(r.invalidReason()).isEqualTo("EMPTY_QUERY");
    }

    @Test
    @DisplayName("requirementIds 全局唯一 (跨多次 entity)")
    void uniqueIds() {
        RuleTemplateRequirementExtractor.RequirementExtractionResult r =
                ex.extract(decision(TaskIntent.MULTI_HOP, List.of("a", "b", "c"), Map.of()), "x");
        long unique = r.requirements().stream().map(req -> req.requirementId()).distinct().count();
        assertThat(unique).isEqualTo((long) r.requirements().size());
    }

    @Test
    @DisplayName("ENTITY_ATTRIBUTE Requirement targetEntities 包含原 entity")
    void targetEntitiesCaptured() {
        RuleTemplateRequirementExtractor.RequirementExtractionResult r =
                ex.extract(decision(TaskIntent.ENTITY_LOOKUP, List.of("compA"), Map.of()),
                        "what is compA");
        assertThat(r.valid()).isTrue();
        assertThat(r.requirements()).isNotEmpty();
        assertThat(r.requirements().get(0).targetEntities()).contains("compA");
    }
}
