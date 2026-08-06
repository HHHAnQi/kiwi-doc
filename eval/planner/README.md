# PR-7d Planner / Multi-hop Benchmark

> Status: 数据集 SEED (4 示意 cases), 金标需人工审核才能评测.

## 目录

```
eval/planner/
  schemas/planner_case.schema.json      # 单 case JSON Schema v1
  datasets/
    planner_benchmark_v1.seed.jsonl     # 4 条示意 seed (reviewStatus=candidate, 未审核)
    planner_benchmark_v1.reviewed.jsonl # 占位; 由 domain expert 补齐 ≥80 条 + review
  validate_dataset.py                   # schema + cross-field 校验
  run_planner_eval.py                   # 主指标 calculator (Trajectory / Sufficiency / Plan)
  aggregate_report.py                   # 多运行结果聚合 + 消融对照
  README.md                             # 当前文件
  reports/                              # 木板 JSON / CSV 输出 (运行后产物)
```

## 使用 (本地无真实模型/无 Docker)

```bash
# 1. 校验 seed schema
python3 eval/planner/validate_dataset.py eval/planner/datasets/planner_benchmark_v1.seed.jsonl

# 2. (人工审核后) 严格 review-only 校验
python3 eval/planner/validate_dataset.py \
  eval/planner/datasets/planner_benchmark_v1.reviewed.jsonl --require-reviewed

# 3. 跑 evaluator (无 actuals 时只输出指标占位 + 阻断 explicit NOT_EXECUTED)
python3 eval/planner/run_planner_eval.py \
  eval/planner/datasets/planner_benchmark_v1.reviewed.jsonl \
  --actuals eval/planner/reports/last_actuals.jsonl \
  --out-json eval/planner/reports/A5_full.json \
  --out-csv  eval/planner/reports/A5_full.csv
```

## 数据切片目标 (PR-7d §8.2, ≥80 推荐 100-120)

| slice | min |
|---|---:|
| initial_sufficient | 20 |
| document_fetch_needed | 10 |
| semantic_metadata_combo | 10 |
| replan_success | 15 |
| replan_still_insufficient | 10 |
| no_answer_refuse | 10 |
| permission_denied | 5 |
| evidence_conflict | 5 |
| budget_timeout_edge | 5 |

## 上线门禁 (PR-7d §17 建议)

硬门禁:

```
PlanValidator Pass Rate             >= 99%
Illegal Tool Execution Rate         = 0
Cross-tenant Evidence Leakage       = 0
Repeated Tool Loop Escape Rate      = 0
Non-terminal Step Residue Rate      = 0
SSE Multiple Terminal Rate          = 0
False Sufficient Rate               <= 2%
Citation Precision                  >= 95%
```

效果门禁 (vs Router RAG):

```
Gold Evidence Recall                +5pp 以上
Unsupported Claim Rate              不上升
Refusal Precision                   不下降超过 2pp
```

成本门禁:

```
P95 Latency                         < 基线 2.0x
LLM Calls per Task                  平均 ≤ 3
Real Tool Calls per Task            平均 ≤ budget
Replan Success Rate                 >= 30%
```

## 不允许

- **不要**把 `reviewStatus=candidate` 的 case 当已审核数据混入指标
- **不要**生成 RAGAS / Answer Correctness / Faithfulness / Citation Precision 数值; 这些必须真实
  LLM 跑出 (PR-7d §13 末段)
- **不要**绕过 dataset validator 把非法 case 计入评测
