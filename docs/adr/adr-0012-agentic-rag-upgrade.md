# ADR-0012: Agentic RAG 升级方案（通电 → 实证 → 深化）

> 状态: Proposed（2026-08-23）
> 前置: ADR-0011（多轮记忆）、`roadmap-agentic-rag-evolution.md`（演进路线图）、
> PR-7f.2c-pre（PlannedAgentPipeline runtime gate）
> 本文回答三个问题: 与 Classic RAG 的本质区别是什么 / 技术要求是什么 /
> 在现有代码资产上分几步升级、每步的验收标准是什么。

---

## 0. 决策摘要

| 项 | 决策 |
|---|---|
| 总原则 | **路由分层，不是全量替换**：TaskRouter 判定多跳且置信度 ≥0.80 才升级 PLANNED_AGENT，其余走 Classic（成本可控、回归可守） |
| 规划范式 | Plan-and-Execute 为主 + 严格一次 Replan（`max-replans=1` 硬上限），不用裸 ReAct（延迟方差大、loop 风险高） |
| 升级路径 | 三阶段：Phase 1 规则 Planner 通电+对照评测 → Phase 2 LLM Planner+任务记忆+路由评测 → Phase 3 MCP 外呼+恢复演练+过程可视化 |
| 合并门槛 | 每阶段有量化 DoD；Phase 1 对照若不达标则收敛路由阈值而非强行全开（"单 Agent + 工具链已够"也是合法结论） |

## 1. 与 Classic RAG 的本质区别

**决策权从代码转移给模型**。Classic 是固定流水线（query→改写→检索→rerank→生成，一次
通过）；Agentic 是动态循环——模型决定**要不要检索、检索什么、查哪个源、证据够不够、
要不要继续**。三个可观测差异：

| 维度 | Classic（现状默认） | Agentic（目标） |
|---|---|---|
| 检索次数 | 恒为 1 | 0..N（按需；多跳题 2-5 次） |
| 反馈闭环 | 无 | act→observe→reflect：sufficiency judge 判证据不足 → 改写/换工具再查 |
| 失败处理 | 检索不到 → 拒答 | 换关键词/换工具/换源重试（预算内） |

适用边界（面经共识）: 单跳事实题 Agentic 纯增加成本；多跳/对比/聚合题才受益。
**因此本方案的第一 citizen 是路由准确率，不是 Agent 本身。**

## 2. 技术要求 → 现有资产映射

| 要求 | 现状 | 缺口 |
|---|---|---|
| 规划循环 | ✅ PlannerProvider（RuleTemplate+Model 双实现）、Coordinator（1 次 Replan、loop 检测、no-progress 判定） | 未通电、未评测 |
| 工具层 | ✅ Keyword/Metadata/Semantic 检索 + DocumentFetch + CitationVerify；MCP Server（对外暴露） | 无 MCP 外呼（消费外部工具） |
| 证据反思 | ✅ SufficiencyJudge + DecisionGuard | 阈值未校准 |
| 终止安全 | ✅ 预算/checkpoint/租约 + 路径震荡检测（重复工具签名） | 未在长任务上演练 |
| 状态恢复 | ✅ agent_run/agent_step 表 + lease + checkpoint | 无"继续上次任务"入口 |
| 路由分层 | ✅ TaskRouter + ExecutionStrategyResolver（置信度门） | 路由准确率无评测 |
| 评测 | ✅ G1-G5 gate + RAGAS 框架 | **零 Agentic 实证（最大缺口）** |

## 3. 目标架构

```
POST /chat (SSE)
  └─ ChatOrchestrator
      └─ TaskRouter ──(单跳/闲聊/置信度<0.80)──► ClassicRagPipeline（现状, 不动）
            │
            └─(多跳 && conf≥0.80 && flag ON)──► PlannedAgentPipeline
                 ├─ RuleTemplateRequirementExtractor（需求冻结）
                 ├─ Planner（Phase1: RuleTemplate / Phase2: LLM）
                 │    plan = [step1: keyword_search("seata AT 回滚"),
                 │             step2: semantic_search("undo_log 表结构"), ...]
                 ├─ Executor 循环（预算: max-plan-steps=3, max-replans=1）
                 │    ├─ Tool 调用（三路检索/Fetch/Verify, schema 校验）
                 │    ├─ SufficiencyJudge（证据 vs 冻结需求）
                 │    └─ 不充分 → Replan(仅未覆盖需求) / 充分 → Finalizer
                 ├─ EvidenceGroundedAnswerComposer（带 [n] 引用成文）
                 └─ 全程: agent_run/agent_step 落库 + Langfuse trace + checkpoint
```

## 4. 分阶段实施与验收（DoD）

### Phase 1（本周）: 通电 + 第一组对照数据

1. 配置: `RAG_AGENT_PLANNED_PIPELINE_ENABLED=true`，Planner 用 RuleTemplate（零额外
   LLM 成本，先证明协调层正确）；
2. 题集: 构造 **20 题多跳/对比集**（现有语料内出题，标准: 单次 hybrid 检索 top5 无法
   同时覆盖两个子主题，如 "Seata AT 与 Saga 回滚机制差异及各自依赖的表/日志"）；
3. 对照: 同题集 Classic vs PlannedAgent，judge 复用 DeepSeek（物理隔离不动）；
4. 路由: TaskRouter 对该题集的升级命中率 + 对 50 题单跳集的误升级率。

**DoD**: ① 协调层零 crash（budget/loop/lease 全路径触发日志可查）；② 多跳题集
PlannedAgent ≥ Classic 且差距可解释；③ 误升级率 <10%（否则上调 min-router-confidence）。
**不达标的合法出口**: 结论写为"该语料规模下单 Agent + 三路工具已充分，路由分层
守住成本"，Planner 保持 gate 关闭——同样是可写进简历的实证。

### Phase 2（+1 周）: LLM Planner + 任务记忆

1. `rag.agent.planner.model-enabled=true`，对照 RuleTemplate 的规划质量（步骤可执行率、
   需求覆盖率）；
2. Agent 任务记忆: agent_step 已落库 → 增加 `GET /api/v1/agent/runs/{id}/resume`
   （从 checkpoint 续跑），配 kill -9 中断恢复演练；
3. 路由评测集扩到 100 题（50 单跳 + 50 多跳），产出路由 PR 曲线，定最终阈值。

**DoD**: LLM Planner 多跳命中 ≥ RuleTemplate +5pp；中断恢复演练通过；路由阈值有
PR 曲线依据。

### Phase 3（+1 周）: 生态与体验

1. **MCP 双向**: Agent 可消费外部 MCP 工具（首批: GitHub issues 检索——
   `eval/fetch_github_issues.py` 改造为 tool；工具选择走 tool 描述检索）；
2. 前端: Agent 执行过程可视化（plan 步骤流 + 每步工具调用与证据卡片 + 步骤内流式）；
3. 评测: Agentic 专属指标进 CI（多跳 pass@k、工具选择准确率、平均步数/预算命中率）。

**DoD**: 多跳题集含外源证据题 ≥10 题且全链路可追溯（trace→plan→tool→evidence）；
前端演示可走完一个 3 步 plan 的完整可视化。

## 5. 关键技术细节

- **升级判据**: ExecutionStrategyResolver 现逻辑 `planner.enabled && plannedPipeline
  .enabled && conf≥0.80` 不变——所有 Phase 只动配置与阈值，不动判据代码；
- **Replan 语义**: 仅生成 `uncoveredRequirementIds` 对应的增量步骤（代码已实现），
  防止 Replan 退化为整盘重规划；
- **工具选择**: 步骤描述与工具 schema 的匹配由 Planner 直接指定 tool name（当前实现），
  Phase 3 工具数 >8 后引入 tool 描述检索；
- **成本护栏**: 单 run 预算 = max-plan-steps(3) × 每 step 一次 LLM 调用上限；trace
  记录 token 用量，Agentic 路径 p95 延迟与成本单独出指标（与 Classic 对比是简历素材）；
- **安全**: 工具全部只读（检索/取文档/校验引用），无写操作 → 无需人工确认门；
  未来加写工具时引入 budget 内 confirm。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| LLM Planner 规划质量差（步骤不可执行） | Phase 1 先用 RuleTemplate 证明协调层；LLM 版有步骤 schema 校验 + 降级回规则 |
| 延迟/成本失控 | 路由分层 + 硬预算 + p95 单独观测；不达标即收敛阈值 |
| 多跳题集太小结论不稳 | 20 题起步、Phase 2 扩 50；报告标注置信区间 |
| 伤到 Classic 主路径 | G1 gate（±3pp）每阶段必跑，CI -3% 阻断不变 |
