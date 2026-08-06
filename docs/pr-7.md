# PR-7 报告：受控 Planner、Sufficiency Judge、最多一次 Replan、PlannedAgentPipeline 与 Benchmark

> 状态: PR-7 代码层 **已完成**; 工程验证 / 算法评测 **部分完成** (MySQL IT + Replay E2E + 人工 Gold Benchmark 待 CI / domain expert)
> 8 + N 个实现 commit; 见 §2
> AGENTIC 模式仍统一返回 `422`; 所有 Feature Flag 默认 `false`
> 不伪造任何 RAGAS / Answer Correctness / Faithfulness 数值 (PR-7d §13)

---

## 1. PR-7 总体架构

```
ChatMode.AUTO + MULTI_HOP
→ ExecutionStrategyResolver (能力门禁 + Router 基础分类)
→ PLANNED_AGENT
→ PlannedAgentPipeline (薄适配层)
→ PlannedAgentExecutionCoordinator
    ├── RuleTemplateRequirementExtractor (稳定 ID, 冻结)
    ├── PlannerProvider callIndex=0
    │   └── RuleTemplate / Model / HarnessAware (LIVE/RECORD/REPLAY)
    ├── PlannerPlanAssembler (Schema + Requirement ID + Tool signature + PlanValidator + remaining budget)
    ├── AgentRunFactory (单一 Run + Initial Steps; ROUTED→PLANNED→EXECUTING)
    ├── AgentRunPhaseExecutor (KEEP_EXECUTING)
    │   * cancellation gate → CANCELLED
    │   * deadline gate → TIMED_OUT
    │   * dependency check → SKIPPED_DEPENDENCY (REQUIRED_TOOL_FAILURE)
    │   * BudgetManager.evaluate → hard budget → BUDGET_EXCEEDED + Step SKIPPED_BUDGET
    │   * reserveStep + markStepRunning + ToolExecutor.execute (事务外)
    │   * EvidenceAccumulator + metadata.requirementIds 注入
    │   * settleStep (合并 run CAS + step terminal CAS)
    │   * required FAILED/PERMISSION/TIMED_OUT → premature Terminal
    ├── DispatchingSufficiencyJudge (Rule 优先 + Model fallback)
    │   ├── RuleSufficiencyJudge (entity/filter/version 严格匹配 + contentHash dedup + CONFLICT 检测)
    │   │   * RELATION/FOLLOW_UP 无法判定 → UNDETERMINED
    │   └── ModelSufficiencyJudge (ChatClient JSON, False Sufficient 三层防护)
    ├── SufficiencyDecisionGuard (False Sufficient 第三层)
    │   * 必含 ≥1 evidenceId, ID 必须在授权集
    │   * required Requirement 必须 COVERED, 无 DUPLICATE Coverage
    │   * 任一不满足 → REJECT → REFUSED_NO_EVIDENCE (FALSE_SUFFICIENT_GUARD_REJECTED)
    ├── ReplanDecisionCoordinator
    │   * 9 条 ALLOW 条件 + 9 类 REFUSE 终态映射
    │   * AgentProgressDetector (NO_PROGRESS → 不 Replan)
    │   * Loop Detection (历史 usedToolSignatures 单调增长)
    │   * 唯一一次 Replan (replanCount < maxReplans)
    ├── PlannerProvider callIndex=1 (Replan)
    │   * 同一 runId + appendReplanSteps (单事务原子)
    │   * sequence 续 max + 1
    ├── Phase 1 → 第二次 Sufficiency + Guard
    ├── PlannedAgentRunFinalizer (FinalizeOutcome CAS + idempotent/priority/conflict)
    └── Final Execution → EXECUTING → READY_TO_ANSWER
→ DefaultEvidenceGroundedAnswerComposer (单次 LLM, 安全 Prompt, Citation [Evidence:ID])
→ Citation build (从最终 evidence)
→ Finalizer ANSWERED
→ ChatResult (向后兼容 schema)
```

## 2. 实现 Commit 序列

| Commit | Title | 阶段 |
|---|---|---|
| `19fc4c9` | feat(rag): add structured planner contracts and harness provider | PR-7a |
| `975b793` | feat(rag): add rule-first evidence sufficiency evaluation | PR-7b |
| `fde7987` | feat(rag): add phase-based agent plan execution | PR-7c.1 |
| `0bb3c53` | feat(rag): add bounded agent replan and progress detection | PR-7c.2 |
| `a327565` | feat(rag): guard planned answers with validated sufficiency | PR-7c.3a |
| `71d2f77` | feat(rag): execute multi-hop queries via strategy gating and requirement extraction | PR-7c.3b |
| `4af470d` | feat(rag): complete planned multi-hop agent execution | PR-7c.3c-1 |
| `c1d7311` | feat(rag): replay planned agent trajectories and preserve SSE terminals | PR-7c.3c-2 |
| (本次) | test(rag): PR-7d Benchmark + dataset + Python evaluator + Sphinx report | PR-7d |

## 3. PR-7d Commit

```text
test(rag): add versioned planner and sufficiency benchmark
docs(rag): report planner ablations and define shadow rollout gates
```

包含:

- `eval/planner/schemas/planner_case.schema.json` (JSON Schema v1)
- `eval/planner/datasets/planner_benchmark_v1.seed.jsonl` (4 条示意 seed, **全部 `reviewStatus=candidate`**)
- `eval/planner/validate_dataset.py` (schema + cross-field 校验)
- `eval/planner/run_planner_eval.py` (Trajectory + Sufficiency + Plan 指标计算)
- `eval/planner/aggregate_report.py` (多 scenario 消融聚合 + Markdown 表)
- `eval/planner/test_planner_eval.py` (11 pytest 用例, 全绿)
- `eval/planner/README.md` (数据集使用指南 + 上线门禁)
- `docs/pr-7.md` (本报告)

## 4. 跨组件集成

- Coordinator / Pipeline / Orchestrator 三层在 PR-7c.3c 已接通 (代码层 verify)
- Pipeline / Sufficiency / Replan / Finalizer 代码路径 **完整闭环** (同步 + SSE 单终态)
- **未在 Java JUnit 中实装** Java 端跨组件 Spring Boot IT (mock 树深, 集成测试待 CI Docker 跑 MySQL + Real Bean)

## 5. Replay A–E (Java JUnit 状态)

| Case | 期望轨迹 | Java 集成测试状态 |
|---|---|---|
| A: Initial Sufficient | Planner 0 → Tool → Suff 0 SUFFICIENT → Guard PASS → Answer 0 → ANSWERED | ⏳ 待 Java IT |
| B: Replan Success | Planner 0 → INSUFFICIENT → Replan 1 → Tool → Suff 1 SUFFICIENT → ANSWERED | ⏳ 待 Java IT |
| C: Replan Still Insufficient | Planner 1 → INSUFFICIENT → REFUSED_NO_EVIDENCE (INSUFFICIENT_AFTER_REPLAN) | ⏳ 待 Java IT |
| D: Repeated Tool Signature | Planner Replan 输出已用 Signature → PLAN_REPEATED_TOOL_CALL → REFUSED_NO_EVIDENCE | ⏳ 待 Java IT |
| E: False Sufficient | Model Judge SUFFICIENT + 非 evidence → Guard REJECT → Answer 0 → REFUSED_NO_EVIDENCE | ⏳ 待 Java IT |

PR-7a/b 已实现的 SDK 单测间接覆盖各组件 behavior; 跨组件 Spring Boot IT 留给 CI 主线。

## 6. MySQL IT

- V13 / V14 迁移 + Cas + FK + Replan append 在 PR-6a/6b.3/7c.3c 已写出对应的 Java IT
  (含 `AgentRunJpaRepositoryIT` / `AgentStepJpaRepositoryIT`, 各 8 用例)
- 本机 4 个 Testcontainers IT 仍报 `initializationError` (Docker 阻塞)
- CI 跑通后视为完成

## 7. Benchmark 数据

- Schema v1 (PR-7d §8.3)
- 切片 ≥80 推荐目标 (initial_sufficient=20, document_fetch_needed=10, semantic_metadata_combo=10,
  replan_success=15, replan_still_insufficient=10, no_answer_refuse=10, permission_denied=5,
  evidence_conflict=5, budget_timeout_edge=5)
- **金标需 domain expert 人工审核**; 当前 seed 仅 4 条 `candidate`, 不允许进入指标
- `validate_dataset.py --require-reviewed` 严格模式守住未审核数据

## 8. Planner 指标 (未执行)

| 指标 | 状态 | 备注 |
|---|---|---|
| Plan Schema Valid Rate | NOT_EXECUTED | 待真实 LLM Planner + reviewed dataset |
| PlanValidator Pass Rate | NOT_EXECUTED | 同上 |
| Tool Selection Accuracy | NOT_EXECUTED | |
| Repeated Tool Signature Rate | NOT_EXECUTED | |
| Initial Plan Acceptable Match | NOT_EXECUTED | 需 reviewed AcceptableInitialPlans |
| Planner Calls per Task | NOT_EXECUTED | 同上 |

`run_planner_eval.py` 已支持以上指标 (从 actuals.jsonl 读取); 数据集 / actuals 未补齐前不输出。

## 9. Sufficiency 指标 (未执行)

| 指标 | 状态 |
|---|---|
| Sufficiency Accuracy / Precision / Recall / F1 | NOT_EXECUTED |
| **False Sufficient Rate** (上线门禁 ≤ 2%) | NOT_EXECUTED |
| Requirement-level Coverage F1 | NOT_EXECUTED |
| Conflict Detection Accuracy | NOT_EXECUTED |
| Replan Trigger Precision / Recall | NOT_EXECUTED |

PR-7b 代码层: 3 层 False Sufficient 防护

1. `RequirementCoverage` ctor (COVERED 必须 ≥1 evidenceId)
2. `ModelSufficiencyJudge` (引用未知 evId→拒; CONFLICTED evIds<2→降级)
3. `SufficiencyDecisionGuard` (Coverage 完整性 + 授权集)

## 10. Trajectory 指标 (未执行)

| 指标 | 状态 |
|---|---|
| Trajectory Success Rate | NOT_EXECUTED |
| Initial Plan Success Rate | NOT_EXECUTED |
| Replan Trigger Rate / Replan Success Rate | NOT_EXECUTED |
| No-progress Rate | NOT_EXECUTED |
| Loop Detection Rate | NOT_EXECUTED |
| Invalid Replan Rate | NOT_EXECUTED |
| Budget Exceeded Rate | NOT_EXECUTED |
| Permission Refusal Accuracy | NOT_EXECUTED |
| Non-terminal Step Residue Rate | NOT_EXECUTED |

## 11. 端到端指标 (未执行)

| 指标 | 状态 | 备注 |
|---|---|---|
| Gold Evidence Recall | NOT_EXECUTED | 需 reviewed gold |
| Gold Document Recall | NOT_EXECUTED | 需 reviewed gold |
| Answer Correctness | NOT_EXECUTED | RAGAS / judge; **不伪造** |
| Faithfulness | NOT_EXECUTED | RAGAS; **不伪造** |
| Citation Precision / Recall | NOT_EXECUTED | 需真实 Citation Verifier 接通 |
| Unsupported Claim Rate | NOT_EXECUTED | RAGAS; **不伪造** |
| P50 / P95 Latency | NOT_EXECUTED | 需真实后端 |
| LLM Calls per Task | NOT_EXECUTED | actuals |
| Tokens / Cost per Task | NOT_EXECUTED | 真实 LLM |

`run_planner_eval.py` 对 `answerCorrectness / faithfulness / citationPrecision / etc.` 显式 `None` 即 `NOT_EXECUTED`, **不允许给 0 占位**.

## 12. 消融 (未执行)

| Scenario | 配置 | 状态 |
|---|---|---|
| A0 Classic RAG | Router/Planner off | NOT_EXECUTED |
| A1 Router RAG | Router on / Planner off | NOT_EXECUTED |
| A2 Planner only | Planner on / Sufficiency off / Replan off | NOT_EXECUTED (**生产代码不可绕 Sufficiency**) |
| A3 Planner + Rule Suff | Sufficiency rule only | NOT_EXECUTED |
| A4 Planner + Rule/Model Suff | Model fallback on / Replan off | NOT_EXECUTED |
| A5 Full PR-7 | Replan on | NOT_EXECUTED |
| A6 More Tool Calls Control | 等量 Tool budget 不用 Planner | NOT_EXECUTED |
| A7 Oracle Plan | Gold Plan | NOT_EXECUTED |

`aggregate_report.py` 已实装聚合 + Markdown 表渲染 (pytest 验证); 数据未补齐前报告为空表。

## 13. More Tool Calls Control

未实装 — 待 PR-7d 后续评测阶段做控制变量对比 (是否 Planner 价值只来自更多 Tool 调用)。

## 14. 错误归因

PR-7d §16 给出 18 类归因标签; 当前 Benchmark 未跑无法分布。

## 15. 安全门禁 (建议硬阈值)

```
PlanValidator Pass Rate             >= 99%
Illegal Tool Execution Rate         = 0
Cross-tenant Evidence Leakage       = 0
Repeated Tool Loop Escape Rate      = 0
Non-terminal Step Residue Rate      = 0
SSE Multiple Terminal Rate          = 0
False Sufficient Rate               <= 2%
Citation Precision                  >= 95%
```

PR-7 代码层通过 ctor 硬约束 + Guard 兜底守 False Sufficient 第一/三层；Cross-tenant 由
EvidenceAccumulator ACL 终检 + Sufficiency Input 授权集守护。**真实评测前不报通过**。

## 16. 成本与延迟

未跑 (需真实后端 + 真 LLM)。

## 17. PR-8 Go/No-Go

**NO-GO** (当前). 必要条件未达成:

- ❌ MySQL IT 实际通过 (Docker)
- ❌ Replay A–E Java 集成测试通过
- ❌ Benchmark 人工 Gold 数据
- ❌ False Sufficient Rate 实测 < 2%
- ❌ Citation Precision ≥ 95%
- ❌ Planner 对 Gold Evidence Recall 增益实测
- ❌ P95 / 成本实测

PR-7 代码层闭环 + 单测全绿, 不能自动 Go。完成上述评测后重判。

## 18. 剩余风险

- **MySQL IT**: 4 失败全部 Testcontainers 阻塞 — CI 拥有
- **Java A–E Replay**: 当前未在 JUnit 实装 (各 Provider 单测覆盖组件契约; 跨组件集成留 CI 真实 Bean)
- **appendReplanSteps Read-Committed 边界**: 单事务原子 OK; 并发两次 Replan 极端边界未压测
- **Citation Verifier 未真实接通**: 当前从 evidence 简化转换; `citation_verify` Provider 未调
- **Answer Composer Prompt few-shot 缺**: Safe rules OK; 评测时可用 premium 模型提示调优
- **数据集种子未审核**: 4 条示意 seed, 必须人工补 ≥80 + review
- **多 Planner Provider 选择**: 当前 StrategyResolver + PlannerProvider 注入固定 (RuleTemplate 默认); 真实 Model Provider 上线时需新 Cache 路径测试
- **Comparison 工作流不可被 Planner 替代**: 已在 StrategyResolver intent gate 守 (intent != MULTI_HOP 直接返回保原 strategy)

## 19. 测试结果

| 命令 | 通过 | 失败 | 跳过/未执行 | 说明 |
|---|---:|---:|---:|---|
| `:platform-common:test` | 73 | 0 | 0 | OK |
| `:platform-bootstrap:test` 单测 | 621 | 0 | 0 | OK |
| `:platform-bootstrap:test` Testcontainers IT | 0 | 4 | 4 methods | Docker 缺失 → `initializationError` |
| `python -m pytest eval/planner` | 11 | 0 | 0 | OK (DS validator + evaluator + aggregator) |
| **总计** | **705** | **4** | **4** | 4 失败全部 Testcontainers 基线 |

## 20. 完成判定

| 维度 | 状态 |
|---|---|
| PR-7 代码层 (Planner Contract + Sufficiency + Phase + Pipeline) | **已完成** |
| PR-7 工程验证 (MySQL IT + Replay A–E Java IT) | **未完成** (Docker 阻塞, Java IT 未实装) |
| PR-7 算法评测 (人工 Gold + Benchmark + 消融) | **未完成** (dataset 仅 seed 4 case) |
| PR-7 Benchmark 工具 (schema + validator + evaluator + aggregator) | **已完成** (代码 + pytest 验证) |
| PR-7 总体 | **部分完成** |
| **PR-8 (Shadow)** | **NO-GO** (硬门禁未全部实测通过) |

不伪造任何指标; CI 跑通 MySQL IT + domain expert 补 Gold + 真实 LLM 跑 RAGAS 后才能重判 Go/No-Go.
