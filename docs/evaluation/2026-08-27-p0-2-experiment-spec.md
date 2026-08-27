# P0-2 EXPERIMENT SPEC — LLM Planner Evaluation Pilot (FROZEN)

> 冻结时间: 2026-08-27。运行前任何字段不得修改；如必须修改，需作废本 spec 并重新冻结。
> 修复背景: 此前 200题×3轮 A/B 实测对象为 RuleTemplatePlannerProvider（`model-enabled`
> 评测后才切 true），其结论不能代表 LLM Planner。本 pilot 补上这一证据缺口。

## 1. Research Question

LLM Planner（真实分解查询的 Model Planner）相对 Classic RAG，在单跳与多跳/分解敏感问题上：

1. 是否提升 answer correctness 与 evidence completeness？
2. 是否真实产生 decomposition（初始 plan 步数 > 1）？
3. 代价（latency / LLM calls / 步数）是多少？
4. 降级链触发率（RULE_FALLBACK / CLASSIC_FALLBACK）是多少？

## 2. A/B Definition

- **A = Classic RAG**：`POST /api/v1/chat {"mode":"RAG","top_k":5}`
- **B = Agentic RAG + LLM Planner**：`POST /api/v1/chat {"mode":"AGENTIC","top_k":5}`

同一应用实例、同一端点、同一基础设施。除 Agentic 必需机制（Planner/Sufficiency/Replan/
原查询锚定）外，两臂共用：Embedding(BGE-M3)、Hybrid 检索+RRF、Reranker(bge-reranker-v2-m3,
**冻结不调参**)、Context builder、Generator（primary route 同一模型）、Judge。

## 3. Dataset (FROZEN)

```
PILOT_DATASET_HASH=a6d55294ee4421a608fe03efb2d5de3bf7486c2d5e0ec6691830c25176ee4ee6
N=50
文件=eval/agentic/datasets/agentic_pilot50_llmplanner.jsonl
源=agentic_expanded_200.jsonl (sha256 8f1e1e38...cea059, 未经修改)
选择规则(冻结)= slice C_multi_step 按ID升序前25 (agentic_v2_C_101..C_125)
             + slice S_simple_control 按ID升序前25 (agentic_v2_S_151..S_175)
             ，合并后按 ID 升序
```

S 选择理由：S=单跳对照（Classic 单次检索可答，预期 Classic 占优）；C=多步检索拼接
（入口概念→配置→限制三层，分解敏感，LLM Planner 理论收益所在）。

## 4. Planner Object (FROZEN)

```
PLANNER_PROMPT_HASH=1b8038f7de34e47f5bb4ee8f4dec7e44d24c63f1fe97402f5e34f2eefc2e87a5
  (= sha256 of ModelPlannerProvider.java — buildPrompt 由该文件唯一定义, commit 4eaa109 后未改)
PLANNER_CONFIG_HASH=96be89beeed796fb4410d9a6847d7cbe4b995df309b969a822d2c971f283c431
  (= sha256 of: model=glm-4-plus|temperature=0.3|max_tokens=1024|timeout_ms=10000|retry=1|
     rule_fallback=true|max_plan_steps=3|max_replans=1|min_router_confidence=0.80|
     planner_enabled=true|planned_pipeline_enabled=true|model_enabled=true|
     sufficiency=rule+model_fallback)
MODEL=glm-4-plus (Zhipu compatible-mode; LlmRouter primary route, 复用主 chat 路由,
  取自项目 .env LLM_MODEL 实际值 — 非application-dev.yml的qwen-max默认)
  temperature=0.3 (注意: planner 无独立 route — javadoc 声称 temp=0 与实际不符, 按实际值冻结)
  max_tokens=1024, timeout=10s
调用链= ChatOrchestrator → PlannedAgentPipeline → Coordinator → HarnessAware(LIVE直通)
  → FallbackPlannerProvider → ModelPlannerProvider → LlmRouter(primary) → glm-4-plus
```

## 5. Fallback Handling（评测隔离，P0-2 修复后）

- REPLAY 夹具失败（FIXTURE_*）→ **严格失败**，不重试不降级（防评测对象漂移）。
- LIVE 下 Model 失败 → 重试 1 次 → Rule 兜底 → Pipeline 层 Classic 兜底（生产语义保留）。
- **每条 Agentic 样本记录**（runner `classify_planner_source`）：
  - `planner_source ∈ {MODEL, RULE_FALLBACK, CLASSIC_FALLBACK, RULE_ONLY_MISCONFIG, NO_RUN, FAILED}`
  - 来源依据：响应 `pipeline_type` + `/api/v1/agent/runs/{id}.planner_version`
    （`model-llm-v1[:retry]` / `rule-fallback-v1:REASON` / `rule-based-v1`）
  - `planner_version`（含降级原因）、`decomposition_steps`（plan-step-* 计数）、
    `replan_count`（replan-* 计数）
- **主结论只使用 planner_source=MODEL 的样本**（summary 的 `model_only` 视图）；
  fallback 样本单独计入 `planner_source_counts`，不静默混入。

## 6. Retriever / Reranker / Generator / Judge Config

| 组件 | 配置 | 冻结状态 |
|---|---|---|
| Embedding | BGE-M3（Milvus 2.5, hybrid dense+BM25 RRF） | 冻结 |
| Reranker | bge-reranker-v2-m3 @ 4090D GPU 服务 | **冻结，本轮禁止调参** |
| Generator | LlmRouter primary route = glm-4-plus (项目 .env 实际值), temp 0.3, top_k=5 | 冻结 |
| Judge1 | deepseek-chat, temp 0.1, 绝对分+pairwise 双轮换位盲评 | 冻结 |
| Judge2 | qwen-max (DashScope), 绝对分交叉 | 冻结（已知自偏好风险,靠双judge+盲评换位缓解） |
| 归一化 | strip Markdown + 统一截断 gold800/answer1200 | 沿用 |

## 7. Metrics

- Quality: correctness / evidence_completeness（双 judge）、pairwise win/tie/loss（盲评换位）
- Planning: decomposition_steps、replan_count、executed tool_calls、evidence recall 代理
  （evidence_completeness + citations 数）
- Cost: latency_ms、llm_calls（step 计数代理；**token 未逐样本持久化，为已知限制**）
- Reliability: planner_source 分布、MODEL 占比、fallback/failed 计数

## 8. Seed & Reproducibility

```
sampling seed = random.seed(42 + run_id); bootstrap seed=42, n_boot=5000, CI=95%
fingerprint: dataset_sha256 + corpus 指纹 + rerank 健康 + seed 全部写入 summary
```

## 9. Success / Failure Criteria

- **有效性门槛（先于结论）**：`MODEL 样本占比 ≥ 80%`。不达标 → `PILOT_VALIDITY=FAIL`，
  只报告降级原因排查，不给出 Agentic vs Classic 结论。
- 判读标准（有效时）：
  - LLM Planner 有价值 ⇔ C slice 上 `model_only` correctness delta（bootstrap 95% CI）
    不含 0 且为正，或 pairwise win rate 显著 > loss；
  - 负结果照实报告（不调参、不重跑刷数），并转入 Agentic routing/necessity analysis。
- 本 pilot N=50 为方向性证据，不得表述为统计显著的最终结论。

## 10. 运行前环境要求（Phase 3）

```
RAG_AGENT_PLANNER_ENABLED=true            (planner.enabled — 默认false,必须显式开)
RAG_AGENT_PLANNED_PIPELINE_ENABLED=true   (planned-pipeline-enabled — 默认false,必须显式开)
RAG_AGENT_MODEL_PLANNER_ENABLED=true      (默认已true)
GPU/4090D reranker UP + SSH 隧道; MySQL/Redis/Milvus/MinIO UP; 应用 :8080
```
