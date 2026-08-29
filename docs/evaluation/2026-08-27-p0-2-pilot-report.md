# P0-2 Pilot Report — LLM Planner vs Classic RAG (2026-08-27)

```
P0_2_PILOT_STATUS=PARTIAL
PILOT_VALIDITY=PASS (MODEL 47/50 = 94.0%)
```

> Spec: docs/evaluation/2026-08-27-p0-2-experiment-spec.md (FROZEN @ 3d3120b)
> 数据: eval/agentic/reports/pilot50_llmplanner/ | Dataset sha256 a6d55294... | Reranker UP

## 1. 实验对象确认

**实验组确实是 LLM Planner**。47/50 样本 `planner_version=model-llm-v1`（逐样本经
`/api/v1/agent/runs/{id}` 核验）；C slice 初始 plan 平均 **2.30 步**（1步×3 / 2步×10 / 3步×10），
S slice 平均 **1.08 步**——分解真实发生，且粒度随问题类型自适应。Smoke 阶段 4/4 PASS。

## 2. Validity Gate（先于结论）

```
N_MODEL=47  N_RULE_FALLBACK=0  N_CLASSIC_FALLBACK=0  N_FAILED=3  MODEL_RATE=94.0% ≥80% → PASS
```

3 条非 MODEL 样本逐条查明：
- `C_104`、`C_113`：`INVALID_INITIAL_PLAN:PLAN_TOO_MANY_STEPS: 4>3` — LLM 规划 4 步，
  但冻结 cap=3 → 整计划被拒（**prompt 鼓励 2–4 步与 cap=3 的真实设计冲突，记为缺陷 R1**）；
- `S_160`：Sufficiency 判定证据冲突 → 诚实拒答（机制正常）。
- Classic 臂 0 失败、0 拒答。

## 3. Quality 结果（MODEL-only, n=47）

| slice | Classic | Agentic+LLM | Δ (95% CI) | 显著 |
|---|---|---|---|---|
| C_multi_step (n=23) | 0.926 | 0.830 | **-0.096 [-0.213,-0.004]** | 是（负） |
| S_simple_control (n=24) | 0.950 | 0.883 | -0.067 [-0.196,+0.25→+0.025] | 否 |
| ALL (n=47) | 0.936 | 0.855 | **-0.081 [-0.160,-0.015]** | 是（负） |

- evidence_completeness 同向：ALL Δ=-0.083 [-0.164,-0.017]（显著负）。
- 双 judge 一致性 0.96；Qwen 交叉judge 在 S slice 上反而略偏 Agentic（0.929 vs 0.906），
  盲评换位 pairwise 在 S 上 14:2 偏好 Agentic —— S 上分歧是"信息量略低但表述更受偏好"。

## 4. 与旧 Rule Planner 实验的对照（同 C 题库体系）

| 实验 | Agentic C-slice acc | C-slice Δ vs Classic | 延迟倍数 |
|---|---|---|---|
| Rule Planner (final200 run1) | 0.624 | -0.244 | ×2.6 |
| **LLM Planner (本 pilot)** | **0.830** | **-0.096** | ×3.2 |

**LLM Planner 比 Rule Planner 大幅改善（C slice +0.21），与 Classic 差距从 -0.244 收窄到
-0.096——但仍未反超。**

## 5. 分解粒度与准确率的关系（本 pilot 最重要发现）

| 初始 plan 步数 | n | Agentic acc | Classic acc（同题） | Δ |
|---|---|---|---|---|
| 1（未分解） | 26 | 0.927 | 0.954 | -0.027 |
| 2 | 10 | 0.740 | 0.920 | **-0.180** |
| 3 | 11 | 0.800 | 0.918 | **-0.118** |

**分解越多，答案越差**（与假设相反）。结合 replan_count=0（47/47 全部 Phase-0 即判充分，
agentic loop 从未真正循环）与 Agentic citations 9.0 vs Classic 4.7：合理机制解释是——
子查询分解引入检索方向漂移 + 更多碎片证据进入 Composer，合成质量被稀释；而本语料的
多跳题用整句 hybrid 检索 + rerank 已可一次命中。

## 6. Cost

- Latency: C 18.7s vs 5.8s（×3.2）、S 12.3s vs 4.2s（×3.0）；ALL 约 ×3.1
- LLM calls: 步数 ×2.3 + planner + sufficiency + composer（token 未逐样本持久化，已知限制）

## 7. 当前可以支持的 Claim

1. "评测对象确认为 LLM Planner，94% 样本有效，降级零污染（逐样本 planner_source 核验）。"
2. "LLM Planner 显著优于此前误测的 Rule Planner 路径（C slice +0.21），证明旧 A/B 结论
   不能代表 LLM Agentic——但修正后的结论依然是 Classic 占优。"
3. "在本语料上，查询分解与最终答案质量负相关（dec≥2 时 Δ 显著恶化），且 sufficiency
   循环从未触发——Agentic 的额外机制在该语料上没有找到正向收益，代价是 ×3 延迟。"

## 8. 仍然不能说的

- 不能说 "Agentic 优于 Classic"（全 slice 为负，两项显著）。
- 不能说 "LLM Planner 无价值"（仅单一语料、N=50、单轮；S slice pairwise/Qwen 有反向信号）。
- 不能下"多跳场景需要 Agentic"的结论（C slice 恰恰差距最大且与分解粒度负相关）。
- token 级成本未测。

## 9. 特征问题回答

> **LLM Planner 是否在某一类问题上产生了可以观察到的价值？**

**没有。** 单跳（S）近平手但代价 ×3；多跳（C）差距最大且随分解粒度恶化。
唯一正向信号是 S slice 的盲评 pairwise（14:2）与 Qwen 绝对分偏好 Agentic 表述——
属于表述偏好而非信息优势。**如实记录为负结果。**

## 10. 后续（进入 Agentic routing / necessity analysis）

- 负结果本身构成路由判据的实证基础：当前语料默认 Classic，仅在"整句检索已判不足"
  的场景考虑 Agentic（需新实验验证该触发条件）。
- 缺陷清单（不在本 pilot 修）：R1 PLAN_TOO_MANY_STEPS 整计划拒绝（应截断/反馈重规划
  而非全弃）；R2 token 未逐样本持久化；R3 sufficiency 阈值过松（47/47 零 replan，
  从未触发循环）值得校准审计。
