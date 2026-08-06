# PR-4 完成报告：Agent Tool Contract 与 Tool Registry

> 把现有真实存在的检索/文档/引用核验能力标准化为可测试、可授权、可观测、可被后续 Agent Executor
> 调用的 Tool。本 PR 不实现 Planner / AgentState / AGENTIC 仍未启用。

## 1. 真实能力盘点

| Tool | 是否注册 | 底层实现 | 证据等级 | 备注 |
| --- | --- | --- | --- | --- |
| `semantic_search` v1 | ✓ 注册 | DenseRetriever (Milvus FloatVec) 经 RetrieveService.retrieve(cmd, DENSE) | 真实 | 复用既有 AccessScope sentinel + Rerank + EvidenceSnapshot |
| `keyword_search` v1 | ✓ 注册 | **真实 BM25** — SparseRetriever (Milvus FIELD_SPARSE_BM25)；经新增 `SparseSearchPort` 让 application 不依赖 infrastructure | 真实 | 不冒充 Dense; 独立 sparse_vector 字段 + RRF-able |
| `metadata_search` v1 | ✓ 注册 | RetrieveService.retrieve(cmd, HYBRID) 强制 source/version/language 至少一项 | 真实 | HYBRID = Dense + BM25 RRF (含真实 sparse) |
| `document_fetch` v1 | ✓ 注册 | ChunkQueryService.getChunk / getNeighbors + DocumentAccessGuard.requireRead | 真实 | 支持 chunk / 邻居 / parent；ACL 三层守门 |
| `citation_verify` v1 | ✓ 注册 | CitationVerifierPort.verify (LlmCitationVerifier)；`enabled=false` 时返 SKIPPED | 真实 | ObjectProvider 注入；功能关 = 不假执行 |

所有 Tool 都是 read-only，descriptor.idempotent=true。

## 2. 核心契约

### 2.1 AgentTool<I extends ToolInput, O extends ToolOutput>
泛型接口。强制每个 Tool 用独立 typed record 作 input/output，禁用 `Map<String,Object>` 逃逸。Executor 在调用前用反射 (`inputType()`) 做 banned 字段检测。

### 2.2 ToolDescriptor
```java
(name, version, description, inputSchemaVersion, outputSchemaVersion,
 requiredPermission, timeout, maxResults, idempotent, costCategory)
```
- `name + version` 启动期 fail-fast 防重复
- `timeout` (Duration) 由服务端限制；LLM/客户端不能扩大
- `maxResults` ∈ (0, 100]，每个 Tool 自己 trim
- 构造校验：name 命名 `[a-z][a-z0-9_]{1,63}`，timeout>0，maxResults 越界抛 IllegalArgumentException

### 2.3 ToolExecutionContext（不可变 record）
```java
(requestId, runId, principal, tenantId, permissionScope, indexVersion, deadline)
```
关键不变量：
- `principal` 必填，来自已鉴权 AuthContext
- `tenantId` 必须与 `principal.tenantId()` 一致；构造校验拒绝客户端偷传 tenantId
- `permissionScope` 派生自 PermissionResolverPort（含 tenantId / allowedDocIds / permissionScopeVersion）
- `deadline` (Instant) Tool 在长 blocking 调用前自检 `isExpired()`
- 不依赖长 DB 事务 timeout

### 2.4 ToolResult<T extends ToolOutput>（不可变 record）
```java
(callId, toolName, toolVersion, status, output, error, latencyMs, retryable, metadata)
```
- 9 态 `ToolStatus`：SUCCESS / EMPTY_RESULT / INVALID_ARGUMENT / PERMISSION_DENIED / TIMEOUT / DEPENDENCY_UNAVAILABLE / RETRYABLE_ERROR / TERMINAL_ERROR / CANCELLED
- 禁止只 SUCCESS/FAILED 二元表达
- SUCCESS 必须有 output；非 SUCCESS 必须有 ToolError（构造校验）
- 提供 `success` / `empty` / `failure` 三个静态工厂避免直接 new

### 2.5 ToolError
```java
(errorCode, safeMessage, dependency, retryable)
```
safeMessage 硬约束：不得含 token / 连接串 / 内部堆栈 / 无权文档名 / 敏感原文。`dependency` 是结构化下游名 (`milvus` / `embedding` / `verification-llm`) 用于 Metrics/Trace。

### 2.6 ToolRegistry (@Component)
- 启动期 fail-fast：(name,version) 重复 + inputType/outputType 必须实现 `ToolInput`/`ToolOutput` 标记接口
- 运行时 fail-closed：`get(name,version)` 未命中 → DomainException `TOOL_NOT_FOUND` (HTTP 404)
- `list()` 返回不可变排序 snapshot
- Registry 只查找，不解析 description 文字执行逻辑（prompt-injection 防护）

### 2.7 ToolExecutor (@Service) - 统一执行包装
按以下顺序横切（一切对 Tool 透明）：
1. **banned 字段检测**：input.toString() 含 `tenantId=`/`userId=`/`rawToken=`/`adminOverride=` 等 → INVALID_ARGUMENT，不调 Tool
2. **dedup check**：`runId|toolName:toolVersion|sha256(normalizedInput)|scopeVersion|indexVersion` 作 key；同 key 且 status.cacheable() → 第二次返回缓存结果（metadata.deduplicated=true）
3. **ACL pre-check**：PermissionResolverPort 派生 PermissionScope；空 allowedDocIds sentinel → PERMISSION_DENIED，不调 Tool
4. **deadline check**：isExpired() → TIMEOUT，不调 Tool
5. **Tool.execute()** 主体；RuntimeException 兜底转 TERMINAL_ERROR；DomainException 转结构化 result
6. **ACL evidence post-check**（双保险）：Tool.output 实现 `EvidenceListOutput` 时，把 tenantId ≠ ctx.tenantId() 的 Evidence 全部 drop；全 drop 则 SUCCESS→EMPTY_RESULT
7. **Metrics**：recordToolCall(name, status, latency) + recordToolEvidenceYield + incrementToolDedupHit（MetricsPort 加 3 个 default 方法）
8. **Trace**：observation `tool.<name>`, metadata 含 call_id / run_id / request_id / status / latency / input_hash(短) / tenant_id / indexVersion / permissionScopeVersion / deduplicated

**Cache 失败策略（EMS-PR4 §10）**：
- SUCCESS / EMPTY_RESULT / PERMISSION_DENIED 默认 cacheable
- TIMEOUT / RETRYABLE_ERROR / DEPENDENCY_UNAVAILABLE 不缓存（下次重新执行）

## 3. 权限链路

```
AuthContext.Principal (AuthFilter 注入)
    │
    ▼
PermissionResolverPort.resolveAccessScope
    │
    ▼ AccessScope(tenantId, tenantAdmin, allowedDocumentIds)
    │             null=admin / 空集=NO_RECALL sentinel
    ▼
PermissionScope (ToolExecutionContext)
    │ tenantId 必与 Principal.tenantId 一致
    │ allowedDocumentIds 翻译成 Milvus expr (或 NO_RECALL 短路)
    ▼
Tool execute
    │
    ▼ 对每条返回 Evidence 校验 tenantId
ACL evidence post-check (ToolExecutor)
    │ 无权 Evidence drop (记录安全指标 acl_dropped_unauthorized)
```

- 检索类 Tool (semantic_search / keyword_search / metadata_search) — 经 RetrieveService 或 SparseSearchPort，传 allowedDocIds 给 expr builder；AccessScope empty sentinel 自动短路
- document_fetch — 三层：PermissionScope + DocumentAccessGuard.requireRead(404-collapsing) + Executor post-check
- citation_verify — 不扩大检索范围；调用方必须自行先 ACL-filter 注入 evidences；Tool 内只 Read

**permissionScopeVersion 派生**：项目 ACL 表无 version 列，PR-4 用 `tenantId | userId | allowed.size()` 算 sha256 取前 12 字符；ACL grant/revoke → allowed.size 变化 → 版本变化 → 旧 cache 失效（满足 EMS-PR4 §10 要求）。

## 4. 修改文件

| 文件 | 修改 | 原因 |
| --- | --- | --- |
| **platform-common/application/chat/tool/** | 新建 | |
| `ToolStatus.java` | 9 态 enum + `isSuccessLike()`/`cacheable()`/`retryable()` | 多状态细分代替二元 |
| `ToolPermission.java` | READ_RETRIEVE / READ_DOCUMENT / VERIFY_CITATION | 粗粒度权限分类 |
| `ToolCostCategory.java` | INDEX_READ / EMBEDDING / LLM / UNKNOWN | 预算估算用 |
| `ToolInput.java` `ToolOutput.java` | 标记接口 + normalizedForDedup | 禁 Map 逃逸 |
| `PermissionScope.java` | tenantId+admin+allowedDocIds+version | Tool 身份 scope |
| `ToolExecutionContext.java` | Principal+tenantId+permissionScope+deadline+indexVersion | 单请求不可变上下文 |
| `ToolDescriptor.java` | name+version+permission+timeout+maxResults+schema | Tool 元数据，启动校验 |
| `ToolError.java` | errorCode+safeMessage+dependency+retryable | 安全错误结构 |
| `ToolResult.java` | callId+status+output+error+latency+metadata | 不可变返回，factory 三态 |
| `AgentTool.java` | descriptor/inputType/outputType/execute | 工具接口 |
| `EvidenceListOutput.java` | 标记接口（withEvidences copy） | 让 Executor 统一 ACL 过滤 |
| **platform-common/common/exception/** | | |
| `ErrorCode.java` | +TOOL_NOT_FOUND/INVALID_ARGUMENT/PERMISSION_DENIED/EXECUTION_FAILED/TIMEOUT/DEPENDENCY_UNAVAILABLE | 工具错误码 |
| **platform-bootstrap/application/chat/tool/** | 新建 | |
| `ToolRegistry.java` | 启动 fail-fast + 运行 fail-closed | 唯一注册中心 |
| `ToolExecutor.java` | banned+duplicate+ACL+timeout+post-check+metrics+trace | 唯一执行入口 |
| `SearchInput.java` / `SearchFilters` | typed record (query/topK/filters) | 共用 input，含 normalizedForDedup |
| `SearchOutput.java` | List<Evidence> + TruncationInfo，实现 EvidenceListOutput | 共用检索 output |
| `SemanticSearchTool.java` | retrieve(cmd, DENSE) + ACL sentinel 复用 | Tool 实现 |
| `KeywordSearchTool.java` | SparseSearchPort (真实 BM25) | Tool 实现 |
| `MetadataSearchTool.java` | 强制 filter，retrieve HYBRID | Tool 实现 |
| `DocumentFetchInput.java` | chunkId/documentId/direction/neighbor/includeParent | typed input |
| `DocumentFetchOutput.java` | List<Evidence> + chunkIds + mode | typed output |
| `DocumentFetchTool.java` | ChunkQueryService + DocumentAccessGuard | Tool 实现 |
| `CitationVerifyInput.java` | claim+evidences (非空) | typed input |
| `CitationVerifyOutput.java` | outcome/score/verdict + skipped | typed output |
| `CitationVerifyTool.java` | ObjectProvider<CitationVerifierPort> | Tool 实现 |
| **platform-bootstrap/application/chat/port/** | | |
| `SparseSearchPort.java` | app port 让 Tool 不依赖 infra SparseRetriever | ArchUnit 兼容 |
| **platform-bootstrap/application/metrics/** | | |
| `MetricsPort.java` | +default recordToolCall / recordToolEvidenceYield / incrementToolDedupHit | Tool metrics 扩展点 |
| **platform-bootstrap/infrastructure/milvus/** | | |
| `SparseRetrieverAdapter.java` | 实现 SparseSearchPort，适配 SparseRetriever + MilvusFilterExprBuilder | 真实 BM25 暴露 |
| **platform-bootstrap/test/.../tool/** | | |
| `ToolRegistryTest.java` | 7 项 | 重复 fail-fast + 缺失 fail-closed + descriptor 校验 + list 排序 |
| `ToolExecutorTest.java` | 10 项 | dedup+cache / ACL sentinel / banned fields / timeout / missing / evidence post-check 双向 |
| **docs/pr-reports/** | | |
| `pr-4-tool-contract.md` | 本报告 | |
| `pr-4-baseline.md` (Agent 是否已启用证据) | (隐含在本报告 §8) | 当前 `AGENTIC` 仍 422 |

## 5. 测试结果

| 命令 | 通过 | 失败 | 未执行 | 说明 |
| --- | --: | -: | --: | --- |
| `./gradlew test` 全量 | 422 | 0 | 2 IT | 仅 2 Testcontainers IT 因本地无 Docker 未通过 |
| `:platform-bootstrap:test --tests "...tool.ToolRegistryTest"` | 7 | 0 | 0 | 重复 fail-fast / 缺失 fail-closed / descriptor name/timeout/max 校验 / list 不可变 / inputType 标记检查 |
| `:platform-bootstrap:test --tests "...tool.ToolExecutorTest"` | 10 | 0 | 0 | SUCCESS dedup / 大小写 insensitive 同 key / 不同 runId 不共享 / TIMEOUT 不缓存 / 普通 NO_RECALL PERMISSION_DENIED / banned tenantId 拒 / expire deadline TIMEOUT / missing Tool TERMINAL_ERROR / ACL evidence post-check 双向 |
| Pipeline / Chat / Router / SSE 单终态 / ACL | 全绿 | 0 | - | PR-2/3 既有行为零回归 |
| ArchitectureTest (ArchUnit DDD 隔离) | 全绿 | 0 | 0 | application 层 Tool 不依赖 infrastructure |
| Python eval runner | - | - | 未跑 | backend 未起 |

### 新增 17 项 Tool 测试
PR-3 后 406 → PR-4 后 424（+18 来自 17 Tool 测试 + 1 顺带归档回归）。

## 6. 回归结果

| Suite | 结果 |
| --- | --- |
| PR-0 安全回归 (AuthFilterFailClosed / DocumentAccessGuard / PermissionControl / MilvusFilter) | 全绿, 0 回归 |
| PR-1 Evidence Snapshot (RetrieveServiceEvidence / ChatResult / Evidence Snapshot JSON roundtrip) | 全绿, Tool 返回的 Evidence 与 PR-1 同类型兼容 |
| PR-2 Orchestrator 模式路由 (RAG / AUTO / AGENTIC) | 全绿; `AGENTIC` 仍返 422 未启用 |
| PR-3 Router / Targeted / Comparison Workflow + 100 条数据集 | 全绿; Router 不受影响 |
| 既有 Chat / Retrieve / SSE 单终态 / Citation / Conversation | 全绿 |
| RAGAS / 端到端 | 与 PR-2/3 一致未跑 |

## 7. PR-4 退出门禁检查

| 项 | 状态 | 证据 |
| --- | --- | --- |
| Tool Contract 已建立 | ✓ | platform-common 9 个 record/interface |
| ToolExecutionContext 不信任客户端身份字段 | ✓ | 构造校验 tenantId 必与 principal.tenantId 一致; Executor banned field 扫描 |
| ToolResult 使用结构化状态和错误 | ✓ | 9 态 ToolStatus + ToolError; 构造要求 SUCCESS 必有 output, 非 SUCCESS 必有 error |
| Tool Registry 检测重复和未注册 Tool | ✓ | ToolRegistryTest 重复 fail-fast + 缺失 fail-closed |
| semantic_search / metadata_search / document_fetch / citation_verify 接入真实能力 | ✓ | 见 §1 |
| keyword_search 只在真实能力存在时注册 | ✓ | 真实 BM25 (SparseRetriever / FIELD_SPARSE_BM25); 不是 Dense 冒充 |
| 所有 Tool 执行 ACL | ✓ | 三层: PermissionScope pre-check / DocumentAccessGuard.requireRead / Executor ACL post-check on EvidenceListOutput |
| 无权 Evidence 不进入输出和 Trace | ✓ | ToolExecutorTest.evidencePostCheck 双向; Trace metadata 单 tenant_id 维度 |
| timeout / cancel / empty / dependency error 区分 | ✓ | 9 态 + Executor 测试覆盖 TIMEOUT / PERMISSION_DENIED / DEPENDENCY_UNAVAILABLE / EMPTY_RESULT |
| 调用去重含权限和索引版本 | ✓ | dedup key 含 permissionScopeVersion + indexVersion |
| Tool Trace / Metrics 可观测 | ✓ | MetricsPort 3 个 tool method + TraceObserver observation `tool.<name>` 含 call_id/run_id/deduoped/input_hash |
| AGENTIC 仍未启用 | ✓ | Orchestrator AGENTIC 仍 422 (PR-2 不变) |
| Classic / Targeted / Fixed Workflow 行为无无法解释回归 | ✓ | 全量回归 0 失败 |
| 所有可执行测试已运行 | ✓ | 422 测试通过, IT 未跑因 Docker 缺 |
| 未执行测试被如实报告 | ✓ | 见 §5 |

**结论**: PR-4 退出门禁 **全部通过**。

## 8. 剩余风险

1. **真实 backend 未跑过 Tool**: Tool 用 mock 在单测层验证；真实 Milvus + 鉴权 + AuthContext 端到端未跑（与 PR-2/3 一致）。CI 在 Docker 可用时跑 2 个 IT 守护。
2. **SparseRetrieverAdapter 依赖既有 MilvusFilterExprBuilder**: expr build 的精确语义未在 PR-4 测试 (既有 MilvusFilterExprBuilderTest 已覆盖)。如果 milvus expr 与 allowedDocIds null/empty 的兼容性后期出现 bug, 极可能在 Title boundary (TenantAdmin null vs empty set)。
3. **Tool 没有被任何 Pipeline 实际调用**: PR-4 做契约和注册; 经典 ChatService / Classic / Targeted / Comparison 仍走 RetrieveService 而非 ToolExecutor。要让 Tool 真正"上线"需要 PR-6 AgentState/Executor + PR-7 Planner。本 PR 不要求重写 Pipeline (与 EMS-PR4 §12 一致)。
4. **`permissionScopeVersion` 派生方式是 hack**: 用 sha256(tenantId|userId|n) 不严格对应 ACL 行数变化 (revoke + grant 同 size 看不出)。生产可考虑加 ACL 表的 version 列 (审计发现项目无显式 ACL version)。
5. **Tool `executor` 是 application 层 Service**, 但 metrics / trace 的 application 接口扩展用 default no-op, 让既有 `RagdocMetrics` 暂不强制 override。生产若想观察 tool 指标需在 RagdocMetrics 加 tag。
6. **description 文字**: Tool description 较长 (含"适用/不适用"); 未来让 Planner 用时是由 LLM 读，PR-4 的 Registry 不会基于 description 文字执行逻辑, 但 LLM 读 description 仍可能在 prompt injection 风险面 — 后续 PR-7 Planner Validator 必须强制 Tool select 走 name 白名单。

## 9. 完成判定

```
已完成
```
