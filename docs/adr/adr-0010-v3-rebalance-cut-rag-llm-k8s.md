# ADR-0010: V3 范围重平衡 — 砍掉 rag-service / llm-gateway / k3s, 只保留 parser + SSE + Langfuse

- Status: Accepted
- Date: 2026-08-02
- 关联: ADR-0005(原 3 服务) / ADR-0007(原 k3s on Autodl) / 双视角审查(PM+架构)

## Context

V3 第 0.5 周 + 第 1 周 Commit 1 完成后, 一次"PM/架构双视角审查"暴露资源分配严重失衡:

### 双视角审查核心论点

1. **资源倒挂**(PM 视角): 过去 10-12 天 commits 工程占比 70%, 产品价值 30%。
2. **0 用户场景 DoD**(PM 视角): V3 6 条 DoD 全部"工程演进", 0 条"用户感知"。
3. **拆服务为拆而拆**(架构视角): 流量=0 + 团队=1, 拆 rag/llm-gateway 是典型空壳拆分。
4. **K8s 流量 0 演不出真 HP**(架构视角): k3s on Autodl 演示价值弱, 工时高。
5. **真实 RAG 数字 0.5/0.20 不能 demo**(PM 视角, 致命): V3 5 周做完仍 demo 不出
   "RAG 智能中台" 卖点 — 因为 corpus 缺 150 docs + 评测不稳, 而这些 V3 主线都不动。
6. **V3 5 周工时其实可压缩到 3.5 周**(架构视角): 砍 rag/llm/K8s, 产品价值反而更高。

## Decision

V3 范围从 5 周缩到 3.5 周, 砍掉工程 ROI 红字项。

### 保留(V3 修正版主线)

| 周 | 内容 | 产品价值 | 工程价值 |
|---|---|---|---|
| W0(0.5 天) | **重灌 corpus 50→100 docs** | ⭐⭐⭐⭐⭐ 30 题 NO_RECALL 消除 | 中 |
| W0(0.5 天) | **跑 curated 30 题 baseline 真数字** | ⭐⭐⭐ ADR-0008 CI 真生效 | 高 |
| W1(2 天) | **LLM SSE 流式 chat(首 token <1.5s)** | ⭐⭐⭐⭐ 用户体感大跃迁 | 中 |
| W1-2(5 天) | **parser-service 拆 + 中断恢复 + kill-9 演练**(原 V3-W1 不变) | ⭐⭐⭐⭐ 上传 RTT 10s→2s | 高 |
| W3(2 天) | **Langfuse 接入 chat 链路 + 自动跑 RAGAS** | ⭐⭐⭐⭐ 演示现场看每次问答指标 | 高 |
| W3-4(3 天) | **docker-compose 全栈 + Locust 压测 100 并发** | ⭐⭐⭐ p95 <2s 验证 | 中 |
| W4(1 天) | V3 验收 + 数据流时序图 + 进 V4 门槛 | ⭐⭐ | 中 |

### 砍掉(推 V4 流量来时再做)

| 原 ADR 项 | 推后理由 |
|---|---|
| **rag-service 拆**(ADR-0005 部分) | 灰度召回收益需 ≥100 用户分流量, 当前 0 用户场景演不出 |
| **llm-gateway 拆**(ADR-0005 部分) | 多 LLM router 需 ≥2 个 LLM, 当前只有 1 个(glm-4-plus) |
| **k3s on Autodl**(ADR-0007) | HPA 需真流量, docker-compose 5 服务足够演示微服务化 |
| **Semantic Cache** | 100 并发压测无 cache 都能过(单 query <2s), 推 V4 |

### 修改原 ADR 状态

| ADR | 原状态 | 新状态 |
|---|---|---|
| ADR-0005 | "3 个服务 Accepted" | 改 "1 个服务(parser) Accepted; rag/llm-gateway Superseded by ADR-0010" |
| ADR-0007 | "k3s Accepted" | 改 "Superseded by ADR-0010; docker-compose 替代; V4 流量来时再启 k3s" |

## Alternatives Considered

| 方案 | 取舍 | 选择 |
|---|---|---|
| **3.5 周精简版(本 ADR)** | 工程瘦身 + 产品价值注入 | ✅ |
| 5 周原 V3 全推 | 砍不动沉没成本 | ❌(RAG demo 失败风险) |
| 0 工程只做 RAG 调优 | 架构债高, V4 工时翻倍 | ❌ |

## Consequences

**正面**:
+ 总工时 5→3.5 周(-30%)
+ V3 结束时 corpus 100 docs + RAG 真质量 + 上传 RTT 10s→2s + chat 20s→3s(SSE) — 全部用户可感知
+ ADR-0008 eval 门禁因 baseline 真数字而生效

**负面**:
- ADR-0005 / ADR-0007 部分决策被推翻(架构债 README 需说明)
- "K8s 简历硬资产" 缺失(V4 流量来时不补会被人质疑)
- parser-service 单拆演不出"高分布式", V4 流量到时再加

**缓解**:
- README 加架构演进图: "V3 = 1 服务(parser) + 单体内其他能力; V4 = + rag/llm/K8s/多租户"
- V3 验收报告自己标 "未来流量瓶颈: parser 拆是 proven, rag/llm 拆是 future"

## Revisit

V3 完成后触发评估:
1. 真实流量来(≥50 用户并发) → 立即评估拆 rag-service + 接 HPA
2. 多 LLM 场景(接 Claude/vLLM 自部署) → 立即评估拆 llm-gateway
3. 客户/演示要求"K8s 演示" → 启 V3.5 patch 加 k3s
