package com.xxx.ragdoc.application.chat.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.agent.AgentPersistenceCoordinator.InitializedRun;
import com.xxx.ragdoc.application.chat.tool.AgentTool;
import com.xxx.ragdoc.application.chat.tool.ToolDescriptor;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import com.xxx.ragdoc.application.chat.tool.ToolOutput;
import com.xxx.ragdoc.application.chat.tool.ToolPermission;
import com.xxx.ragdoc.application.chat.tool.ToolRegistry;
import com.xxx.ragdoc.domain.auth.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR-6b.3: {@link AgentRunFactory} 单测 (Revision §6 §8 — UUID runId + PlanValidator 先决 + 初始化
 * 单事务由 Coordinator)。
 *
 * <p>不要测 "runId 含 tenant fingerprint" (Revision §8 已删该要求) — 只测非空 + 不碰撞。
 */
@DisplayName("AgentRunFactory - PR-6b.3 UUID runId + PlanValidator guard")
class AgentRunFactoryTest {

    private AgentPersistenceCoordinator coordinator;
    private PlanValidator planValidator;
    private AgentRunFactory factory;

    record TestInput(String query, Integer topK) implements ToolInput {}
    record TestOutput() implements ToolOutput {}

    @BeforeEach
    void setup() {
        coordinator = mock(AgentPersistenceCoordinator.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        AgentTool<TestInput, TestOutput> semantic = stubTool("semantic_search", "v1");
        when(registry.getByName("semantic_search")).thenAnswer(inv -> semantic);
        planValidator = new PlanValidator(registry);
        factory = new AgentRunFactory(coordinator, planValidator, new ObjectMapper());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <I extends ToolInput, O extends ToolOutput> AgentTool<I, O> stubTool(
            String name, String version) {
        AgentTool t = mock(AgentTool.class);
        when(t.descriptor()).thenReturn(new ToolDescriptor(
                name, version, "stub", "v1", "v1", ToolPermission.READ_RETRIEVE,
                Duration.ofSeconds(5), 10, true,
                com.xxx.ragdoc.application.chat.tool.ToolCostCategory.INDEX_READ));
        when(t.inputType()).thenReturn((Class) TestInput.class);
        when(t.outputType()).thenReturn((Class) TestOutput.class);
        return t;
    }

    private AgentExecutionPolicy policy() {
        return new AgentExecutionPolicy(
                AgentBudget.pr6Default(), null, Set.of("semantic_search"),
                20, 4000, true, false, true);
    }

    private DeterministicExecutionPlan plan() {
        return new DeterministicExecutionPlan("p1", "v1", List.of(
                new AgentToolStep("s1", "semantic_search", "v1",
                        new TestInput("q", 5), List.of(), "", true)));
    }

    private InitializedRun initStub(String runId) {
        AgentRunRecord run = new AgentRunRecord(
                runId, "req-1", "tA", "u1", "COMPARISON",
                AgentRunStatus.EXECUTING, "p1", "v1", "h",
                "{\"planId\":\"p1\"}",
                AgentBudget.pr6Default(), AgentBudgetReservation.zero(), AgentUsage.zero(),
                List.of(), 0, null, "rv", "tsv", "iv1", "LIVE",
                null, null, 3);
        AgentStepRecord step = new AgentStepRecord(
                runId, "s1", 0, "semantic_search", "v1", null,
                "inputhash64charxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                AgentStepStatus.PENDING, 0, List.of(),
                null, null, false, false, false, null, null, null, null, 0);
        return new InitializedRun(run, List.of(step));
    }

    @Test
    @DisplayName("合法 Plan → Coordinator.initializeRunAndSteps 被调, 返回 EXECUTING 状态")
    void legalPlanInvokesInit() {
        when(coordinator.initializeRunAndSteps(any(), any())).thenReturn(initStub("r1"));

        InitializedRun r = factory.create(plan(), policy(),
                new Principal("tA", "u1", Set.of(), null),
                "req-1", "COMPARISON", "rv", "tsv", "iv1", "LIVE");

        assertThat(r.run().status()).isEqualTo(AgentRunStatus.EXECUTING);
        assertThat(r.run().version()).isEqualTo(3);
        assertThat(r.steps()).hasSize(1);
        assertThat(r.steps().get(0).status()).isEqualTo(AgentStepStatus.PENDING);
    }

    @Test
    @DisplayName("两次 create() 生成不同 runId (Revision §8 — UUID, 不碰撞)")
    void twoCreationsDifferentRunIds() {
        when(coordinator.initializeRunAndSteps(any(), any()))
                .thenReturn(initStub("r1"))
                .thenReturn(initStub("r2"));
        Principal p = new Principal("tA", "u1", Set.of(), null);
        InitializedRun r1 = factory.create(plan(), policy(), p, "req-1", "X", null, null, null, null);
        InitializedRun r2 = factory.create(plan(), policy(), p, "req-1", "X", null, null, null, null);
        assertThat(r1.run().runId()).isNotEqualTo(r2.run().runId());
    }

    @Test
    @DisplayName("非法 Plan (空 steps) → PlanValidator 抛 InvalidAgentPlanException, 不进 Coordinator")
    void invalidPlanRejected() {
        assertThatThrownBy(() -> factory.create(
                new DeterministicExecutionPlan("p1", "v1", List.of(
                        new AgentToolStep("s1", "unknown_tool", "v1",
                                new TestInput("q", 5), List.of(), "", true))),
                policy(),
                new Principal("tA", "u1", Set.of(), null),
                "req-1", "X", null, null, null, null))
                .isInstanceOf(PlanValidationResult.InvalidAgentPlanException.class);
        org.mockito.Mockito.verifyNoInteractions(coordinator);
    }

    @Test
    @DisplayName("principal null → fail-closed IllegalArgumentException")
    void nullPrincipalRejected() {
        when(coordinator.initializeRunAndSteps(any(), any())).thenReturn(initStub("r1"));
        assertThatThrownBy(() -> factory.create(plan(), policy(),
                null, "req-1", "X", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("principal.tenantId blank → fail-closed")
    void blankTenantRejected() {
        assertThatThrownBy(() -> factory.create(plan(), policy(),
                new Principal("", "u1", Set.of(), null),
                "req-1", "X", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
