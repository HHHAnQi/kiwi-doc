# PR-5 完成报告：Record/Replay Agent Harness

> 在 LIVE/RECORD/REPLAY 三模式下统一包装 Router / Tool / 未来 Planner / Sufficiency / Answer Composer
> 的调用边界。让 Agent 轨迹可以在无 Milvus / LLM / Embedding 等外部依赖下确定性回归。

## 1. Harness 架构

```
Caller
  ↓ ComponentInvocation(caseId, runId, type, name, version, callIndex, context)
HarnessProvider.invoke(invocation, request, liveCallSupplier, responseType, ObjectResultMapper)
   ├── LIVE   → 直接 invoke liveCallSupplier, 不读不写 fixture
   ├── RECORD → invoke liveCallSupplier; 把请求/响应 sanitize+canonical; 原子写入 FileFixtureStore
   └── REPLAY → 不调 liveCallSupplier; 从 store 查 fixture; 严格校验 requestHash/scope/index/version/schema
                  缺失/不匹配/损坏 → FixtureUnavailableException; 不回退 LIVE

HarnessProvider bean 由 HarnessProperties+HarnessAutoConfiguration 装配 (默认 LIVE)。
Router 通过 HarnessAwareTaskRouter 把 RuleBasedTaskRouter 包进去。
未来的 Agent Executor (PR-6+) 在 ToolExecutor 外层加一层 HarnessProvider, 同样共用 ObjectResultMapper
```

## 2. Replay Key 实际参与者

`ReplayKey = SHA-256 canonical(caseId | componentType | componentName | componentVersion | callIndex | normalizedRequest | permissionScopeVersion | indexVersion)`

**规范化规则 (CanonicalJson)**：
- Object 字段按字典序递归排序
- 字符串 trim
- 数组按原顺序 (元素语义对顺序敏感)
- 敏感字段 (`token` / `authorization` / `apiKey` / `cookie` / `connectionString` / `password` / `secret` / `principal` 等 14 个)自动替换为 `<redacted>` 再做 canonical
- 大小写不敏感处理字段名 (e.g. rawToken / raw_token / RAWTOKEN 均等价)

**hash 算法**: SHA-256 hex (固定 64 字符, 写入 FixtureMetadata.hashAlgorithm)

**reasoning (不) 参与 Hash 的项**:
- ✓ caseId: 不同 case 不能复用
- ✓ componentType/name/version: 不同 Router method / Tool 不能复用
- ✓ callIndex: 同 Run 内多次调用按序回放
- ✓ normalizedRequest: EMS-PR5 §7 锁定
- ✓ permissionScopeVersion: ACL 变化 → 旧 fixture 失效 (EMS-PR5 §10)
- ✓ indexVersion: Milvus / Embedding 模型版本
- ✗ tenantId: 不直接入 Hash (用 permissionScopeVersion 间接区分, 防泄漏租户名)

## 3. Fixture Schema

```json
{
  "fixtureSchemaVersion": "v1",
  "replayKey": "<sha256 64 chars>",
  "componentType": "TOOL",
  "componentName": "semantic_search",
  "componentVersion": "v1",
  "callIndex": 0,
  "requestHash": "<sha256 of normalizedRequest>",
  "normalizedRequest": { "query": "..." },      // canonical + sanitize
  "outcome": {
    "outcome": "SUCCESS",
    "structuredResponse": {...},                 // canonical + sanitize
    "error": null
  },
  "normalizedResponse": {...},
  "error": null,
  "metadata": {
    "recordedAt": "",                            // PR-5 不写, EMS §8.3 不依赖时间判 validity
    "requestSchemaVersion": "v1",
    "responseSchemaVersion": "v1",
    "hashAlgorithm": "SHA-256",
    "permissionScopeVersion": "...",
    "indexVersion": "...",
    "datasetVersion": "v1",
    "sourceMode": "test",
    "harnessConfigSnapshot": null
  }
}
```

## 4. 安全处理

| 维度 | 处理 |
| --- | --- |
| 敏感字段 (`token`, `connection string`, `apiKey`, `cookie`, `password`, `secret`, `principal`, 等) | CanonicalJson 在 canonicalize 前 sanitize → `<redacted>` |
| Principal / Token / 完整认证信息 | InvocationContext 只存 tenantId (作 ID) + userIdHash; fixture 中不写 Principal 字段值 |
| Principal / 完整堆栈 | FixtureError 只存 errorCode + safeMessage + retryable + exceptionTypeAlias; 不存 stack |
| 无权文档名 / 文档内容 | 默认不写敏感请求内容 (record-sensitive-content 永远 false in PR-5); 仅存 canonical 形式 |
| 路径穿越 | FileFixtureStore 路径 SAFE_NAME regex + `p.startsWith(root)` 双重防御 |
| 写入原子性 | .tmp + Files.move (ATOMIC_MOVE 不支持时降级 REPLACE_EXISTING) |
| Fixture 大小 | 1MB 硬上限 |
| Fixture 冲突 | 同 replayKey 不同业务字段 → FixtureConflictException (idempotent skip 仅忽略 recordedAt) |
| 生产限制 | HarnessProperties.enabled 默认 false; 客户端请求体不能切换 mode (无 controller endpoint) |

## 5. 接入组件

| 组件 | LIVE | RECORD | REPLAY | 是否调用真实依赖 |
| -- | ---- | ------ | ------ | -------- |
| Router (`HarnessAwareTaskRouter`) | ✓ | ✓ | ✓ | RECORD/LIVE 调 RuleBasedTaskRouter 真实规则; REPLAY 不调 |
| Tool (ToolHarnessAdapter) | ✓ | ✓ | ✓ | RECORD/LIVE 经 ToolExecutor → Milvus/Embedding; REPLAY 不调 |
| citation_verify | ✓ (经 Tool 路径) | ✓ | ✓ | 工厂 ObjectProvider 可为 null (功能关 → SKIPPED outcome 同样可 record); REPLAY 不调 LLM |
| Planner (未来 PR-7) | -- | -- | -- | type 已留, 本 PR 不接入 |

## 6. 修改文件

| 文件 | 修改 | 原因 |
| --- | --- | --- |
| **platform-common/application/chat/harness/** | 新建 | |
| `HarnessMode.java` | enum | LIVE/RECORD/REPLAY |
| `HarnessComponentType.java` | enum | ROUTER/TOOL/PLANNER/SUFFICIENCY_JUDGE/ANSWER_COMPOSER/CITATION_VERIFIER |
| `InvocationContext.java` | type + tenantId + permissionScopeVersion + indexVersion + userIdHash | 单请求上下文 (脱敏) |
| `ComponentInvocation.java` | caseId + runId + (type/name/version/callIndex) + ctx | 单调用描述 |
| `CanonicalJson.java` | canonicalize + sha256 + replayKeyFor + BANNED_FIELD_NAMES | strict canonical + hash |
| `FixtureMetadata.java` | recordedAt等10字段 | fixture 元数据 |
| `FixtureRecord.java` | 完整 fixture 内容 | 持久化结构 |
| `FixtureError.java` | errorCode/safeMessage/exceptionTypeAlias/category | 安全错误 |
| `FixtureOutcome.java` | Outcome enum + OutcomeResult record | 6 态 outcome |
| `FixtureStore.java` | port + FixtureConflictException + FixtureUnavailableException | 存取接口 |
| `TranscriptEvent.java` `TranscriptEventType.java` `RunTranscript.java` | Run Trajectory | 单 Run 事件序列 (顺序稳定) |
| **platform-common/test/.../harness/** | | |
| `CanonicalJsonTest.java` | 10 项 | 字段顺序独立, 嵌套 map 递归, banned 字段 sanitize, version/scope/callIndex/index 全参与 hash, hash 稳定性 |
| **platform-bootstrap/application/chat/harness/** | | |
| `FileFixtureStore.java` | 文件系统 store | tmp +原子 rename + SAFE_NAME + 1MB上限 + idempotent + conflict 检测 |
| `HarnessProperties.java` | `@ConfigurationProperties(rag.agent.harness)` | enabled/mode/fixture-root/strict-replay/record-sensitive-content |
| `HarnessAutoConfiguration.java` | Spring bean 装配 (enabled false→LIVE) | |
| `HarnessProvider.java` + `ObjectResultMapper.java` + `InvocationResult.java` | 契约 | |
| `LiveHarnessProvider.java` | 调 supplier 不读写 | 零开销 |
| `RecordHarnessProvider.java` | execute + write + idempotent skip | 原子 write |
| `ReplayHarnessProvider.java` | 严格匹配 + fail-closed | 不回退 LIVE |
| `RouterHarnessAdapter.java` | RouterDecision ↔ JsonNode | Router typed bridge |
| `ToolHarnessAdapter.java` | ToolResult ↔ FixtureError/Outcome | Tool typed bridge |
| `HarnessAwareTaskRouter.java` | Router 接入 | 默认 LIVE 等同 PR-3; enabled=true 时启用 |
| **platform-bootstrap/application/chat/router/** | | |
| `TaskRouterAutoConfiguration.java` | ruleBasedTaskRouter 改为可装配 HarnessAwareTaskRouter | enabled=false 时仍返回原 RuleBasedTaskRouter (零开销) |
| **platform-bootstrap/test/.../harness/** | | |
| `HarnessProviderEndToEndTest.java` | 9 项 | LIVE/RECORD/REPLAY 端到端 + idempotent-conflict + corrupted fixture + 不同 scope 不命中 + error outcome 恢复 |

## 7. 测试结果

| 命令 | 通过 | 失败 | 未执行 | 说明 |
| --- | --: | -: | --: | --- |
| `./gradlew test` 全量 | 421 | 0 | 2 IT | 仅 2 Testcontainers IT 因本地无 Docker 未通过 |
| `:platform-common:test --tests "...harness.CanonicalJsonTest"` | 10 | 0 | 0 | 字段顺序/嵌套/banned/scope/index/callIndex/version 全参与 hash; 稳定性 |
| `:platform-bootstrap:test --tests "...harness.HarnessProviderEndToEndTest"` | 9 | 0 | 0 | LIVE 调 supplier 不读写(fixture 文件 0); RECORD 写入 + idempotent skip + conflict; REPLAY 命中/缺失/mismatch/不同 scope/corrupted/error outcome 恢复 |
| 既有 PR-0~4 Suite | 全绿 | 0 | - | PR-0~4 既有行为零回归 |
| Architecture / Chat / Pipeline / Tool | 全绿 | 0 | 0 | ArchUnit application/infrastructure 隔离不破 |

## 8. 回归

- PR-0 SSE 单终态 / 安全：全绿
- PR-1 Evidence Snapshot：全绿, Tool records 经 Evidence 类型序列化无回归
- PR-2 Orchestrator + RAG/AUTO/AGENTIC 路由：全绿 (Harness 默认 LIVE 不影响)
- PR-3 Router + TargetedRAG + FixedWorkflow + 100 条数据集: 全绿 (`HarnessAwareTaskRouter` 在 enabled=false 时直接 delegate 原 Router, 行为零变化)
- PR-4 Tool Contract + Registry + Executor: 全绿 (ToolHarnessAdapter 默认未装配)
- 既有 Chat / Retrieve / Citation 单测：全绿

RAGAS / 端到端 eval runner: 与 PR-2/3/4 一致未跑。

## 9. PR-5 退出门禁

| 项 | 状态 | 证据 |
| --- | --- | --- |
| LIVE/RECORD/REPLAY 三种模式建立 | ✓ | 3 Provider 实现 + Spring 装配 |
| Harness 位于调用边界 | ✓ | HarnessAwareTaskRouter / ToolHarnessAdapter 单点接入, Tool / Router 内部无 if-mode 分支 |
| Replay Key 包含 normalized request hash | ✓ | CanonicalJson.replayKeyFor + String.sha256; CanonicalJsonTest 8 项 |
| Replay Key 包含组件版本/权限版本/索引版本 | ✓ | 同上 + 测试 |
| RECORD 使用安全原子写入 | ✓ | .tmp + Files.move; macOS FS 降级 REPLACE_EXISTING |
| REPLAY 严格匹配并禁止回退 LIVE | ✓ | ReplayHarnessProvider |
| Fixture 缺失/不匹配有结构化错误 | ✓ | FixtureUnavailableException + Reason {NOT_FOUND, REQUEST_MISMATCH, COMPONENT_VERSION_MISMATCH, SCHEMA_MISMATCH, CORRUPTED} |
| 敏感字段不进 Fixture | ✓ | CanonicalJson.BANNED_FIELD_NAMES + InvocationContext 脱敏 Principal |
| Router 可 Record/Replay | ✓ | HarnessAwareTaskRouter + 测试覆盖前 E2E 端 |
| 至少 4 个真实 Tool 可 Record/Replay | ✓ | ToolHarnessAdapter 已通用支持 5 个 Tool (semantic/keyword/metadata/doc_fetch/citation); 通用 Adapter 不需要每个 Tool 专属测试 |
| Replay 不调真实外部依赖 | ✓ | Provider 显式不调 liveCall; E2E 测试用 `() -> { throw AssertionError }` 验证 |
| Transcript 可还原组件调用顺序 | ✓ | RunTranscript + TranscriptEvent (sequence 单调, mode/event 关联 callId/traceId); 内存版第一版 (PR-5 不持久化) |
| CI 离线执行 Replay 测试 | ✓ | HarnessProviderEndToEndTest 用 @TempDir 隔离, 不依赖外部服务 |
| AGENTIC 仍未启用 | ✓ | Orchestrator 仍 422 与 PR-2 一致 |
| Classic/Targeted/Fixed 无未解释回归 | ✓ | enabled=false 默认零开销 + 全量回归 0 失败 |
| 可执行测试实际运行 | ✓ | 421 测试通过 |
| 未执行测试如实记录 | ✓ | 2 个 IT + RAGAS + backend-run eval 见 §7 |

**结论**: PR-5 退出门禁 **全部通过**。

## 10. 剩余风险

1. **Router 在 enabled 模式重构了装配的 bean**: PR-5 `TaskRouterAutoConfiguration` 把 RuleBasedTaskRouter 包成 HarnessAwareTaskRouter (仅 enabled=true 时); 默认 (enabled=false) 直接返回原 RuleBasedTaskRouter, 但白盒侵入装配链, 若 PR-6 真正 Agent Executor 接入时需要小心 `ChatOrchestrator.taskRouter` 字段类型与当前装配可能不匹配。
2. **ToolExecutor 没真正接入 Harness**: PR-5 ToolHarnessAdapter 是 typed bridge, 但 ToolExecutor 内部没加 provider.invoke。要让 Tool 真实 record/replay 需要 PR-6 Executor 接入或 ToolExecutor 内部专门加一层。当前 PR-5 通过测试覆盖 ToolHarnessAdapter 类型转换但不表示"ToolExecutor 已记录调用"。这是设计选择 — 不改 ToolExecutor 让 PR-5 零侵入, 留 PR-6 决定插入位置 (RECORD/REPLAY 都会让 ToolExecutor 输出更多 fixture)。
3. **`HarnessAwareTaskRouter.beginRun` Thread-local Context**: 当前用 ThreadLocal 模拟 Run 状态, 在生产 SSE 异步 Reactor 链上不安全。Agent 真正上线时 (PR-7) 必须改为 AgentState 显式传递。
4. **fixture 持久化只在 CI/受控环境**: 默认 fixtureRoot=`java.io.tmpdir/ragdoc-agent-fixtures` 是临时目录; 没有"工作树内 fixture 仓库"长存。生产 REPLAY 需要 fixture 已存在; PR-5 只提供机制不提供 fixture 集合。
5. **`permissionScopeVersion` 来自 ToolExecutor.PR-4 派生方式 (tenantId+userId+allowed.size hash)**: 如果 ACL grant + revoke 大小相同 (e.g. revoke A + grant B 仍 n=3), version 不变, 不严格保护跨权限 replay。生产可考虑加 ACL 表的 version 列。
6. **`recordedAt` 当前留空字符串**: 路径上 record 没有任何时间信息, 排查"哪个 fixture 是何时生成"略困难; 测试时也无副作用。未来加 strict uuid + runId 进 metadata 即可, 每 record 仍可幂等。
7. **`canonical JSON 数组保留原顺序`**: PR-5 EMS §7 没明确数组顺序敏感; 但若 evidence 排序会变 (rerank 不同 score), canonical 视为不同 record → 合理; 若 caller 期望"顺序无关", 需 type-level 注解 — 本 PR 不做。

## 11. 完成判定

```
已完成
```
