# Current-corpus reranker 评测报告（2026-08-25）

## 正式结论

- 旧 `golden_v2_grounded.jsonl` 的 chunk ID 已与当前 corpus 错位：80 题在同题 Top-5 中命中 0，不能继续作为参数优化标尺。
- 已完成 59 题人工复核，并补充 17 题 evidence-first 样本，冻结 80 题 current-corpus 集。数据 SHA256 为 `e797e495e98d6aa56fa6a058bf87d70455c6d6990cdce9049252a7c844843ce7`。
- 旧索引 Hybrid + pool=50 + threshold=0.3 连续运行 3 次，240/240 `rerank_state=applied`；Hit/Recall@5 为 **82.50%**。这是修复前基线，不再是当前值。
- Dense 在旧索引上仅 **16.25% Hit@5**。抽查 10 个 chunk 后发现，旧 Milvus 向量与当前 BGE-M3 对同文本重算向量的余弦仅约 -0.03～0.04，已证实为索引向量漂移，而非 ANN 参数问题。
- 已蓝绿重建 `documents_v2_bge_m3_20260825`：3076/3076 rows，通过 10 个样本 fresh-vs-stored cosine 校验（min 0.99964，mean 0.99996）；旧 `documents_v1` 未删除。
- 重建后 Dense Hit@5 **87.50%**（较旧索引 +71.25pp）；Hybrid Hit@5 **91.25%**，说明 Dense 已修复，但 Hybrid 仍有 +3.75pp 优势。
- 对 top-5 seed 做 window=2、最多 20 条的相邻 chunk 批量扩展，再统一 rerank；最终 3 轮 Hit/Recall@5 均为 **92.50%**，MRR@5 **81.04%**，NDCG@5 **83.92%**，240/240 rerank applied。
- 当前 Hit/Recall@5 Wilson 95% CI 为 **84.59%–96.52%**；三轮 mean latency 均值 **916.23ms**、p95 均值 **1124.59ms**。运行标准差与抽样置信区间仍分开解释。
- Milvus alias `documents_current` 已指向新 collection；chat app 与 parser 默认都使用 alias，回滚只需把 alias 指回旧 collection。
- 候选池 100 只再增加 1 题命中（95.24%），平均延迟升至 1271ms，因此默认采用 50，100 仅作为质量优先配置。
- 分数门槛 0.3 与 0 的检索命中完全一致；0.3 把平均返回上下文从 5 条降到 2 条，因此保留 0.3。

## 当前正式冻结基线（80 题，3 次）

| 指标 | 三轮均值 | run stdev | 逐题抽样 95% CI |
|---|---:|---:|---:|
| Hit/Recall@5 | **92.50%** | 0 | 84.59%–96.52%（Wilson） |
| MRR@5 | **81.04%** | 0 | 73.44%–87.81%（bootstrap） |
| NDCG@5 | **83.92%** | 0 | 77.30%–89.96%（bootstrap） |
| Precision@5 | **19.00%** | 0 | 17.50%–20.25%（bootstrap） |

分组 Hit@5：Dubbo 32/42（76.19%）、Nacos 16/20（80.00%）、RocketMQ 6/6、Seata 7/7、Sentinel 5/5。题型中 procedural 最弱，为 19/28（67.86%）；factual 为 13/14（92.86%）。

## 21 题参数选择实验（pilot）

| Hybrid 配置 | Hit/Recall@5 | MRR@5 | NDCG@5 | mean latency | p95 latency | 平均返回条数 |
|---|---:|---:|---:|---:|---:|---:|
| pool=20, threshold=0.3 | 66.67% | 66.67% | 66.67% | 642ms | 787ms | 2.71 |
| pool=50, threshold=0.3 | **90.48%** | **88.10%** | **88.72%** | 954ms | 1134ms | 2.00 |
| pool=100, threshold=0.3 | 95.24% | 92.86% | 93.48% | 1271ms | 1410ms | 2.48 |
| pool=50, threshold=0 | 90.48% | 88.10% | 88.72% | 993ms | 1124ms | 5.00 |

该表只用于选择 pool/threshold，不再作为正式质量结论；正式结论以上述冻结 80 题三轮基线为准。

## 产物

- 高置信 current-corpus 金标：`eval/golden/golden_v3_current_corpus.jsonl`
- 待人工复核：`eval/golden/golden_v3_needs_review.jsonl`
- 人工复核后集合：`eval/golden/golden_v3_reviewed_current_corpus.jsonl`
- 冻结 80 题：`eval/golden/golden_v3_frozen80.jsonl`
- 三轮正式基线：`eval/runs/frozen80_pool50_baseline.json`
- 重建后无邻居扩展基线：`eval/runs/frozen80_reindexed_hybrid_baseline.json`
- 当前邻居扩展基线：`eval/runs/frozen80_reindexed_neighbor_baseline.json`
- 新 collection manifest：`eval/runs/documents_v2_bge_m3_20260825_manifest.json`
- Dense 异常复现：`eval/runs/frozen80_dense_pool50_run1.json`
- 检索基线聚合器：`eval/aggregate_retrieval_runs.py`
- 蓝绿向量重建工具：`scripts/reindex_milvus.py`
- 金标重定位工具：`eval/remap_ground_truth.py`
- pool=20 报告：`eval/runs/retrieval_3090_v3_high_conf_threshold_030.json`
- pool=50 报告：`eval/runs/retrieval_3090_v3_high_conf_pool50_threshold_030.json`
- pool=100 报告：`eval/runs/retrieval_3090_v3_high_conf_pool100_threshold_030.json`
- threshold=0 报告：`eval/runs/retrieval_3090_v3_high_conf_pool50_threshold_000.json`

## 后续完成情况

- 已在用户明确授权后完成生成评测：Answer Correctness 0.8753、Faithfulness 0.9705、Evidence Completeness 0.9230、Citation Hit 1.0000、Context Recall 0.9698。
- 已完成多轮严格聚合门禁：G1 PASS(80)、G2 PASS(19/20)、G3 PASS(10/10)、G4 PASS(50/50，mean fidelity 0.995)、G5 PASS(50/50)，题集指纹一致。汇总见 `eval/multi_turn/all_gates_20260825_082849.md`。
- Python 评测测试 89/89 通过；HistoryCompressor、QueryContextualizer、TopicShiftDetector 的 Java 定向测试分别为 9/9、11/11、13/13。
- Agentic 继续默认关闭。是否恢复不按功能展示决定，而按 `docs/evaluation/agentic-incremental-value-protocol.md` 的复杂切片 paired A/B、质量安全门槛、成本与回退能力共同决定。
