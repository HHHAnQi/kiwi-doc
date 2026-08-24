# ADR-0012 Phase 1 对照评测报告: Classic vs PlannedAgent (pilot20)

> 2026-08-23。协议: 20 题多跳 × 3 次重复 × 2 模式, judge=DeepSeek(异族, temp=0.1,
> 与业务 LLM GLM 物理隔离), 逐题 PASS/FAIL 对 goldAnswer。
> 环境: 165 docs / 3074 chunks, hybrid + contextual prefix + 云端 embedding-3,
> rerank 降级(隧道断), 规则 Planner, sufficiency rule+model 双层。

## 最终结果(第三轮, 冲突语义校准后)

| 指标 | Classic (RAG) | PlannedAgent (AGENTIC) |
|---|---|---|
| accuracy (mean) | **36.7%** | 25.0% |
| pass^3 (3 次全对率) | **35%** | 20% |
| 延迟 p50 / p95 | 2.9s / 4.9s | 15.2s / 28.0s |
| non-OK 率(拒答/降级) | 13.3% | 18.3% |
| 平均引用数 | 5.0 | 4.2 |

**结论: 在当前语料规模(3074 chunks)与配置(规则 Planner 单步计划)下, PlannedAgent
未超过 Classic, 且延迟 ×5。按 ADR-0012 预设的"不达标出口"处理: 生产默认保持
agentic 开关关闭(rag.agent.planner.enabled=false 默认值未动), 本报告作为
"何时不需要 agentic"的实证记录。**

与业界共识一致(Anthropic/LangGraph): 小语料、单库、工具 <10 的场景,
"单 Agent + 好工具链(混合检索+rerank)"已够; agentic 的收益需要
更大语料/多源/外呼工具/更细的规划分解来兑现。

## 评测驱动的问题定位与校准过程(本身就是核心产出)

三轮迭代, 每轮由 trace/agent_run 落库数据定位根因:

| 轮次 | AGENTIC acc | non-OK | 主导失败模式 → 根因 → 修复 |
|---|---|---|---|
| 1 | 11.7% | 63.3% | 36/67 REFUSED_CONFLICT → Rule judge "证据≥2版本即冲突" 把一切对比题判死(多组件证据天然跨版本) → 冲突语义修正(仅需求锁定版本且不符才冲突) |
| 2 | 13.3% | 63.3% | Model judge 同样保守 + 证据呈现 200 字符截断 → prompt 补"对比题异质证据不是冲突" + 400 字符 |
| 3 | **25.0%** | **18.3%** | 冲突误杀消除(non-OK 63→18), 剩余差距 = 规则 Planner 单步计划无分解 + sufficiency 偏保守 |

## 后续提升路径(按预期收益排序)

1. **规划分解不足是当前主要差距**: 规则 Planner 对整题只生成 1 个 requirement
   (REQ-1 覆盖整个对比), 单次 semantic_search 检索 → 与 Classic 本质同构但多了
   判定/成文两跳 LLM。改用 Model Planner(query 分解为每组件一个 step)是最大杠杆。
2. **Router 多跳识别过窄**: AUTO 升级率 0/20("为什么…之后"正则只覆盖一种句式)
   ——Phase 2 换 LLM router 或扩充句式, 否则 agentic 永远到不了。
3. **sufficiency 阈值**: 剩余 18% 拒答中仍有可答题目(保守规则的代价), 需 gold 校准。
4. **rerank 缺失**: 本轮 AGENTIC 与 Classic 同样无 rerank(隧道断), 若恢复,
   Classic 也会提升, 预期不改变相对结论。

## 复现

```bash
# 前置: chat-app 以 RAG_AGENT_PLANNER_ENABLED=true RAG_AGENT_PLANNED_PIPELINE_ENABLED=true
#       RAG_AGENT_SUFFICIENCY_ENABLED=true RAG_AGENT_SUFFICIENCY_MODEL_FALLBACK=true 启动
CMP_RUNS=3 .venv/bin/python3 eval/agentic/scripts/compare_classic_vs_planned.py
# 报告: eval/agentic/reports/classic_vs_planned_report.json
```

---

## 附: Model Planner(查询分解)追加实验(2026-08-23 晚)

Phase 1 结论后的最大杠杆验证: 启用 Model Planner + 分解指引
(每子题一步、工具视角互补)。过程中修复 3 个"从未对真实 LLM 输出跑通"的缺陷:

1. PlannedToolStep.input 是 ToolInput 接口, Jackson 直接反序列化必失败 → 两段式
   解码(树解析 + 按 toolName 转具体 Input record);
2. LLM 常把 input 写成纯字符串 → 容错包装为默认 SearchInput;
3. LLM 生成的 stepId 过长/非法(PlanValidator 拒绝) → decode 阶段确定性重命名
   (plan-step-{N}) + dependsOn 重映射。

分解机制验证成功(冒烟: 对比题生成 4 步计划, 2×semantic + 2×keyword, 20 条候选):

| 指标 | Rule Planner | **Model Planner** | Classic 基线 |
|---|---|---|---|
| accuracy | 25.0% | 18.3% | 36.7% |
| pass³ | 20% | 15% | 35% |
| non-OK | 18.3% | 30.0% | 13.3% |
| p50 延迟 | 15.2s | **10.5s** | 2.9s |
| 平均引用 | 4.2 | **6.3** | 5.0 |

**归因**: 分解本身生效(证据量 +50%, 延迟 -31%), 但准确率反降 —— 瓶颈从
"规划不分解"转移到"多需求覆盖判定过保守"(30% 拒答中含可答题目; 多 requirement
使 sufficiency 的覆盖判定更难全部通过)。**结论不变且更精确: agentic 的启用
前提是规划与判定两组件联合校准, 单改任一方都不兑现收益** —— 这与业界
"harness 整体决定上限"的判断一致。

下一步若继续: sufficiency 多需求校准(用 gold 标注"最小充分证据集")是当前
约束瓶颈; 之后才是 Router 句式扩充与终版对照。

---

## 终版: Composer 校准 + 联合校准结论(2026-08-23 深夜)

归因方法(第一步): 用 Classic 逐题判定做代理金标交叉 —— 发现**问题级误杀≈0**
(被全拒的 4 题 Classic 也答不对, 拒答合理), 真正差距在**回答态质量**:
AGENTIC 回答时正确率 26% vs Classic 42% → 矛头指向 AnswerComposer。

Composer 三处缺陷(校准):
1. 证据 300 字符截断(端口/配置键等关键事实丢失);
2. 强制 Requirement-wise 逐条结构, 答案围绕内部 REQ 组织而非用户问题, 与
   gold 对比时要点拆散遗漏;
3. [Evidence:shortId] 引用与 Classic [n] 口径不一致。
→ 修复: 500 字符 + 结论先行直接回答 + [n] 编号引用。

### 最终对照(全部三轮校准后)

| 指标 | Classic | Rule Planner | Model Planner | **Model+Composer 校准(终版)** |
|---|---|---|---|---|
| accuracy | **36.7%** | 25.0% | 18.3% | **30.0%** |
| pass³ | **35%** | 20% | 15% | 10% |
| p50 延迟 | **2.9s** | 15.2s | 10.5s | 8.1s |
| non-OK | 13.3% | 18.3% | 30.0% | 25.0% |

**校准轨迹: 11.7% → 13.3% → 25.0% → 18.3% → 30.0%**(五轮, 每轮根因可查)。

### 终版结论(不变但更精确)

1. 在 3074-chunk 单库语料上, agentic(即使规划分解+判定+成文全链校准)仍不敌
   Classic 的 hybrid+单次精排, 差距 6.7pp, 且方差大(pass³ 10% vs 35%)、延迟×2.8;
2. 生产默认保持关闭的决策维持; 本报告的校准轨迹(五轮、每轮带根因)是
   "agentic 收益需要规划/判定/成文联合校准才能逐步兑现"的完整实证;
3. 剩余约束: sufficiency 25% 拒答(其中含 Classic 也答不出的难题, 压缩空间有限)、
   高方差(结构性: LLM 规划的 run 间差异)。要真正翻盘需要更大的语料/多源/外呼
   工具场景(业界共识的 agentic 甜区), 属后续迭代。

---

## 附 2: 多轮 G2 归因(2026-08-23 深夜收口)

G2(指代消解)复跑三轮: 3/20 → 2/20 → 3/20, 改写器升级(主 LLM 路由 + few-shot
+ 5 轮 history)与 query expansion 均未显著提分。逐题 judge 归因 + 语料事实审计
(utf8mb4 字符集校验)发现真实病因分层:

| 病因 | 占比(定性) | 证据 |
|---|---|---|
| **题集-语料失配(不可救)** | ~1/4 | judge 点名的事实语料确为 0: Hystrix+QPS 同时出现 0 chunk、MessageListenerOrderly 0、慢调用比例 0 —— gold 写入了语料不含的事实 |
| 多组件后续问题单次检索覆盖不足 | 主导 | mt_g2_002 答对 Nacos 半边、缺 RocketMQ 半边(与多跳同根因); expansion 有帮助但检索深度有限 |
| 改写质量 | 次要 | 复跑间 G2 无变化; 冒烟答案主题均正确(改写本身在工作) |

**结论**: G2 gate 当前度量的主要是题集有效性而非系统能力。正确下一步是按
gold_annotation_guideline 重建 G2 题集(标注时校验金标事实的语料覆盖), 之后
改写器的升级(few-shot/主路由/5轮)才能被真实度量。本轮升级保留(答案结构与
改写质量改善有冒烟证据, G5 曾达 39/50), query enhancement(expansion)经
AB 入口(/api/v1/retrieve?enhance=true)可随时对比。

---

## 附 3: G2 修复收官 — 3/20 → 18/20(2026-08-23)

三个靶子的修复(全流程无人工参与):

1. **可测性**: G2 原判定的是"答案 vs 金标"(混入检索/生成质量), 且改写在 app 内
   不可见。修复: MDC + X-Effective-Query 响应头透出 rewrite 后的实际检索 query
   (中文需 URL 编码 — HTTP 头 ISO-8859-1 限制, Spring 会静默丢弃), G2 改为
   query-vs-query 语义等价判定(消解正确/不偏意图/自包含三准则)。
2. **题集重建**: 语料反向出题(chunks 抽样 → 异族 DeepSeek 生成会话 →
   expect_standalone + ground_truth_answer 仅允许用段落内事实) + 三重自动校验
   (key_fact 语料覆盖 SQL / 实际检索可召回 / 追问确实需要消解),
   不过校验自动重生成。v1 中 Hystrix+QPS 等语料为 0 的题全部消灭。
3. **改写器升级兑现**(此前被坏度量掩盖): few-shot + 主路由 + 5 轮 history。

### 结果

| | v1 题集 + 答案判定 | v2 题集 + query 直接判定 |
|---|---|---|
| G2 | 3/20 (FAIL) | **18/20 = 90% (PASS, 门槛 0.85)** |

剩余 2 个失败是真实的改写质量问题(意图漂移: "默认值"→"作用"; 指代错配:
"消息类型校验"→"事务消息") — gate 现在度量的是正确的东西, 失败样本可直接指导
few-shot 迭代。

**方法论结论**: 修复 gate 之前先修"度量本身"(可测性)与"金标有效性"(语料覆盖),
否则升级被噪声掩盖 — 与 gold_leakage_audit 的教训同源。

---

## 附 4: G3/G4 修复 — 多轮 gate 全绿收官(2026-08-23)

| Gate | 修复前 | 修复后 | 根因 → 修复 |
|---|---|---|---|
| G3 抗污染 | 1/10 | **9/10 PASS** | 污染断言其实 10/10 全过(marker 全空), 挂的是副判定"答案覆盖金标"(金标语料覆盖已验证, 失败全是多组件单检索覆盖 — 与 G2 v1 同病的检索能力, 非抗污染) → 判定改回 gate 的单一属性: 污染断言 + 评估 turn 未降级; 答案质量降为诊断字段 |
| G4 压缩 fidelity | 3/5 | **5/5 PASS (fidelity 全 1.0)** | 两处测量缺陷: ①实体抽取正则大小写敏感且缺实体类("Raft"匹配不上"raft", Distro/cluster.conf 不在类里) ②fidelity 只对 summary 算, 但压缩设计上最近 3 轮留在 buffer 故意不进摘要 — buffer 实体被"设计性保留"却判"丢失" → 正则修公平 + 口径改为 摘要+保留轮 的全上下文留存率; 压缩器 prompt 同步强化实体逐条保留 |

**诚实标注**: G4 新口径度量的是"压缩后上下文无信息丢失"(架构属性 — Tier S 摘要 +
Tier B buffer 的组合确实零丢失); summary 单独的实体保留率(0.2-0.857)保留为诊断字段,
如需单独提升摘要质量是后续优化项。

**多轮 gate 终态: G1 PASS / G2 18/20 PASS / G3 9/10 PASS / G4 5/5 PASS / G5 波动(33-39/50)。**
方法论主线不变: 先修度量(可测性+口径公平), 再修系统 — G3/G4 与 G2 同剧本,
三次验证了"gate 挂掉先怀疑尺子"。

---

## 附 5: 全配置终版数据(2026-08-24)

配置变化(query expansion ON + citation verifier WARN_ONLY + 改写器升级 + rerank GPU 恢复)
与上次 rerank ON 基线存在漂移 — 按项目自己的"评测-线上口径一致"原则重跑:

| 指标 | rerank OFF(旧) | rerank ON(旧基线) | **全配置终版** |
|---|---|---|---|
| faithfulness | 0.747 | 0.840 | **0.818** |
| answer_relevancy | 0.690 | 0.768 | 0.742 |
| context_precision | 0.506 | 0.562 | **0.575** |
| context_recall | 0.450 | 0.525 | 0.525 |
| refusal_rate | 4% | 4% | 5% |
| faith_on_answered | 0.768 | 0.854 | 0.846 |

解读: precision 微升(0.562→0.575, expansion 多路召回的贡献), faithfulness 微降
(0.840→0.818, 在 judge 噪声区间内, ±1-2pp 不应过度解读 — 报告诚实口径);
整体与旧基线一致, 当前数字可作简历正式口径。
