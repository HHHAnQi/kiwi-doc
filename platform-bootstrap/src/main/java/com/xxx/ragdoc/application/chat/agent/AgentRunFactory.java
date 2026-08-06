package com.xxx.ragdoc.application.chat.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxx.ragdoc.application.chat.tool.ToolInput;
import com.xxx.ragdoc.domain.auth.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PR-6b.3 / EMS-PR6 §6: Agent Run 工厂 — 把服务端构造的合法 DeterministicExecutionPlan
 * 落成一个可执行的 Initialized Run + Steps。
 *
 * <p>硬约束 (Revision §6 §8):
 * <ol>
 *   <li>Plan 必须先通过 {@code PlanValidator.throwIfInvalid()} — 失败不进入 Executor, 不创建 run。
 *   <li>{@code runId = UUID} (Revision §8 — 不用含 nonce 的业务 hash; 租户隔离靠 ACL / Repository 查询,
 *       不靠 runId)。幂等另设 idempotency_key 在 PR-7 引入。
 *   <li>{@code tenantId / userId} 来自服务端 Principal, 不接受 caller 传入。
 *   <li>Step 按 {@code PlanValidationResult.topologicalStepOrder()} 创建为 PENDING。
 * </ol>
 *
 * <p>初始化<b>单事务</b>由 {@link AgentPersistenceCoordinator#initializeRunAndSteps} 完成 (Revision §1 §9):
 * 任一失败整体回滚, 抛 {@link AgentRunInitializationException}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunFactory {

    private final AgentPersistenceCoordinator coordinator;
    private final PlanValidator planValidator; // PR-6a.1 已就位
    private final ObjectMapper mapper;

    /**
     * 构造并初始化 Run + Steps。
     *
     * @param plan             服务端构造的合法 plan
     * @param policy           执行策略 (budget / allowedTools / maxEvidence 等)
     * @param principal        服务端 Principal (tenantId/userId 来源)
     * @param requestId        入口请求 ID (来自 ChatRequest / Orchestrator)
     * @param strategy         路由策略 (CLASSIC_RAG / COMPARISON / TARGETED_RAG / 等)
     * @param routerVersion    Trace
     * @param toolsetVersion   Trace
     * @param indexVersion     关键 — ReplayKey / ACL scope 一部分
     * @param harnessMode      LIVE / RECORD / REPLAY
     */
    public AgentPersistenceCoordinator.InitializedRun create(
            DeterministicExecutionPlan plan,
            AgentExecutionPolicy policy,
            Principal principal,
            String requestId,
            String strategy,
            String routerVersion,
            String toolsetVersion,
            String indexVersion,
            String harnessMode) {
        // 1. Plan 先校验 — 必须由 PlanValidator 拒非法 plan
        if (plan == null) throw new IllegalArgumentException("plan 必填");
        if (principal == null) throw new IllegalArgumentException("principal 必填");
        if (principal.tenantId() == null || principal.tenantId().isBlank()) {
            throw new IllegalArgumentException("Principal.tenantId 必填 (服务端 fail-closed)");
        }
        PlanValidationResult validation = planValidator.validate(plan, policy);
        validation.throwIfInvalid(); // 抛 InvalidAgentPlanException

        // 2. UUID runId (Revision §8)
        String runId = java.util.UUID.randomUUID().toString();

        // 3. Plan JSON / hash
        String planJson = writePlanJsonSafe(plan);
        String planHash = sha256(planJson);

        // 4. 构造 RECEIVED 的 Run
        AgentRunRecord run = new AgentRunRecord(
                runId, requestId, principal.tenantId(), principal.userId(), strategy,
                AgentRunStatus.RECEIVED,
                plan.planId(), plan.planVersion(), planHash, planJson,
                policy.budget(),
                AgentBudgetReservation.zero(), AgentUsage.zero(),
                List.of(), 0,
                null, routerVersion, toolsetVersion, indexVersion,
                harnessMode == null ? "LIVE" : harnessMode,
                Instant.now(), Instant.now(), 0);
        // 5. 构造 PENDING Steps (按拓扑序)
        List<AgentStepRecord> steps = buildPendingSteps(runId, plan);

        // 6. 单事务原子初始化 (含三次 CAS)
        return coordinator.initializeRunAndSteps(run, steps);
    }

    private List<AgentStepRecord> buildPendingSteps(String runId, DeterministicExecutionPlan plan) {
        List<AgentStepRecord> result = new java.util.ArrayList<>();
        int seq = 0;
        for (AgentToolStep s : plan.steps()) {
            String inputHash = sha256(inputForHash(s.input()));
            result.add(new AgentStepRecord(
                    runId, s.stepId(), seq,
                    s.toolName(), s.toolVersion(), null, inputHash,
                    AgentStepStatus.PENDING, 0, List.of(),
                    null, null, false, false, false,
                    null, null, Instant.now(), Instant.now(), 0));
            seq++;
        }
        return result;
    }

    private String writePlanJsonSafe(DeterministicExecutionPlan plan) {
        try {
            return mapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent_run plan JSON 序列化失败", e);
        }
    }

    private static String inputForHash(ToolInput input) {
        return input == null ? "" : input.normalizedForDedup();
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
