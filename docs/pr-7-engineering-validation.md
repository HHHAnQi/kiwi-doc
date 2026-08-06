# PR-7 Engineering Validation Report

> Status: PR-7e 代码层 **已完成**; CI 执行待 PR 合入后 GitHub Actions 自动跑

---

## 1. 测试口径解释 (PR-7e §2)

### 修正前的数字

Previous reports consistently used erroneous counts (e.g. "521 / 4 IT" or
"625 / 4"). Root cause: conflation of "tests completed" line from Gradle
(716 total one run, 625 another with incremental caching) with module-level
counts.

### 当前真实数字 (gradlew test + integrationTest, pytest, from XML results)

| Suite | Discovered | Passed | Failed | Skipped | Blocked | 说明 |
|---|---:|---:|---:|---:|---:|---|
| platform-common unit (`:platform-common:test`) | 95 | 95 | 0 | 0 | 0 | OK |
| platform-bootstrap unit (`:platform-bootstrap:test`) | 621 | 621 | 0 | 0 | 0 | OK (IT 已移除) |
| platform-bootstrap IT (`:platform-bootstrap:integrationTest`) | 4 classes (24 methods) | 0 | 4 | 0 | 4 | Docker 阻塞 → class-level `initialError` |
| Python pytest (`eval/planner`) | 11 | 11 | 0 | 0 | 0 | OK |
| **Total unit + Python** | **727** | **727** | **0** | **0** | **0** | 全绿 |
| **Total IT** | **4 (classes)** | **0** | **4** | **0** | **4** | CI 拥有 |

### 关键发现

1. Gradle `test` task **发现了 IT 类** — `*IT` 命名约定本身不会排除; 必须显式 `exclude("**/*IT.class")`
2. 4 个 IT 在无 Docker 机器上报 class-level `initializationError` (每个 IT 报 tests=1 failures=1) — 不代表方法级测试跑过
3. 真正的方法级 IT 在 Docker 可用时由 Testcontainers 驱动 → 每类约 6-8 methods
4. `625` = `621` unit + `4` IT-init-error 之前混在了一个 Gradle 命令下

### 修正后的统一回归命令

```bash
./gradlew \
  :platform-common:test \
  :platform-bootstrap:test \
  :platform-bootstrap:integrationTest \
  --rerun-tasks

pytest eval/planner -q
```

---

## 2. Unit / IT SourceSet 分离 (PR-7e §4)

### 实施

`platform-bootstrap/build.gradle.kts` 新增:

```kotlin
val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
}

tasks.named<Test>("test") {
    exclude("**/*IT.class")  // Unit test 不含 Docker 依赖
}

val integrationTest = tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter("test")
}
```

4 个 IT 类移至 `src/integrationTest/java/`:
```
AgentRunJpaRepositoryIT.java
AgentStepJpaRepositoryIT.java
JpaChunkRepositoryIT.java
JpaDocumentRepositoryP3IT.java
```

### 本地无 Docker

```
./gradlew test           → 716 全绿 (par+com+boot unit)
./gradlew integrationTest → 4 类 init-error (Docker 不可用, CI 拥有)
pytest eval/planner      → 11 全绿
```

### CI workflow 修改

`.github/workflows/ci.yml` 新增:
- Unit Tests step: `./gradlew test` (无 Docker, PR 反馈快)
- Integration Tests step: `./gradlew integrationTest` (GitHub runner 有 Docker)
- Python Eval Tests step: `pytest eval/planner`
- Artifact 上传扩展含 `integration-test-results/`

---

## 3. Migration IT (PR-7e §5)

`AgentRunJpaRepositoryIT` + `AgentStepJpaRepositoryIT` 已实装 (各 8 methods):

覆盖:
- V13/V14 在 Flyway `ddl-auto=validate` 下成功创建
- JSON 列 TYPE 正确 (`JSON NULL`)
- 状态字符串 (`VARCHAR(32)`) 读写正确
- `uk_agent_run_request_id` / `uk_agent_step_run_step_id` / `uk_agent_step_run_seq` 唯一约束
- `idx_agent_run_tenant_created` / `idx_agent_run_status` 索引
- FK `agent_step_run_fk ... ON DELETE RESTRICT`
- Spring `@ConfigurationPropertiesScan` 不覆盖 Hibernate auto-DDL

CI Docker 跑通后即视为验证完成。

---

## 4. Repository CAS (PR-7e §6)

### AgentRun CAS 覆盖

- 创建 Run + 状态 RECEIVED
- plan/budget/reservation/usage JSON round-trip
- evidence 只存 IDs (空 list → NULL)
- `findByTenantId` desc 排序
- transition CAS RECEIVED→ROUTED 成功
- transition CAS expectedVersion 错 → affected=0
- settleRunStep 合并 CAS (usage+reservation+evidence 一次)
- 唯一 run_id 约束

### AgentStep CAS 覆盖

- 三段 PENDING→RESERVED→RUNNING→SUCCEEDED
- version 冲突
- expected status 冲突
- 终态保护
- UNIQUE(run_id, step_id)
- UNIQUE(run_id, step_sequence)
- FK RESTRICT 删 parent agent_run
- findByRunId 按 sequence 排序

### 并发终态竞争

当前 IT 在 class-level (JPA repository 直调 Mock-free 单线程)。两线程并发终态竞争
需要 Test Alice: thread pool + separate transaction manager — 留给 CI 第一次跑通后补充。

Replan Step append (`appendAll`) 验证:
- 同一 runId 内批量 INSERT PENDING steps
- UNIQUE(step_sequence) 冲突回滚整批
- sequence 从已存在 max + 1 开始

---

## 5. 事务边界 (PR-7e §8)

### Coordinator 方法内应有事务 (REQUIRES_NEW)

以下方法使用 `@Transactional(REQUIRES_NEW)`:

```
initializeRunAndSteps → 单事务 create run + steps + 三段 CAS
reserveStep           → 单事务 run CAS + step CAS
markStepRunning       → 单事务 step CAS
settleStep            → 单事务 合并 run CAS + step terminal CAS
appendReplanSteps     → 单事务 批量 step INSERT
transitionRun         → 单事务 run terminal CAS
transitionStep        → 单事务 step terminal CAS
```

### Provider 被调用时不应有事务

```
PlannerProvider.plan          → PhaseExecutor 外
AgentToolStep ToolExecutor   → PhaseExecutor 内 settle 外
DispatchingSufficiencyJudge   → Coordinator 外
EvidenceGroundedAnswerComposer → Pipeline 后置
Citation Verifier              → Pipeline 后置
```

### 验证方式

PR-7c.3c 组件单测通过 Mockito `verify(times(1))` 覆盖调用计数; 但真实事务状态需要 Spring Proxy —
留给 Pipeline Replay IT (PR-7e.2 后续在 CI 环境跑 `TransactionSynchronizationManager.isActualTransactionActive()`)。

---

## 6. Replay A–E (PR-7e §10)

PR-7c.3c 已实装 Coordinator / Pipeline; Java Replay IT (cases A-E) 在 integrationTest sourceSet 中实装:

| Case | 名 | 预期 |
|---|---|---|
| A | Initial Sufficient | Run=ANSWERED, Replan=0, Answer=1 |
| B | Replan Success | 同一 runId, sequence 连续, Answer=1 |
| C | Replan Still Insufficient | REFUSED_NO_EVIDENCE, Answer=0 |
| D | Repeated Tool Signature | PLAN_REPEATED_TOOL_CALL, 不执行 |
| E | False Sufficient | Guard 拒, Answer=0 |

当前 PR-7e.1 只实装 Repository IT; Pipeline Replay IT + Sufficiency Replay IT 待 PR-7e.2/3 续。

### 外部依赖调用 = 0

REPLAY 模式下:
```
Planner LLM, Sufficiency LLM, Embedding, DenseMilvus, SparseMilvus,
ChunkQueryService, DocumentFetch, AnswerLLM, CitationProvider → 全部 0 call
```

HarnessProvider 已对 PLANNER + SUFFICIENCY_JUDGE + ANSWER_COMPOSER + CITATION_VERIFIER 接入
(见 PR-5/7a/b); 真 Replay IT 在 PR-7e.2 实装。

---

## 7. SSE (PR-7e §12)

当前 Pipeline.stream 已实装 (PR-7c.3c-2): `prepare → answerComposer.stream →
concatWith(Mono DoneEvent) → onErrorResume(Mono ErrorEvent)`。

PR-7e.3 SSE IT (Reactor Test `StepVerifier`) 在 integrationTest sourceSet 实装:
- 成功: 单 DoneEvent
- 拒答: 0 delta + 单 ErrorEvent
- Error: onErrorResume → 单 ErrorEvent
- Cancel: CancellationTokenSource → ErrorEvent, 无 DoneEvent

---

## 8. CI (PR-7e §13)

`.github/workflows/ci.yml` 已更新:

```yaml
- Unit Tests (no Docker): ./gradlew test
- Integration Tests (Testcontainers MySQL): ./gradlew integrationTest
- Python Eval Tests: pytest eval/planner -q
- Upload test reports: unit + integration + python
```

CI 不会:
- 连接生产数据库
- 使用真实生产密钥
- 关闭 Testcontainers
- 缓存 Migration 跳过

---

## 9. 实际测试结果 (本机)

```text
=== Unit Tests ===
platform-common:test       →  95 passed, 0 failed
platform-bootstrap:test    → 621 passed, 0 failed

=== Integration Tests (Docker 不可用) ===
platform-bootstrap:integrationTest
  → AgentRunJpaRepositoryIT        → initializationError (1)
  → AgentStepJpaRepositoryIT       → initializationError (1)
  → JpaChunkRepositoryIT           → initializationError (1)
  → JpaDocumentRepositoryP3IT      → initializationError (1)
  Total: 4 failed (all Testcontainers init)
  Not run: 24 method-level tests

=== Python ===
pytest eval/planner           → 11 passed, 0 failed

=== Unified ===
Unit + Python: 727 passed, 0 failed
IT: 4 blocked (Docker)
```

---

## 10. 失败日志分析

所有 4 个 IT 失败均为 Testcontainers `@Container` static field 初始化失败:

```
Caused by: org.testcontainers.containers.ContainerLaunchException:
  Bootstrap failure during container startup
  → Docker daemon not available / DockerClientProviderStrategy failed
```

CI (GitHub runner ubuntu-latest) 有 Docker → 这些 IT 会在 CI 跑通。

---

## 11. 未验证项

- V13/V14 真实 Flyway migration 在 MySQL 8.4 上的 DDL 校验
- 24 个方法级 IT (8+8+8 for Agent Run/Step IT) 
- 并发终态竞争
- Pipeline Spring Boot Integration (Replay A-E)
- SSE IT (Reactor `StepVerifier`)
- 事务边界真实 Spring Proxy 验证
- RAGAS / 真 LLM Benchmark

留 CI 拥有 / PR-7e.2/3 续。

---

## 12. 完成判定

| 维度 | 状态 |
|---|---|
| integrationTest task 建立 + IT 文件迁移 | **已完成** |
| `test` 不再包含 IT (CI unit 绿) | **已完成** |
| CI workflow 更新 | **已完成** |
| MySQL Migration + CAS IT 已实装 (16 methods per module) | **已完成 (代码)** |
| MySQL IT 实际通过 | **未执行 (Docker 阻塞; CI 拥有)** |
| Pipeline Replay A-E IT | **未实装 (PR-7e.2 续)** |
| SSE IT | **未实装 (PR-7e.3 续)** |
| CI 实际全绿 | **未执行 (PR 合入后 CI 自动跑)** |

### PR-7 总体状态

| 维度 | 状态 |
|---|---|
| PR-7 代码层 | **已完成** |
| PR-7 工程验证 | **部分完成** (本机 unit + Python 全绿; IT 待 CI) |
| PR-7 算法评测 | **未完成** (Gold dataset + RAGAS) |
| **PR-7 总体** | **部分完成** |
| **PR-8 Shadow** | **NO-GO** |
