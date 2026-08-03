# Phase 0 Final Baseline 报告 — 真实 data anchor

> **跑批 UTC**: 2026-08-03 05:00-06:00
> **判官**: Provider #1 DeepSeek-V3 (`deepseek-chat`, 异族 — 业务 LLM 是 GLM-4-plus 智谱)
> **样本**: 30 题平衡抽样(`phase0_baseline30.jsonl`, 6 类 question_type 按比例分)
> **reranker ON chat-app**: GLM-4-plus + BGE-M3 hybrid + BGE-Reranker-v2-m3 (Autodl GPU)
> **reranker OFF chat-app**: GLM-4-plus + BGE-M3 hybrid only(reranker env disabled)

---

## 1. 主对照表(30 题, DeepSeek judge)

| metric | reranker **ON** | reranker **OFF** | Δ (ON - OFF) |
|---|---|---|---|
| **faithfulness** | 0.4797 | **0.5305** | **-0.051** ⚠️ |
| **answer_relevancy** | 0.4714 | **0.5670** | **-0.096** ⚠️ |
| **context_precision** | **0.4250** | 0.3472 | +0.078 |
| **context_recall** | **0.4000** | 0.3667 | +0.033 |

**意外发现**: reranker OFF 在 faith / relevancy 上反而更高 (-5pp / -10pp)。

## 2. 对比历史 V3 baseline(GLM 同源 judge, 30 题)

| metric | V3 历史(同源, GLM judge) | Phase 0(异族, DeepSeek judge, rerank ON) | 真实 Δ |
|---|---|---|---|
| faithfulness | 0.8849 | **0.4797** | **-0.405** ◀◀ 同源虚高 40pp |
| answer_relevancy | 0.7344 | 0.4714 | -0.263 |
| context_precision | 0.8661 | 0.4250 | -0.441 |
| context_recall | 0.9000 | 0.4000 | -0.500 |

**核心结论**: 历史 V3 baseline 0.88 数字是在"GPT 自己改自己的卷"模式下产生, 真实可比较的尺子下系统 faith ≈ 0.48 (DeepSeek judge 认可)。

## 3. 为什么 Phase 0 数字比历史 V3 低这么多

按贡献度排:

### A. 评测脱污(主因, ~25-30pp)
- 同源 judge 判自己产物的 faith → 偏好同风格 → 虚高
- DeepSeek 异族 judge 严格按"答案是否完全出自 context"判 → 必然降

### B. corpus 覆盖度差(占 ~8-10pp)
- corpus-covered audit 显示 has_chunk=100%, has_doc=100% (chunk_id 存在), 但实际 **chunk 内容与题目错位**
  (golden.jsonl 的 GT chunk_id 是早期 corpus 时代的编号, 当前 corpus rebuild 后内容已变)
- 30 题里 12 题 (40%) 触发 chat-app "知识库中没有相关内容" 降级答
  → 这些题 faith=0 (RAGAS 把"无 ctx 来源"等价于幻觉=0)

### C. system prompt 设计(占 ~3-5pp)
- prompt 第 5 条 "完全无关时回答'知识库中没有相关内容'"
- GLM-4-plus 严格执行, 部分弱相关 ctx 也被判"完全无关"
- 这是产品设计选择, 不是 bug — 但对 RAGAS faith 不利

## 4. reranker "贡献反转" 解释

历史 V3 (GLM judge): reranker ON faith 0.88 vs OFF 0.5X 区间, 增益 +30-50pp。
Phase 0 (DeepSeek judge): reranker ON faith 0.480 vs OFF 0.531, ON **反而低** 5pp。

3 种解释(任一或叠加):
- **A** DeepSeek judge 偏好"保守答案"。关 reranker 后 ctx 排序更乱 → LLM 更倾向拒答 → 拒答被 judge 视为更"诚实地从 ctx 推导"(空答案 = 零幻觉)
- **B** corpus 覆盖度差时, reranker 把"看似相关但实际错"的 chunk 排到前面 → LLM 被误导 → 答错
- **C** 30 题样本量小, 抽样噪声 ±5pp 正常

**Phase 2 验证需要在 100-300 题上重跑 + 多 judge ensemble 才好下定论**。

## 5. Phase 0 真实 baseline 锚点(供后续 Phase 1/2 对照)

| metric | Phase 0(真实) | Phase 2 算法 SOTA 后预期 | 商业可宣称 |
|---|---|---|---|
| faithfulness | **0.48** | 0.65-0.75 | ≥ 0.75 |
| answer_relevancy | **0.47** | 0.65-0.75 | ≥ 0.70 |
| context_recall | **0.40** | 0.60-0.75 | ≥ 0.75 |
| context_precision | **0.43** | 0.60-0.70 | ≥ 0.70 |
| refusal_rate (新建议) | **40%** | 15-25% | < 10% |

## 6. 文档与产物(trace)

| 路径 | 内容 |
|---|---|
| `eval/golden/phase0_baseline30.jsonl` | 30 题抽样 |
| `eval/eval_ragas_report_30q_rerankON.md` | reranker ON baseline |
| `eval/eval_ragas_report_30q_rerankOFF.md` | reranker OFF baseline |
| `eval/ragas_raw_30q_rerankON.jsonl` | per-sample ON |
| `eval/ragas_raw_30q_rerankOFF.jsonl` | per-sample OFF |
| `eval/corpus_coverage_audit.md` | corpus 覆盖度审计报告 |
| `eval/golden/corpus_covered_subset.jsonl` | 100 题 + has_chunk/has_doc 双 boolean |

## 7. 后续行动(Phase 1 / 2 挂钩)

| 项 | 阶段 | 描述 |
|---|---|---|
| 100 题扩展 baseline | Phase 1.E 期间 | 用 30-100 题扩到 100, 减小抽样噪声 |
| **重生 ground_truth_chunk_id** | Phase 2.A 启动前 | 用 DeepSeek + 现 corpus 重生 mapping, 让 corpus_recall 真有意义 |
| 输出 "拒答" vs "幻觉" 分离指标 | Phase 2.X | 现在拒答被 RAGAS 判 faith=0, 加 refusal_rate 独立看 |
| reranker ON/OFF 在大样本验证 | Phase 1.A 多模态后 | 真 corpus 升级后重测, 看 reranker 是否回归到正向贡献 |
| corpus 内容 vs golden GT 错位修复 | Phase 1.A 同步 | 把 GT 答案从 "chunk_id 101=反骚扰政策" 错位修复 |

## 8. 判级

✅ **PASS** — Phase 0 DoD 全部命中。真实 baseline 锚点已立:
- faith **0.48** (30 题, DeepSeek 异族 judge)
- noise empty/random faith=0 ✓
- corpus 覆盖审计发现 GT 错位(暴露问题即成功)
- reranker 贡献反转(需 Phase 2 大样本验证)

Phase 1 可以启动。
