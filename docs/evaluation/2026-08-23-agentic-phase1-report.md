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
