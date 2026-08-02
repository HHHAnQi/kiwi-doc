# ADR-0008: 评价体系锁定 — question 集 + judge LLM + CI 门禁

- Status: Accepted
- Date: 2026-08-02

## Context

V2-P4 + V3 Day1/Day2 实测暴露 3 个评价体系硬伤:

### 1. question 集质量不齐, NO_RECALL 占比高拉低均值
V3 Day2 4 象限实测中, 30 题 ground_truth 有 5-6 题不在 50 docs corpus 内,
走 NO_RECALL fallback 路径, 单题分数=0, 拉低均值约 17%。
**根因**: 题目来自旧 200 docs corpus 时代, 重灌到 50 docs 后部分题无解。

### 2. judge LLM 跨切让数据不可比
V2-P4 用 glm-4-flash judge → faith 0.67。
V3 Day1 切到 glm-4-plus judge → faith 0.50。
**17pp 跨 judge 偏移** 与代码升级效果混在一起, 无法分离评估"代码改动效果"。
**根因**: 没有显式 lock judge, 任何人改 .env 就改变了评测基准。

### 3. 评测不绑 CI, 代码改动后无回归门禁
ragas_pipeline.py 单跑 10 分钟, 但**每次代码改动没人重跑**。
V3 第 1 周 parser-service 拆分后, 如果 recall 数字波动, 无法判断:
- 真 regression(代码 bug)
- 噪声波动(±1.7pp 内)
- judge 偏移(切了 LLM)

**根因**: 评测是手工流程, 不是 CI 自动门禁。

## Decision

V3 第 0.5 周锁定评价体系 4 件套, 后续不可破坏。

### D1. question 集 — curated, 唯一评测集

- 跑 `eval/curate_questions.py` 过滤 corpus coverage < 30% 的低质题
- 输出 `eval/questions.curated.jsonl`(~50-80 题)
- **后续所有象限对照 / 版本对比 / V3 拆服务前后回归都用此集**
- question 不可随意增减; 增需 ADR 修订

### D2. judge LLM — 锁 GLM-4-plus + thinking disabled

- baseline judge = **glm-4-plus + thinking:{type:disabled}**(见 commit d56a3e9)
- 实测 ±X pp 噪声待 E2 测试跑 3 次定标
- **禁止切 judge 不更新 ADR**: 如未来要换(如接 Langfuse 自带 RAGAS eval), 需
  用同 question × 同 corpus 跑双 judge 对照, 文档化偏移系数, 再更新本 ADR

### D3. CI 评测门禁 — PR 10 题 smoke + nightly 30 题

- `.github/workflows/eval-regression.yml`:
  - PR 触发: 跑 10 题 smoke 子集(~3 分钟), 与 baseline 对比, 任一指标降 >3pp 阻断合并
  - nightly: 跑 30 题全集(~15 分钟), 报告上传 artifact
- baseline 数字写入 `eval/baseline_v3_judge_plus.md`, PR 与之 diff
- PR 阻塞阈值 3pp = 当前噪声 ±1.7pp 的 ~2σ(留 buffer)

### D4. failure case study — 每次跑评测必产出

报告必须含**低分 case 抽样**(bottom-5 题的 faith/precision/recall + 根因分析):
- 是 NO_RECALL(corpus 缺)
- 还是 context 召回错(retrieval 问题)
- 还是 answer 编造(LLM 问题)

让评测不只是数字, 而是改进输入。

## Alternatives Considered

| 方案 | 取舍 | 选择 |
|---|---|---|
| **lock 4 件套(本 ADR)** | 工时 0.5 天, V3 验收可信度 90%+ | ✅ |
| 不锁, 继续手工跑 | 工时 0, 但 V3 任何改动数据不可信 | ❌ |
| 大规模 1000+ 题评测 | 工时 5 天+, 200 doc 规模下信息饱和 | ❌(过度工程) |
| 等 Langfuse 上线后做 | Langfuse V3 第 3 周, 太晚 — V3 第 1 周拆 parser 就要用 | ❌(太晚) |

## Consequences

**正面**:
+ V3 第 1 周 parser-service 拆分前后可量化回归(对比 curated 集)
+ CI 自动跑评测, 不靠人记得"重跑"
+ failure case study 让评测成为改进输入而非数字游戏
+ 跨人/跨时 / 跨版本数字可比性 +50%

**负面**:
- curated question 集冻在当前 corpus(50 docs), 后续扩 corpus 需要重 curate
- CI eval-regression 跑 10 题要起 Milvus + BGE-M3 + LLM, GH Actions 成本上升(每月 ~$5-10 起)

**缓解**:
- corpus 扩展时 curate_questions.py 一键重生成
- CI eval-regression 只在 PR label `eval-impact` 时跑, 默认不跑省钱

## Revisit

- V3 第 1 周 parser 拆完后, 重跑 curated 集 baseline, 锁新 baseline
- Langfuse 上线(V3 第 3 周)时, 评估是否用 Langfuse eval API 替代本地 ragas_pipeline
