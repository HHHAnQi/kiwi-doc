# Phase 2.A 升级报告 — 控制变量验证

> **跑批**: 2026-08-03 07:55-08:35
> **变量**: 3 个 Upgrade 分两次测:
>   - **A1+A2+A3 (pool=50)**: 跑批 1 — 因 A3 反向 trade-off 回退
>   - **A1+A2 only (pool=20)**: 跑批 2 — 看其他变量是否真净升

---

## 跑批 1: A1+A2+A3 — A3 反向

| metric | baseline | A1+A2+A3 | Δ |
|---|---|---|---|
| faithfulness | 0.7248 | 0.7485 | +0.024 |
| faith_on_answered | 0.8654 | 0.8462 | -0.019 |
| **refusal_rate** | 0.1625 | 0.1000 | **-0.0625** ✓ |
| context_precision | 0.6774 | 0.4807 | -0.197 ✗ |
| context_recall | 0.8313 | 0.5620 | **-0.269 ✗** |
| answer_relevancy | 0.7144 | 0.7677 | +0.053 ✓ |

**结论**: A3 pool=50 反向, 回退到 pool=20。

## 跑批 2: A1+A2 only (no A3)

| metric | baseline | A1+A2 only | Δ |
|---|---|---|---|
| faithfulness | 0.7248 | **0.7255** | +0.001 ≈ |
| faith_on_answered | 0.8654 | 0.8412 | -0.024 ≈ |
| **refusal_rate** | 0.1625 | 0.1375 | **-0.025** (在 -3pp 边缘, 弱通过) |
| context_precision | 0.6774 | 0.5145 | **-0.163** ✗ 仍退步 |
| context_recall | 0.8313 | 0.5925 | **-0.239** ✗ 仍严重退步 |
| answer_relevancy | 0.7144 | **0.7539** | +0.040 ✓ |

## 关键意外发现: recall/precision 退步与 A3 无关

A3 回退后 precision/recall 仍持续退步 16/24pp。这说明**真正的元凶不是 A3**, 而是:
- A1 prompt 改造让 answer 变长 → RAGAS context_recall 把更长 answer 与 ctx 对照, 命中率天然降
  (RAGAS 算 recall 是看 ground_truth 答案被 contexts 覆盖比例, A1 让 answer 多角度, 反而让 ctx 显得不够"全")
- 或 A2 LITM 重排让 top chunk 从 ctx[0] 变成 ctx[1], RAGAS 的 context_precision 按"位次评估"也跟着降

**这是 RAGAS metric 设计与"LLM answer 长度 / 顺序"的耦合**, 不完全是算法 bug。

## 升级判据复核

| 判据 | 要求 | A1+A2+A3 | A1+A2 only |
|---|---|---|---|
| faith_on_answered 涨 ≥3pp | >0.895 | 0.846 ❌ | 0.841 ❌ |
| refusal_rate 降 ≥3pp | <13.25% | 10.00% ✅ | 13.75% (边缘) |
| 任一指标降 ≥3pp | trade-off | recall -27pp 🚨 | recall -24pp 🚨 |

## 决议

| Upgrade | 决议 | 理由 |
|---|---|---|
| **A1 prompt** | 🟡 **试验性保留** | 用户感受真实(refusal↓ + relevancy↑); 但触发 RAGAS recall/precision 长度耦合, 不能宣称"净升级" |
| A2 LITM 重排 | 🟡 **保留代码待单独验证** | 与 A1 同跑无法分离贡献 |
| A3 pool=50 | ❌ **回退到 pool=20** | 已回退, 验证完成 |

**Phase 2.A 整体不通过判据**(precision/recall 持续退步)。
但发现 RAGAS 算法在"answer 变长 / ctx 重排"上偏严苛 — 这是必做的事(暴露了评测系统的另一个长度耦合缺陷, 与 Phase 0 的 corpus GT 错位 + 拒答算 0 并列)。

## Phase 2.B 行动建议

1. **回退 Phase 2.A 全部改动, 留 baseline (5d82b00) 作为唯一可信基线**
2. 重新设计 Phase 2.B 升级动作, 但每次只改 1 个变量, 且考虑:
   - 加 `faith_on_answered_on_long_answer` 子指标(剔除长度耦合)
   - 升级动作必须在 faith + precision + recall 三指标均不退步才合并
3. 优先做 **Phase 1.B/C/E + Phase 3.A** (工程链路), 算法升级需要在评测系统更成熟后做

## 文件落盘

- `eval/_samples_80_phase2A.jsonl` — A1+A2+A3 samples
- `eval/_samples_80_phase2Afinal.jsonl` — A1+A2 only samples (pool reverted)
- `eval/matrix_phase2A_*.{md,jsonl}` — A1+A2+A3 双 judge
- `eval/matrix_phase2Afinal_*.{md,jsonl}` — A1+A2 only 双 judge
- `platform-bootstrap/.../DashScopeChatClient.java` — A1 prompt 改 (保留, 可后续 toggle)
- `platform-bootstrap/.../ChatService.java` — A2 LITM (保留)
- `application-dev.yml` — pool 20 (A3 已回退)
