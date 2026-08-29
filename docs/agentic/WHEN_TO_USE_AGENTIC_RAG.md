# WHEN TO USE AGENTIC RAG — 基于实测数据的启用边界分析 (Phase 7)

> 2026-08-29 · 数据来源：Post-D3 pilot（50 题, MODEL 48/50 逐样本核验）+ E1 common-cohort
> audit + E2 routing 可行性分析（`docs/evaluation/2026-08-27-postd3-residual-audit.md`）。
> 本文是分析结论，不实现 Router。

## TL;DR

```text
当前语料(165 docs / 3076 chunks, 单库结构化技术文档): 默认 Classic RAG。
Agentic 的收益出现在"检索证据被语义判定为不足"的流量上(实测 21% 触发率),
但系统级收益上限(+0.6pp)未证明值得全流量成本。
```

## 什么 query 值得 Agentic（实测证据）

| 信号 | 证据 | 结论 |
|---|---|---|
| **语义不充分**（检索后判定） | replan 触发的 10 题上 Agentic 0.820 > 同题 Classic 0.790（该子集 Classic 全局最弱处 0.79 vs 全局 0.91）；且 Pre-D3 Agentic 在同 10 题落后 23pp → 翻转归因语义 replan | **唯一的正向信号**，但 n=10 个体不显著 |
| 多跳/分解敏感题 | Post-D3 C slice +2.3pp（n.s., 首次名义反超） | 弱正向, 修复后出现 |
| 单跳简单题 | S slice -2.5pp（n.s.）, 盲评偏好但绝对分落后 | 不值得 |

## 什么 query 应保持 Classic（实测证据）

- 分解粒度与质量**负相关**曾实测成立（Pre-D3: 1步-2.7pp / 2步-18pp / 3步-11.8pp）——
  语义判定修复后此负相关被吸收为平手，说明**问题从来不是分解本身，而是"何时该分解/何时该停"**。
- Classic 在本语料整句 hybrid+rerank 单次命中 0.91——**检索已充分时控制环是纯开销**（×2.8 延迟,
  3.4 LLM 调用/run）。

## 信号分类（E2 审计结论）

```text
pre-routing signal        ✗ 不存在稳定 query 级特征(slice/复杂度在 D3 前后与优势关系翻转)
post-execution control ✓ 语义不充分(SufficiencyJudge 输出) — Agentic 内部控制信号
escalation signal         ✓ 唯一证据对齐的路由形态: Classic 检索 → 语义判定 → 不足才升级
```

## 未来若语料变化的启用判据（待新实验，当前不实现）

1. **Retrieval-first Escalation 实验**：Classic 检索后 +1 次 LLM 语义判定（规则 extractor 无需
   Planner），INSUFFICIENT 流量升级 Agentic。需先在 Classic 语境复测判定触发率与质量。
   启用条件：系统级配对收益 CI 下界 > 0（当前估算上限仅 +0.6pp）。
2. **语料扩大复查**：>10k chunks / 多源异构 / 跨文档推理占比升高时重跑 pilot——
   Classic 单次命中的优势会随检索空间稀疏化衰减，这是 Agentic 理论甜区。
3. **成本红线**：无论质量如何，×2.8 延迟与 3.4 LLM 调用/run 是当前硬成本；除非流量以
   "检索不足"为主，默认 Classic 维持不变（`rag.agent.planner.enabled=false`）。
