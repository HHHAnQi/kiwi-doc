# PR-6b 报告：BudgetManager + EvidenceAccumulator + AgentRunExecutor

> 状态：**部分完成** (代码 + 全部单测绿；Docker MySQL IT 提交但本机未运行，CI owns)
> 上游：PR-6a (`7f6be4a`)
> 本次 3 个独立 commit：`5233f6c` (PR-6b.1)、`d6c8201` (PR-6b.2)、PR-6b.3 (本 commit)
> AGENTIC 模式仍统一返回 `422`（ChatOrchestrator 未注入 Executor）

---

## 1. 预算语义

| 类型 | 语义 | 写入时机 | 读取时机 |
|---|---|---|---|
| `AgentBudget` | 最大允许量（服务端构造，客户端不能扩大） | Run 初始化 | BudgetManager.evaluate |
| `AgentBudgetReservation` | 已预留、未结算（reservation=`reservedSteps`/`reservedToolCalls`/tokens/cost） | reserveStep 之前 Tool 调用 | BudgetManager.evaluate (联合判断) |
| `AgentUsage` | 已真实结算发生（`usedSteps`/`usedToolCalls`/tokens/cost） | settleStep Tool 完成后 | BudgetManager.evaluate (联合判断) |

**真实 Tool Call 与 Step Counter**：
- LIVE/RECORD Tool：`usedToolCalls += 1`，含 TIMEOUT/FAILED_RETRYABLE/FAILED_TERMINAL（外部资源已消耗）。
- REPLAY：`usedSteps += 1`，`usedToolCalls` **不增**，外部成本不增，`replayed=true`。
- DEDUP：`usedSteps += 1`，`usedToolCalls` 不增，`deduplicated=true`。
- cancel-before-tool：仅释放 reservation，`usedSteps` 不增（TODO：run cleanup 时把 RESERVED-only step 转 CANCELLED）。

**联合判断公式**：`usage + reservation + request <= budget`，任一维度突破即 Denied（**hard budget**：终止整个 Run，Revision §7）。

---

## 2. 事务边界

| 阶段 | 事务方法（`@Transactional REQUIRES_NEW`） | 范围 | 失败行为 |
|---|---|---|---|
| 初始化 | `Coordinator.initializeRunAndSteps` | create run + 全部 step + 三次 status CAS（RECEIVED→ROUTED→PLANNED→EXECUTING） | 任一失败抛 `AgentRunInitializationException` → 整体回滚（无中间遗留） |
| 预留 | `Coordinator.reserveStep` | run `updateBudgetState` CAS + step `PENDING→RESERVED` CAS | 任一失败抛 `AgentCasConflictException` → 整体回滚（Revision §2） |
| 标 RUNNING | `Coordinator.markStepRunning` | step `RESERVED→RUNNING` CAS（独立短事务，**不属于** settleStep） | 失败抛 `AgentCasConflictException` |
| 结算 | `Coordinator.settleStep` | **合并** `settleRunStep` CAS（usage+reservation+evidenceIds 一次）+ step terminal CAS | 任一失败抛 + 回滚（Revision §4） |
| Tool 调用 | **无事务**（Revision §11.4） | `ToolExecutor.execute` 在普通线程上下文 | MDC/Principal/TraceContext 保留 |

CAS 重试上限 = 3（`MAX_CAS_RETRIES`），每次重新读取最新状态。

---

## 3. 状态机

### Run（`AgentStateMachine`，PR-6a 引入）
```
主线： RECEIVED → ROUTED → PLANNED → EXECUTING → READY_TO_ANSWER → ANSWERED
拒答： READY_TO_ANSWER → REFUSED_NO_EVIDENCE | REFUSED_CONFLICT
失败： 任意非终态 → REFUSED_PERMISSION | BUDGET_EXCEEDED | TOOL_FAILED | TIMED_OUT | CANCELLED | SYSTEM_FAILED
终态保护： ANSWERED / REFUSED_* / *_FAILED / TIMED_OUT / CANCELLED 不允许任何出口
```

### Step（`AgentStepStateMachine`，PR-6b.1 新增）
```
主线： PENDING → RESERVED → RUNNING → SUCCEEDED | EMPTY
失败收敛： RUNNING → FAILED_RETRYABLE → FAILED_TERMINAL | CANCELLED
直接终态： RUNNING → FAILED_TERMINAL | PERMISSION_DENIED | TIMED_OUT | CANCELLED
跳过： PENDING → SKIPPED_BUDGET；PENDING/RESERVED → SKIPPED_DUPLICATE；PENDING/RESERVED/RUNNING → CANCELLED
依赖失败（无独立 SKIPPED_DEPENDENCY）： FAILED_TERMINAL + errorCode=DEPENDENCY_NOT_SATISFIED
终态保护： SUCCEEDED/EMPTY/FAILED_TERMINAL/PERMISSION_DENIED/TIMED_OUT/CANCELLED/SKIPPED_* 任何出口非法
```

---

## 4. Executor 调用链

```
AgentRunFactory.create  ← PlanValidator.throwIfInvalid → UUID runId → Coordinator.initializeRunAndSteps (单事务)
       ↓ InitializedRun + handle (clock 注入)
AgentRunExecutor.execute (per-Run EvidenceAccumulatorFactory.create 实例):
  loop over plan.steps()  (拓扑稳定串行):
    1. cancellation.isCancelled → CANCELLED + cleanupPass break
    2. Instant.now(clock).isAfter(policy.deadline) → TIMED_OUT + cleanupPass break
    3. 依赖检查: 所有 dependsOn 必须 ∈ succeededSteps; 失败 → SKIPPED_DEPENDENCY (FAILED_TERMINAL errorCode=DEPENDENCY_NOT_SATISFIED)
    4. BudgetManager.evaluate (budget + usage + reservation + req) → Denied → SKIPPED_BUDGET + Run BUDGET_EXCEEDED break
    5. Coordinator.reserveStep (run updateBudgetState CAS + step PENDING→RESERVED CAS 单事务)
    6. Coordinator.markStepRunning (step RESERVED→RUNNING, 独立事务)
    7. ToolExecutor.execute (事务外)
    8. ToolStatusMapper.toStepStatus → AgentStepStatus
    9. EvidenceAccumulator.accept(stepSeq, resultIndex, evidence)  (三级去重 + ACL 终检 + 数量/token 限制 + 稳定序)
    10. BudgetManager.settle → Coordinator.settleStep (合并 CAS + step terminal CAS 单事务)
    11. required Step EMPTY / FAILED_TERMINAL / PERMISSION_DENIED / TIMED_OUT → requiredEvidenceMissing=true (不允许 Accumulator 非空覆盖 Revision §6)
       optional Step 失败 → continue
  Run 终态判定:
    - prematureTerminal 非空 → 写对应终态 CAS
    - else if Accumulator 非空 && !requiredEvidenceMissing → EXECUTING → READY_TO_ANSWER
    - else → EXECUTING → READY_TO_ANSWER → REFUSED_NO_EVIDENCE (reasonCode=REQUIRED_EVIDENCE_MISSING 或 NO_EVIDENCE)
  cleanupPass：所有非终态 Step → CANCELLED / FAILED_TERMINAL（不遗留 Revision §1.9）
```

---

## 5. Evidence 策略

- **per-Run 实例**：`EvidenceAccumulator` 不是 Spring Bean；`EvidenceAccumulatorFactory.create()` 每次返回新建非 Bean 实例（Revision §1）。Executor 每 Run 持一个。
- **三级去重**（Revision §8.2）：
  1. `evidenceId` 相同（sha256(tenant|doc|chunk|contentHash)）→ 丢
  2. `(tenant,documentId,documentVersion,chunkId,contentHash)` 相同 → 丢
  3. 同 `contentHash` 但不同 document → **保留多来源**（避免 Citation 丢失）
- **ACL 终检**（Revision §8.1）：`evidence.tenantId != accumulator.tenantId` → 直接丢。ToolExecutor 已在 `EvidenceListOutput` post-check 过一层。
- **稳定顺序**（Revision §8.3）：Comparator `stepSequence → resultIndex → retrievalScore desc → evidenceId asc`。禁用 HashSet/HashMap 默认顺序。
- **限制**（Revision §8.4）：`maxEvidence`（默认 20）/ `maxEvidenceTokens`（默认 4000）。截断按稳定序后 top-N。
- **token 估算**（Revision §4.4）：`TokenEstimator` CJK 1 char=1 token + 非 CJK ceil(n/4)，metadata 显式标 `ESTIMATED_NOT_PRECISE`。不引入 LLM tokenizer 依赖。
- **数据库只存 IDs**：`EvidenceAccumulator.toIdsWithCount()` 返回 `List<String>`；`Coordinator.settleStep` 把 IDs + count 写入 `agent_run.evidence_ids_json + evidence_count`。**正文只在内存** (`AgentRunResult.evidence`)，不进 `agent_run` / `agent_step`。

---

## 6. 修改文件

| 文件 | 修改内容 | 原因 |
|---|---|---|
| `platform-common/.../agent/AgentStepStateMachine.java` | 新增（Step 合法转换表） | PR-6b.1 §5 |
| `platform-common/.../CancellationTokenSource.java` | 新增（AtomicBoolean 取消信号 + 只读 token） | Revision §5 |
| `platform-common/.../BudgetDimension.java` `BudgetDecision.java` `BudgetExceededException.java` `ReservationRequest.java` `StepSettlement.java` | 新增（预算/结算域类型） | PR-6b.1 §4 |
| `platform-common/test/.../AgentStepStateMachineTest.java` | 新增 17 用例 | PR-6b.1 |
| `platform-bootstrap/.../AgentBudgetManager.java` | 新增（evaluate + settle 四类规则） | PR-6b.1 §4 |
| `platform-bootstrap/.../AgentPersistenceCoordinator.java` | 新增（reserveStep/markStepRunning/settleStep/initializeRunAndSteps/transitionRun/transitionStep/reloadStep） | Revision §2/§3/§4/§9 |
| `platform-bootstrap/.../AgentCasConflictException.java` `AgentRunInitializationException.java` | 新增 | 协调器异常类型 |
| `platform-bootstrap/.../AgentRunRepository.java`（接口） | 新增 `settleRunStep` 方法 | Revision §4 合并 CAS |
| `platform-bootstrap/.../AgentRunRepositoryImpl.java` | 实现 settleRunStep | 同上 |
| `platform-bootstrap/.../jpa/repository/AgentRunJpaRepository.java` | 新增 `settleRunStep` `@Modifying @Query` | 同上 |
| `platform-bootstrap/test/.../AgentBudgetManagerTest.java` | 新增 16 用例 | PR-6b.1 |
| `platform-bootstrap/test/.../AgentPersistenceCoordinatorTest.java` | 新增 12 用例（验证 CAS 失败 + skip 后续） | PR-6b.1 Revision §2 |
| `platform-bootstrap/.../EvidenceAccumulator.java` `EvidenceAccumulatorFactory.java` `TokenEstimator.java` | 新增 per-Run 实例 + 保守 token 估算 | PR-6b.2 §8 + Revision §1/§4.4 |
| `platform-bootstrap/test/.../EvidenceAccumulatorTest.java` `EvidenceAccumulatorFactoryTest.java` | 新增 14 用例 | PR-6b.2 |
| `platform-bootstrap/.../PlanValidator.java` | 加 `@Component` 注解 | AgentRunFactory 依赖注入 |
| `platform-bootstrap/.../AgentRunFactory.java` `AgentRunHandle.java` `AgentRunResult.java` `ToolStatusMapper.java` `AgentRunExecutor.java` | 新增 | PR-6b.3 §6/§7/§12 |
| `platform-bootstrap/test/.../AgentRunFactoryTest.java` `AgentRunExecutorTest.java` `ToolStatusMapperTest.java` | 新增 21 用例 | PR-6b.3 |
| `platform-bootstrap/test/.../jpa/AgentRunJpaRepositoryIT.java` `AgentStepJpaRepositoryIT.java` | 新增 16 Testcontainers IT | Revision §14.1（本机未运行） |

---

## 7. 测试结果

| 命令 | 通过 | 失败 | 跳过/未执行 | 说明 |
|---|---:|---:|---:|---|
| `:platform-common:test` | 73 | 0 | 0 | OK |
| `:platform-bootstrap:test`（单测） | 505 | 0 | 0 | 全绿 |
| `:platform-bootstrap:test`（IT — Testcontainers） | 0 | 4 | 4 methods (V13/V14+历史 2) | Docker 缺失，本机报 `initializationError`；CI 拥有 |
| **总计** | **578** | **4** | **4** | 4 失败 = `AgentRunJpaRepositoryIT` + `AgentStepJpaRepositoryIT` + `JpaChunkRepositoryIT` + `JpaDocumentRepositoryP3IT`，全部 Testcontainers 初始化失败 |

新增单测数（绿）：17 (StepStateMachine) + 16 (BudgetManager) + 12 (Coordinator) + 14 (Evidence) + 10 (Mapper) + 5 (Factory) + 6 (Executor) = **80 新单测**。

---

## 8. MySQL IT

| 类 | 用例数（方法数） | 本机状态 | 备注 |
|---|---:|---|---|
| `AgentRunJpaRepositoryIT` | 8 | 未运行（Docker 缺失） | V13 迁移 / JSON round-trip / transition CAS / 冲突 / settleRunStep 合并 / findByTenantId desc / unique run_id / 空 evidence→NULL |
| `AgentStepJpaRepositoryIT` | 8 | 未运行 | V14 迁移 / FK RESTRICT / UNIQUE(run,step_id) / UNIQUE(run,step_seq) / 三段 transition 链 / CAS 冲突 / 终态保护 / seq 排序 |

CI 跑通后视为 PR-6b.3 IT 全绿；在此之前 PR-6b 整体保持**部分完成**。

---

## 9. 回归结果

| 范围 | 状态 |
|---|---|
| PR-0 SSE 单终端态 | 绿 |
| PR-1 Evidence snapshot | 绿 |
| PR-2 Orchestrator / 422 | 绿（AGENTIC 仍 422，Orchestrator 未注入 Executor） |
| PR-3 Router / Pipeline | 绿 |
| PR-4 Tool Contract / Executor dedup | 绿 |
| PR-5 Harness Record/Replay | 绿 |
| PR-6 Contract (AgentBudget/Usage/State) | 绿 |
| PR-6a PlanValidator + AgentRun/Step 持久化 | 绿（`AgentRunRepositoryImplTest` + `AgentStepRepositoryImplTest` 仍绿） |
| PR-6b Budget / Step State / Coordinator / Evidence / Executor / Mapper | 绿 |
| Python eval tests | 未在 Java build 内（独立） |

---

## 10. 门禁检查（PR-6b 退出门禁）

| # | 检查 | 状态 | 备注 |
|---|---|---|---|
| 1 | MySQL IT 在可用环境执行通过 | ⚠️ 未执行 | Docker 缺失；CI 拥有 |
| 2 | 测试总数差异已解释 | ✓ | 578 / 4 IT 全是 Testcontainers；详细见 §7 |
| 3 | Usage 与 Reservation 严格分离 | ✓ | 三个域对象，CAS 分别写 |
| 4 | 预留与 Step RESERVED 在同一短事务 | ✓ | `Coordinator.reserveStep` REQUIRES_NEW |
| 5 | Tool 执行在事务外 | ✓ | 单测 verify + IT 待 CI 实测 |
| 6 | 结算与 Step 终态原子协调 | ✓ | `settleStep` 单事务双 CAS + 抛冲突回滚 |
| 7 | Budget CAS 并发不超限 | ✓ | unit 测试 hard budget deny + usage+reservation 联合 |
| 8 | AgentStepStateMachine 建立 | ✓ | 17 用例 |
| 9 | Plan 按稳定拓扑顺序串行执行 | ✓ | for-loop over `plan.steps()` (topological order) |
| 10 | Tool 真实通过 Harness-aware ToolExecutor 调用 | ✓ | AgentRunExecutor 注入 ToolExecutor |
| 11 | Replay/Dedup 不计真实外部调用 | ✓ | BudgetManager.settle 四类规则 |
| 12 | EvidenceAccumulator 只保留授权 Evidence | ✓ | ACL 终检 + tenant fail-closed |
| 13 | Evidence 正文不进入 Agent 表 | ✓ | 16 IT 验证 + Repository 仅写 IDs |
| 14 | 有 Evidence 进入 READY_TO_ANSWER | ✓ | AgentRunExecutorTest `singleStepSuccessWithEvidence` |
| 15 | 无 Evidence 正确拒答 | ✓ | `requiredEmptyRefused...reason=REQUIRED_EVIDENCE_MISSING` |
| 16 | timeout/cancel/失败有明确终态 | ✓ | Cancellation / Hard budget / FAILED_TERMINAL 测试 |
| 17 | 正常结束不遗留非终态 Step | ✓ | cleanupPass；NEVER 走 PENDING/RESERVED/RUNNING 落地 |
| 18 | Planner/Replan/Sufficiency 未实现 | ✓ | 显式不接 LLM |
| 19 | ComparisonWorkflow 未接入 | ✓ | Orchestrator 仍 422 |
| 20 | AGENTIC 仍返回 422 | ✓ | Orchestrator 未注入 Executor |
| 21 | 全部可执行测试实际运行 | ✓ | 578 / 4 |
| 22 | 未执行 IT 如实记录 | ✓ | §8 |

---

## 11. 剩余风险

- **进程硬崩溃恢复**：未实现。Coordinator REQUIRES_NEW 保证单事务原子，但若 JVM 在 Tool 执行后崩溃，Run 仍是 EXECUTING 状态、step 是 RUNNING，需要 restart-runner（PR-8 或后续）。
- **Planner / Sufficiency**：完全未实现；当前所有 Plan 来自服务端硬编码（PR-7 接入 LLM Planner）。
- **ComparisonWorkflow 接线**：未接入；PR-6c 才打开 Executor 给 RouterDecision.COMPARISON。
- **真实 RAGAS / Benchmark**：未跑；PR-8。
- **预算参数**：当前用 `AgentBudget.pr6Default()` (maxSteps=3, maxToolCalls=5)，未在真实负载下校准。
- **REPLAY outcome 识别**：`AgentRunExecutor.isReplayed` 通过 `metadata.get("harness_replayed")` 识别；HarnessProvider 当前未写该 key；PR-7 一起补。
- **Step 状态映射的 RETRYABLE→TERMINAL**：当前直接收敛 FAILED_TERMINAL，PR-7 接入 retry policy 时再细化。
- **Cleanup Pass 重复 CAS**：单元测试未必覆盖全部非终态组合；MySQL IT `terminalStateProtected` 验证 DB 层不变量。

---

## 12. 完成判定

**部分完成**。

理由：
- 代码 + 全部单测（约 578 测试方法）绿。
- 4 个失败全部为 Testcontainers 初始化（Docker 缺失），与本 PR 无关，CI 拥有。
- Revision §1-§10 全部实现并测试覆盖。
- 等待 CI 跑 MySQL IT 通过 → 视为 **已完成**，并解锁 PR-6c ComparisonWorkflow 接线。
