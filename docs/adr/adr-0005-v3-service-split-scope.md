# ADR-0005: V3 服务拆分范围 — parser + rag + llm-gateway（3 个）

- Status: Accepted
- Date: 2026-08-02

## Context

V3 原版规划「微服务化」列 11+ 服务: gateway / user / tenant / document / parser /
rag / llm-gateway / model-registry / feedback / eval / obs-agent。一次性大拆分有 3 个风险:

1. **未经验证的一次性大拆分必然出 5-10 个降级不可用**: V3 DoD-1 "任一服务 kill -9
   优雅降级" 是硬指标, 11 服务一次性拆完没经过 doomsday 演练 必然 5+ 服务降级路径不全。
2. **200 doc 流量级用不到 11 服务的细分**: 用户/租户/反馈等在 V3 无业务(无多租户, 无 tenant 用户),
   拆出空壳服务没意义。微服务的本质是「按变更隔离 + 团队边界」, 不是「看着像企业级」。
3. **4 周工时拆 11 服务严重低估**: 真实工时是 12+ 周(V3 原版规划只给 4 周)。

业界经验(微软 Mono-Repo → Microservice 渐进迁移 + Marttin Fowler "monolith first" 原则):
「**等单体出现真实痛点 → 拆该痛点对应的服务**」, 而非"看大纲一次性拆完"。

## Decision

V3 拆 **3 个服务**: parser-service / rag-service / llm-gateway。其余 8 服务推 V4 等真实压力出现再拆。

| 服务 | 拆出来 ROI | 命中 V3 DoD |
|---|---|---|
| **parser-service** | 解析是 CPU/IO 重活, 独立扩缩 + 中断恢复(retry queue) | DoD-1 + DoD-2 + DoD-4 |
| **rag-service** | 检索 + rerank 独立扩容 + 灰度召回切换 | DoD-6 |
| **llm-gateway** | 多 LLM router 起步(glm-4-plus 主 + glm-4-flash 兜底), 集中 cost / rate limit | DoD-1(llm kill 时 chat 走 NO_RECALL 兜底) |

**为什么是 3 个不是 8 个**:
- 每个都有当前架构上证明必要的边界价值(不是"为拆而拆")
- 4 周(V3 主线) 工时容得下 3 个的: 边界契约 + 灰度 + 任务总线
- 4 周后未做完的, 推 V4 ppm 列入「V3.5 patch」

## Alternatives Considered

| 方案 | 优点 | 缺点 | 选择 |
|---|---|---|---|
| **3 个服务(本 ADR)** | 工时合理 + 每服务有真实价值 + 命中多数 V3 DoD | 不如 11 个"看着企业级" | ✅ |
| **2 个(parser + rag)** | 工时更短 | llm-gateway 缺失 → 切 LLM 时改 chat-app 代码 → 临床 DoD-1 llm kill 路径不演示出来 | ❌ |
| **11+ 一次性全拆** | "全面企业级架构" 演示价值 | 工时 12+ 周(V3 原版低估) + DoD-1 必然失败 | ❌ |

## Consequences

**正面**:
+ 拆分范围清晰, V3 第 1-2 周可完成主线 + 第 3-5 周做 Langfuse/k3s/压测
+ chat-app 主流程改动最小(只换 ParserClient/RagClient/LlmClient 三个 Feign)
+ DoD-1 演练场景明确: kill -9 parser / rag / llm-gateway 任一 → chat 走对应降级路径

**负面**:
- 11 服务拆 3 个的 "差异" 需 README 解释(面试现场可能被追问 "为什么不拆更多")
- V4 多租户 / tenant 服务还需独立拆, 但 V3 不动这部分

**缓解**: README 加架构演进图, 明示「V3 = 3 服务, V4 = 8+」。

## Revisit

V3 完成后重新评估:
- 若 V3 压测发现某服务真实瓶颈 → V4 优先拆该服务
- 若 V3 完成后客户/演示需求出现新服务请求 → 按业务驱动拆(非想象驱动)
