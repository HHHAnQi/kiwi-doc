# Phase 2.0 数据增长 严谨审查报告

> **审查对象**: Phase 0 (faith=0.48) → Phase 2.0 (faith_on_answered=0.88) 的"+0.40 飞跃"
> **审查日期**: 2026-08-03
> **审查目的**: 用户提出质疑"数据增长太快是否有问题", 做诚实自检
> **结论(先说)**: ✅ **数字本身没有造假/bug, 但与 Phase 0 不可直接比较**:
> 等于"换了一组题集 + 修了 GT + 改了指标口径" — 表面看是同一个项目的两次跑分,
> 实际是 **3 个变量同时改了**, 不是"控制变量法"的可比较对照。
> **0.88 是"chat-app 在能答的题上的真实能力", 不是 chat-app 的平均水平**。

---

## 1. 三变量同时变更(自检大事)

| 变量 | Phase 0 跑批 | Phase 2.0 跑批 | 是否变量 |
|---|---|---|---|
| **题集** | `phase0_baseline30.jsonl` (含 9 题 ungroundable) | `phase2_baseline30.jsonl` (0 题 ungroundable) | ✅ **是变量** |
| **GT 文件** | `golden.jsonl` (GT 错位严重) | `golden_v2_grounded.jsonl` (GT 已重生) | ✅ **是变量** |
| **指标口径** | 含拒答 `faithfulness` | 剔拒答 `faith_on_answered` | ✅ **是变量** |
| chat-app 配置 | rerank ON（SSH 隐道已修） | rerank ON | ❌ 一致 |
| judge | DeepSeek | DeepSeek | ❌ 一致 |
| chat-app LLM | GLM-4-plus | GLM-4-plus | ❌ 一致 |

> **变量多 → 0.48 vs 0.88 不能说"系统提升 0.40"**, 而只能说:
> "在 Phase 2.0 评测体系(新题集 + 新 GT + 剔拒答口径)下, chat-app 真实 RAG 能力 ≈ 0.88"

---

## 2. 题集差异(审查警示 1)

直接对比两个 30 题：

| 维度 | Phase 0 老 30 | Phase 2.0 新 30 |
|---|---|---|
| 题重合数 | — | **6 / 30**(20%) |
| 含 ungroundable 题 | **9 题**(30%) | **0 题**(0%) |

**Phase 2.0 的 30 题剔了所有 ungroundable** → "答得出来的题" 集合 → 必然 faith 偏高。
这是**评测口径偏移**, 不是系统能力上涨。

---

## 3. 共同 6 题上的答案几乎完全相同(审查警示 2)

Phase 0 与 Phase 2.0 共同覆盖的 6 题里, chat-app 答案逐字对比:

| # | Phase 0 答 (字数) | Phase 2.0 答 (字数) | 差异 |
|---|---|---|---|
| 1 | 178 (Sentinel Hystrix) | 147 (同) | 仅措辞微调 |
| 2 | 62 (Nacos 容量) | 62 (同) | **逐字相同** |
| 3 | 74 (分支命名) | 68 | 措辞微调 |
| 4 | 153 (override://) | 126 | 措辞微调 |
| 5 | 147 (服务降级) | 147 (同) | **逐字相同** |
| 6 | 11 (知识库中没有) | 11 (同) | **拒答** |

**重大结论**: **chat-app 一行代码没改, 答案完全相同**。faith 0.48 → 0.88 飞跃**不是系统能力提升**,
是**换了尺子和换了考卷的结果**。

---

## 4. 题集为什么变(Phase 2.0 设计合理性自检)

| 题集改变 | 是否合理 |
|---|---|
| 剔除 20 ungroundable 题 | ✅ 合理 — GT 错位是真的, 这 20 题是"超 corpus 覆盖" 的诚实拒答 |
| 把 ungroundable 题当作"系统失败" | ❌ 不公平 — corpus 没有该内容不是 chat-app 的算法问题 |
| 改用 faith_on_answered 剔拒答 | 🟡 部分合理 — RAGAS faith 对拒答严苛确实是行业问题, 但同时反映 chat-app 拒答率高 |

**真正的争议点**: refusal_rate 16.67% (5/30) 算高吗?
- 行业 RAG 系统: < 10% 是优秀, 10-20% 合格, > 20% 待优化
- 16.67% 处在"合格但偏上限"
- Phase 2.A 主要任务: 把 refusal_rate 降下来, 同时不变坏 faith

---

## 5. ungroundable 20 题是真的 corpus 不覆盖吗? (审查警示 3)

LIKE SQL 直查 corpus：

| Ungrounded 题关键词 | corpus 命中数 |
|---|---|
| 公网 IP / 权限系统 / 虚拟节点数 / 会话保持时间 / 服务身份识别 / 上下线实例 / 全局监听器 / 鉴权 | **0 命中** |
| Spring 配置映射优先级 | 164 命中(关键词命中了但 LLM 没选出最佳 chunk — 这是检索召回问题) |

**结论**: 20 题里大部分确实 corpus **不覆盖** (合规的诚实拒答), 少数(~4-5 题) 是**检索召回不到**
(Phase 2.A HyDE/master-query 的发力点)。

---

## 6. 三种"读 0.88" 的说法对比

| 解读 | 是否准确 |
|---|---|
| ❌ "chat-app 系统能力 = 0.88(显著高于 0.48 Phase 0)" | **错误**: 同一组 chat-app 答案的水平 |
| ✅ "在能答的题上, chat-app faith ≈ 0.88(异族 judge 实证)" | **正确**: 这是 faith_on_answered 真正含义 |
| ✅ "全平均水平比 0.88 低, 因为 refusal_rate 16.67% 拉低" | **正确**: faithfulness 含拒答 = 0.7347, 这才是"平均水平近似" |
| ✅ "0.88 ≠ 'V3 历史 0.8849 同源虚高', 而是真实能力(异族 DeepSeek 也判 0.88)" | **正确**: **之前 Phase 0 的"同源虚高"判读部分错误** |

---

## 7. 修正 Phase 0 的错误结论

| 原 Phase 0 结论 | 修正后 |
|---|---|
| "V3 历史 0.8849 是同源虚高 40pp" | ⚠️ 部分错。同源 GLM judge 偏宽 ~3-5pp, 不是 40pp |
| "Phase 0 faith 0.48 是真实系统能力" | ❌ 错。0.48 是被 corpus 错位 + RAGAS 拒答算 0 + 抽样偏差同时拉低 |
| "reranker 贡献反转" | 🟡 待 Phase 2.A 大样本复测, 30 题噪音不足以结论 |

---

## 8. 当前可以确信的数据(无歧义真值)

| 指标 | 数值 | 信心 |
|---|---|---|
| **faith_on_answered = 0.8816** (30 题, DeepSeek 异族 judge, v2 GT) | **0.88** | 🟢 高(可直接作 baseline) |
| **refusal_rate = 16.67%** (corpus 不覆盖 + 检索召不回) | **17%** | 🟢 高 |
| **faith 含拒答 = 0.7347** | **0.73** | 🟢 高 |
| **context_recall = 0.8833** (新 GT 后真值, 之前 0.40 是 GT 错位假数据) | **0.88** | 🟢 高 |
| chat-app "在能答的题上" 真实能力 | **0.88** | 🟢 高 |
| chat-app "用户感受到的" 平均水平 | **0.73** | 🟢 高 |
| 是否 V3 同源虚高 40pp | ❌ 否, 实际偏宽 ~3-5pp | 🟢 高 |

---

## 9. 后续 Phase 2.A 的"控制变量"Baseline 建议

为避免 Phase 2.0 这种"3 变量同时改" 的混乱, Phase 2.A 升级时:

1. **固定题集**: `phase2_baseline30.jsonl` 锁定, 改型前后都用同一份
2. **固定 GT**: `golden_v2_grounded.jsonl`, 不要再重生
3. **固定指标**: `faith_on_answered` + `refusal_rate` 双轨, **不要只用 faithfulness**
4. **每次只改一个变量**: 例如本次 Phase 2.A 先做 prompt 改造, 跑完 → 与 baseline 比; 再做 HyDE, 跑完 → 与上次比

**Phase 2.A baseline 锁定值**:
```
faith_on_answered = 0.8816    faithfulness(含拒答) = 0.7347
refusal_rate = 16.67%          context_recall = 0.8833
context_precision = 0.7233    answer_relevancy = 0.7084
```

Phase 2.A 升级后, 在同一 `phase2_baseline30.jsonl` 上重跑, 看:
- faith_on_answered 有没有 +5pp 真涨
- refusal_rate 有没有降到 < 10%
- context_recall 在 HyDE 上有没有突破 0.92

**这才是"算法升级可信对比"的方式**。

---

## 10. 判级

🟢 **PASS** — 数据本身没有作弊或 bug。
**但 Phase 0 → Phase 2.0 的 "+0.40 飞跃" 说法误导性高**, 应表述为:
- Phase 0 的 0.48 是被 corpus GT 错位 + 拒答算 0 双重假数据拉低
- Phase 2.0 的 0.88 是 chat-app "在能答的题上" 的真实能力
- chat-app 平均水平(含拒答) ≈ 0.73, 这是用户感受到的真值

Phase 2.A 起点 baseline 锁定在这里, 必须用控制变量法做后续算法升级。
