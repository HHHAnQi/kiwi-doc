# ADR-0006: V3 trace 选型 Langfuse over Jaeger

- Status: Accepted
- Date: 2026-08-02

## Context

V3 DoD-5 原文: "一次问答 trace 在 Jaeger span 清晰"。但 DoD 原文写 Jaeger 是设计阶段
未细究的默认选项。实测评估两种主流 trace 系统:

| 维度 | OpenTelemetry + Jaeger | Langfuse |
|---|---|---|
| 设计取向 | 通用 SRE / DevOps 调用链 (HTTP/DB/MQ 自动埋点) | LLM-app 特化(prompt+answer+chunks+metrics) |
| UI 演示价值 | HTTP span 堆栈, 偏技术 | 每次问答直接看 prompt→retrieved chunks→answer→4 metrics, 视觉直观 |
| 接入工时 | 各服务装 OTel SDK + 起 Jaeger collector + OTEL_EXPORTER 配置 | 单一 Langfuse SDK + 3-5 行 `@trace` 装饰器 |
| RAG 指标自动算 | ❌ Jaeger 不懂 RAGAS | ✅ Langfuse 直接接 RAGAS, 自动落 trace |
| token / cost 监控 | ❌ 需另接 Prometheus | ✅ 原生支持 |

**项目目标偏向**: RAG 演示 / 面试 / 客户展示场景 **多过** SRE 故障排查场景。

## Decision

V3 trace 系统**用 Langfuse 替代 Jaeger**(对应 DoD-5 字面 Jaeger 但本质要"问答 trace 清晰"):

- V3 第 3 周接入 Langfuse, 埋 "chat 一条链路" + 自动跑 RAGAS 落 trace
- OTel SDK 仍 import 但不在 V3 启用 collector(为 V4 SRE 视角留接口)
- V3 DoD-5 验收说明: Langfuse trace 贯通 retrieve → rerank → LLM 三段(等价于原文 Jaeger 三 span)

## Alternatives Considered

| 方案 | 优点 | 缺点 | 选择 |
|---|---|---|---|
| **Langfuse only(本 ADR)** | 演示价值高 + 工时小 + RAG 指标直接落 trace | 不通用, 跨服务调试弱 | ✅ |
| Jaeger only (原文) | 符合 DoD 字面 + 工业 SRE 标准 | RAG 演示效果平庸, 工时大 | ❌ |
| Langfuse + Jaeger 双跑 | 全覆盖 | trace 系统重复(都填 chat 链路) + 工时 +1 周 | ❌ |

## Consequences

**正面**:
+ V3 第 3 周工时降到 2 天就接完 trace(Langfuse SDK 极轻量)
+ 面试现场打开 Langfuse UI 看每次问答的 RAGAS 指标, 比看 Jaeger span 视觉冲击大
+ 自动接 RAGAS, V4 一上线就出 "每次 chat 自动评 4 指标" 闭环

**负面**:
- 不符合 V3 DoD 字面 "Jaeger" — 需在验收报告注明「替换为 Langfuse, 等价演示场景」
- 服务网格视角(V4 多租户治理)仍缺 Jaeger → V4 再补 OTel collector

**缓解**:
- V3 完成时在 DoD-5 验收记录中加 footnote: "V3 用 Langfuse 替代 Jaeger, 决策见 ADR-0006"
- V4 多租户治理时把 OTel collector + Jaeger 补全

## Revisit

V3 完成后:
- 若客户/演示场景明确要求 SRE 视角(如 "展示分布式调用链定位超时") → 立即补 Jaeger
- Langfuse 上线后真实使用反馈, 评估再加 token/cost 仪表板(V4 治理范畴)
