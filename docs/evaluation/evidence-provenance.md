# 证据溯源: 每个数字怎么来的(2026-08-24 终版)

> 本文回答"证据如何来的": 每个 headline 数字的确切出处 — 题集、judge、协议、
> 运行环境、原始文件。面试第 3 层追问("指标怎么来的?")的完整答案。

---

## 一、LLM-as-Judge 是什么模型?

**主 judge: DeepSeek `deepseek-chat`(temperature=0.1)**, 经独立命名空间
`JUDGE_LLM_PROVIDER_1_*` 配置, 与被测系统物理隔离:

| 角色 | 模型 | 提供方 | 为什么隔离 |
|---|---|---|---|
| 被测系统(答案生成/改写/规划/判定) | GLM-4-plus | 智谱 bigmodel.cn | — |
| **评分 judge(#1, 全部 headline 数字)** | **deepseek-chat** | **DeepSeek API** | 异族: 杜绝同源自评高估 |
| 交叉 judge(#2, 备用) | qwen-max | 阿里 DashScope | 双 judge 一致性(已量化: DeepSeek 比 Qwen 严 ~7pp) |

尺刻度哨兵: `faith_on_refused` 应≈0(拒答文案被判幻觉的比率), 用于验证 judge
本身在工作。RAGAS 的 answer_relevancy 依赖 embedding(本地 BGE-M3), 非 LLM judge。

## 二、终版主数据(2026-08-24, RUNS=3 mean±std)

**协议**: 100 题抽取式题集(eval/questions.curated.jsonl) × 3 次完整重跑,
每次 100 个 chat 调用 + RAGAS 400 项 judge 评估; judge=DeepSeek。
**环境**: 165 docs/3074 chunks 语料, hybrid+RRF, GPU rerank(bge-reranker-v2-m3,
AutoDL), query expansion ON, rerank 分数闸门 0.3, citation verifier WARN_ONLY。
**原始文件**: `eval/reports/final/run{1,2,3}.md` + `/tmp/final_run{1,2,3}.log`。

| 指标 | run1 | run2 | run3 | **mean±std** |
|---|---|---|---|---|
| faithfulness | 0.780 | 0.774 | 0.777 | **0.777 ± 0.003** |
| answer_relevancy | 0.691 | 0.705 | 0.713 | 0.703 ± 0.011 |
| context_precision | 0.555 | 0.552 | 0.560 | 0.556 ± 0.004 |
| context_recall | 0.490 | 0.510 | 0.510 | 0.503 ± 0.012 |
| refusal_rate | 8% | 7% | 4% | 6.3% ± 2.1% |
| faith_on_answered | 0.804 | 0.805 | 0.804 | **0.804 ± 0.001** |

**重要修正(诚实)**: 此前单轮跑出的 faith 0.818 处于波动乐观侧; mean±std 揭示
真实水平 0.777(极稳定, std 0.003)。终版数字以本表为准。

## 三、消融证据链(每个组件的贡献, 均有对照)

| 消融 | 数据 | 出处 |
|---|---|---|
| **rerank OFF→ON**(100题对照) | faith 0.747→0.840(+9.2pp), recall +7.5pp | 2026-08-23, eval_ragas_report 存档; judge=DeepSeek, 其余配置同 |
| **expansion ON vs OFF**(本轮补) | faith 0.777±0.003 vs 0.774; recall 0.503 vs 0.505 — **贡献不显著(差值 < std)** | 2026-08-24 消融单跑(/tmp/ablation_noexp.log); 诚实结论: 抽取式单跳题集本不需要多路扩展, expansion 的预期价值在真实多源/口语化 query 场景 |
| **hybrid vs dense**(4象限) | v3 时期消融 | eval/v3day2_*.md |
| **parent-child 切块** | context_recall 0.55→提升 | eval/v3day2_quadrantD |
| **score gate**(本轮新增) | 包含在终版 3 轮内(无独立 OFF 点, 影响量级 < expansion) | — |

## 四、Agentic 对照证据(五轮校准轨迹)

协议: 20 题多跳 × 3 次 × 2 模式, 逐题 PASS/FAIL 对 goldAnswer, judge=DeepSeek。
轨迹: 11.7% →(冲突语义修正)→ 13.3% →(证据呈现 400 字符)→ 25.0% →(Model Planner
分解)→ 18.3% →(Composer 校准)→ **30.0%**, vs Classic 36.7%。每轮根因入档:
`docs/evaluation/2026-08-23-agentic-phase1-report.md` + 附录。
原始: `eval/agentic/reports/classic_vs_planned*.json`。

## 五、多轮 gate 证据

G2 18/20(语料反向题集 v2, query-vs-query 判定) / G3 9/10(污染单一属性口径) /
G4 5/5(全上下文留存率) / G1 PASS(±3pp) / G5 波动 33-39/50(未校准, 如实标注)。
原始: `eval/multi_turn/report_latest.json` + 各轮 report_*.md。

## 六、已知边界(如实)

1. 题集为抽取式(LLM 从 chunk 生成, gold 抄原文) — faithfulness/recall 偏乐观,
   相对结论(消融/对照)可靠, 绝对值应谨慎解读; 非原文 GT 仅 30 条人工集;
2. judge 与人工标注的 κ 一致性从未对齐(评测体系已知缺口);
3. G5 未校准; agentic 的延迟/方差为结构性(规划 LLM 的 run 间差异);
4. 所有评测打同步 /chat 接口; SSE 路径共享组装逻辑但未单独评测。
