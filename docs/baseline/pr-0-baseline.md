# PR-0: 基线、安全与契约冻结

> Agentic RAG 升级的第一步：固化基线、跑通现有测试、确认 P0 安全回归有效，
> 并修复在此过程中发现的真实缺陷。本 PR 不引入任何 Agent 功能。

## 1. 审计结论

### 1.1 当前 Classic RAG 调用链

```
ChatController (/api/v1/chat, /api/v1/chat/sse)
  → ChatService                        # 单体 orchestrator（PR-2 才抽 ClassicRagPipeline）
      ├─ AuthContext.currentPrincipal  # ThreadLocal，由 AuthFilter 注入
      ├─ documentRepository.countByStatus / findById (docId 校验 + EMPTY_KB 判断)
      ├─ retrieveService.retrieve(cmd)
      │     ├─ permissionResolverPort.resolveAccessScope(principal) → AccessScope
      │     │       # null  = unrestricted admin
      │     │       # empty = NO_RECALL sentinel（deny-by-default）
      │     ├─ VectorStore.MetadataFilter(tenantId, allowedDocIds, source, version, ...)
      │     ├─ Retriever.search (Dense / Hybrid via RRF) → Milvus
      │     ├─ MySQL chunk rehydrate (text 不存在向量里)
      │     ├─ (optional) RerankClient.rerank (BGE，失败回退 hybrid 序)
      │     └─ parent/child llmContext 解析 → Citation(snippet/score/sectionPath)
      ├─ chatClient.chat(query, context)         # LlmRouter primary→fallback (CB)
      │   或 chatClient.chatStream(...) -> Flux<Delta>
      ├─ (optional) CitationVerifierPort.verify  # NLI LLM，REFUSE/WARN_ONLY，默认关
      ├─ traceObserver.observe / endTrace        # Langfuse / NoOp
      └─ chatTracesRepository.save(ChatTrace)    # 持久化（仅基础字段，无 Evidence）
```

### 1.2 状态机（StateHint）

| StateHint      | 触发                                     | 落 trace? | LLM?  |
| -------------- | -------------------------------------- | ------- | ----- |
| EMPTY_KB       | READY 文档数为 0                           | 是       | 否    |
| NO_RECALL      | 检索结果为空（ACL deny 也走此路）                  | 是       | 否    |
| DOC_NOT_READY  | 指定 docId 状态非 INDEXED → 409             | 否       | 否    |
| OK             | 检索有命中 + LLM 成功                          | 是       | 是    |
| LLM_DEGRADED   | 检索有命中 + LLM 抛错 → 兜底文案 + trace_id        | 是       | -    |
| VERIFY_FAILED  | CitationVerifier OnFail=REFUSE → 拒答文案   | 是       | 是    |

### 1.3 认证与 ACL（Task 11 P0，PR-0 验证仍有效）

- AuthFilter：无 token / 格式错误 / 未知 token → **401**；DB 异常 → **500**（不静默 fallback）；
  develop profile + `rag.auth.dev-default-principal-enabled=true` 才允许 magic token。
  `finally` 清 ThreadLocal 防串号。
- AccessScope sentinel：`null` = 全租户 admin；空 Set = NO_RECALL（不返回任何 chunk）。
- MilvusFilterExprBuilder：`allowedDocIds` 空 → `ALWAYS_FALSE`（保证无权用户拿不到向量）。
- DocumentAccessGuard：跨租户 / 不存在 / ACL 缺失 一律塌缩成 404（反枚举）。

### 1.4 此次发现并修复的缺陷

**SSE 双终态 bug（P0 严重）**：`ChatService.chatStream` 原先把 `onErrorResume` 写在 `concatWith(OK defer)` 之前。
LLM 抛错时，`onErrorResume` 把错误替换为 `Flux.just(DoneEvent(LLM_DEGRADED))`，该 Flux 完成后，
下游 `concatWith(OK defer)` 仍被订阅，**再发一个 `DoneEvent(OK)`** → 一个失败流连发两个终态事件。

修复：把 `concatWith(OK defer)` 与 `onErrorResume` 的顺序对调——OK 终态接在源 Flux 后、错误冒泡时不订阅；
最后的 `onErrorResume` 收下错误转成唯一的 `DoneEvent(LLM_DEGRADED)`。单终态由测试钉死。

### 1.5 已知未处理 / 推迟到后续 PR

- `chat_traces` 表当前只存 `query_hash / query_len / answer_len / state_hint`，**不存 Evidence / Context**。
  这是 PR-1 的核心工作。
- ChatService 是单体 orchestrator（1500+ 行），未抽 `ClassicRagPipeline` / `ChatOrchestrator` —— 推迟到 PR-2。
- 101 个文件 spotless 违规为**项目预存状态**（PR-0 前已存在），不在本 PR 处理范围。

## 2. 修改文件

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `platform-bootstrap/src/main/java/com/xxx/ragdoc/application/chat/ChatService.java` | 调换 `chatStream` 链中 `concatWith(OK)` 与 `onErrorResume(DEGRADED)` 顺序 | 修复 SSE 双终态：LLM 失败流连发 DEGRADED + OK 两个终态事件 |
| `platform-bootstrap/build.gradle.kts` | 新增 `testImplementation("io.projectreactor:reactor-test")` | 给 SSE 流单终态/取消传播测试用 StepVerifier |
| `platform-bootstrap/src/test/java/.../ChatServiceStreamTerminalStateTest.java` | 新增 6 个测试 | 钉死 SSE 单终态不变量 + 客户端取消传播 |
| `docs/baseline/pr-0-baseline.md` | 新增本文件 | 记录 PR-0 基线 |

## 3. 核心设计

### 状态机 / 接口
- 完全不变。SSE 仍产 `citations → delta* → done`；EMPTY_KB/NO_RECALL 仍各产 1 个 `done`。
- LLM 失败由 `done(LLM_DEGRADED)` 终止，**不再叠加** `done(OK)`。

### 权限 / 安全
- 不动 AuthFilter / AccessScope / DocumentAccessGuard / MilvusFilterExprBuilder。
- P0 安全回归全靠现有测试覆盖，新测试纯加 SSE 流层面。

### SSE 单终态不变量（chatStream）
- 成功流：`CitationsEvent` + N×`DeltaEvent` + 1×`DoneEvent(OK)`，无 `ErrorEvent`。
- 失败流：`CitationsEvent` + 可选 K×`DeltaEvent` + 1×`DoneEvent(LLM_DEGRADED)`，无 `OK DoneEvent`、无 `ErrorEvent`。
- EMPTY_KB / NO_RECALL：仅 1×`DoneEvent(state)`，无 `CitationsEvent`。
- 客户端断开（subscriber cancel）：通过 Reactor `doFinally` 触发 endTrace + metrics；上游 LLM 流被 cancel，不再产 token。

### 兼容性
- 旧 API 响应 schema 不变；DONE 事件 stateHint 取值不变。
- 唯一行为变化：失败流少了**冗余的第二个 `done(OK)` 事件**。前端原本 `onComplete` 收尾，多发事件被忽略；
  修复后行为更符合规范，理论上不会破坏前端。

## 4. 测试结果

| 命令                                                                                                 | 通过  | 失败 | 未执行 | 说明 |
| -------------------------------------------------------------------------------------------------- | --: | -: | --: | --- |
| `./gradlew test`（全量，含 IT）                                                                          | 353 | 0  | 2 IT | 仅 2 个 Testcontainers IT 因**本地无 Docker** 未通过 |
| `./gradlew :platform-bootstrap:test --tests <P0 关键类>`（10 类）                                        | 82  | 0  | 0   | AuthFilter / DocumentAccessGuard / PermissionControl / MilvusFilterExprBuilder / ChatService / CitationVerifier / TraceContract / RetrieveService / Architecture / **新 StreamTerminalState** |
| `./gradlew :platform-common:test`                                                                  | 16  | 0  | 0   |     |
| `./gradlew :parser-service:test`                                                                   | 11  | 0  | 0   |     |
| `cd frontend && npm test`（vitest）                                                                  | 27  | 0  | 0   | format.test (12) + chat.test (15) |
| `pytest eval/tests/badcase/`                                                                       | 25  | 0  | 0   | verdict / error_type 纯函数单测 |
| 需要真实 backend + LLM/Milvus/MySQL 的 `make eval-*`、`make eval-ab-*`                                   | -   | -  | 多项  | 本地无运行中的服务栈，**未执行**，与 PR-0 修改无关 |
| `make perf-run`（Locust）                                                                            | -   | -  | 1   | 需 backend 运行中 + Locust 已装，**未执行** |
| `./gradlew :platform-bootstrap:spotlessCheck`                                                      | -   | -  | -   | **预存失败**（101 文件违规，PR-0 前已存在；我的改动不引入新违规） |

### IT 未执行原因细节
- `JpaChunkRepositoryIT`、`JpaDocumentRepositoryP3IT` 依赖 Testcontainers 起 MySQL；本机无 Docker →
  `Could not find a valid Docker environment`。这是基础设施缺失，不是代码缺陷。
- 集成测试、Locust 性能测试需启动全栈中间件 + 应用，不在 PR-0 自动测试目标内。

## 5. PR-0 退出门禁

| 门禁项 | 状态 | 证据 |
| --- | --- | --- |
| P0 安全回归通过 | 通过 | AuthFilterFailClosedTest / DocumentAccessGuardCrossTenantTest / PermissionControlTest / MilvusFilterExprBuilderTest 全绿 |
| Classic RAG 基线已记录 | 通过 | 本文件 §1.1–§1.3 |
| SSE 单终态通过 | 通过（含修复一项真实 bug）| ChatServiceStreamTerminalStateTest 6/6 |
| 测试命令与结果已保存 | 通过 | 本文件 §4 |

**结论：PR-0 门禁全部通过**，可进入 PR-1。

## 6. 剩余风险

1. **Testcontainers IT 本地未跑**：`JpaChunkRepositoryIT` / `JpaDocumentRepositoryP3IT` 仅在 CI 跑。
   建议 CI 在 PR-0 后续 push 时仍跑这两个 IT，以持续保障 JPA 层正确。
2. **集成 / 性能 / eval 测试本地未跑**：底层服务未运行；如 CI 跑过请以 CI 结果为准。
3. **spotless 违规为项目预存**：不阻塞 PR-0，但建议后续单独清理。
4. **SSE 前端兼容**：双终态修复改了失败流多发的 `done(OK)` 事件。理论上前端忽略多余事件，
   但 PR-3 上线 SseEmitter 改造前需端到端跑过一次真实失败流验证。

## 7. 完成判定

```
已完成
```
