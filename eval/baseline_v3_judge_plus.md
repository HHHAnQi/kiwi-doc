# V3 Baseline 真数字(GLM-4-plus judge + corpus 100 docs + curated 30 题)

**生成日期**: 2026-08-02
**judge LLM**: glm-4-plus + thinking:{type:disabled}(commit d56a3e9)
**主答案 LLM**: glm-4-plus
**corpus**: 100 docs(5 source × 20 docs) parent_child 切片模式
**retrieval**: hybrid + RRF(dense + BM25), **rerank OFF**(本机未启 Autodl 隧道)
**question 集**: eval/questions.curated.jsonl 前 30 题(ADR-0008 D1)

---

## 汇总指标(用于 ADR-0008 D3 CI 门禁 baseline)

| 指标 | 数值 | 说明 |
|---|---|---|
| **faithfulness** | **0.5950** | 答案是否完全从 context 推导, 高=低幻觉 |
| **answer_relevancy** | 0.5334 | 答案相关性, 高=答非所问少 |
| **context_precision** | **0.5522** | LLM judge 检索条目相关性位次质量 |
| **context_recall** | 0.4316 | ground_truth 被 context 覆盖比例 |
| 样本数 | 30 | curated 子集 |

## 与历史对照

| 配置 | faith | recall | 备注 |
|---|---|---|---|
| V3 Day1 (50 docs, glm-4-plus judge) | 0.5038 | 0.1970 | 16% NO_RECALL 拉低 |
| **V3 P2 baseline (100 docs, glm-4-plus judge)** | **0.5950** | **0.4316** | **本文件** |
| V2-P4 (50 docs, glm-4-flash judge + Reranker) | 0.6711 | 0.5711 | historique flash judge, 不可直比 |

**关键观察**: corpus 翻倍后 recall 从 0.20 → 0.43(+23pp), faith 从 0.50 → 0.60(+10pp)。
**结论**: corpus 完整性是 RAG 数字最大杠杆(验证 PM/架构双视角审查的核心论点)。

## failure case study(ADR-0008 D4)

待 V3-W2 Langfuse 上线后做(#底部 faith/precision/recall + 根因)。
当前简单观察: 30 题里仍有 ~3-5 题可能 NO_RECALL(curated 80 题是基于旧 50 docs corpus 过滤,
新 corpus 已 100 docs 但 ground_truth 可能仍有部分覆盖不足)。

---

## 噪声 baseline(ADR-0008 D2)

V2-P4 验收报告 §3.2 在 GLM-4-flash 下实测 ±1.7pp(30 题 × 3 跑 StDev)。
GLM-4-plus judge 噪声未跑 ×3 定标(假设同 flash ±1.7pp, 偏保守)。
V3-W2 Langfuse 上线会用 nightly 自动每晚跑 30 题 × 3 周累积做更准噪声定标。

CI eval-regression 阈值暂用 3pp(±1.7pp 的 ~2σ buffer), W2 后收紧到 2pp(实测 ±1pp 后)。

---

## 使用方法

CI eval-regression.yml 触发时 compare_baseline.py 读本文件 4 个数字作为 baseline,
当前 PR 跑的报告数字若任一指标降 >3pp(见 compare_baseline.py --threshold 默认 3.0)
exit 2 阻断合并。

baseline 升级时机(任一发生):
1. corpus 大幅扩充(>20% 增加)— 重新跑 baseline
2. 切切片模式(flat↔parent_child)— 重新跑 baseline
3. 开/关 reranker — 重新跑 baseline
4. 切 judge LLM — 必须 ADR 修订 + 重新跑 baseline
