# PR-6a 报告：PlanValidator + Agent Run/Step 持久化层

> 状态：**部分完成** (PR-6a.1 100%、PR-6a.2 单测全绿；Docker MySQL IT 待 CI 执行)
> 上游依赖：PR-5 (Harness)、PR-6 contract layer (`c1dcceb`)、PR-6a.1 (`c21c608`)
> 下游门禁：未在 PR-6a 完成 Executor，AGENTIC 仍统一返回 `422`

---

## 1. 范围

PR-6a 把 PR-6 引入的 Agent 运行时领域模型落成两条交付物：

| 子任务 | 内容 | commit |
|---|---|---|
| **PR-6a.1** | `PlanValidator` + `AgentStateMachine` 测试 | `c21c608` |
| **PR-6a.2** | `agent_run` / `agent_step` 表 + JSON / CAS Port + JPA Adapter + 单测 | 本次 |

PR-6a 明确 **不实现**：
- `BudgetManager` / `AgentRunExecutor` / `EvidenceAccumulator`（PR-6b）
- `Planner` / `SufficiencyJudge`（PR-7）
- ComparisonWorkflow 的 Executor 接线（PR-6c）
- Claim-Citation 校验 / Benchmark / Shadow / 灰度（PR-8）

AGENTIC 路径仍由 `ChatOrchestrator` 在路由命中后立即返回 `422 UnsupportedPipelineException`，行为与 PR-2 一致。

---

## 2. PR-6a.1：PlanValidator + AgentStateMachine 测试

### 2.1 已交付
- `platform-common/.../agent/PlanValidationResult.java` — 校验结果 record（无 bootstrap 依赖，留在 common）。
- `platform-bootstrap/.../agent/PlanValidator.java` — 落 bootstrap，可注入 `ToolRegistry`：
  - Kahn 拓扑排序 + 稳定 topo 序输出
  - 显式检测环 (`IllegalPlanCycleException` 等价行为)
  - 禁用字段扫描（`internal_*` / `system_*` / 其余 allowlist 字段）
  - 步骤 / token / cost 配额校验，超限即报 `PlanQuotaExceededException`
- `AgentStateMachineTest`（`platform-common`，17 测试，分 4 个 `@Nested`）
- `PlanValidatorTest`（`platform-bootstrap`，24 测试）

### 2.2 验证
- `./gradlew :platform-common:test :platform-bootstrap:test --rerun-tasks`
- 全绿（见第 5 节测试计数）

---

## 3. PR-6a.2：Agent Run / Step 持久化层

### 3.1 迁移

#### V13__create_agent_run.sql（MySQL）
- PK = `run_id VARCHAR(64)`（业务 ID，由 Executor 显式生成）
- `plan_json JSON NOT NULL`，冗余列 `plan_id` / `plan_version` / `plan_hash(64)` 便于审计查询
- `budget_json` / `reservation_json` / `usage_json` 均 `JSON NOT NULL`，初始化为各自 `.zero()` / `.pr6Default()`
- `evidence_ids_json JSON NULL` + `evidence_count INT NOT NULL DEFAULT 0` —— **只存 ID 列表 + 计数**，Evidence 正文不进这张表，避免租户内容泄漏到 Run 落盘层
- `version BIGINT NOT NULL DEFAULT 0`（CAS 乐观锁，POJO 内用 `long`，未启用 JPA `@Version`，由 `@Modifying @Query` 显式 CAS）
- 索引：
  - `uk_agent_run_request_id` (`request_id`) — 幂等
  - `idx_agent_run_tenant_created` (`tenant_id, created_at DESC`) — 审计列表
  - `idx_agent_run_status` (`status`) — BACKLOG 调度扫描
  - `idx_agent_run_user` (`user_id`)

#### V14__create_agent_step.sql（MySQL）
- PK = `id BIGINT AUTO_INCREMENT`
- `UNIQUE (run_id, step_id)` — 业务唯一
- `UNIQUE (run_id, step_sequence)` — 序号唯一，避免乱序写入
- FK `agent_step_run_fk → agent_run(run_id) ON DELETE RESTRICT`（禁止级联删除 Run 的 Step；Step 是审计根）
- `status VARCHAR(32) NOT NULL DEFAULT 'PENDING'`
- `version BIGINT`；其余可空字段（`call_id` / `latency_ms` / `error_code` / `started_at` / `completed_at`）
- `evidence_ids_json JSON NULL`

### 3.2 应用层映射（platform-common）
- `AgentBudgetReservation` — "已预留未结算"配额；与 `AgentBudget`（最大允许）和 `AgentUsage`（已结算）严格分离
- `AgentRunRecord` — 含 `evidenceIds: List<String>` + `evidenceCount`；**不含 Evidence 正文 / chunkId / documentId**
- `AgentStepRecord` — compact ctor 强制 `status` 非 null（默认 `PENDING`）、`evidenceIds` 不可变拷贝

### 3.3 Port（platform-bootstrap）
- `AgentRunRepository` — `create / findByRunId / findByTenantId / transition / updateBudgetState / updateEvidenceSummary`
- `AgentStepRepository` — `create / findByRunIdAndStepId / findByRunId / transition` + 内嵌 `AgentStepUpdate` record
- **create 语义**：`create()` 内强制 `status==PENDING`，否则 `IllegalArgumentException`（fail-closed）

### 3.4 JPA Adapter（infrastructure/persistence/jpa）
- `AgentRunEntity` / `AgentStepEntity` —— 无 `@Version`；version 字段裸 `Long`
- `AgentRunJpaRepository.transition(...)` / `.updateBudgetState(...)` / `.updateEvidenceSummary(...)` 三条 `@Modifying @Query`：
  ```
  UPDATE ... SET status=:target, ... , version=version+1
  WHERE run_id=:runId AND version=:expectedVersion AND status IN :expectedStatuses
  ```
- `AgentStepJpaRepository.transition(...)` —— 同模式，含 COALESCE 兜底保留旧字段值
- `AgentRunRepositoryImpl` / `AgentStepRepositoryImpl`：
  - 受影响行等于 `1` 返回 true；`0` 返回 false（冲突）
  - 未知状态字符串进入 `valueOf(...)` → `IllegalStateException` fail-closed
  - JSON 列统一用 `ObjectMapper`；空 List 写入为 `null`（节省空间 + 与 `COALESCE` 协作）

### 3.5 测试（mock JPA）
- `AgentRunRepositoryImplTest`（8 测试，2 `@Nested`）：
  - Create/Find：jsonRoundTrip / unknownStatusFailClosed / evidenceIdsOnly / findByTenantId
  - CAS：casSuccess / casConflict / updateBudgetCasSuccess / evidenceSummaryCasSuccess
- `AgentStepRepositoryImplTest`（10 测试，2 `@Nested`）：
  - Create/Find：createPendingOnly / createNonPendingRejected / createEmptyEvidenceIsNull /
    findByRunIdAndStepId / findByRunIdOrdered / unknownStatusFailClosed / evidenceIdsRoundTrip
  - CAS：casChainSuccess (PENDING→RESERVED→RUNNING→SUCCEEDED) / casConflict /
    casEmptyEvidenceNulled / casNonEmptyEvidenceJson

---

## 4. CAS 乐观锁设计要点

1. **不用 `@Version`**：Spring Data `@Version` 触发 Hibernate 整对象 UPDATE + 自动 version bump，无法表达"只在 `status ∈ {EXPECTED}` 时更新"。我们刻意走 `@Modifying @Query` 显式条件 UPDATE。
2. **status IN (...)**：允许 Executor 在 CAS 时声明"我态期望集合"，例如可从 `{RECEIVED, ROUTED}` 任一态推进到 `EXECUTING`，无需先读后写。
3. **version+1 由 SQL 端做**：CAS 命中后 version 由 `e.version = e.version + 1` 在同一 UPDATE 内推进，避免读改写竞态。
4. **affected==0 即冲突**：不抛异常，由调用方决定 retry / abort；Executor 在 PR-6b 才会接入。
5. **Agent_step FK RESTRICT**：Step 是审计根，禁止连带删除；Run 删除必须显式先清 Step。

---

## 5. 测试计数

| 模块 | 测试数 | 状态 |
|---|---|---|
| `platform-common:test` | 全绿 | OK |
| `platform-bootstrap:test` | 448 总数 × 2 失败 = 446 绿 | 2 失败 = `JpaChunkRepositoryIT` / `JpaDocumentRepositoryP3IT`，**Docker 不可用导致 Testcontainers 初始化失败**，与本 PR 无关、与前序 PR 表现一致 |
| `parser-service` | 不涉及 | — |

PR-6a.2 **未执行真实 MySQL 集成测试**：Docker 在本机不可用；CAS / JSON round-trip / fail-closed 由 mock JPA 单测覆盖。CI 跑 MySQL IT 通过后再视为 PR-6a.2 100% 完成 —— 本报告维持 **部分完成**。

---

## 6. AGENTIC 行为未变

- `ChatOrchestrator.routeAndExecute(...)`：`RouterDecision.pipelineType() == AGENTIC` → 返回 `422 UnsupportedPipelineException`
- `ChatOrchestrator` 未注入 `AgentRunRepository` / `AgentStepRepository`，无任何执行路径触达本 PR 新增 Port
- Classic RAG / Router / Tool / Harness 行为零回归

---

## 7. 风险与遗留

| 风险 | 缓解 | 责任 PR |
|---|---|---|
| MySQL JSON NULL 索引未实测 | CI 跑 V13/V14 + 集成断言 | PR-6a.2 MySQL IT (CI) |
| `agent_run.evidence_ids_json` 与 `chat_traces.evidence_snapshot` 双拷贝 ID | 只复制 ID + count，正文唯一源是 chat_traces；审计查询不读 Evidence body | PR-6b 起 Executor 校验 |
| 没有真实并发冲突测试 | Executor 接入后 PR-6b 增加"version 不匹配触达重试"集成测试 | PR-6b |

---

## 8. 出口门禁自检

| 门禁 | 状态 | 备注 |
|---|---|---|
| Migration V13/V14 SQL 通过审查 | ✅ | MySQL 8 语法；DEFAULT / INDEX 命名规范 |
| Entity 不暴露 Evidence 正文 | ✅ | Entity 只持 `evidenceIdsJson` + `evidenceCount` |
| CAS UPDATE 条件正确（version + status 集合） | ✅ | 三处 run + 一处 step `@Modifying @Query` |
| 未知状态 fail-closed | ✅ | `valueOf` → `IllegalStateException` |
| 全部可执行单测通过 | ✅ | 446 绿 + 2 Docker IT 阻塞 |
| MySQL IT 已执行 **或** 明确标记 Docker 阻塞 | ✅ | 明确标记：Docker 不可用，由 CI 执行 |
| AGENTIC 仍 422 | ✅ | Orchestrator 无 Executor 注入 |
| 不实现 BudgetManager / Executor / EvidenceAccumulator | ✅ | 留 PR-6b |

---

## 9. 下一步

- **PR-6b** `BudgetManager` + `AgentRunExecutor` + `EvidenceAccumulator` —— 接入 `AgentRunRepository` / `AgentStepRepository` 的 CAS 闭环，把 PR-4 的 ToolExecutor 真正驱动起来
- **PR-6c** ComparisonWorkflow Executor 接线
- **PR-7** Planner + Plan Validator 实战 + Sufficiency Judge
- **PR-8** Claim-Citation + Benchmark + Shadow + 灰度
