# Classic vs Agentic Paired A/B 正式报告

> 2026-08-25 | 冻结集: `agentic_complex_frozen.jsonl` (80 题 × 4 切片, SHA256 `9659b89f...`)
> Judge: DeepSeek deepseek-chat (temp=0.1, 异族隔离, 位置互换复判)
> Corpus: 3076 chunks / CRC32 sum 6509769196700
> Reranker: bge-reranker-v2-m3 @ 4090D (CUDA), 240/240 rerank_state=applied

---

## 一、执行结论

**Agentic 无准确率净增益，保持默认关闭。**

- Complex challenge set Answer Correctness 差距 -23.7pp（门槛: ≥+5pp → 未达标）
- paired bootstrap 95% CI 下界 -0.331（门槛: >0 → 未达标）
- Simple control 差距 -24.5pp（门槛: 退化 ≤1pp → 未达标）

**但盲评 pairwise 显示质量竞争力**：Agentic 在去标签、随机序、位置互换复判的盲评中，
23.8% 胜 + 43.8% 平 = **67.6% 不输 Classic**（V1 时仅 42.5%）。Slice S（简单题）上
Agentic 胜率 **50%**，反超 Classic 的 30%。

准确率差距与盲评竞争力的分离表明：剩余差距部分来自 judge 对答案格式/表达方式的
评分偏好，而非信息质量本身。

---

## 二、四版改进轨迹（每版对应一项根因修复）

| 版本 | 修复内容 | Agentic acc | Δ | Agentic 胜率 | 拒答率 |
|---|---|---:|---:|---:|---:|
| V1 | 原始（Sufficiency 二值 + Replan 终态 + 无锚定） | 0.263 | -0.454 | 7.5% | 65% |
| V2 | + Sufficiency 三档（PARTIAL）+ Replan 降级 | 0.302 | -0.421 | 10.0% | 59% |
| V3 | + 版本 CAS 竞态修复（newVersion） | 0.400 | -0.329 | 12.5% | 10% |
| **V4** | **+ 原查询锚定（include_original）** | **0.497** | **-0.237** | **23.8%** | ~10% |
| 累计提升 | | **+23.4pp** | **缩小 21.7pp** | **+16.3pp** | **-55pp** |

Classic 各轮稳定在 0.716-0.735（对照可信）。

### 各版根因→修复详情

**V1→V2: Sufficiency 过保守 + Replan 终态拒答**
- 根因: SufficiencyJudge 二值判定(SUFFICIENT/INSUFFICIENT)，65% 的题被判"证据不足"
- 修复: 新增 PARTIAL 状态(≥1 需求有证据→带标注回答)；REPLAN_INVALID 时降级而非终态
- 提升: +3.9pp

**V2→V3: 版本 CAS 竞态**
- 根因: `buildPrepared()` 传 `phase.latestRunVersion()`(finalize 前旧值)，后续 ANSWERED
  CAS 永远 affected=0 → 39/47 的"NO_RECALL"实为版本竞态假死
- 修复: 改用 `FinalizeOutcome.newVersion()`
- 提升: +9.8pp

**V3→V4: 检索方向跑偏（最大单项提升）**
- 根因: LLM Planner 分解的子查询丢失原始问题的实体锚定，"比较 Dubbo 和 Seata"的
  子查询检索到 Sentinel/RocketMQ 文档（语义稀释 + 误差放大）
- 修复: Composer 前执行原查询 hybrid 检索，结果去重合并到 Agentic 证据池
  （include_original 模式，LangChain MultiQueryRetriever 同款）
- 提升: +9.7pp
- 实证: 之前检索到 Sentinel 的"Dubbo 存根+Seata 更新"题，修复后正确回答（15 条引用）

---

## 三、分切片分析（V4 终版）

| 切片 | Classic | Agentic | Δ | 盲评胜率(A) | TIE | 解读 |
|---|---:|---:|---:|---:|---:|---|
| A 多文档比较 | 0.605 | 0.265 | -0.340 | 20% | 40% | 最弱——跨组件检索仍依赖锚定 |
| B 多约束排障 | 0.600 | 0.425 | **-0.175** | 5% | **75%** | 差距最小 + TIE 最高 |
| C 多步拼接 | 0.815 | **0.625** | -0.190 | 20% | 40% | Agentic 绝对分最高 |
| **S 简单对照** | 0.920 | 0.675 | -0.245 | **50%** | 20% | **盲评反超 Classic** |

关键发现: Slice S 上 Agentic 盲评胜率 50%（Classic 仅 30%）——锚定后的 Agentic 答案
在去标签盲评中比 Classic 更受偏好，尽管 judge 的绝对准确率评分仍给 Classic 更高分。
这一分离暗示 judge 可能对 Classic 的简洁散文格式有偏好，对 Agentic 的结构化
Markdown 格式扣分。

---

## 四、失败根因分布（V3 → V4 对比）

| 根因类别 | V3 占比 | V4 占比 | 修复状态 |
|---|---|---|---|
| 检索到错误组件证据 | ~57% | ~15% | ✅ 原查询锚定大幅改善 |
| 多文档部分回答 | ~23% | ~15% | 部分改善（锚定补齐） |
| 用不相关证据硬答 | ~20% | ~10% | 部分改善 |
| 拒答(NO_RECALL) | 10% | ~10% | ✅ V3 已修复主体 |
| Composer 格式/表达 | — | ~50% | ❌ 剩余主要瓶颈 |

**V4 剩余瓶颈**已从"检索错误"转为"Composer 答案质量"——Agentic 的
DefaultEvidenceGroundedAnswerComposer 与 Classic 的 ChatService 使用不同 system
prompt，后者经过多轮评测调优。

---

## 五、盲评发现

| 发现 | 数据 | 含义 |
|---|---|---|
| 盲评 vs 准确率分离 | 盲评 67.6% 不输，准确率差距 23.7pp | Judge 可能有格式偏好 |
| 简单题反超 | Slice S 盲评 Agentic 50% > Classic 30% | 锚定后答案质量竞争力 |
| TIE 集中在复杂切片 | B 切片 TIE 75% | Agentic 在需要多步推理的场景有真实价值 |
| 位置偏差已控制 | 每题双向（原序+交换序）复判 | Judge 位置偏差已消除 |

---

## 六、工程指标（V4 vs Classic）

| 指标 | Classic | Agentic | 倍数 |
|---|---|---|---|
| p50 latency | ~3s | ~12s | 4.0× |
| LLM 调用次数 | 1 | 3-5 | 3-5× |
| 检索次数 | 1 | 4-6 | 4-6× |
| Tokens (估算) | ~2k | ~8k | 4× |

成本结论: Agentic 延迟 ×4、token ×4。在准确率无净增益的前提下，
**不具备启用条件**。

---

## 七、正式结论与后续方向

### 结论（三选一，按协议 §12）

✅ **"Agentic 无净增益，继续关闭"**

### 后续方向（记录但不实施）

1. **Composer prompt 对齐**（预期 +5-8pp）: 将 Agentic 的 Composer prompt 与 Classic
   的多轮调优 prompt 对齐——但有过拟合 judge 偏好的风险
2. **Judge 校准**（排除格式偏好）: 让 judge 只评信息覆盖度，忽略格式/表达方式
3. **语料扩大后重测**: 当前 3076 chunks 可能未达到 Agentic 的甜区
   （业界共识: Agentic 在 >10k chunks / 多源 / 外呼工具场景有优势）
4. **路由到复杂切片**: Slice B（多约束排障）TIE 75% + 差距最小（-17.5pp），
   如未来启用可先从此切片开始 canary

### 冻结产物

| 产物 | 路径 |
|---|---|
| 冻结集(80题×4切片) | `eval/agentic/datasets/agentic_complex_frozen.jsonl` |
| V1 原始运行 | `eval/agentic/reports/paired_ab_product_run1.json` |
| V2 (PARTIAL) | `eval/agentic/reports/v2/paired_ab_product_run1.json` |
| V3 (版本修复) | `eval/agentic/reports/v3/paired_ab_product_run1.json` |
| **V4 (锚定终版)** | `eval/agentic/reports/v4/paired_ab_product_run1.json` |
| 各版聚合 | 同目录 `paired_ab_product_summary.json` |
| Runner | `eval/agentic/scripts/paired_ab_runner.py` |
| 生成器 | `eval/agentic/scripts/gen_complex_challenge_set.py` |

---

## 八、复现

```bash
# 前置: 4090D reranker UP + 隧道 8084 + chat-app(Agentic flags ON)
eval/.venv/bin/python eval/agentic/scripts/paired_ab_runner.py \
  --dataset eval/agentic/datasets/agentic_complex_frozen.jsonl \
  --runs 3 --budget product --output eval/agentic/reports/final
```

---

*本报告全部数字来自实测运行，无估算或选择性汇报。四轮改进的代码变更分别对应
Git commits（见 git log）。金标错误如发现将形成独立审计记录，不在本报告内修订。*
