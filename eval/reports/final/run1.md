# RAGAS 评测报告 (P1)

设计文档 README.md L16: RAGAS 答案质量评测 + CI 门禁 (-3% 阻断)

> Judge provider #1 `deepseek/deepseek-chat` (temperature=0.1, thinking=False). Judge 与业务 LLM 配置物理隔离 (JUDGE_LLM_PROVIDER_* env namespace), Phase 0 同源污染已脱。

## 核心指标

| 指标 | 数值 | 说明 |
|---|---|---|
| faithfulness | 0.7800 | 答案是否完全从 context 推导, 高=低幻觉 |
| answer_relevancy | 0.6914 | 答案相关性, 高=答非所问少 |
| context_precision | 0.5550 | LLM judge 检索条目相关性位次质量 |
| context_recall | 0.4900 | ground_truth 被 context 覆盖比例 |

## 样本数: 100

## Phase 2.0.2 拒答分离指标

> RAGAS faithfulness 把 [诚实拒答 (知识库中没有相关内容)] 与 [幻觉] 都判 0,

> 拒答分离指标把两类分开看, 才能真实衡量 RAG 能力。

| 指标 | 数值 | 说明 |
|---|---|---|
| **refusal_rate** | 0.0800 (8/100) | 拒答率(短答 or 含'无相关') |
| **faith_on_answered** | 0.8043 | 非拒答题 faith 均值 ← 真实 RAG 能力 |
| faith_on_refused | 0.5000 | 拒答题 faith, 应≈0(尺刻度验证) |
