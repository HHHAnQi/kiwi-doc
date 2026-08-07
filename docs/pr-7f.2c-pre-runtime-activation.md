# PR-7f.2c-pre: PlannedAgentPipeline Runtime Activation

> 状态：**Activation gate 接线完成**, 默认 `false` 保持安全, 评测 Runner live mode 不再
> 因 `RUNTIME_NO_STRATEGY_TRACE` 默认判 NOT_EXECUTED。未修改 Planner / Executor / Tool /
> Sufficiency / Dataset / Eval 任何业务逻辑 —— 仅 (a) 注入激活开关 与 (b) 暴露 trace 字段。

---

## 1. 背景与目标

PR-7f.2b.3 runner adapter 接好了 REST 调用路径, 但实际运行被三处 gate 阻断:

1. `ExecutionStrategyResolver` (生产 1 参 ctor) 把 `plannedPipelineEnabled` 硬编码 `false` —
   不读任何 `@ConfigurationProperties`。
2. `PlannerProperties` javadoc 提到 `rag.agent.planned-pipeline.enabled` 但 Java 字段并不存在。
3. `ChatResponse` 不返回 `pipelineType` — 让 runner adapter 看见 `RUNTIME_NO_STRATEGY_TRACE`
   (无依据判定 PLANNED_AGENT 是否生效, 只能 fallback `NOT_EXECUTED`)。

本 PR 关闭这 3 处, **不动 Agent 能力本身**。

---

## 2. 文件清单

| 文件 | 改动 |
|---|---|
| `application/chat/planner/PlannerProperties.java` | 新增字段 `plannedPipelineEnabled=false`, getter/setter。`@ConfigurationProperties(prefix="rag.agent.planner")` 现绑定 `rag.agent.planner.planned-pipeline-enabled`。 |
| `application/chat/planned/ExecutionStrategyResolver.java` | 1 参 ctor 改为读 `PlannerProperties.isPlannedPipelineEnabled()` (默认 false, zero-diff); 2 参 ctor override 保留 (测试 API 不破坏); 新方法 `isPlannedPipelineEnabled()` 统一决策门面。 |
| `application/chat/command/ChatResult.java` | 新增可空尾字段 `PipelineType pipelineType`; 新增 `withPipelineType(PipelineType)` 用于 orchestrator 出口装饰; 保留既有 4/5/6 字段兼容 ctor。 |
| `application/chat/pipeline/ChatOrchestrator.java` | `execute(...)` 出口处 `result.withPipelineType(ctx.effectivePipeline())` — 一处装饰, 不改各 pipeline 实现。 |
| `interfaces/rest/dto/ChatResponse.java` | 新增 `String pipelineType` 字段 (Jackson SNAKE_CASE → `pipeline_type`); `from(ChatResult, boolean)` 透传。 |
| `test/.../planned/ExecutionStrategyResolverTest.java` | 新增 3 测试: 单参 ctor 读字段 (true / default false), 2 参 ctor override 胜出。 |
| `test/.../planned/PlannedAgentActivationSmokeTest.java` | 新增: 4 测试覆盖 MULTI_HOP+flag→PLANNED_AGENT, flag=false zero-diff, 低置信 / 非 MULTI_HOP 不升级。 |
| `test/.../pipeline/ChatOrchestratorTest.java` | 2 测试由 `isSameAs(stub)` 升级为内容相等 + `pipelineType=CLASSIC_RAG` 契约校验 (orchestrator 出口装饰改变 instance)。 |
| `eval/agentic/scripts/agentic_runner.py` | `STRATEGY_TRACE_KEYS` 加 `pipeline_type` / `execution_strategy` 与 snake_case 别名。 |
| `eval/agentic/tests/test_runner_adapter.py` | 扩展 strategy 提取测试覆盖 snake_case; 新增 PLANNED_AGENT → executed=True 契约测试。 |
| `docs/pr-7f.2c-pre-runtime-activation.md` | 本文档。 |

---

## 3. 激活 gate 真值表

`resolve(...)` 升级为 `PLANNED_AGENT` 须同时满足以下全部条件:

| 条件 | 在哪里设 | 默认 |
|---|---|---|
| `rag.router.enabled=true` | RouterProperties (已有) | `false` |
| `rag.agent.planner.enabled=true` | PlannerProperties.enabled | `false` |
| `rag.agent.planner.planned-pipeline-enabled=true` | PlannerProperties.plannedPipelineEnabled (**本 PR 新增**) | `false` |
| `RouterDecision.intent == MULTI_HOP` | RuleBasedTaskRouter | 运行时 |
| `RouterDecision.confidence >= rag.agent.planner.min-router-confidence` | PlannerProperties.minRouterConfidence | `0.80` |

任一不满足 → 原 strategy 保留 (zero-diff)。COMPARISON / FACT / ENTITY_LOOKUP / 低置信都不会升级。

---

## 4. `ChatResponse.pipeline_type` 契约

| 场景 | `pipeline_type` |
|---|---|
| mode=AUTO, flags=false | `CLASSIC_RAG` |
| mode=RAG (硬保留) | `CLASSIC_RAG` |
| mode=AUTO, Router=MULTI_HOP, flags 全开 | `PLANNED_AGENT` |
| Router 异常 fallback | `CLASSIC_RAG` ( Orchestrator fail-closed ) |
| Pipeline 抛异常 | 不返回 (`DomainException` 走 GlobalExceptionHandler) |

实现上只在 `ChatResult.withPipelineType(null)` 时跳过分配, 不影响其它字段。
Jackson `NON_NULL` (应用全局 strategy) 保证 null 字段不序列化, 历史客户端 schema 不变。

---

## 5. Runner adapter 行为修正

PR-7f.2b.3 文档的三个 NOT_EXECUTED reason:

| 旧 reason | 新行为 |
|---|---|
| `RUNTIME_NO_STRATEGY_TRACE` | **不再出现**: ChatResponse 自带 `pipeline_type` |
| `RUNTIME_NOT_PLANNED_AGENT` | 保留: flag 关时 runtime 返 `CLASSIC_RAG`, runner 据实标记 NOT_EXECUTED |
| 其它非 2xx / unreachable | 不变 |

聚合 NOT_EXECUTED 政策不变: `executed=false` 的记录 metrics 仍返 `None`。

---

## 6. 测试与验证

```
./gradlew :platform-common:test :platform-bootstrap:test
```

- `platform-common`: 通过 (未触及此模块业务逻辑)
- `platform-bootstrap`: 通过; 含
  - 新增 `PlannedAgentActivationSmokeTest` (4 测试)
  - 新增 `ExecutionStrategyResolverTest` 3 测试 (property 注入路径)
  - `ChatOrchestratorTest` 2 测试已对齐 `pipeline_type` 契约
  - 全量 621+ 测试零回归

```
python3 -m pytest eval/agentic/tests/ -q
```

→ 75 passed (含 runner adapter 新增 contract 测试)。

---

## 7. 验收对照 (用户 PR 描述)

| 验收 | 状态 |
|---|---|
| 默认配置仍关闭 (`planned-pipeline-enabled` 默认 false, flag 关时 zero-diff) | ✓ |
| 测试开启后可执行 (SmokeTest 在 mock orchestrator 中端到端执行 PlannedAgentPipeline) | ✓ |
| Runner live mode 不再返 `RUNTIME_NO_STRATEGY_TRACE` | ✓ (字段 `pipeline_type` 已暴露; adapter 命中并据实分类) |
| 新增 Tool / 改 Planner / Executor / Sufficiency / Dataset / Eval | 全部未触及 |
| 不破坏 Runtime 架构 (添加字段 / 添加 @ConfigurationProperties 字段; 无新增 Pipeline、无新 Tool) | ✓ |

---

## 8. 后置 (不属本 PR, 不进入下一步)

- 人工填 pilot20 的 36 个 FILL_* 标记 (PR-7f.2b.3 已 block)
- Spring `bootRun` + 真实 LLM 下端到端跑 MULTI_HOP case → benchmark
- 历史 SSE `done` 事件是否要同步暴露 `pipeline_type` (SSE 事件目前不变; 当前只在同步 JSON 路径装饰, 已满足 runner adapter 需求)
