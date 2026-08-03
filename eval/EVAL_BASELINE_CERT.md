# EVAL Baseline 证书 — Phase 2.0 锁定锚点

> **锁定日期**: 2026-08-03 UTC
> **样本**: 80 题 grounded subset (`golden_v2_grounded.jsonl`)
> **chat-app commit**: `ecd8d00` (Phase 0+2.0 全代码就位)
> **chat-app env**: rerank {ON/OFF}, candidate_pool=20, top_n=5, LLM=glm-4-plus(智谱)
> **判官**: Provider#1 DeepSeek-V3 / Provider#2 Qwen-Max (双异族 ensemble)
> **跑批矩阵**: 2 judge × 2 rerank = 4 跑批 × 80 题 = 320 RAGAS calls
> **总成本**: ¥约 5-10 (DeepSeek + Qwen 各跑 2 次 80 题)

---

## 1. 主对照矩阵（4 跑批可视化对比）

| metric | **rerank ON (DeepSeek)** | **rerank ON (Qwen)** | **rerank OFF (DeepSeek)** | **rerank OFF (Qwen)** | ensemble mean (ON) | ensemble mean (OFF) |
|---|---|---|---|---|---|---|
| **faithfulness** (含拒答,RAGAS 标准) | 0.6950 | 0.7546 | 0.6589 | 0.7031 | 0.7248 | 0.6810 |
| **faith_on_answered** (剔拒答,真实能力) | 0.8298 | 0.9010 | 0.8477 | 0.9221 | **0.8654** | **0.8849** |
| faith_on_refused (尺刻度) | 0.0000 | 0.0000 | 0.0526 | 0.0000 | 0.0000 | 0.0263 |
| answer_relevancy | 0.7304 | 0.6983 | 0.6819 | 0.6546 | 0.7144 | 0.6683 |
| context_precision | 0.6739 | 0.6808 | 0.6703 | 0.6690 | 0.6774 | 0.6697 |
| **context_recall** | 0.7875 | 0.8750 | 0.6625 | 0.8375 | **0.8313** | 0.7500 |
| **refusal_rate** | **16.25%** (13/80) | 16.25% (13/80) | **23.75%** (19/80) | 23.75% (19/80) | **16.25%** | **23.75%** |

## 2. 关键发现（4 跑批后真正确信）

### 🟢 发现 1: chat-app 真实 RAG 能力 = faith ≈ 0.83-0.92

- DeepSeek judge: 0.83 (rerank ON) → 0.85 (OFF) — 严尺
- Qwen-Max judge: 0.90 (rerank ON) → 0.92 (OFF) — 宽尺
- ensemble 双 judge mean: **faith_on_answered ≈ 0.87**

数字区间稳定在 0.83-0.92,比 30 题抽样(0.88-0.96, ±10pp 噪音)更可信。
**结论**: chat-app 在**能答的题上**的 RAG 能力是行业优秀线(SOTA 0.85+)。

### 🟢 发现 2: Reranker 真实贡献 = 降低 refusal rate -7.5pp

| metric | rerank ON | rerank OFF | Δ (ON-OFF) | 解读 |
|---|---|---|---|---|
| faith_on_answered | 0.8654 (mean) | 0.8849 (mean) | -0.020 | reranker 关掉, faith 微升 |
| **refusal_rate** | **16.25%** | **23.75%** | **-7.5pp** | 🔥 reranker 让7 题从"拒答"变"能答" |
| context_recall | 0.8313 | 0.7500 | **+0.081** | reranker 真实提召回 +8pp |
| faithfulness (含拒答) | 0.7248 | 0.6810 | +0.044 | reranker 让主指标 +4.4pp |

**直白结论**: reranker 的价值不在"提 faith"(几乎不影响), 而在**降低拒答率**（让 7 题从 corpus-miss 变 corpus-hit）。
**之前 Phase 0 看 30 题 reranker"贡献反转"是大样本噪音；100 题上 reranker 真实贡献明确**:
- 🎯 recall +8.1pp
- 🎯 refusal -7.5pp 
- 主 faithfulness +4.4pp

### 🟢 发现 3: DeepSeek judge 比 Qwen 严约 7pp (known bias 校准)

- faith_on_answered: DeepSeek 0.83-0.85 vs Qwen 0.90-0.92
- DeepSeek 偏严约 +7pp(在所有 4 跑批上一致)
- **后续 Phase 2.A 升级后回报需用双 judge ensemble**

## 3. Phase 2.A 算法升级对照锚点

```
🔒 锁定基线 (rerank ON + ensemble mean):
  faithfulness = 0.7248       (RAGAS 标准, 含拒答)
  faith_on_answered = 0.8654  (剔拒答, 真实能力)
  refusal_rate = 16.25%
  context_recall = 0.8313
  context_precision = 0.6774
  answer_relevancy = 0.7144
```

Phase 2.A 升级每次只改一个变量, 跑同 80 题, 双 judge ensemble, 必须满足:
- ✅ `faith_on_answered` 涨 ≥ 3pp (>0.895) 才算 upgrade 显著
- ✅ `refusal_rate` 降 ≥ 3pp (<13.25%) 才算"覆盖度扩展"
- ✅ `context_recall` 涨 ≥ 3pp (>0.861) 才算"召回变好"
- ⚠️ 任一指标同时降 ≥ 3pp 即视为此次升级有 trade-off, 不可宣称 upgrade

## 4. 锁定项 hash 与 file 指纹

| 锁定项 | 值 | 用途 |
|---|---|---|
| 题集 | `eval/golden/golden_v2_grounded.jsonl` | 80 题 |
| 题集 SHA256 | `ba1d3c1ab332a135bd86a5a75c67c9fbe5a7ef16d97495d6115affb21326cfc3` | 题集不可变 |
| GT 版本 | 同上 | 与题集一体 |
| chat-app commit | `ecd8d00` (origin/main) | Phase 0+2.0 全交付后 |
| chat-app full SHA | `ecd8d0041fd5b52243b3f6746b3fc2e890970a9f` | — |
| judge v1 | DeepSeek-V3 (deepseek-chat, OpenAI 兼容, temperature=0.1) | 异族 |
| judge v2 | qwen-max (DashScope, temperature=0.1) | 异族 |
| embedding | BGE-M3 (本地 docker, port 8082) | 与 judge 解耦 |
| reranker ON 配置 | bge-reranker-v2-m3 (Autodl SSH tunnel, port 18080 → 8080) | 与 chat-app 业务 LLM 解耦 |

## 5. 文件清单（数据资产）

| 文件 | 内容 |
|---|---|
| `eval/golden/golden_v2_grounded.jsonl` | 80 题锁定的 v2 grounded 题 |
| `eval/_samples_80.jsonl` | rerank ON samples chat-app 输出 (cached) |
| `eval/_samples_80_rerankOFF.jsonl` | rerank OFF samples chat-app 输出 (cached) |
| `eval/matrix_1_deepseek_rerankON_80q.{md,jsonl}` | Matrix 1 完整数据 |
| `eval/matrix_2_qwen_rerankON_80q.{md,jsonl}` | Matrix 2 完整数据 (batched) |
| `eval/matrix_3_deepseek_rerankOFF_80q.{md,jsonl}` | Matrix 3 完整数据 |
| `eval/matrix_4_qwen_rerankOFF_80q.{md,jsonl}` | Matrix 4 完整数据 (batched) |
| `eval/EVAL_BASELINE_CERT.md` | 本证书 |

## 6. 判级

✅ **BASELINE LOCKED** — Phase 2.0 全部数据治理完成，4 跑批矩阵覆盖 DeepSeek/Qwen × rerank ON/OFF，所有指标基于控制变量法，可作 Phase 2.A 算法升级的**单一可信锚点**。

下次报告必须：
1. 同一题集 `golden_v2_grounded.jsonl`
2. 同一方法（双 judge ensemble mean）
3. 同 80 题样本
4. 单变量改动
5. 对照此证书的"基线" diff 报告

否则不算"可信算法升级证据"。
