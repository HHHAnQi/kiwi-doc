# PR-7f.1: Agentic RAG 算法验证框架设计

> 状态: **设计 + 代码审计**, 不含任何实际实验结果
> Agentic RAG Runtime 已冻结 — 不修改 Planner / Executor / Tool / StateMachine / Budget / Pipeline
> 本文档仅建立 eval 框架; PR-7f.2 才跑实验
> 不伪造任何指标

---

## 0. 必须回答的四个核心问题

### Q1: 如何证明 Agentic RAG 比 Hybrid RAG 好?

**不是**: 在 Agentic 上跑 90 分, 在 Hybrid 上跑 80 分 — 这可能是 Tool 多了而不是 Planner 好.

**必须是**:

1. **同 Gold Evidence**: 两种 pipeline 在同一手标 Gold 上跑, 比较 Gold Evidence Recall + Faithfulness
2. **控制变量 More-Tool-Calls Baseline**: 给 Hybrid RAG 相同或更多 Tool Call 预算 (例: K=20 vs Planner K=10); 如果 Hybrid RAG with K=20 达到同样 Recall, 则 Planner 增益是虚假的
3. **Fairness Lock**: Agentic 的 maxToolCalls 必须 <= Hybrid 的 topK; 如果 Planner 调了 5 次 Tool 而 Hybrid 检索了 5 个 chunk, 两者 budget 等价
4. **Per-Slice 分解**: MULTI_HOP 子集上 Agentic 必须 > Hybrid; 单跳 FACT 子集上 Hybrid 可能不差甚至更好 (因为 Planner 引入额外开销)
5. **延迟 / 成本不暴涨**: P95 不超过基线 2.0x, LLM 平均 ≤ 3 calls/task

### Q2: 如何避免 Planner 只是增加 Tool Calls?

**设计**: `A6 More-Tool-Calls Control` 消融实验 (PR-7d §14). 实验回答:

```
Hybrid RAG with topK = N (= Agentic maxToolCalls * avg_evidence_per_call)
          vs
Agentic RAG with Planner, maxToolCalls = N / avg_evidence_per_call
```

如果 A6 (Hybrid with N) 的 Gold Evidence Recall 接近 A5 (Agentic full), 说明 Planner 只是通过更多检索窗口获益, 选择直接扩 topK 而非引入 Planner.

**另一层 guard**: `A7 Oracle Plan` — 用 Gold Plan 跑, 看 Planner 与 Oracle 差距.
如果 Planner ≈ Oracle, 说明 Planner 生效; 如果 Planner ≈ Hybrid, 说明 Planner 没收益.

### Q3: 如何评价 Replan?

**不能只看 Replan 触发率** — 高触发率可能说明 Initial Plan 差.

**必须是**:
- `Replan Trigger Precision`: 触发 Replan 的 case 最终是否获得 Gold 不在 Initial Phase 的新 Evidence
- `Replan Trigger Recall`: Initial Phase 不足但应该 Replan 能补救的 case 中, Replan 是否被触发
- `Replan Success Rate`: Replan 后 SUFFICIENT 的比例
- `A2 Planner only` (不 Replan) vs `A5 Full` (一次 Replan) 的 ablation:
  - Replan 增益 = A5 Recall - A2 Recall
  - Replan 成本 = A5 LLM calls - A2 LLM calls
  - 如果增益 < 成本增量, Replan 应关闭
- `No-progress Refusal Quality`: ReplanDecisionCoordinator 判定 No-progress 后拒绝的 case 中有多少确实没答案 (高 precision 拒答)

### Q4: 如何评价 Sufficiency Judge?

**三层对照**:
- `Rule Judge only` (model fallback off): 看规则判覆盖率
- `Rule + Model fallback` (model on): 模型是否补上语义判
- `Guard 后最终`: SufficiencyDecisionGuard 是否拦住了 False Sufficient

**最关键门禁**:
- `False Sufficient Rate`: real-insufficient 但判 SUFFICIENT and Guard 漏放 → **此 rate 必须 ≤ 2%**
- `False Insufficient Rate`: real-sufficient 但判 INSUFFICIENT → 安全但浪费成本, 应可接受但需公开
- `Guard Catch Rate`: Model Judge 判 SUFFICIENT 但 Guard 拒的比例 → 证明 Guard 不是空摆设
- `Dispatching Judge Latency Overhead`: Model fallback 额外延迟是否可接受

---

## 1. Benchmark Dataset Schema

### 1.1 现有基础

| 现有文件 | 内容 | 是否直接复用 |
|---|---|---|
| `eval/golden/golden_v2_grounded.jsonl` | ~100 条 Classic RAG Gold (question + ground_truth_answer + chunk_id + doc_id) | ✅ 用作 Classic/Router Baseline 的 Gold |
| `eval/_samples_80.jsonl` | ~80 条 Classic RAG 实测结果 | ✅ 用作 Baseline A0/A1 |
| `eval/planner/datasets/planner_benchmark_v1.seed.jsonl` (PR-7d) | 4 条示意 seed MULTI_HOP case | ⚠️ 占位; 需要补到 ≥80 并转成 v2 schema |
| `eval/router/router_cases.jsonl` | 100 条 Router 用例 | ✅ 用作 Router 判准确性参考 |

### 1.2 Agentic RAG Benchmark v2 Schema (在 PR-7d v1 基础上扩展)

```json
{
  "schemaVersion": "v2",
  "caseId": "mh-001",
  "question": "...",
  "questionType": "MULTI_HOP",
  "intent": "MULTI_HOP",
  "entities": ["..."],
  "filters": {},

  "requirements": [
    {
      "requirementId": "REQ-1",
      "type": "ENTITY_ATTRIBUTE",
      "required": true,
      "description": "...",
      "targetEntities": ["..."],
      "expectedFilters": {}
    }
  ],

  "gold": {
    "goldAnswer": "...",
    "goldEvidenceIds": ["sha256prefix12", "..."],
    "goldDocumentIds": [1, 2],
    "goldChunkIds": [10, 20],
    "goldCoverageByRequirement": {
      "REQ-1": {"chunkIds": [10], "documentVersion": "v1"},
      "REQ-2": {"chunkIds": [20], "documentVersion": "v2"}
    },
    "answerable": true
  },

  "planConstraints": {
    "acceptableInitialPlans": [{"toolSequence": [...], "coveredReqIds": [...]}],
    "acceptableReplanPlans": [...],
    "forbiddenToolSignatures": [],
    "expectedInitialPlanMaxSteps": 2,
    "expectedReplanIfTriggered": true
  },

  "expected": {
    "expectedFinalStatus": "ANSWERED",
    "replanExpected": false,
    "maxSteps": 3,
    "maxToolCalls": 3,
    "expectedFalseSufficientRisk": false,
    "expectedConflictRisk": false
  },

  "slice": "initial_sufficient",
  "review": {
    "reviewStatus": "candidate",
    "reviewer": "...",
    "reviewedAt": "..."
  },

  "notes": "..."
}
```

### 1.3 v2 相比 v1 的关键变化

- `gold` 子对象分离 Gold Answer / Evidence IDs / Chunk IDs / per-Requirement coverage 分解, 支持细粒度指标
- `planConstraints` 把 Plan 评估限制 (acceptable plans + forbidden sigs) 隔离, 不混入 expected
- `expected` 把终态期望 / 触发 Replan 期望 / 预算上限独立, 便于 evaluator 比对
- `slice` 用枚举 (initial_sufficient / document_fetch_needed / semantic_metadata_combo / replan_success / replan_still_insufficient / no_answer_refuse / permission_denied / evidence_conflict / budget_timeout_edge)
- `review` 强制人工审核流程; `reviewStatus=candidate` 不参与正式指标

### 1.4 Gold Evidence 标注格式

每条 Gold Evidence 必须含:

```json
{
  "evidenceId": "sha256prefix_12_char",
  "tenantId": "tA",
  "documentId": 1,
  "chunkId": 10,
  "documentVersion": "v1",
  "contentHash": "sha256_of_content",
  "contentSnippet": "前 200 字符 (脱敏)",
  "bindsToRequirementIds": ["REQ-1"],
  "rationale": "为什么这条 Evidence 满足 REQ-1 (≤200 字)",
  "reviewer": "...",
  "reviewedAt": "..."
}
```

**核心规则**:
- `evidenceId` 与运行时 Evidence.evidenceId 一致: sha256(tenantId|docId|chunkId|contentHash)[:12 prefix]
- 同一 chunk 可以被多条 Requirement 引用 (`bindsToRequirementIds`)
- `rationale` 是 reviewer 写的: "这条 chunk 的第 12-34 行包含了 REQ-1 的确切属性值"
- 禁止仅用 question→answer 的笼统 Gold; 必须拆到 Requirement 层

---

## 2. Baseline 设计

### 2.1 实验矩阵 (扩展 PR-7d 的 A0–A7)

| ID | 名 | Router | Planner | Sufficiency | Replan | Tool Budget | 公开 Flag | 描述 |
|---|---|---|---|---|---|---|---|---|
| **A0** | Classic RAG | off | off | off | off | topK=5 | — | 现有 `eval/_samples_80` 的 pipeline |
| **A1** | Router RAG | on | off | off | off | topK=5 | — | Router 选 Classic / Targeted / Comparison |
| **A2** | Planner only | on | on | off | off | maxCalls=3 | planner.enabled, sufficiency.enabled=false | 仅 Initial Plan, 直接回答 |
| **A3** | Planner + Rule Suff | on | on | rule | off | maxCalls=3 | + sufficiency.enabled=true | Rule 拒答不调 Model |
| **A4** | Planner + Rule/Model Suff | on | on | rule+model | off | maxCalls=3 | + sufficiency.modelFallback | Model Judge 兜底 |
| **A5** | Full PR-7 | on | on | rule+model | on (≤1) | maxCalls=3 | planner + pipeline + sufficiency + maxReplans=1 | 真实 PR-7 链路 |
| **A6** | More-Tool-Calls Control | off | off | off | off | **topK=10** | — | 给 Hybrid 同等 budget 反证 Planner 价值 |
| **A7** | Oracle Plan | on | **Gold** | rule+model | on | maxCalls=3 | PlannerProvider 直接返 Gold Plan | 分离 Planner Plan 误差与 Retrieval 误差 |
| **A8** | Hybrid RAG with Rerank | on | off | off | off | topK=5+rerank | — | 当前产线最强 Hybrid |
| **A9** | Agentic without Sufficiency | on | on | **off** | off | maxCalls=10 | planner.enabled, sufficiency.disabled | 反证 Sufficiency 价值 |

**A9 防 PR-7b 被绕过的设计**: 在 evaluation-only mode 中, SufficiencyDecisionGuard 被一个
`always-pass` 替代; **绝不在生产代码中加任何 bypass** — 仅在 evaluator 单独 profile 时通过系统属性切换.

### 2.2 Baseline 数据来源

- A0/A1/A8: 直接跑现有 `eval/eval_pipeline.py` + `_samples_80.jsonl`
- A2–A5: 跑 Planner paths (本 PR 不实现 runner; PR-7f.2)
- A6: 跑 `eval_pipeline.py` topK=10 (参数化)
- A7: 需要 Gold Plan 数据集 (手工标注 acceptable_initial_plans)

---

## 3. Evaluation Metric 定义

### 3.1 检索指标 (Retrieval)

| 指标 | 定义 | 计算 |
|---|---|---|
| Gold Evidence Recall@Plan | 最终 accumulated evidence ∩ Gold Evidence / Gold Evidence 数 | `len(result_evidence ∩ gold) / len(gold)` |
| Gold Document Recall | 最终 cited docs ∩ Gold Docs / Gold | `len(docs ∩ gold_docs) / len(gold_docs)` |
| Gold Chunk Recall | 最终 cited chunks ∩ Gold Chunks / Gold | chunk-level |
| Per-Requirement Coverage | 每个 Requirement 在最终 evidence 中是否至少 1 条命中 Gold | 微观 |
| Tool Selection Accuracy | Planner 选的 Tool vs acceptableInitialPlans 是否一致 (accept ⊇) | 模糊匹配 |
| Tool Argument Accuracy | Planner 给出的 input 参数 (filter version / source / query tokens) 与 Gold constraint 是否符合 | 部分匹配 |

### 3.2 生成指标 (Generation, NOT_EXECUTED 不报告)

| 指标 | 定义 | 计算方式 |
|---|---|---|
| Answer F1 (token) | 与 ground_truth_answer 的 token-level F1 | 现有 `eval_pipeline.metrics.answer_f1` |
| Answer EM | Exact match | 现有 |
| Faithfulness (RAGAS) | 答案是否完全 grounded | 跑 `eval/ragas_pipeline.py` |
| Unsupported Claim Rate | 答案中无 Evidence 支持的 claim 比例 | RAGAS |
| Citation Precision | 答案中 Citation 引用的 evidence 是否真的覆盖 | LLMjudge / RAGAS |
| Citation Recall | 应被 Citation 的关键 claim 是否全部引用 | LLM judge |

### 3.3 Planner 指标

| 指标 | 定义 |
|---|---|
| Plan Schema Valid Rate | Planner output 通过 schema 校验的比例 |
| PlanValidator Pass Rate | Planner output 通过 PlanValidator 的比例 |
| Repeated Tool Signature Rate | Planner 同 Plan 内出现重复 Tool 签名的比例 |
| Average Planner Calls per Task | 平均一次任务调用 Planner 次数 (Initial=1, Replan=2) |
| Initial Plan Acceptable Match | Planner 初始 Plan 是否落入 acceptableInitialPlans 集合的比例 |
| Replan Plan Acceptable Match | Replan 输出是否落入 acceptableReplanPlans 的比例 |

### 3.4 Sufficiency 指标

| 指标 | 定义 | 计算 |
|---|---|---|
| Sufficiency Accuracy | decision.status == gold sufficiency status 的比例 | / |
| Sufficiency Precision (SUFFICIENT 判对) | 真正 SUFFICIENT 中判对 | TP / (TP+FP) |
| Sufficiency Recall | 实际 SUFFICIENT 中判 SUFFICIENT 的比例 | TP / (TP+FN) |
| **False Sufficient Rate** | **实际 INSUFFICIENT 但被判 SUFFICIENT and Guard 漏放 → ANSWERED** | **上线门禁 ≤ 2%** |
| False Insufficient Rate | 实际 SUFFICIENT 但判 INSUFFICIENT → REFUSED_NO_EVIDENCE | safety-side, 可接受需公开 |
| Guard Catch Rate | Model Judge 判 SUFFICIENT 但 Guard 拒的比例 → 证明 Guard 不是空摆设 | / |
| Per-Requirement Coverage F1 | Coverage ⊆ Gold Coverage 的微观 F1 | / |
| Conflict Detection Accuracy | 金标 conflict / no-conflict 是否被 judge 正确识别 | / |

### 3.5 Replan 指标

| 指标 | 定义 |
|---|---|
| Replan Trigger Rate | 触发 Replan 的 case 比例 |
| Replan Trigger Precision | 触发 Replan 后获新 Gold Evidence 的比例 |
| Replan Trigger Recall | 应触发 Replan 的 case 中被触发的比例 |
| Replan Success Rate | Replan 后 SUFFICIENT 的比例 |
| No-progress Refusal Precision | ReplanDecisionCoordinator 拒绝 Replan (no-progress) 中确实无后续 Evidence 的比例 |
| Cost-Benefit = Recall 增量 / LLM 调用增量 | |
| A2 (Planner only) vs A5 (Full) delta Recall | Replan 边际收益 |

### 3.6 Trajectory / System 指标

| 指标 | 定义 |
|---|---|
| Trajectory Success Rate | 终态正确 + 无非终态 Step + 无 SSE 多终态 |
| Final Status Accuracy | finalStatus == expectedFinalStatus 的比例 |
| Non-terminal Step Residue Rate | 终态后剩余 PENDING/RESERVED/RUNNING step 的 Run 比例 (必须 0) |
| SSE Multiple Terminal Rate | completed + failed 同时出现 (必须 0) |
| Cross-tenant Evidence Leak Rate | 跨租户 Evidence 穿透率 (必须 0) |
| Illegal Tool Execution Rate | 不在 allowedTools 的 Tool 被执行 (必须 0) |
| P50 / P95 Latency | 单 task 延迟 |
| LLM Calls per Task | 平均 LLM 调用数 |
| Real Tool Calls per Task | 实际 Tool calls (REPLAY 不计) |
| Estimated Cost per Task | LLM tokens * 单价 + Milvus calls * 单价 |

### 3.7 Ablation 增量指标

| 指标 | 计算 |
|---|---|
| Planner Value (A1→A2 Gold Recall delta) | Planner 增益 |
| Replan Value (A2→A5 Gold Recall delta) | Replan 增益 |
| Rule Suff Value (A2→A3 unsupported claim delta) | Rule Sufficiency 减少虚假 |
| Model Suff Value (A3→A4 False Sufficient delta) | Model 比 Rule 多拦的 |
| More-Tool-Calls Control (A6 Reach A5 Recall) | if true → Planner 无价值 |
| Oracle to Planner Gap (A7-A5 Gold Recall) | Planner Plan 与 Gold Plan 的差距上限 |

---

## 4. Experiment Matrix

### 4.1 主矩阵

| 配置 | Dataset | Metric Groups | LLM 真实? | 何时跑 |
|---|---|---|---|---|
| A0 Classic RAG | reusing `golden_v2_grounded` 100 | Retrieval + Generation | Yes | Day1 (现有) |
| A1 Router RAG | same | Retrieval + Generation | Yes | Day1 |
| A2 Planner only | `planner_benchmark_v2.reviewed.jsonl` ≥80 | Retrieval + Planner | Yes | Day2 |
| A3 Planner + Rule Suff | same | Retrieval + Planner + Sufficiency | Yes | Day2 |
| A4 Planner + Rule/Model Suff | same | 同 A3 | Yes | Day3 |
| A5 Full PR-7 | same | Retrieval + Planner + Sufficiency + Replan + Generation | Yes | Day3 |
| A6 More-Tool-Calls | reusing 100 | Retrieval + Generation | Yes | Day4 |
| A7 Oracle Plan | same ≥80 (with gold plan) | Retrieval only | Yes | Day4 |
| A8 Hybrid+Rerank | reusing 100 | Retrieval + Generation | Yes | Day1 |
| A9 Agentic w/o Suff | same ≥80 | Retrieval + Planner + (force ANSWER) | Yes | Day4 (safety 仅 eval mode) |

### 4.2 Per-Slice 矩阵

每个 case 必须属于 9 个 slice 之一; 评测必须报告每 slice 的全指标 (不只全量平均), 让
"Planner 仅在 MULTI_HOP 切片上 > Hybrid" 能被识别.

### 4.3 评测最小样本要求

- 每 slice 至少 5 条 reviewed case
- 总 ≥80 条 reviewed case
- 任何 ablation 配置在同一 reviewed set 上跑 (禁止跨 dataset 比较)
- Review gold 必须双签: 第 1 人写 gold + 第 2 人审核 (reviewStatus=reviewed 时需 reviewer ≠ 标注人)

---

## 5. 需要新增的 eval 目录结构

### 5.1 已有 (保留)

```
eval/
├── README.md
├── golden/                       # Classic RAG gold (现有)
├── _samples_80*                  # Classic 实测 (现有)
├── router/                       # Router 用例 (现有)
├── planner/                      # PR-7d Planner benchmark (现有; seed only)
│   ├── schemas/planner_case.schema.json
│   ├── datasets/planner_benchmark_v1.seed.jsonl (4 seed)
│   ├── validate_dataset.py
│   ├── run_planner_eval.py
│   ├── aggregate_report.py
│   └── test_planner_eval.py (11 pytest)
```

### 5.2 新建 (本 PR-7f.1 设计为止, 不实现代码)

```
eval/
├── agentic/                          # PR-7f.1 新
│   ├── README.md                     # 本设计的简化版 + 使用指南
│   ├── schemas/
│   │   ├── agentic_case_v2.schema.json       # §1.2 v2 Benchmark case schema
│   │   └── gold_evidence.schema.json         # §1.4 Gold Evidence schema
│   ├── datasets/
│   │   ├── README_DATASETS.md                # 标注流程 + 双签规则
│   │   ├── agentic_v2.seed.jsonl             # 占位 seed (≥1 示意 case)
│   │   └── agentic_v2.reviewed.jsonl         # 空; 待 domain expert 填
│   ├── metrics/
│   │   ├── retrieval_metrics.py              # Gold Recall (Doc/Chunk/Evidence) + Coverage
│   │   ├── generation_metrics.py             # F1/EM 代理; RAGAS 交给 judge_pipeline
│   │   ├── planner_metrics.py                # Schema valid / PlanValidator pass / repeated sig
│   │   ├── sufficiency_metrics.py            # False/Insufficient / Guard catch / conflict
│   │   ├── replan_metrics.py                 # Trigger P/R / success / no-progress refusal P
│   │   ├── trajectory_metrics.py             # final status acc / residue / SSE multi
│   │   └── ablation_metrics.py               # A0..A9 delta 计算
│   ├── baselines/
│   │   ├── README.md                         # 各 baseline 配置 + 怎么跑
│   │   └── config/
│   │       ├── A0_classic_rag.yaml
│   │       ├── A1_router_rag.yaml
│   │       ├── A2_planner_only.yaml
│   │       ├── A3_planner_rule_suff.yaml
│   │       ├── A4_planner_model_suff.yaml
│   │       ├── A5_full_pr7.yaml
│   │       ├── A6_more_tool_calls.yaml
│   │       ├── A7_oracle_plan.yaml
│   │       ├── A8_hybrid_rerank.yaml
│   │       └── A9_agentic_no_suff.yaml       # eval-only (Guard bypass 在评测脚本中显式开关)
│   ├── runners/
│   │   ├── run_agentic_eval.py               # 统一入口: 读 dataset + actuals → 跑 metrics + report
│   │   ├── replay_runner.py                  # PR-7c.3c 任 replay 模式下跑 Pipeline 多次 (各 ablation)
│   │   └── live_runner.py                    # PR-7f.2 才用; 同 dataset 真实 LLM + Milvus 跑
│   ├── reports/
│   │   └── (空, 跑完填 JSON + Markdown)
│   └── test_agentic_metrics.py               # Python pytest 单测各 metric calculator
```

### 5.3 README.md (eval/agentic)

包含内容:
- A0–A9 baseline 描述
- 评测命令模板 (PR-7f.2 实现; 当前空):
  ```bash
  python3 eval/agentic/runners/run_agentic_eval.py \
    --dataset eval/agentic/datasets/agentic_v2.reviewed.jsonl \
    --actuals <ablation_run.jsonl> \
    --ablation A5_full_pr7 \
    --out-json eval/agentic/reports/A5_full_pr7.json
  ```
- 上线门禁 (False Sufficient ≤ 2%, Illegal Tool = 0, etc.)
- 明确标注: NOT_EXECUTED 指标不报告也不允许伪造

---

## 6. 代码审计: 现有代码是否可支撑本框架

### 6.1 已有可复用 (无需修改运行时代码)

| 组件 | 用途 | 文件 |
|---|---|---|
| PlannerPlanAssembler | Plan 校验 + Tool signature Loop check | `agent/planner/PlannerPlanAssembler.java` |
| RuleSufficiencyJudge | 规则判 | `sufficiency/RuleSufficiencyJudge.java` |
| ModelSufficiencyJudge | Model fallback | `sufficiency/ModelSufficiencyJudge.java` |
| DispatchingSufficiencyJudge | Rule→Model 切换 | `sufficiency/DispatchingSufficiencyJudge.java` |
| SufficiencyDecisionGuard | 第三层防护 | `planned/SufficiencyDecisionGuard.java` |
| ReplanDecisionCoordinator | Replan 准入控制 + ProgressDetector | `agent/ReplanDecisionCoordinator.java` |
| AgentRunPhaseExecutor | Phase 执行 (KEEP_EXECUTING) | `agent/AgentRunPhaseExecutor.java` |
| PlannedAgentExecutionCoordinator | 主编排 | `planned/PlannedAgentExecutionCoordinator.java` |
| HarnessAwarePlannerProvider | LIVE/RECORD/REPLAY Planner | `planner/HarnessAwarePlannerProvider.java` |
| ToolExecutor | Tool 执行 (含 dedup) | `tool/ToolExecutor.java` |
| Listener: run_planner_eval.py | 已能算 Trajectory / Sufficiency / Plan metrics | `eval/planner/run_planner_eval.py` |
| validate_dataset.py | 已能 schema + cross-field 校验 | `eval/planner/validate_dataset.py` |
| aggregate_report.py | 已能 ablation 聚合 + Markdown | `eval/planner/aggregate_report.py` |

### 6.2 缺失需要补充的内容 (仅描述, 本 PR 不实现)

| 项 | 描述 | 归属 |
|---|---|---|
| Gold EvidenceId resolved mapping | 运行时 sha256 vs Gold prefix12 对齐 | PR-7f.2 emprun 生成 |
| More-Tool-Calls Control runner | 跑 Classic RAG topK=10 baseline | PR-7f.2 |
| Oracle Plan runner | 直接读 acceptableInitialPlans 放 PR-7c.3c Pipeline 跑 | PR-7f.2 |
| A9 Guard-bypass 安全证明 | 安全隔离的 reviewer-mode switch, 仅在 evaluation context | PR-7f.2 (需严格审计) |
| RAGAS runner | 跑 `eval/ragas_pipeline.py` 对 Agentic 输出 | PR-7f.2 |
| Gold 双签 tool | 第一人标 → 第二人 review; 状态机 candidate→reviewed→rejected | PR-7f.2 |
| Multi-LLM-judge ensemble | 借用现有 `eval/judge_ensemble.py` 跑 Faithfulness / Citation | PR-7f.2 |

### 6.3 ssafety 约束 (PR-7f.2 实施时强守)

- 不修改运行时代码: AgentRuntime frozen 时不允许改任何 `agent/` / `tool/` / `sufficiency/` / `planner/` / `planned/` / `pipeline/` 的源码
- 仅在 `eval/agentic/` 内允许脚本 / schema / dataset / config
- Guard bypass (用于 A9) 必须通过 subprocess 启动独立 eval-only jvm, 不与 production runtime 共享 bean graph
- 不接入生产密钥 / 不跑 production corpus; 仅合成 fixture + 人工 Gold

---

## 7. 退出门禁 (PR-7f.1)

| # | 检查 | 状态 |
|---|---|---|
| 1 | Benchmark Dataset schema (v2) 已定义 | ✓ §1.2 |
| 2 | Gold Evidence 标注格式定义 | ✓ §1.4 |
| 3 | Baseline 设计 (A0–A9) | ✓ §2.1 |
| 4 | Metric 定义 (Retrieval / Generation / Planner / Sufficiency / Replan / Ablation) | ✓ §3 |
| 5 | Experiment matrix 明确每 ablation 跑什么 | ✓ §4 |
| 6 | eval/agentic/ 目录结构图 | ✓ §5 |
| 7 | Q1「比 Hybrid 好怎么证明」回答 + 控制 More-Tool-Calls baseline | ✓ §0.Q1 + §2 A6 |
| 8 | Q2「Planner 不只是增加 Tool Calls」回答 + Oracle Plan baseline | ✓ §0.Q2 + §2 A7 |
| 9 | Q3「如何评 Replan」回答 (Trigger P/R + Success Rate + 边际 Cost-Benefit) | ✓ §0.Q3 |
| 10 | Q4「如何评 Sufficiency」回答 (False Sufficient Rate + Guard Catch) | ✓ §0.Q4 |
| 11 | 不修改运行时代码 (Runtime 冻结) | ✓ §6.3 + 实施时守 |
| 12 | 不跑实验 (设计 + 审计 only) | ✓ 本 PR 不产实际数据 |

PR-7f.2 才跑实验; PR-7f.1 仅交付设计.

---

## 8. 完成判定

**PR-7f.1 已完成 (设计层)**

下次进入 PR-7f.2 时:
1. 创建 `eval/agentic/` 目录 + schemas + seed + README
2. 实现 metrics/*.py + 单测
3. 实现 runners (replay_runner 优先; live_runner 需真实 LLM 在 CI-ci-cloud 跑)
4. domain expert 补 ≥80 条 reviewed case
5. 在 reviewed set 上跑 A0–A9, 报告所有指标
6. 输出 `eval/agentic/reports/pr-7f.2_final_report.md`

PR-7 总体状态: **部分完成** (代码层 + Engineering validation 部分完结, 算法验证未完成)
PR-8 (Shadow / 灰度): **NO-GO** — 需 PR-7f.2 数据驱动决策
