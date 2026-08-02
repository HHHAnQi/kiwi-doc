# RAGAS 评测报告 (P1)

设计文档 README.md L16: RAGAS 答案质量评测 + CI 门禁 (-3% 阻断)

## 核心指标

| 指标 | 数值 | 说明 |
|---|---|---|
| faithfulness | 0.5950 | 答案是否完全从 context 推导, 高=低幻觉 |
| answer_relevancy | 0.5334 | 答案相关性, 高=答非所问少 |
| context_precision | 0.5522 | LLM judge 检索条目相关性位次质量 |
| context_recall | 0.4316 | ground_truth 被 context 覆盖比例 |

## 样本数: 30
