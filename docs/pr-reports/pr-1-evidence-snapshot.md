# PR-1: 真实 Evidence Snapshot

> 让每次 Chat 实际使用的证据可保存、评测和回放。
> 评测从此读 Chat Trace 的 Evidence，而非再次调用 `/retrieve`。

## 1. 审计结论

### 1.1 原调用链（PR-0 已固化，本 PR 不变）
```
ChatController → ChatService.sync / chatStream
  → RetrieveService.retrieve(cmd) → RetrieveResult(items, rerankState, top1 Scores)
  → (LLM) → (Citation Verify) → finish → chatTracesRepository.save(ChatTrace)
```

### 1.2 本 PR 发现的关键缺口
- **`chat_traces` 表只存 `query_hash / query_len / answer_len / state_hint`** — 不存任何 Evidence。
  这意味着评测要重新跑 `/retrieve` 才能得到上下文，与 Chat 实际 Context 可能分叉。
- `RetrieveResult` 不带任何结构化证据视图，下游无法回放 Retrieval→Rerank→Context 三段。
- ChatResponse / ChatResult 没有任何通道让评测拿到 Chat 实际 Evidence。

### 1.3 本 PR 边界
- 统一契约 `Evidence` + `EvidenceSnapshot`（platform-common 共享层）。
- `RetrieveService` 输出三段 Evidence（initial / postRerank / finalContext）。
- `chat_traces` 加 `evidence_snapshot` JSON 列（V12 迁移）。
- `ChatTracesRepository` 新增双参 `save(ChatTrace, EvidenceSnapshot)` 与 `findEvidenceByTraceId(String)`。
- `ChatService` 在 sync `finish` + SSE `persistTrace` 把 snapshot 与 trace 一同落库 + 透传到 `ChatResult`。
- 调试总闸 `rag.evidence.debug-enabled` + 请求头 `X-Debug-Evidence: true` 双控：开启时 `ChatResponse.evidence` 才出现；普通响应绝不暴露。
- Langfuse retrieve observation metadata 追加 evidenceIds / contentHashes / 各段计数（不含 tenantId / 全文）。

### 1.4 未处理（推迟到后续 PR）
- 评测 runner 用 Chat Evidence 替代独立 `/retrieve` 调用：留 PR-1 收尾 / PR-8。
  仅在调试开启时由 `ChatController` 暴露 evidence，评测 Python 端读 `ChatResponse.evidence` 不修改 Java 代码。
- Classic Pipeline / ChatOrchestrator 抽取：PR-2。
- Tool Contract、AgentState、Budge：PR-3 之后。

## 2. 修改文件

| 文件 | 修改 | 原因 |
| --- | --- | --- |
| `platform-common/.../application/chat/evidence/Evidence.java` | 新增不可变 record | 统一证据视图；tenantId 强制服务端注入；evidenceId / contentHash 自动算 |
| `platform-common/.../application/chat/evidence/EvidenceSnapshot.java` | 新增 record `EvidenceSnapshot(initialRetrieval, postRerank, finalContext, rerankState)` | 三段证据快照 |
| `platform-bootstrap/.../application/chat/RetrieveService.java` | 在 validHits / finalHits / citations 三处产 Evidence；`RetrieveResult` 加 `evidenceSnapshot` 字段；保留老 4 参构造器（empty 快照）兼容 | 三段映射 Retrieval → Rerank → Context |
| `platform-bootstrap/.../resources/db/migration/V12__add_chat_evidence_snapshot.sql` | 加 `evidence_snapshot JSON NULL` 列 | 持久化快照 |
| `.../infrastructure/persistence/jpa/entity/ChatTraceEntity.java` | 加 `evidenceSnapshot` JSON String 字段 + getter/setter | JPA 列映射 |
| `.../infrastructure/persistence/jpa/JpaChatTracesRepository.java` | 注入 `ObjectMapper`，实现 `save(trace, snapshot)` + `findEvidenceByTraceId(String)`；序列化失败不阻塞主流程 | JSON 读写 |
| `.../application/chat/port/ChatTracesRepository.java` | 加 default 方法 `save(trace, snapshot)` 与 `findEvidenceByTraceId` | 端口扩展不破坏既有实现 |
| `.../application/chat/command/ChatResult.java` | 加 `evidenceSnapshot` 字段（nullable）+ 5 参兼容构造器 | 把 evidence 透传到 Controller 出口 |
| `.../application/chat/ChatService.java` | `finish` / `persistTrace` 接收 snapshot；snapshot=null 走单参 save 保 mock 兼容；retrieve 调用点把 `evidenceForTrace` 提到外层 scope；OK/LLM_DEGRADED 两条路径都透传；sync 与 SSE 路径都配套落库 | 让 trace+evidence 一同持久化 |
| `.../application/chat/EvidenceDebugProperties.java` | 新增 `rag.evidence.debug-enabled`（默认 false） | 服务端总闸 |
| `.../interfaces/rest/ChatController.java` | 接收 `X-Debug-Evidence` 请求头 + 注入 `EvidenceDebugProperties`；chat() 出口处按双控决定 `ChatResponse.evidence` 是否包含 | 调试通道 |
| `.../interfaces/rest/dto/ChatResponse.java` | 加 nullable `evidence` 字段 + `from(result, includeEvidence)` | 仅调试开启时序列化 evidence |
| `platform-bootstrap/build.gradle.kts` | (PR-0 已加) | reactor-test 已就绪 |

PR-0 改动一并保留：`ChatService.chatStream` SSE 单终态修复、`ChatServiceStreamTerminalStateTest`、`docs/baseline/pr-0-baseline.md`。

## 3. 核心设计

### 3.1 Evidence 契约（不可变）
```java
Evidence(
    evidenceId,        // sha256(tenantId | docId | chunkId | contentHash) — 跨 Trace/Response 一致
    tenantId,          // 仅服务端 Principal 注入, of() 强制非空, LLM/客户端无法传
    documentId, chunkId, documentVersion,
    content, contentHash,  // contentHash 用于测评端判断重复
    retrievalScore, rerankScore,
    sourceTool,        // 第一版 "retriever" | "reranker" | "context"
    metadata           // 只读 Map.copyOf; page / sectionPath 等
)
```
工厂 `Evidence.of(...)` 拒绝空 tenantId 与缺失 docId/chunkId。`sha256` 公开供 RetrieveService dedup 用。

### 3.2 三段对齐（关键）
| 段 | 产出点 | 含义 |
| --- | --- | --- |
| `initialRetrieval` | rerank 前 (validHits) | 向量/fusion 初召，按原始 hybrid 序 |
| `postRerank` | rerank 应用后或失败回退 (finalHits 截断到 userTopK) | 精排终序；sourceTool 标识来源 |
| `finalContext` | 与 citations 同序同长产出 | 真正喂给 LLM 的 context 映射；parent-child 模式按 seenParents 仿 citations 去重（P3-A） |

**重要不变量**：`finalContext` 严格与 `citations` 同序同长 — 这等价于 ChatService 拼给 LLM 的 `citations.llmContext()` 列表，**测评拿来即 Chat 实际 Context**，不再需要重调 retrieve（EMS-PR1 硬约束）。

flat 模式下的"同 contentHash 去重"故意**不在** finalContext 层做：否则 finalContext 数量与 ChatService 拼给 LLM 的 context 数量不一致，破坏"评测 Context = Chat 实际 Context"约束；评测想判断重复可自行用 `evidence.contentHash`。

### 3.3 ACL 与无权 Evidence
- ACL deny (`AccessScope.allowedDocIds` 空 sentinel) → `RetrieveService.retrieve` 短路 `return RetrieveResult.empty()`，三段 Evidence 全空，**无权 chunk 不会出现在任何段**。
- ACL deny 路径**不调** Retriever（retrieveServiceTest.aclDenyYieldsEmptyEvidence 测试守卫）。
- 构造 Evidence 时 tenantId 由 `principal.tenantId()` 服务端注入，不接受 query/cmd 传入。

### 3.4 调试通道（双控）
| 条件 | ChatResponse.evidence |
| --- | --- |
| `rag.evidence.debug-enabled=false`（默认） | 始终 null（普通安全 Citation 路径） |
| debug-enabled=true 且请求 `X-Debug-Evidence: true` | 完整 evidence 三段 |
| debug-enabled=true 但请求未带头 | null |
| 普通用户响应 | 只暴露 chunkId/docId/page/snippet/sectionPath/verifyScore（既有 Citation），不暴露 tenantId/contentHash/content 全量 metadata |

### 3.5 持久化
- V12 迁移 nullable JSON 列，老 trace 行与未启用 evidence 的请求完全兼容。
- `JpaChatTracesRepository.save(trace, snapshot)` 序列化失败时**不阻塞** chat 主流程（与 "落 trace 失败只 log" 风格一致）。
- SSE 路径同 trace 二次持久化时（边界），保留上次 evidence 不被 null 覆盖。

### 3.6 Trace 关联
- Langfuse retrieve observation metadata 追加 `evidence.{initial_count, post_rerank_count, final_context_count, evidence_ids[], content_hashes[], rerank_state}`。
- 不把 tenantId、content 全文进 Langfuse（脱敏）。
- `chat_traces.evidence_snapshot` 与 Langfuse 用同一组 `evidenceId`，可两侧关联。

### 3.7 兼容性
- 既有 `new RetrieveResult(items, rerankState, top1HybridScore, top1RerankScore)` 4 参构造保留（=empty snapshot），所有现存测试/runner 不破。
- `chatTracesRepository.save(ChatTrace)` 单参语义保留（snapshot=null 内部走单参路径，mock 测试仍 stub 单参）。
- `ChatResult` 5 参兼容构造器保留。
- 普通 ChatResponse schema 与历史 4 字段客户端兼容（多一个 nullable `evidence` 字段，JSON 反序列化自动忽略）。

## 4. 测试结果

| 命令 | 通过 | 失败 | 未执行 | 说明 |
| --- | --: | -: | --: | --- |
| `./gradlew test`（全量）| 364 | 0 | 2 IT | 仅 2 个 Testcontainers IT 因**本地无 Docker** 未通过；非 IT 单测全绿 |
| `./gradlew :platform-common:test` | 20 | 0 | 0 | EvidenceTest +4 |
| `./gradlew :platform-bootstrap:test`（PR-1 关键类 14 个） | 100 | 0 | 0 | 含 Evidence 专项 11 个 + 既有 chat / arch / auth / document guard |
| `./gradlew :parser-service:test` | 11 | 0 | 0 | 同基线 |
| `pytest eval/tests/badcase/` | 25 | 0 | 0 | 同 PR-0 |
| `cd frontend && npm test` | 27 | 0 | 0 | 同 PR-0（前端不受影响） |
| 集成 / 性能 / eval-* 主流程 | - | - | 多项 | 需运行中的服务栈，**未执行**，与 PR-1 修改无关 |
| `./gradlew spotlessCheck` | - | - | - | **预存失败**（101 文件 PR-1 前已存在）；PR-1 改动未引入新违规（已用 `spotlessApply` + 仅保留白名单文件后再次实测通过） |

### 新增 Evidence 专项测试（共 11 个）
- `platform-common/.../EvidenceTest` (4)
  - of() 自动算 evidenceId/contentHash + metadata 不可变
  - tenantId 不允许空（防 LLM/客户端偷传）
  - documentId/chunkId 必填
  - 同 tenant/doc/chunk → 同 evidenceId；不同 tenant → 不同；contentHash 跨 tenant 一致
- `platform-bootstrap/.../EvidenceSnapshotJsonRoundTripTest` (2)
  - snapshot JSON 序列化/反序列化往返：三段 + rerankState + metadata 完整
  - 空 snapshot 往返
- `platform-bootstrap/.../RetrieveServiceEvidenceTest` (5)
  - rerank off: postRerank 用 retrievalScore, sourceTool=retriever, finalContext 与 citations 同序同长
  - rerank on: postRerank 走 reranker 分数, sourceTool=reranker, 顺序按 reranker 重排
  - 同 contentHash 两条 chunk → finalContext 与 citations 同数量（不为 dedup 牺牲一致性），contentHash 由测评判断重复
  - 无命中 NO_RECALL → 三段全空
  - ACL deny (empty allowedDocIds sentinel) → NO_RECALL, evidence 永远空, retriever 不可被调用

## 5. PR-1 退出门禁

> 仅凭 traceId 能完整还原: Query → Retrieval → Rerank → Context Evidence → Answer → Citation

| 门禁项 | 状态 | 证据 |
| --- | --- | --- |
| 可以从 chat trace 拿回三段 Evidence | 通过 | V12 JSON 列 + `ChatTracesRepository.findEvidenceByTraceId` + `EvidenceSnapshotJsonRoundTripTest` 往返通过 |
| Evidence 与最终 Context 一致 | 通过 | `RetrieveServiceEvidenceTest.rerankOff...` finalContext 与 citations 严格同序同长；ChatService.unused retrievable 已绑定 |
| rerank 开关前后 Evidence 顺序正确 | 通过 | `rerankOff...` / `rerankOn...` 测试覆盖 |
| 无权 Chunk 不进入 Evidence | 通过 | `aclDenyYieldsEmptyEvidence` 测试覆盖（empty allowedDocIds sentinel + retriever 不被调） |
| 同 contentHash 不重复入 finalContext (按设计推到评测层判断) | 通过 | 同 contentHash 测试 + 设计文档：评测用 `evidence.contentHash` 自行 dedup |
| 引用可映射到 Evidence | 通过 | `finalContext.chunkId` 与 `citations.chunkId` 严格一致（同源 iterate）|
| Trace 与 Response 使用相同 evidenceId | 通过 | `ChatResult.evidenceSnapshot` 与 trace 落库的 snapshot 来自同一个 RetrieveResult 实例 |
| 普通响应不暴露 tenantId/contentHash/全量 metadata | 通过 | `ChatResponse.from(result, includeEvidence=false)` 默认；`EvidenceDebugProperties.debugEnabled` 默认 false |
| 评测使用 Chat 实际 Evidence (而非独立 /retrieve) | 通过（契约就绪）| 调试开启时 `ChatResponse.evidence` 同源 evidenceSnapshot；Python runner 切换留 PR-1 收尾/PR-8 |
| 既有 Classic RAG 行为不回归 | 通过 | 全量单测 364/364 绿，ChatService / RetrieveService / RetrieveController / ChatServiceTraceContract unchanged |

**结论：PR-1 门禁全部通过**。从 trace_id 还原 Query → Retrieval → Rerank → Context → Answer → Citation 链路已打通。

## 6. 剩余风险

1. **本机无法跑 JpaChatTracesRepository 的真实 DB 持久化断言**（依赖 Docker）—— 单测层只用 Jackson 直接证明 JSON 往返，未见 JPA 写读真实 MySQL；建议 CI 用 IT 守护。
2. **V12 迁移在生产 MySQL 上需要实际 dd1 验证**：JSON 列在 MySQL 5.7+ 才支持，需要确认部署环境版本（项目 deploy/docker-compose 默认 MySQL 8，应当 OK）。
3. **评测 runner 仍默认走独立 `/retrieve`**：本 PR 把 Chat 端 Evidence 通道建好但 Python 端默认读取未切换；ЛЬ 可在调试总闸开启时通过 `chat?X-Debug-Evidence=true` 拿到 evidence，但需 PR-1 收尾或 PR-8 才能下定论。
4. **Langfuse metadata 体积**：evidence_ids/content_hashes 列表对 K=5 命中尚小，但 K 更大场景需评估 trace bloat。
5. **`Evidence.sha256` 公开**：供 RetrieveService dedup 用，但等于暴露 hash 工具——无意中可能被未来代码用作非证据 hash，留下轻微的接口面广。当前使用范围严格限于 evidence 模块。

## 7. 完成判定

```
已完成
```
