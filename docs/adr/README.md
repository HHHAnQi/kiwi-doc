# 架构决策记录（ADR）

本目录记录项目中具有长期影响的技术决策。每篇 ADR 阐明上下文、决策、备选方案与代价，便于未来回溯与复盘。

## 索引

| # | 标题 | 状态 |
|---|---|---|
| [0001](./adr-0001-data-domain.md) | 数据领域选择：中文 SCA 生态技术文档 | Accepted |
| [0002](./adr-0002-vector-db.md) | 向量库选型：Milvus 2.5+ | Accepted |
| [0003](./adr-0003-trace-id-soft-ref.md) | trace_id 软引用：feedbacks 不加外键到 chat_traces | Accepted |
| [0004](./adr-0004-sla-tiered.md) | SLA 分层标尺修订：废弃端到端 p99<1s，改三层 SLA | Accepted |
| [0005](./adr-0005-v3-service-split-scope.md) | V3 服务拆分范围：~parser + rag + llm-gateway(3 个)~ **Superseded by ADR-0010(只保留 parser)** | Superseded |
| [0006](./adr-0006-v3-trace-langfuse.md) | V3 trace 选型：Langfuse over Jaeger（RAG 场景演示价值更高） | Accepted |
| [0007](./adr-0007-v3-k3s-on-autodl.md) | ~V3 部署：k3s on Autodl GPU~ **Superseded by ADR-0010(docker-compose 替代; V4 流量来时再启)** | Superseded |
| [0008](./adr-0008-evaluation-harness-lockdown.md) | 评价体系锁定：curated question + judge LLM lock + CI 门禁 | Accepted |
| [0009](./adr-0009-parser-service-decisions.md) | parser-service 拆分关键决策：RocketMQ + chunk-level 续点 + DLQ broker-native | Accepted |
| [0010](./adr-0010-v3-rebalance-cut-rag-llm-k8s.md) | V3 范围重平衡：砍 rag/llm-gateway/K8s, 3.5 周精简版 + 产品价值注入 | Accepted |

> ADR 只记录"重要取舍"，不记录日常细节。新增决策按编号追加；废止决策不删除，标记为 Deprecated 或 Superceded。

## 模板

```markdown
# ADR-XXXX: [决策标题]

- Status: Proposed | Accepted | Deprecated | Superceded by ADR-YYYY
- Date: YYYY-MM-DD

## Context
[业务约束、技术约束、团队约束]

## Decision
[最终决策，一句话]

## Alternatives Considered
| 方案 | 优点 | 缺点 |
|---|---|---|

## Consequences
+ 正面
- 负面
- 缓解

## Revisit
[重新评估触发条件]
```
