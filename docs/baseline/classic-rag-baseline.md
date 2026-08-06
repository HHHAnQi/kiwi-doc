# Classic RAG Evaluation Baseline（冻结于 PR-2 后 / PR-3 前）

> 用途：作为后续 Router / Targeted RAG / Fixed Workflow / Agentic RAG 的回归对照锚点。
> 任何后续 PR 触发的 Classic RAG 评测显著变化必须可解释。

## 1. 冻结点

- 代码状态：`main` HEAD = `28deee4 feat(rag): PR-2 抽取 ClassicRagPipeline + ChatOrchestrator`
- 调用链：`ChatController → ChatOrchestrator(mode=RAG|AUTO) → ClassicRagPipeline → ChatService.chat/chatStream`
- Classic RAG 业务代码路径与 PR-1 末状态字节级一致（PR-2 采用提取+委托，零搬运）

## 2. 当前已具备的客观证据（E2 等级，未跑 RAGAS）

| 维度 | 证据 |
| --- | --- |
| 单元/契约测试 | 382/382 非 IT 全绿；2 个 IT 仅因 Docker 缺失未跑 |
| ChatService 行为路径 | EMPTY_KB / NO_RECALL / OK / LLM_DEGRADED / VERIFY_FAILED 测试覆盖（ChatServiceTest 等 14 项） |
| Evidence Snapshot | PR-1 三段证据完整（initialRetrieval / postRerank / finalContext 严格与 citations 同序同长） |
| SSE 单终态 | ChatServiceStreamTerminalStateTest 6/6（PR-0 修复后） |
| ACL 安全回归 | AuthFilterFailClosedTest / DocumentAccessGuardCrossTenantTest / PermissionControlTest / MilvusFilterExprBuilderTest 全绿 |
| Pipeline 重构不回归 | ChatOrchestratorTest 8 / ClassicRagPipelineTest 4 / ChatPipelineRegistryTest 3 |

**关键不变量**：`Classic RAG 路径完全委托 ChatService 同一方法`（PR-2 ClassicRagPipeline.execute/stream），因此 retrieval / embedding / rerank / context / prompt / LLM / citation 字段全部与改动前字节级一致，**理论上**不应产生功能性回归。

## 3. 未补齐的 E3 等级证据

PR-2 / PR-3 之间，未跑：
- 真实 backend 的 RAGAS 评测（Answer Correctness / Faithfulness / Citation Precision/Recall 等）
- Locust 性能基线
- Testcontainers JPA 集成测试（需 Docker）

按"务实推进，不阻塞架构演进"策略，这些在 PR-3 完成后或 CI 环境就绪后统一回归，不影响 PR-3 引入 Router / Targeted RAG / Fixed Workflow 不改 Retrieval/Generation 本身的判断。

## 4. PR-3 评测目标

PR-3 完成后，需要至少输出：
- `Classic RAG` vs `Router RAG` 在 router_cases 数据集上的 Intent Accuracy / Strategy Accuracy 对比；
- 在既有 eval/datasets/retrieval_eval.jsonl + multi_turn 上的 retrieval Recall@K / MRR 不回归；
- （可选）跑 RAGAS 拿 Answer Correctness / Faithfulness，证明 Router 选择不破坏既有回答质量。

## 5. Classic 配置默认值（冻结）

| 配置 | 默认 | 影响 Classic 路径 |
| --- | --- | --- |
| `rag.retrieve.mode` | `dense` | Dense Retrieval |
| `rag.retrieve.candidate-pool` | 4 | vector pre-fetch k |
| `rag.rerank.enabled` | false | 关 BGE rerank |
| `rag.citation-verifier.enabled` | false | 关 NLI verify |
| `rag.query-enhance.enabled` | false | 关 rewrite |
| `rag.conversation.enabled` | false | 关多轮 |

PR-3 Router 引入后，`AUTO` 路径根据 RouterDecision 可能用 Hybrid mode（低置信回退或 Targeted RAG），但 `RAG` 模式仍然沿用以上 dense 默认。
