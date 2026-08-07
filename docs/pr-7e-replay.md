# PR-7e.2: PlannedAgentPipeline Replay Integration Tests

> Status: **已完成** — 6 IT (Replay A–E + Consistency) 全部通过；本机无需 Docker

---

## 1. 测试定位

`PlannedAgentReplayIT` 位于 `integrationTest` sourceSet, **不依赖 Docker / MySQL / Testcontainers**。
通过手动 wire real beans + mock 边界 Provider, 验证 `PlannedAgentExecutionCoordinator` 的编排逻辑在 5 种业务场景下产生正确 trajectory。

### Real beans (编排逻辑验证真实路径)

```
RuleTemplateRequirementExtractor      ← 真实
SufficiencyDecisionGuard              ← 真实 (False Sufficient 第三层)
AgentProgressDetector                 ← 真实
ReplanDecisionCoordinator             ← 真实 (包装 real ProgressDetector)
PlannedAgentExecutionCoordinator      ← 真实 (主体编排)
```

### Mock beans (边界 Provider, deep dep 不在 Replay 范畴)

```
PlannerProvider                       ← Mock (返回确定性 PlannerResponse)
PlannerPlanAssembler                  ← Mock (返回 AssemblyResult.ok)
AgentRunFactory                       ← Mock (返回 InitializedRun)
AgentRunPhaseExecutor                 ← Mock (返回 PhaseExecutionResult)
DispatchingSufficiencyJudge           ← Mock (返回 SufficiencyDecision)
PlannedAgentRunFinalizer              ← Mock (返回 FinalizeOutcome.written)
AgentPersistenceCoordinator           ← Mock (appendReplanSteps = doNothing)
```

CI Docker 跑通后, 这些 Mock 会替换为真实 Spring Bean 的 @SpringBootTest。

---

## 2. 五个 Replay Case

### Case A: Initial Plan Sufficient

```
Requirement (v1, v2 → REQ-1,REQ-2 ENTITY_ATTRIBUTE + REQ-3 RELATION)
→ Planner callIndex=0 (plan-A)
→ PlanAssembler OK
→ AgentRunFactory (EXECUTING)
→ PhaseExecutor (Phase 0): 1 Evidence=ev0
→ Sufficiency SUFFICIENT (all 3 REQs COVERED by ev0)
→ Guard PASS
→ Finalizer READY_TO_ANSWER
```

**断言**:
- `result.ok() == true`
- `result.prepared().evidence()` 含 ev0 (evidence ids 一致)
- `replanCount == 0`
- `plannerProvider.plan()` 调用 1 次

**通过** ✅

### Case B: Replan Success

```
Phase 0: ev0 → Sufficiency INSUFFICIENT (REQ-2,REQ-3 missing)
→ ReplanDecision ALLOW (Phase 0 有新 ev_id → PROGRESS)
→ Planner callIndex=1 (plan-B-replan)
→ appendReplanSteps
→ Phase 1: ev1 (新) → accumulated = [ev0, ev1]
→ Sufficiency SUFFICIENT (all 3 COVERED by ev0+ev1)
→ Guard PASS
→ Finalizer READY_TO_ANSWER
```

**断言**:
- `replanCount == 1`
- evidence ids = [ev0, ev1]
- `plannerProvider.plan()` 调用 2 次 (initial + replan)
- `phaseExecutor.executePhase()` 调用 2 次

**通过** ✅

### Case C: Replan Still Insufficient

```
Phase 0: ev0 → INSUFFICIENT (REQ-2,REQ-3 missing)
→ ALLOW (新 ev_id → PROGRESS)
→ Planner 1 → appendReplanSteps → Phase 1: ev1 (新) → accumulated=[ev0,ev1]
→ Sufficiency INSUFFICIENT (missing=[REQ-3] 缩减 但仍不足)
→ 第二次 replan 被阻断 (replanCount=1)
→ Finalizer REFUSED_NO_EVIDENCE
```

**断言**:
- `result.ok() == false`
- `failureTerminal == REFUSED_NO_EVIDENCE`
- `failureReason` 含 `INSUFFICIENT_AFTER_REPLAN`
- `plannerProvider.plan()` 调用 2 次 (无第三次)

**通过** ✅

### Case D: Conflict → REFUSED_CONFLICT

```
Phase 0: ev1(v1) + ev2(v2) 同 REQ-1
→ Sufficiency CONFLICTED (VERSION_VALUE_MISMATCH)
→ 不进 Sufficiency Guard (status != SUFFICIENT)
→ 不 Replan (CONFLICTED 阻断)
→ Finalizer REFUSED_CONFLICT
```

**断言**:
- `failureTerminal == REFUSED_CONFLICT`
- `plannerProvider.plan()` 调用 1 次 (无 Replan)
- `persistenceCoordinator.appendReplanSteps()` never called

**通过** ✅

### Case E: Required Tool Failure

```
Phase 0: prematureTerminal=TOOL_FAILED (required Step FAILED_TERMINAL)
→ 不调 Sufficiency / 不 Replan
→ Finalizer TOOL_FAILED
```

**断言**:
- `failureTerminal == TOOL_FAILED`
- `failureReason` 含 `REQUIRED_TOOL_FAILED`
- `dispatchingSufficiencyJudge.evaluate()` never called
- `plannerProvider.plan()` 调用 1 次

**通过** ✅

### Replay Consistency (Case A 跑两次)

```
同一 Coordinator 同一输入跑两次 → evidence/terminal/replanCount 一致
```

**断言**:
- evidence_id[0] 相等
- readyRunVersion 相等
- replanCount 都为 0

**通过** ✅

---

## 3. Replay 一致性验证

| 维度 | 验证方式 |
|---|---|
| planHash 一致 | PlannerResponse.planId/planVersion 在 Initial/Replan 中显式 stub; Replay 两次相同 planId |
| tool signature 一致 | PhaseExecutionResult.usedToolSignatures 携带; ReplanDecisionCoordinator 用它做 Loop Detection |
| evidence ids 一致 | Case A/B 断言 `result.prepared().evidence()` 的 evidenceId == stub evidenceId |
| terminal status 一致 | Finalizer 写的 target == PrepareResult 的 failureTerminal 或 Guard 通过时 READY_TO_ANSWER |

---

## 4. Bug 修复 (IT 发现)

### priorAccumulatedEvidenceIds 错误 (Phase 0)

**问题**: Coordinator 对 `ReplanDecisionCoordinator.decide(...)` 传入
`priorIds(phase0.accumulatedEvidence())` — Phase 0 的 accumulated 中已含 Phase 0 自身产生
的 evidence, 所以 ProgressDetector 对比 newEvidence 时永远命中 selbst → FALSE → NO_PROGRESS。

**修复**: Phase 0 的 prior accumulated 应为**空集** (Phase 0 前无证据);
改为 `new HashSet<>()`。

**根因**: `PhaseExecutionContext.initial()` 的 priorEvidence 为空, 但 Coordinator 错误地把
Phase **结果** 的 accumulated 当 prior 传给 detector。

---

## 5. 实际测试结果

| 命令 | 结果 |
|---|---|
| `:platform-bootstrap:test` (unit) | BUILD SUCCESSFUL (0 failures) |
| `:platform-bootstrap:integrationTest --tests PlannedAgentReplayIT` | BUILD SUCCESSFUL: 6 tests, 0 failures |

```
PlannedAgentExecutionCoordinator Replay - PR-7e.2 5 轨迹: tests=5 failures=0
  PASS: Case A: Initial Plan → Sufficiency SUFFICIENT → Guard PASS → READY_TO_ANSWER
  PASS: Case B: Initial INSUFFICIENT → Replan ALLOW → Replan Phase SUFFICIENT → ANSWERED
  PASS: Case C: Initial INSUFFICIENT → Replan → 仍 INSUFFICIENT → REFUSED_NO_EVIDENCE
  PASS: Case D: Sufficiency 检测到 version 冲突 → CONFLICTED → REFUSED_CONFLICT
  PASS: Case E: Phase 内 required Tool FAILED_TERMINAL → premature → TOOL_FAILED
Replay consistency: planHash / tool sig / evidence ids / terminal: tests=1 failures=0
  PASS: Case A 跑两次 → 返回的 evidence / terminal 一致 (Coordinator 确定性)
```

---

## 6. 未验证项 (留给 PR-7e.3 / CI)

以下在 Coordinator 编排层已验证; 真实 Spring Bean 跨层级 需 @SpringBootTest + Docker:

- ToolExecutor 事务外执行 (`TransactionSynchronizationManager.isActualTransactionActive()`)
- MySQL CAS 并发竞争 (两线程同时 transition)
- SSE `StepVerifier` 单终态
- Harness 真 Replay Provider (PLANNER + SUFFICIENCY + ANSWER)

---

## 7. 完成判定

| 维度 | 状态 |
|---|---|
| Replay IT Case A–E 实装 + 通过 | **已完成** |
| Replay Consistency 验证 | **已完成** |
| ProgressDetector Phase 0 bug 修复 | **已完成** |
| MySQL/Testcontainers 真 Bean Spring IT | 未完成 (CI Docker 拥有) |
| SSE Reactor IT | 未完成 (PR-7e.3) |

**PR-7e.2**: 已完成。
