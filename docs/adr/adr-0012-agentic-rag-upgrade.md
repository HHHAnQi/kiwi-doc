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

---

## 7. v2 修订（2026-08-23 代码复查 + 业界调研后）

代码复查结论：编排/工具/预算框架/CAS/checkpoint/评测脚手架完成度很高，但存在
**3 个 P0 缺陷使主链路（sufficiency 不足 → replan 再检索 → 作答）无法走通**，
通电前必须修复。

### Phase 0（新增，2-4 天）: 修 P0 通电

| # | 缺陷 | 证据 | 修复 | 工作量 |
|---|---|---|---|---|
| P0-1 | `rag.agent.sufficiency.*` 不在 yml，默认 disabled → DispatchingSufficiencyJudge 恒返 UNDETERMINED → Guard 全部拒答 | SufficiencyProperties.java:23-30; DispatchingSufficiencyJudge.java:38-47 | application.yml 补 sufficiency 开关（enabled/model-fallback/timeout 环境变量占位） | 0.5h |
| P0-2 | reqId→stepId 映射恒空 Map（PlannerPlanAssembler 丢弃 requirementIds）→ Rule judge 永远 NO_EVIDENCE | Coordinator.java:571-583; PlannerPlanAssembler.java:104-117 | AgentToolStep 增加 requirementIds 字段并透传；Coordinator 由 plan 构建 map | 1-2 天 |
| P0-3 | 预算硬编码 pr6Default（maxReplans=0, maxSteps=3）且无配置绑定 → Replan 必然 BUDGET_ZERO | PlannedAgentPipeline.java:61,137; AgentBudget.java:44-47 | 新增 rag.agent.budget.* + AgentExecutionPolicyFactory；maxReplans 字面量 1 改读 properties | 0.5-1 天 |

**Phase 0 DoD**: `mode=AGENTIC` 冒烟 3 个多跳 query 走完 plan→execute→sufficiency→
(必要时 replan)→answer 全程，agent_run/agent_step 落库可查，无 REFUSED_NO_EVIDENCE
误拒。

### Phase 1 修订: 对照评测（原方案 + 调研口径）

- P1-1 Planned 路径接 citation_verify（与 Classic 口径公平）: 1 天
- P1-2 ModelPlanner 补 temperature=0/超时/maxTokens: 1 天
- P1-3 pilot20 金标双签冻结（gold_freeze_check.py 已就绪）后扩 60 题: 3-5 天（人工）
- P1-4 只读 run/step 查询端点（评测与审计必需）: 0.5 天
- P1-5 Replan 查询注入已获证据关键词（防签名重复去重导致 INSUFFICIENT_AFTER_REPLAN）: 1-2 天
- 评测协议: 每题 3-5 次报 pass^k；准确率/单题 token 成本/p50-p95 时延三维并列；
  trace 失败模式聚类（引用不支持/漏检索/循环/超步）；路由命中率与误升级率

### Phase 2 修订: 研究驱动的增强

- 85% 预算强制收尾（Beast Mode：预算尽则基于已有证据强制作答，不 REFUSE）
- 步骤级证据压缩（工具输出→结论化摘要再进协调器上下文，防上下文爆炸）
- 无依赖 plan step 并行扇出（工具全只读，天然可并行；业界实测省 90% 研究时长）
- P2 遗留: token/cost 预算接入 usage 记账、discoveredEntities、Composer 引用解析、
  resume 执行器接线（checkpoint 底座已就位无人消费）

### 确认不需返工的决策

路由分层（Adaptive-RAG 同款）、Plan-Execute+单次增量 Replan、不上多 agent（工具<10）、
自研轻量协调层（Bitter Lesson）——与业界共识一致。
