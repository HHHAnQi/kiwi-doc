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
