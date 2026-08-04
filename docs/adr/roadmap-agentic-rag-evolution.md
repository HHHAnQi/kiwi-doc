# RagDoc RAG 演化路线图：从 Chat-only 到 Agentic（3-6 个月）

- Status: Proposed
- Date: 2026-08-04
- 关联 ADR：[ADR-0011](./adr-0011-multi-turn-context-memory.md)（Phase 1 详细设计）
- 目标：**循序渐进**地把 RagDoc 从 stateless chat-only RAG 演化到 full agent（自主 + 反思 + 长程记忆），每 Phase 有独立 gate，不可跳级

---

## 0. 设计纪律（贯穿所有 Phase）

| 纪律 | 原因 |
|------|------|
| **feature flag 默认 OFF** | 老 baseline 行为不破 |
| **每 Phase 一套评测 gate** | 不能"凭感觉"上 agent |
| **不跳 Phase** | Phase 5 不做 Phase 4 重构会代码乱 |
| **commit atomic** | 单 commit ≤ 500 行 |
| **失败回退 SOP** | 每 Phase 写 runbook |
| **不照搬 LangGraph 全套** | Spring 生态与 Python agent 框架文化不同 |

---

## 1. 三条能力轴（每 Phase 都在推进其中 1-2 条）

Agentic RAG 不是单一物体，是 **Memory × Context × Action** 三轴同时推进：

| Phase | Memory 轴 | Context 轴 | Action 轴 | 自主度 |
|---|---|---|---|---|
| **P1** 多轮对话 | chat memory | 硬编码拼装 | none | Lv0 stateless → Lv0.5 |
| **P2** Query Rewriting | - | - | adaptive / HyDE / multi-query | Lv0 |
| **P3** 文档生命周期 + Observability | - | observability 闭环 | - | Lv0 |
| **P4** Context Engineering 模块化 | - | ContextManager 骨架 | Tool port 留位 | Lv0 |
| **P5** Agentic Lite | unified ctx | 六格 task context | 1-2 个 real tool | Lv1 |
| **P6** Full Agent | working + long-term | token budget | plan + tool + reflect | Lv2-3 |

参考自主度分级（Anthropic 2025 《building effective agents》+ LangGraph chain→state graph→agent 演化）。

---

## Phase 1 — Chat Memory 完整版（Week 1-2, ~9.5 天）

### 目标
单 turn 行为零回归，多轮指代 / 上下文继承可用。用户说"那它呢"不再 NO_RECALL。

### 设计产出
1. `ConversationContext` + `ConversationStore` port（NoOp / Redis 两 impl）
2. `QueryContextualizer`（fallback LLM condense rewrite + CircuitBreaker + 失败回退 stateless）
3. `TopicShiftDetector`（cosine < 0.5 自动跳过 history rewrite）
4. `HistoryCompressor`（@Async, BufferWindow=3 + RollingSummary）
5. 失败 turn 不写 history（抗污染硬 gate）
6. RAGAS multi-turn session eval 扩展（topic_recall@K + entity_recall + 抗污染率）
7. Langfuse trace 加 `conversation_id` metadata + nested observation

### DoD（5 道 gate）
- G1: 80 题单 turn baseline RAGAS faith/recall/precision ±3pp（不退化）
- G2: 20 题多 turn holdout topic_recall@3 ≥ 0.85
- G3: 5 个 failed turn + 5 个 NO_RECALL turn 序列 → summary 中污染 = **0**（硬 gate）
- G4: 50 个长会话（≥ 8 turn）summary 关键实体保留率 ≥ 0.70
- G5: 50 个含 shift 的会话 shift 后 retrieve 召回正确 ≥ 0.80

### 业界对照
- LlamaIndex `CondenseQuestionChatEngine` + `SummaryChatHistoryBuffer`
- LangChain `ConversationSummaryBufferMemory`（默认推荐模式）
- Dify "FULL + CONDENSE_QUESTION" 双模式

### 详细设计文档
**见 [ADR-0011](./adr-0011-multi-turn-context-memory.md)** —— 完整的算法 / 工程纪律 / 评测 / Feature Flag 矩阵。

### 触发下一 Phase 的条件
多轮 baseline 通过 + commit + push + 评测回归 gate 跑通。不要等"完美再进下一步"——80% recall 就够继续推进。

---

## Phase 2 — Query Rewriting / Adaptive Retrieval（Week 3, 3 天）

### 目标
召回质量在多轮基础上再上一台阶。对开放题、长 tail query、跨文档对比场景召回精准。

### 设计产出
1. `TopicDetector`（rule-based, chitchat 跳 retrieve）
2. `HyDERewriter`（fallback LLM 生成假设答案 → 用于 embed 召回）
3. `MetadataExtractor`（rule-based 自动抽 Nacos/Sentinel 实体做 metadata filter）
4. `MultiQueryRewriter`（生成 3 个变体 query 并行 retrieve → RRF 融合）
5. Feature flag 切换：`rag.query_rewrite.mode = off | hyde | multi_query | contextual`

### DoD
- 80 题单 turn baseline：faith/recall/precision ±3pp
- 10 题 factoid 子集：recall@5 ≥ baseline + 5pp
- 10 题 cross-doc 子集：recall@5 ≥ baseline + 3pp
- TopicDetector 误杀率 ≤ 5%

### 业界对照
- HyDE (Gao et al. 2022) — RAG 标配
- RAG-Fusion (Adkins 2023) — Multi-query + RRF
- LlamaIndex `SubQuestionQueryEngine`

### 触发条件
至少 1 个 rewriting 模式通过 ±3pp gate；新 metric 全部进 Grafana dashboard。

---

## Phase 3 — 文档生命周期 + Observability 闭环（Week 4-5, 5 天）

### 目标
生产运维底线：文档可治、事故可定位、SLO 可 alert。这是上量的前置条件。

### 设计产出
1. `DocumentStatus` 扩展（ARCHIVED / DEPRECATED）+ 状态机迁移规则
2. `document_audit_log` 表 + 4 endpoint（archive / unarchive / set-default / audit list）
3. 版本回滚链路（双版本共存 + isDefault flag + Milvus filter）
4. **Loki** 结构化日志 + `traceId` MDC
5. **SamplingTraceObserver**（success 10% / error 100% force send）
6. Cost observability（`ragdoc.llm.token_total` + Grafana PromQL 美元换算 panel）

### DoD
- 文档软删后立刻从 retrieve 结果消失（不破在线检索）
- unarchive 可一键恢复，total < 1s
- 一次完整"SLO alert → trace_id 搜日志 → Langfuse 看 trace → 修复" 1 分钟定位 pass
- 高 QPS 压测 Langfuse Postgres 不崩（sampling 起作用）

### 业界对照
- OpenAI Assistants API 软删 + audit log 模式
- Datadog trace + log + metric correlation
- Langfuse sampling 模式

### 触发条件
chaos 演练通过 + 监控 dashboard 上线 + 评价回归 baseline 稳定（这是后面 agent 阶段的"安全网"）。

---

## Phase 4 — Context Engineering 模块化重构（Week 6-8, ~10 天）

### 目标
为 agent 时代铺地基。把硬编码的 prompt 拼装改为可插拔 ContextManager，让将来加 tool / task / working memory 不再改 ChatService 代码。

### 设计产出

#### ContextManager 接口（核心骨架）
```
ContextManager
  ├── SystemPromptProvider       （第 1 层）
  ├── MemoryProvider              （第 2 层：当前接 ConversationStore）
  ├── RetrievedContextProvider    （第 3 层：RetrieveService）
  ├── ToolDescriptionProvider     （第 4 层：本 Phase 留空，Phase 5 填充）
  ├── TaskContextProvider         （第 5 层：本 Phase 留空，Phase 5 填充）
  └── TokenBudgetAllocator        （给所有 provider 分 token 预算）
```

#### TokenBudgetAllocator
- 输入 total_budget（如 8K）
- 按比例分配：system 5% / memory 25% / retrieved 50% / tool 10% / task 5% / query 5%
- provider 之间协商：memory 超额 → 触发 summary compress；retrieved 超额 → 砍 topK

#### PromptAssembler
按 Anthropic 推荐的 context ordering 拼装：
```
system → memory → tool → retrieved → task → user_query
```

#### ChatService 改造
调 `ContextManager.build(cmd, ctx)` 取 unified prompt 喂 LLM，不再硬编码拼装。

### DoD
- 老 ChatService 行为零回归（baseline ±3pp）
- 加一个 mock Provider 进 ContextManager，不改 ChatService 代码
- Token budget 超 8K 自动触发降级（memory compress / retrieved topK--）
- Langfuse observation 显示每个 provider 的 token 占用

### 关键设计选择
- **不要现在加 tool / task / working memory，只搭骨架**。提前加会过度设计；Phase 5/6 才有真需求
- 这是 refactoring 而非新功能，**baseline ±3pp 必须 hard gate**

### 业界对照
- **LangChain `PromptTemplate` + `Context` 治理**（LCEL 区分 prompt vs context）
- **Anthropic 2025.06 context engineering blog**: "treat context window as a discipline"
- **Cursor agent scratchpad**: 模块化 context source

### 触发下一 Phase 条件
ContextManager 骨架通过回归 + 第一个 agent 场景已定义（推荐"中间件排障助手"）。

---

## Phase 5 — Agentic Lite：六格 Task Context + 第一个 Tool（Week 9-13, ~15 天）

### 目标
从"问答 bot" 跃迁到"任务助手"。用户一次请求触发多步，每步带 plan / 进度 / 工具调用。

### 关键前置：选定产品场景
不直接做通用 agent，先选 **1 个最痛的场景做闭环**（只选 1 个，不要 3 个一起做，agent 框架复杂度非线性）：
- **推荐**：中间件排障助手 — 用户说"Sentinel 没流控住"→ bot 调 Nacos API 拉配置 + 检索文档 + 查 Prometheus 指标 → 给排查结论
- 备选：多文档对比助手（Sentinel vs Hystrix vs Resilience4j）
- 备选：批量文档批处理（上传一沓 PDF → agent 自动切分 + 标签 + 去重）

### 设计产出（六格对照）

| 六格 | 落到代码 | 备注 |
|---|---|---|
| 1 目标 | `TaskGoal`（LLM 从 user query 抽） | 如"诊断 Sentinel 流控不生效的原因" |
| 2 规则 | `TaskRules`（从 chatMessages system prompt 继承） | 如"没找到根因必须说不知道" |
| 3 工具 | `ToolRegistry` + 1-2 个真实 tool | 推荐启动 tool：`fetch_nacos_config` / `query_prometheus_range` |
| 4 资料 | `TaskDocuments`（同 RAG retrieve 注入） | 已有 |
| 5 进度 | `Plan` + `StepStatus` 数组 | LLM 生成 plan，每步执行后更新 |
| 6 结果 | `DoneCriteria`（"找到根因 + 修复建议"） | LLM judge 自评估 |

### 核心组件
1. `TaskContext` record（六格字段）
2. `Planner`（fallback LLM 把 user query → 3-5 step plan）
3. `ToolRegistry` + `Tool` 接口：
   ```java
   interface Tool {
       String name();
       String description();           // 给 LLM 的 tool description
       Map<String, Object> schema();   // JSON schema 入参
       ToolResult execute(Map<String, Object> args);  // 同步执行
   }
   ```
4. **第一个 tool 推荐**：`query_prometheus_range` —— Spring Cloud Alibaba 团队本来就有 Prometheus 数据
5. `AgentExecutor`（循环：选 step → 调 tool / 调 LLM → 更新 plan → 检查 done）
6. SSE 流式输出每一步进展给用户（"我在查 Nacos 配置..." "我在查 Prometheus 30min 数据..."）

### DoD
- 选定场景能端到端跑通（如输入"Sentinel 没流控住" → bot 完成 3-5 步并给出结论）
- 六格里 5 格（goal / 工具 / 资料 / 进度 / 结果）都非空可见（规则可隐式从 system 继承）
- AgentExecutor 单步失败 → retry 1 次；3 次失败 → 标 task failed 让用户干预
- 评测：5 个真实排障 case 人工评分 ≥ 4/5（agent 评测初版用人工，不要瞎用 RAGAS）

### 关键 trade-off
- **同 step 不并行多 tool**（Lv3 才考虑）：单 step sequential 容易调试
- **不做 approval matrix**（人审 tool）：第一波 tool 都只读，没副作用
- **Plan 不可用户改写**：简化 v1

### 业界对照
| 我的组件 | 对照 |
|---|---|
| TaskContext 六格 | LangGraph `StateGraph`, CrewAI `Task`, Claude Agent SDK |
| Planner | LangGraph planner, Anthropic agentic loop |
| ToolRegistry | OpenAI tool calling, LangChain Tool |
| AgentExecutor | LangGraph executor, Claude Agent SDK |

---

## Phase 6 — Full Agent：Reflection + Multi-step + Long-term Memory（Week 14-20, ~20 天）

### 目标
让 agent 真的"靠谱"：自纠错、长任务可恢复、跨 session 记住用户习惯。

### 设计产出
1. **Reflection loop** - 每 step 后 LLM 自评估 (`SelfReflector`)：
   - 输出有依据吗（faithfulness 自检）
   - 离 goal 更近了吗
   - 需要 replan 吗 → 触发 `Planner.replan(currentPlan, stepResult)`
2. **Retry + alt path** - 单 tool 失败 → retry 1 次失败 → Planner 选 alternative tool 或 alternative step
3. **Long-term user memory**：
   - `UserProfile`（偏好、领域背景、tool 凭证列表）
   - 存储：从 chat history 异步抽 entity + preference → Postgres
   - 注入：每次 chat 时拼进 prompt 第一段
4. **Checkpointer**（仿 LangGraph `MemorySaver`）：长任务可中断 + resume
   - Redis 存 `task_state` 序列化（plan / step_status / intermediate_results）
   - 用户离开 → 任务暂停；回来 → 从 checkpoint 续跑
5. **Eval 升级** - AgentBench 风格 metric：
   - 成功率 / 步数 / token cost / 工具调用准确率
   - 自动跑 10 个真实场景 case suite，跑一次出 dashboard

### DoD
- 选定场景：成功率（正确完成 task）≥ 70%（业界 SOTA ~75% on simple domain）
- 长任务断点续跑：能从中间 step 接上
- Long-term memory：同一用户第 2-5 session 表现优于无 memory（A/B 显著）
- AgentEval dashboard：成功率 / 平均步数 / 平均 token / 多次失败率 全可视

### 业界对照
- **LangGraph full state graph**：checkpointer + reflection
- **Reflexion 论文（Shinn 2023）**：self-correction loop
- **MemGPT / Letta (2024)**：长程 memory 分层管理
- **Anthropic agentic loop 2025**：自纠错六步循环
- **Generative Agents (Stanford 2023)**：importance scoring for memory

### 触发后续（本 Phase 不封顶）
看用户实际使用数据，若需求转向 multi-agent 协作（文档 agent + 排障 agent + 工单 agent）→ 进 Phase 7（单独再 plan）

---

## 总览：每月里程碑

| 时间 | Phase | 里程碑（用户视角） | 内部能力突破 |
|---|---|---|---|
| **第 1 月末** | P1 + P2 | bot 会聊天不丢上下文，召回复准 | Memory + Rewrite |
| **第 2 月末** | P3 + P4 | 后台稳、运维快、context 模块化 | Observability + ContextManager |
| **第 3 月末** | P5 | bot 会"做任务"，能调工具 | Agentic Lite 启动 |
| **第 4-5 月** | P6 上半 | bot 会自纠错，长任务可断点续跑 | Reflection + checkpoint |
| **第 5-6 月** | P6 下半 | bot 跨 session 记住用户偏好，A/B 显著 | Long-term memory |

---

## 三个最容易踩的坑

1. **Phase 5 想 1 天搞 5 个 tool** —— 每个 tool 都要 schema + description 调试 + eval case，实际 1 tool ~3 天。规划按这个算
2. **Phase 6 跳 reflection 直接 multi-agent** —— 这是 LangChain 社区公开的失败模式。**Reflection 永远先于 multi-agent**
3. **每个 Phase 都想做 RAGAS 自动 gate** —— agent 阶段 RAGAS 不灵了，必须人工 eval / 后启 AgentBench。**别死守一套指标体系**

---

## 决策点（实施前需要明确）

1. **Phase 5 选哪个场景**？（排障助手 vs 多文档对比 vs 文档批处理）
2. **Phase 4（Context Engineering 模块化）要不要做**？
   - 不做：Phase 5 加 tool 时 ChatService 满天飞，3 个月技术债
   - 做：花 10 天做 refactoring 没用户感知，但避免欠债

**推荐都做**，但具体取舍由产品 / 团队优先级决定。
