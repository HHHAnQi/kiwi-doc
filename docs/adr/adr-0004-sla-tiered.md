# ADR-0004: SLA 分层标尺修订(p99 不再以"端到端 <1s"为唯一口径)

- Status: Accepted
- Date: 2026-08-01

## Context

V2 版本规划(`.internal/版本规划-含DoD内部纪律.md/版本规划.md` 第 71 行)中, "p99 < 1s" 被列为
V2 DoD 硬指标。但 V2 验收报告(`.internal/V2-B-C-D-DoD验收报告.md` §1 DoD-3)实测数据表明:

- chat 接口端到端 p99 = **20.7s**(200 req / 并发 10)
- 拆解: LLM(GLM-4-flash) 占 66% (~13.7s) / retrieve 占 34% (~7s)
- 即使 retrieve 优化到 0 延迟, 端到端 p99 仍 >13s

根因在于 "端到端 <1s" 标尺在含外部 LLM 的 RAG 系统上**结构性不可达**:

1. LLM 生成 token 受网络 + 服务端推理双重约束, 付费档(GLM-4-plus)最佳也只能到 ~2-3s,
   免费档 5-15s 是常态; 我们没办法把第三方 LLM 拉进我们自己的 p99 SLA。
2. 评测报告 §3 暴露了对应的二级问题: 用 GLM-4-flash 作 RAGAS judge 时精度 ±1.7pp,
   边际优化(<2pp 的策略差异)无法证明有效。LLM 性能与评测地基是同根问题。
3. 业界主流 RAG 系统(LangChain / LlamaIndex 商业版 / OpenAI Assistants)的端到端 p99
   均在 3-8s 区间, "1s" 是无 LLM 的纯检索系统标尺。

继续用错标尺会让团队对 V3 的优化方向产生误判: 把人力压到 retrieve 的 7s 上,
而解 80% 问题的真正杠杆(LLM 升级)反而被忽视。

## Decision

废弃单一"chat 端到端 p99 <1s"标尺, 改为三层 SLA:

| 层 | 指标 | 目标 | 说明 |
|---|---|---|---|
| **L1 检索层** | retrieve-only p99 | **< 1s** | 原"1s"承诺迁移至此。这是我们能完全掌控的部分 |
| **L2 端到端(含 LLM, 非流式)** | chat p95 | **< 5s** | V3 切付费 LLM + Semantic Cache 后的合理目标 |
| **L3 首 token(流式, V3 SSE 上线后)** | chat first-token p95 | **< 1.5s** | 流式上线后用户体感的真实标尺 |

**SLA 适用边界**:
- L2/L3 在 LLM / Embedding / Milvus 三个外部依赖任一不可用时**不适用**,
  对应期间走 ChatService 的 `LLM_DEGRADED` state_hint 显式降级(见 ADR-0003)。
- 评测场景使用 LLM judge 时, 切换到付费档后判定噪声应 < 0.5pp(由本 ADR 衍生为评测 SLA)。

## Alternatives Considered

| 方案 | 优点 | 缺点 | 选择 |
|---|---|---|---|
| **维持"chat p99 <1s"** | 数字硬, 简历好看 | 结构性不可达, 误导团队优化方向; 指标永远 MISS 等于没指标 | ❌ |
| **"chat p99 < 5s"** | 单一数字好沟通 | 把"我们能控的"与"外部约束"混成一锅, 看不出 retrieve 是否退化 | ❌ |
| **三层 SLA(本 ADR)** | 各层归口清晰, 每层有明确杠杆; 与业界 RAG 一致 | 三个数字比一个复杂, 需在 README 解释 | ✅ |
| **只管 retrieve, 不管 LLM** | 工程边界最干净 | 与"端到端问答助手"产品承诺脱节, demo 时说服力差 | ❌ |

## Consequences

**正面**:
+ retrieve p99 单独成 SLA → 后续 Semantic Cache / N+1 改造 / Milvus hybrid 优化有明确归口
  (Phase 0.3 的 RetrieveService 批量化就是服务于 L1)
+ chat p95 < 5s 可达(V3 LLM 升级 + cache 命中场景), 团队不再追逐幻影指标
+ 流式首 token SLA 给 V3 SSE 改造提供 ROI 论证(用户体感主要看首 token)
+ 把"LLM judge 精度 < 0.5pp"挂进 SLA, 解锁 V3 评测基线可信度问题(评测报告 §3.2)

**负面**:
- 外部依赖(LLM/Milvus)不可用时 SLA 不适用 → 需配套"可用性 SLA"(uptime SLO)
  这块本 ADR 不覆盖, 留 V4 治理版统一处置
- 三层 SLA 给非技术读者多一档解释成本, 需 README 同步修订

**缓解**: 在主 README 和 docs/architecture/performance.md 同步标注新 SLA,
把废弃的"p99<1s"作为 Deprecated 标尺列出避免历史 commit 引用错读。

## Revisit

重新评估触发条件:
1. V3 SSE 流式上线后, 验证首 token p95 < 1.5s 实测成立 → 升级 L3 为硬 SLA
2. 自部署开源 LLM(70B level, V4 候选)生效 → LLM 延迟进入可控域, L2 目标可降到 < 3s
3. 多租户隔离(V4)落地后引入新的可用性 SLA, 本 ADR 范围相应收窄
4. 若 retrieve 层(Semantic Cache 命中后)实测能 p99 < 200ms, 考虑把 L1 收紧到 500ms

## 关联

- V2 验收报告 `.internal/V2-B-C-D-DoD验收报告.md` §1 DoD-3 + §3.2 judge LLM
- 工程债清单 V3/V4 `.internal/工程债清单-V3-V4.md` A1
- 版本规划 `.internal/版本规划-含DoD内部纪律.md/版本规划.md` 第 71 行(原标尺)
- ADR-0003 trace_id 软引用(LLM_DEGRADED 降级路径)
