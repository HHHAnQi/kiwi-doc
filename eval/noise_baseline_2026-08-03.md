# Noise Baseline 报告

> 跑批日(UTC): 2026-08-03T04:08:12.067745+00:00
> Judge provider #1, 题数 5

## 指标对照

| mode | faithfulness | context_precision | context_recall | answer_relevancy | samples | judge |
|---|---|---|---|---|---|---|
| random_distractor | 0.0000 | 0.0000 | 0.0000 | 0.5527 | 5 | deepseek/deepseek-chat |

## 梯度校验(sanity)

| 校验 | 值 | 期望 | 通过 |
|---|---|---|---|
| empty_context_is_lowest | 0.0000 | ≤ random_distractor(0.000) | ✓ |
| empty_context_faith_lt_0p3 | 0.0000 | <0.30 | ✓ |
| random_distractor_lt_no_rerank | 0.0000 | ~< no_rerank(0.000) [weak] | ✓ |

**所有严格校验**:✗ FAIL
