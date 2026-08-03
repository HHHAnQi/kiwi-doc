# Corpus 覆盖度审计报告

> 题库: `golden.with_labels.jsonl` (100 题)

## 总覆盖

| 维度 | 覆盖数 | 覆盖率 |
|---|---|---|
| chunk_id 精确匹配 | 100/100 | **100.0%** |
| doc_id 同文档(粗粒度) | 100/100 | 100.0% |

## 按 question_type 分布

| type | 总题数 | chunk覆盖 | doc覆盖 |
|---|---|---|---|
| config | 34 | 34 (100%) | 34 (100%) |
| factual | 8 | 8 (100%) | 8 (100%) |
| multi_hop | 5 | 5 (100%) | 5 (100%) |
| other | 5 | 5 (100%) | 5 (100%) |
| procedural | 42 | 42 (100%) | 42 (100%) |
| troubleshoot | 6 | 6 (100%) | 6 (100%) |

## 后续

- `corpus_covered_subset.jsonl` 含 has_chunk / has_doc 双 boolean, 可直接拿来过滤
- 30 题抽样应只从 `has_chunk=true` 的 100 题里取, 避免超 corpus 题拉低均值
