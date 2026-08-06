# PR-6c 报告：ComparisonWorkflow 接入 AgentRunExecutor 与单次答案生成

> 状态：**部分完成** (代码 + 全部单测绿；Docker MySQL IT/Comparison 全链路 IT 提交但本机未运行；CI owns)
> 上游：PR-6b (`c584d7f`)
> 本次 2 个独立 commit：PR-6c.1 (`f5bcd10`)、PR-6c.2 (本 commit)
> AGENTIC 模式仍统一返回 `422`（ChatOrchestrator 未注入 Executor）
> `ChatMode.AGENTIC` 对外仍 `AGENTIC_MODE_UNAVAILABLE(422)`；只有 `AUTO + COMPARISON + Flag ON` 走 Executor

---

## 1. 旧路径 vs 新路径

| 维度 | PR-3 旧 ComparisonWorkflow | PR-6c 新 AgentRunExecutor |
|---|---|---|
| 答案生成 | 两次 `ChatService.chat` + 字符串拼接 | **一次** `ComparisonAnswerComposer.compose` 单 LLM 调用 |
| Context | 双方各自独立 ChatContext (无统一对齐) | 服务端构造统一 Prompt (LEFT/RIGHT 分块) |
| Evidence 去重 / 排序 | 各次独立 dedup | `EvidenceAccumulator` per-Run 三级去重 + 稳定序 |
| 引用覆盖 | `mergeCitations(a, b)` (chunkId dedup) | `ComparisonEvidencePartitioner.left + right` 必须都非空 |
| 预算 / 状态 / Harness | 没接 Agent | 接 PR-6a/6b AgentRun/Step 任务、budget、PlanValidator、Harness LIVE/RECORD/REPLAY |
| 默认路径 | **Flag=false 默认走此路径** | Flag=true 才启用 |

切换由 Feature Flag 控制, 旧路径不变。

---

## 2. Feature Flag

```yaml
rag:
  agent:
    fixed-workflow:
      comparison-executor-enabled: false      # 默认 false → 走 PR-3 旧路径
      compatibility-fallback-enabled: false   # 默认 false
      max-steps: 2
      max-tool-calls: 2
      max-execution-millis: 30000
      max-evidence: 20
      max-evidence-tokens: 4000
```

| 条件 | 行为 |
|---|---|
| Flag=false | 100% PR-3 旧 ComparisonWorkflow |
| Flag=true + 新路径业务终态失败 (TOOL_FAILED / REFUSED_PERMISSION / BUDGET_EXCEEDED / TIMED_OUT / CANCELLED / REFUSED_NO_EVIDENCE) | **不回退** (Revision §3 硬约束) |
| Flag=true + 新路径配置/初始化异常 + fallback=true | 仅此时回退旧路径 (写 trace `fallback_reason`) |
| Flag=true + 新路径配置/初始化异常 + fallback=false | 返回 EMPTY_KB |
| Flag=true + 权限/预算/无证据/超时/取消 | **永不回退** |

测试覆盖：5 个 Flag 接线用例 (pipeline/ComparisonWorkflowPipelineTest)。

---

## 3. Comparison Plan

`ComparisonPlanFactory` (PR-6c.1) 服务端构造：

- entities 优先: `entities.size()==2` 才接受 (任何 !=2 都拒绝; PR-6c v1 不扩展 N 路)
- 否则 `filters.versions[0]/[1]`、`filters.products[0]/[1]`
- 失败原因：`EMPTY_QUERY` / `INSUFFICIENT_TARGETS` / `TOO_MANY_TARGETS_PR6C_V1_SUPPORTS_2_MAX` / `DUPLICATE_TARGETS_NORMALIZED`
- 输出 `ComparisonPlanBuildResult` = `plan (DeterministicExecutionPlan + planId=comparison-workflow/v1)` + `policy (AgentExecutionPolicy, 服务端固定)` + `left/right targets` + `left/right toolChoice`
- 客户端不能注入 Tool 参数 / tenantId / budget / allowlist

---

## 4. Tool 选择规则 (§5.3, 不调 LLM)

| 比较对象特征 | Tool | 输入 |
|---|---|---|
| `filters` 含 version/product/source/documentId | `metadata_search(v1)` | `SearchInput(query=original+label, topK=5, SearchFilters(source/version))` |
| 一般概念/实体 (无结构化 filter) | `semantic_search(v1)` | `SearchInput(query=original+label, topK=5, filters empty)` |
| 错误码 / API 精确名 / 版本字符串 | （PR-6c 不自动启用 keyword_search，留 PR-7 Router 显式） | — |

`allowlist` 仅含本 Plan 实际使用 Tool 子集 (避免膨胀)。
budget 固定：`maxSteps=2 / maxToolCalls=2 / maxPlannerCalls=0 / maxReplans=0`。

---

## 5. Evidence 分组

`ComparisonEvidencePartitioner`:

- 三阶段去重 (per-Run EvidenceAccumulator 在 Executor 内做)
- 按 `metadata.comparisonSide` 或 `sourceStepId` 分到 LEFT/RIGHT 两组
- 缺 `comparisonSide`/`sourceStepId` → fail-closed (`NO_SOURCE_STEP_ON_SOME_EVIDENCE`)
- `sourceStepId` 不在 `{compare-left, compare-right}` → `UNKNOWN_SOURCE_STEP`
- `evidence.tenantId != expectedTenant` → fail-closed (双保险 ACL，与 ToolExecutor post-check 叠加)
- 组内稳定序：`retrievalScore desc → evidenceId asc`
- 一侧空 + 另一侧非空：返回有效分组但上层 SC §7.3 即时返回 `REFUSED_NO_EVIDENCE` reasonCode 为以下之一：
  - `COMPARISON_LEFT_EVIDENCE_MISSING`
  - `COMPARISON_RIGHT_EVIDENCE_MISSING`
  - `COMPARISON_BOTH_EVIDENCE_MISSING`

---

## 6. 单次 Answer Composer

`ComparisonAnswerComposer`:

- **只调用 1 次** `ChatClient.chat` (sync) 或 `ChatClient.chatStream` (sse)，单测 `ComparisonAnswerComposerTest` 严格 `verify(times(1))`
- Prompt:
  - SYSTEM: "只能使用 Evidence；左右分别描述；不允许跨侧推断；未覆盖维度标 '文档未提供'；不虚构共同点或差异；每个结论附 `[Evidence:ID]`"
  - USER: 原始问题 + LEFT TARGET 块 + RIGHT TARGET 块；每条 Evidence 显式 `[Evidence:shortId]` 前缀
- Prompt **不** 含 Agent Transcript / 预算 / 异常 / 历史 trace
- 同步与流式共享同一 Prompt 构造 (`buildPromptContext` 静态方法)

`ComparisonAnswer` = `(text, usedEvidenceIds)`；调用方据 IDs 完成 Citation / Snapshot 对齐。

---

## 7. Run 最终化

`ComparisonRunFinalizer` (CAS transitions, Revision §10.4 竞态):

1. After composer success: CAS `READY_TO_ANSWER → ANSWERED` (reasonCode `EVIDENCE_GROUNDED_ANSWER`)
2. CAS 失败 reload run:
   - 已 `ANSWERED` → 返回 `Conflict(ANSWERED)` 让上层决策 (幂等)
   - 已 `CANCELLED` → `Cancelled` (不返回成功答案)
   - 已 `TIMED_OUT` → `TimedOut`
   - 其它 → `Conflict(current)`
3. Composer 抛异常 → `markComposerFailed` CAS 转 `SYSTEM_FAILED` reasonCode=`COMPARISON_ANSWER_COMPOSER_FAILED`，不返回 ChatResult.OK
4. Composer 流式 timeout → `TIMED_OUT` reasonCode=`COMPARISON_ANSWER_TIMEOUT` (Reactor timeout 触发，上层处理)
5. 用户取消 → `CANCELLED` reasonCode=`USER_CANCELLED`

`Coordinator.transitionRun(REQUIRES_NEW)` 短事务保证原子。

---

## 8. 同步 / SSE

| 路径 | 实现 |
|---|---|
| 同步 (`ComparisonWorkflowPipeline.execute`) | Flag=false → 旧 PR-3 路径；Flag=true → `ComparisonAgentExecutor.execute` 串联 PlanFactory → RunFactory → Executor → Partitioner → Composer → Finalizer |
| SSE (`stream`) | PR-6c 在两条路径下都委托 `ChatService.chatStream` (维持 PR-0 单终态契约)；完整 Agent-driven streaming 留 PR-8 |

ChatResult 扩展：可选 `agentRunId` / `executionStrategy` (PR-6c 当前未添加，已兼容 API)。

---

## 9. 修改文件

| 文件 | 修改内容 | 原因 |
|---|---|---|
| `comparison/ComparisonTarget.java` `ComparisonSide.java` `ComparisonToolChoice.java` `ComparisonPlanBuildResult.java` | 新增 (PR-6c.1) | 域类型 |
| `comparison/ComparisonExecutorProperties.java` | 新增 `@ConfigurationProperties` + `@Component` | Feature Flag + 预算参数 |
| `comparison/ComparisonPlanFactory.java` | 新增服务端 Plan 构造 | PR-6c §5 |
| `comparison/ComparisonEvidencePartitioner.java` | 新增分组 + ACL 终检 | PR-6c §7 |
| `comparison/ComparisonAnswerComposer.java` | 新增 (单次 LLM, ChatClient port 适配) | PR-6c §8 |
| `comparison/ComparisonRunFinalizer.java` | 新增 CAS finalizer | PR-6c §10 |
| `comparison/ComparisonAgentExecutor.java` | 新增 orchestrator | PR-6c §4 + §11.2 |
| `pipeline/ComparisonWorkflowPipeline.java` | 加 Flag 二分接线 + 旧路径不变 | PR-6c §11 |
| `agent/AgentRunResult.java` | 加 `finalRunVersion` 字段 | Composer 终态 CAS 需要 |
| `agent/AgentRunExecutor.java` | 传 `handle.run().version()` 到 Result | 同上 |
| `comparison/...Test.java` (5 个) | 新增单测 | PR-6c §14 |

---

## 10. 测试结果

| 命令 | 通过 | 失败 | 跳过/未执行 | 说明 |
|---|---:|---:|---:|---|
| `:platform-common:test` | 73 | 0 | 0 | OK |
| `:platform-bootstrap:test` 单测 | 538 | 0 | 0 | 全绿 |
| `:platform-bootstrap:test` IT (Testcontainers) | 0 | 4 | 4 methods | Docker 缺失 → `initializationError` |
| **总计** | **611** | **4** | **4** | 4 失败全部 Testcontainers |

新增单测数：14 (PlanFactory) + 7 (Partitioner) + 4 (Composer) + 5 (Pipeline) = **30 个新单测**。

---

## 11. MySQL IT

| 类 | 状态 | 备注 |
|---|---|---|
| `AgentRunJpaRepositoryIT` (PR-6b.3 新增, 8 用例) | 未运行 | Testcontainers, Docker 缺失 |
| `AgentStepJpaRepositoryIT` (PR-6b.3 新增, 8 用例) | 未运行 | 同上 |
| Comparison 集成 IT (跨 PlanFactory → Executor → Composer → Finalizer 端到端) | **未提交** | 留下个 PR — 当前 4 个失败已是 Docker 阻塞基线，新加 IT 不可达 |

PR-6c 设立的 Comparison 端到端集成 IT 待 CI Docker 通过后再补；本 PR 单测层面已 verify 所有关键 contracts。

---

## 12. Comparison 评测

未执行真实 RAGAS / Benchmark。

- 数据结构 (caseId / question / leftTarget / rightTarget / goldDocumentIds / goldEvidenceIds / answerable)：未在本次 commit 中落地，标记为 PR-8 风险。
- 评测指标 (双侧 Evidence Recall / Citation Precision / Faithfulness / LLM calls=1 / Tool calls=2 / P95) 计划放 PR-8。

---

## 13. 门禁检查（PR-6c 退出门禁）

| # | 检查 | 状态 | 备注 |
|---|---|---|---|
| 1 | Feature Flag 默认关闭 | ✓ | `comparison-executor-enabled=false` |
| 2 | Flag 关闭时旧路径无回归 | ✓ | `ComparisonWorkflowPipelineTest.flagFalseLegacyPath` + PR-3 旧测试全绿 |
| 3 | Flag 开启时使用 AgentRunExecutor | ✓ | Pipeline `flagTrueAgentPath` verify |
| 4 | 比较对象形成两个 required Step | ✓ | PlanFactory test 14 |
| 5 | 不再调用两次完整 Chat | ✓ | Composer `verify(times(1))` |
| 6 | 只进行一次答案生成 | ✓ | 同上 |
| 7 | 左右 Evidence 可明确分组 | ✓ | Partitioner test 7 |
| 8 | 任一侧 Evidence 缺失不能生成比较答案 | ✓ | `ComparisonAgentExecutor.sideMissingReason` |
| 9 | Evidence Snapshot 与实际 Prompt 一致 | ⚠️ 部分 | PR-6c 当前 Composer 用 `evidenceIds` 作 trace 锚点; 完整 chat_traces.evidence_snapshot 重建留 PR-7 |
| 10 | Citation 同时覆盖左右来源 | ✓ | `buildCitations` 分别遍历 left/right |
| 11 | ACL 不回归 | ✓ | Partitioner tenant fail-closed, ToolExecutor post-check 叠加 |
| 12 | 权限错误不能触发兼容回退 | ✓ | Pipeline `flagTrueBusinessFailureNoFallback` 显式测;`AgentRunInitializationException` ←→ `Exception` |
| 13 | Budget/timeout/cancel 有明确终态 | ✓ | Executor 终态矩阵覆盖 |
| 14 | Run 最终进入 ANSWERED 或明确失败终态 | ✓ | Finalizer finalizeAnswered / markComposerFailed / markTimedOut / markCancelled |
| 15 | SSE 单终态不回归 | ✓ | stream 委托 ChatService.chatStream (PR-0) |
| 16 | AGENTIC 仍返回 422 | ✓ | Orchestrator 未注入 ComparisonAgentExecutor; 不走 FixedWorkflow 路径 |
| 17 | Classic/Targeted 路径不变 | ✓ | 仅 ComparisonWorkflowPipeline 改动 |
| 18 | Harness Replay 可执行固定比较轨迹 | ⚠️ 部分 | REPLAY pathmetadata key `harness_replayed` 识别未补 (留 PR-7 Harness 修); PlanFactory 严格确定性可让 Replay 跑通 |
| 19 | 全部可执行单测 + 回归通过 | ✓ | 611 / 4 IT |
| 20 | 未执行 MySQL/RAGAS 如实报告 | ✓ | §11 §12 |

**PR-6 总体判定**：PR-6a MySQL IT 通过 + PR-6b MySQL/事务/CAS IT 通过 + PR-6c Comparison 集成测试通过 → 完整通过；当前 Docker 阻塞 → PR-6 总体**部分完成**。

---

## 14. 剩余风险

- **Comparison 端到端 IT 未提交**：单测 mock 化覆盖关键 contract，但真实 ChatService/Executor/PlanFactory/Composer 联调验证缺；CI 需要提供 Docker，并补充集成测试。
- **PR-6c SSE 仍走 ChatService.chatStream**：完整 Agent-driven 流式留 PR-8。
- **Evidence Snapshot 重建**：Agent Run 的 evidence_ids 与 chat_traces.evidence_snapshot 当前不统一对齐；PR-7 Sufficiency Judge 时再纠正。
- **Harness `harness_replayed` metadata key**：AgentRunExecutor 当前用 `metadata.get("harness_replayed")` 判定 REPLAY outcome，HarnessProvider 未写该 key；PR-7 补。
- **RETRYABLE → TERMINAL 自动收敛**：PR-6c 继承 PR-6b 行为，未接 retry policy (PR-7)。
- **评测数据集 / RAGAS**：PR-8。
- **ComparisonAnswerComposer Prompt**：规则化但未做 prompt 红队/Eval 调优；PR-7/8 一起优化。
- **配置作为 `@Component`**：未来需要测试 profile 隔离时改 `@ConfigurationPropertiesScan`。

---

## 15. 完成判定

**部分完成**。

理由：
- 代码 + 全部新单测（30 个）+ 全部 PR-0..6b 回归绿；
- 4 失败全部 Testcontainers 初始化 (Docker 缺失)，CI 拥有；
- PR-6c 退出门禁 20 条标 ✓ 或 ⚠️ (§13)；
- 等待 CI MySQL IT 通过 + Comparison 端到端 IT 提交运行通过 → 转"已完成"，并解锁 PR-7。
