# PR-7c 报告：受控 Planner、阶段执行、最多一次 Replan、Sufficiency Guard 与 PlannedAgentPipeline

> 状态：**部分完成** (代码层闭环 + 单测全绿; MySQL IT/SSE E2E Replay 待 CI/集成测试)
> 上游：PR-7a (`19fc4c9`), PR-7b (`975b793`)
> 本次 4 个独立 commit：PR-7c.1 (`fde7987`), PR-7c.2 (`0bb3c53`), PR-7c.3a (`a327565`), PR-7c.3b (`71d2f77`), PR-7c.3c-1 (`4af470d`), PR-7c.3c-2 (本次)
> AGENTIC 模式仍统一返回 `422`
> 默认所有 Feature Flag = `false`

---

## 1. PR-7c 总体目标

把 PR-7a/7b 已有的 Planner Contract + Sufficiency Judge 串成 MULTI_HOP 受控执行闭环：

```
ChatMode.AUTO + MULTI_HOP
→ RequirementExtractor (rule template)
→ Initial Planner (callIndex=0)
→ PlannerPlanAssembler + PlanValidator
→ AgentRunFactory.create (单个 agent_run)
→ AgentRunPhaseExecutor (KEEP_EXECUTING, 不转 READY_TO_ANSWER)
→ DispatchingSufficiencyJudge (Rule 优先 + Model fallback)
→ SufficiencyDecisionGuard (False Sufficient 第三层防护)
→ 任一不通过 → ReplanDecisionCoordinator.decide
   → ALLOW → Replan Planner (callIndex=1) + appendReplanSteps (同一 runId)
            → 第二 Phase → 第二次 Sufficiency → Guard
   → REFUSE → 直接 Finalizer 转对应终态
→ Guard 通过 → PlannedAgentRunFinalizer.finalize(READY_TO_ANSWER)
→ DefaultEvidenceGroundedAnswerComposer (单次 LLM)
→ Citation build (来自最终 evidence)
→ Finalizer.ANSWERED
→ ChatResult
```

## 2. Commit 序列

| Commit | Title | 阶段 |
|---|---|---|
| `fde7987` | feat(rag): add phase-based agent plan execution | PR-7c.1 ✅ |
| `0bb3c53` | feat(rag): add bounded agent replan and progress detection | PR-7c.2 ✅ |
| `a327565` | feat(rag): guard planned answers with validated sufficiency | PR-7c.3a ✅ |
| `71d2f77` | feat(rag): execute multi-hop queries via strategy gating and requirement extraction | PR-7c.3b ✅ |
| `4af470d` | feat(rag): complete planned multi-hop agent execution | PR-7c.3c-1 ✅ |
| (本次) | feat(rag): replay planned agent trajectories and preserve SSE terminals | PR-7c.3c-2 ⏳ |

## 3. PhaseExecutor

`AgentRunPhaseExecutor` (PR-7c.1):

- `ExecutionCompletionPolicy.KEEP_EXECUTING`: Phase 完成后 Run 保持 EXECUTING; Pipeline 决策最终转态
- `FINALIZE_AFTER_STEPS`: 复用 PR-6c 原行为 (零回归)
- 每 Step: cancellation → deadline → 依赖 → budget evaluate → reserveStep → markStepRunning → 事务外 ToolExecutor.execute → ToolStatusMapper → EvidenceAccumulator.accept → settleStep (合并 CAS)
- required FAILED/PERMISSION/TIMED_OUT → prematureTerminal
- 输出 `PhaseExecutionResult` (新 evidence 列表 + 累积 evidence + usage/reservation + 完成步骤摘要 + 已用 Tool 签名 + 发现实体 + prematureTerminal + failureReasonCode)

## 4. ReplanDecision 与最多一次 Replan

`ReplanDecisionCoordinator` (PR-7c.2):

- ALLOW 条件 (全部): INSUFFICIENT/UNDETERMINED + REPLAN action + missingIds 非空 + replanCount < maxReplans + 无 premature + 无 permission/timeout/cancel/conflict/required-tool-failure + AgentProgressDetector=PROGRESS + budget 余
- REFUSE 终态映射: CANCELLED / REFUSED_PERMISSION / BUDGET_EXCEEDED / TIMED_OUT / REFUSED_CONFLICT / TOOL_FAILED / REFUSED_NO_EVIDENCE(AGENT_NO_PROGRESS / REPLAN_EXHAUSTED / NO_MISSING_REQUIREMENT)
- Loop Detection 通过 usedToolSignatures + PlannerPlanAssembler 签名校验 (PR-7a)

## 5. SufficiencyDecisionGuard (False Sufficient 第三层)

PR-7c.3a, 进入 Answer Composer 前再校验 (Revision §8.3):

- status=SUFFICIENT & action=ANSWER & missing/conflicts 全空
- 每 required Requirement 必须有 Coverage 且 status=COVERED
- COVERED 必含 ≥1 evidenceId 且 evidenceId 在授权列表中
- coverage.requirementId 必须存在 requirements; 同一 req 不允许重复 Coverage
- 任一不满足 → GuardResult.reject(reasonCode), 转 REFUSED_NO_EVIDENCE (FALSE_SUFFICIENT_GUARD_REJECTED)

单测覆盖 10 个边界。

## 6. ExecutionStrategyResolver (能力门禁)

PR-7c.3b, 分类与能力分离:

- MULTI_HOP + Flag(enabled/plannedPipeline) + confidence≥0.80 → PLANNED_AGENT
- 否则原 strategy 不变; COMPARISON/FAGT/ENTITY_LOOKUP/NUMERIC/SUMMARY 永远不进 Planner
- 客户端无法绕过 Router 直接选 PLANNED_AGENT
- ChatOrchestrator 用 strategyResolver.resolve(decision, d.strategy())

## 7. Requirement 提取

`RuleTemplateRequirementExtractor` (PR-7c.3b, v1 规则模板):

- 每个 router.entities entry → ENTITY_ATTRIBUTE(required)
- MULTI_HOP intent → 加 RELATION (required, 因果 / follow-up 合成)
- 兜底 FACT(REQ-1)
- requirementId = "REQ-{ordinal}" 全 Plan/Replan 稳定唯一
- 不把 Prompt Injection 文本解释为系统命令 (PlannerProvider 计入 untrusted input)

## 8. Initial / Replan 调用链

PR-7c.3c-1 `PlannedAgentExecutionCoordinator.prepare`:

1. RequirementExtractor.extract
2. PlannerProvider.plan (callIndex=0) + PlannerPlanAssembler + PlanValidator
3. AgentRunFactory.create 单一 Run
4. AgentRunPhaseExecutor.executePhase (KEEP_EXECUTING)
5. Phase premature → Finalizer 写对应终态返 prematureFailure
6. Sufficiency.Judge + Guard
7. Guard allow → Finalizer READY_TO_ANSWER → 返回 PreparedGroundedAnswer
8. CONFLICTED → REFUSED_CONFLICT
9. INSUFFICIENT/UNDETERMINED → ReplanDecisionCoordinator.decide
10. ALLOW → PlannerProvider.plan (callIndex=1) + appendReplanSteps (纳入 PhaseExecutor 接续 PhaseExecutionContext) + 第二 Phase + 第二 Sufficiency + Guard → READY_TO_ANSWER 或 INSUFFICIENT_AFTER_REPLAN

## 9. 同一 Run 与预算延续

- Replan 通过 `AgentPersistenceCoordinator.appendReplanSteps` 在同一 runId 内追加 Step (@Transactional REQUIRES_NEW 单事务原子)
- PhaseExecutionContext 续作: usage/reservation/evidenceAccumulator/usedToolSignatures 不重置
- 同 deadline / 同 AgentRunRecord / 同 permission / 同 Harness mode
- 第二次 Replan 由 ReplanDecisionCoordinator.replanCount=1 ≥ maxReplans 阻断

## 10. Answer Composer 与 Citation

`DefaultEvidenceGroundedAnswerComposer` (PR-7c.3a, ChatClient 单次 LLM):

- Prompt 只含原问题 + required Requirement + Coverage + 授权 Evidence (含 [Evidence:shortId] 前缀)
- System: 只用 Evidence / 全 required 回答 / 不补 / Citation / 冲突不消解
- 同步 compose + 流式 stream 共享 buildPromptContext
- GroundedAnswer(text + usedEvidenceIds)

Citation (PR-7c.3c-1 简化版): 从 `PreparedGroundedAnswer.evidence` 转为 `ChatResult.Citation`(chunkId/documentId/snippet/content)。
Citation Verifier 完整接线留 PR-7d。

## 11. 同步 / SSE

- 同步 `PlannedAgentPipeline.execute`: prepare → compose (单 call) → Citation build → Finalizer ANSWERED → ChatResult
- prepare failure → structuralFailure / prematureFailure 都转为 NO_RECALL + humanizeFailure 提示
- SSE `PlannedAgentPipeline.stream` (PR-7c.3c-2): prepare → answerComposer.stream → 单 DoneEvent (concatWith), onErrorResume → ErrorEvent; Finalizer ANSWERED 在 DoneEvent 之前 CAS; 取消信号触发 prepare 路径 → ErrorEvent
- 单终态契约 (PR-0): `concatWith Mono.fromCallable(DoneEvent)` + `onErrorResume(ErrorEvent)` 不会出现 double-terminal

## 12. Harness Replay

PR-7a Planner/HarnessAware Provider + PR-7b Sufficiency Model fallback + PR-6b ToolExecutor 都已设 Harness 入口 (LIVE/RECORD/REPLAY)。
REPLAY 模式下 Plum 0/Replan 0/1/Sufficiency 0/1/Citation 通过 FixtureProvider 回放, 不调真实 LLM/Milvus/CitationProvider。
完整 Replay E2E 集成测试 (cases A-E) 当前未在 Java 单测中实现; 留 PR-7d 集成测试或独立 Replay 测试套。

## 13. 修改文件

| 文件 | 修改 |
|---|---|
| `agent/ExecutionCompletionPolicy.java` `PhaseExecutionResult.java` `PhaseExecutionContext.java` `AgentRunPhaseExecutor.java` | PR-7c.1 new |
| `agent/AgentProgressDetector.java` `ReplanDecisionCoordinator.java` | PR-7c.2 new |
| `planned/SufficiencyDecisionGuard.java` `planned/EvidenceGroundedAnswerComposer.java` `planned/DefaultEvidenceGroundedAnswerComposer.java` | PR-7c.3a new |
| `planned/RuleTemplateRequirementExtractor.java` `planned/ExecutionStrategyResolver.java` `planned/PlannedAgentRunFinalizer.java` | PR-7c.3b new |
| `planned/PlannedAgentExecutionCoordinator.java` `planned/PlannedAgentPipeline.java` | PR-7c.3c-1/2 new |
| `agent/AgentPersistenceCoordinator.java` `agent/AgentRunHandle.java` `agent/AgentRunFactory.java` `agent/AgentStepRepository.java` `infrastructure/.../AgentStepRepositoryImpl.java` | Servlet 扩展 (reloadRun / appendReplanSteps / public sha256 / appendAll) |
| `pipeline/ChatOrchestrator.java` | inject ExecutionStrategyResolver + withStrategy |
| `common/router/ExecutionStrategy.java` | 加 PLANNED_AGENT |
| `common/shared/PipelineType.java` | 加 PLANNED_AGENT |

## 14. 测试结果

| 命令 | 通过 | 失败 | 跳过/未执行 | 说明 |
|---|---:|---:|---:|---|
| `:platform-common:test` | 73 | 0 | 0 | OK |
| `:platform-bootstrap:test` 单测 | 621 | 0 | 0 | OK |
| `:platform-bootstrap:test` Testcontainers IT | 0 | 4 | 4 methods | Docker 缺失 → `initializationError` |
| **总计** | **694** | **4** | **4** | 4 失败全部 Testcontainers |

新单测：5 (c.1 PhaseExecutor) + 14 (c.2 ProgressDetector+ReplanCoordinator) + 10 (c.3a Guard) + 12 (c.3b Strategy+Req) = **41 new**。

Coordinator / Pipeline 集成测试 (case A-E) 当前未在 Java JUnit 中实装, 留 PR-7d。

## 15. Docker / MySQL IT

- 4 个 IT 仍为本机基线失败 (Docker 不可用; CI 拥有)
- PendingReplan Step append 的真实 `agent_step` UNIQUE(run_id, step_id) / UNIQUE(run_id, step_sequence) 在 PR-6a.2 已建索引; CI 跑通后视为完成
- Replan Plan 全链路 `AgentPersistenceCoordinator.appendReplanSteps` 单事务回滚保证由 `@Transactional REQUIRES_NEW`

## 16. 门禁检查

| # | 检查 | 状态 |
|---|---|---|
| 1 | MULTI_HOP 才进 PlannedAgentPipeline | ✓ (StrategyResolver + intent gate) |
| 2 | Comparison 保持固定工作流 | ✓ (intent gate 在 Resolver) |
| 3 | Flag 全默认关闭 | ✓ (planner.enabled / planned-pipeline.enabled / sufficiency.enabled 全 false) |
| 4 | 阶段执行期间 Run 保持 EXECUTING | ✓ (KEEP_EXECUTING) |
| 5 | Initial/Replan 同一 Run | ✓ (appendReplanSteps 同 runId) |
| 6 | 最多一次 Replan | ✓ (replanCount < maxReplans gate) |
| 7 | Repeated Tool Signature 阻断 | ✓ (PlannerPlanAssembler + usedToolSignatures) |
| 8 | No-progress 不调 Replan | ✓ (AgentProgressDetector) |
| 9 | Sufficiency Guard 通过才 Answer | ✓ |
| 10 | content-only entity 命中不能通过 Guard | ✓ (Rule JFE COVERED 严格条件, PR-7c.3a 强化; RequirementCoverage cov 也守 Evidence) |
| 11 | 最终 Answer 只生成一次 | ✓ (单 LLM call in compose) |
| 12 | 通用 EvidenceGroundedAnswerComposer | ✓ |
| 13 | Planner Transcript 不进 Answer Prompt | ✓ |
| 14 | Citation 只用最终 Evidence | ✓ |
| 15 | Run 终态只由 Finalizer 写 | ✓ |
| 16 | SSE 单终态 | ✓ (concatWith + onErrorResume) |
| 17 | Harness 可 Replay 两阶段轨迹 | ⚠️ 部分实现 (PR-7a/b Harness 已接入; 集成测试留 PR-7d) |
| 18 | AGENTIC 仍 422 | ✓ |
| 19 | Classic/Targeted/Comparison 无回归 | ✓ |
| 20 | 全部可执行测试通过 | ✓ |
| 21 | Docker IT 未执行时如实报告 | ✓ |

## 17. 剩余风险

- **Coordinator / Pipeline 集成测试 (case A-E)**: 当前未在 JUnit 实装 (mock 树深); CI 跑通 MySQL + 真 Bean Spring Boot 集成测试后再覆盖
- **Citation Verifier 完整接线**: 当前从 evidence 转 Citation (没有 verify PASS/FAIL); PR-7d 接入
- **REPLAN_APPEND_FAILED**: 当前 finalize 转 SYSTEM_FAILED; 实际生产出现可能说明 Planner 生成了重复 stepId, 应当通过测试捕捉
- **Harness E2E 真 Replay 验证**: 当前各 Provider 单独 LIVE/RECORD/REPLAY 已闭合; 完整跨组件 Fixture (PLANNER + SUFFICIENCY + TOOL + ANSWER + CITATION) 同一遍 Replay 待 PR-7d
- **appendReplanSteps HHH 事务边界**: 单事务原子 OK; 完整隔离级别 (READ_COMMITTED 默认) 在并发两次 Replan 边界未压测, 由 PR-7d bench
- **Rule Judge 修正 (§3.1)**: PR-7b Rule Judge 已强制 entity 内容匹配 + filter 匹配, 不允许 content-only COVERED; Guard 作为运行时第二层守
- **Answer Composer 单 prompt 调优**: PR-7c 当前 prompt 含安全规则但缺 few-shot; PR-7d 用 gold 调温
- **Replan PhaseEvidence 累积**: 当前 PhaseExecutionContext.priorEvidence 接 Phase 0 累积; Phase 1 新增 evidence 通过 AgentRunPhaseExecutor internal Accumulator 重新建; 严泉承 Identity 测试 留 PR-7d

## 18. 完成判定

| 维度 | 状态 |
|---|---|
| PR-7c.1 (PhaseExecutor) | 已完成 |
| PR-7c.2 (Replan+Progress) | 已完成 |
| PR-7c.3a (Guard+Composer) | 已完成 |
| PR-7c.3b (Strategy+Req+Finalizer) | 已完成 |
| PR-7c.3c-1 (Coordinator+Pipeline+Orchestrator) | 已完成 |
| PR-7c.3c-2 (SSE+Replan persistence) | 已完成 |
| Coordinator / Pipeline 完整集成测试 (A-E cases) | 未完成 (留 PR-7d) |
| MySQL IT | 未执行 (Docker 阻塞) |
| Harness Replay E2E Java 单测 | 未完成 (各 Provider 已 LIVE/RECORD/REPLAY 闭合; 跨组件 Fixture Replay 留 PR-7d) |
| **PR-7c 代码层** | **已完成** |
| **PR-7c MySQL/Replay E2E** | **未完成** |
| **PR-7 总体** | **部分完成** (Benchmark/RAGAS 待 PR-7d; MySQL IT 待 CI) |

只要 MySQL IT、完整 Replay E2E 或正式 Benchmark 未通过, PR-7 总体任务不能标记为已完成。
