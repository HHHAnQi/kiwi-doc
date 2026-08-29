# PRE_MERGE_DIFF_AUDIT — 合并前差异审计

> 2026-08-29 · 分支 `codex/rag-metrics-multiturn-baseline`（41→42 commits ahead of `origin/main`）
> · 审计基线 commit `d762df9`（已含本审计的清理提交）

## 总量

```text
277 files / ~204K insertions(清理 .evidence 后, 原 1.17M 行的 83% 为已移除的生成物)
```

## A. Secret / Credential — **PASS（MERGE_BLOCKED=NO）**

- diff 新增行按 `sk-[A-Za-z0-9]{20}` / api_key 赋值 / Bearer / password 赋值模式全量扫描；
  命中项全部为**评测产物 JSON 中的 Dubbo 官方文档示例代码**（`map.put("password","yyy")` 语料内容），
  无任何真实 provider credential。
- 无 `.env`/`.pem`/`.key` 类文件进入 diff（`.env` 在 .gitignore，仓库只有 `.env.example`）。
- workflows 中 secrets 全部经 `${{ secrets.* }}` 引用，无硬编码。

## B. Local Environment Leakage — **PASS（已清理 1 项）**

- `.DS_Store`/`.idea`/debug log/临时文件：diff 中零命中。
- `/Users/huanqi` 绝对路径 58 处：**主体位于已移除的 `.evidence/` 生成物**；
  余下少量位于 `eval/multi_turn/*.json` 小体积冻结报告的**运行环境元数据字段**
  （记录评测运行环境，非 secret、不影响指标值）——如实保留并在本审计声明。

## C. Evaluation Artifacts — **PASS（已清理 1 项）**

- **已移除**：`.evidence/`（21MB+12MB 脚本生成索引，占分支体积 83%，零引用）——命中
  "巨型生成 dump 不应提交"；脚本保留可再生。
- **保留（合规）**：frozen config/spec（含 hash）、逐样本 anonymized 结果（paired A/B
  run JSON）、summary/审计报告、故障注入日志——均属"应提交"四类。

## D. Gold / Benchmark Integrity — **PASS**

```text
WHAT_CHANGED = 新增两个冻结数据集文件(agentic_expanded_200.jsonl 200题 /
               agentic_pilot50_llmplanner.jsonl 50题), 未修改任何既有 gold/baseline
WHY_CHANGED  = P0-2 评测对象勘误后需要 LLM Planner pilot cohort; 200题集为
               对照扩容, 均带 sha256 记录
BEFORE       = 既有 gold/baseline 零改动(eval/baseline_v3_judge_plus.md 的
               git log 过滤为空)
AFTER        = 新文件新增; 判定 rubric/judge 逻辑的修改均伴随正式报告与 commit 说明
               (D3 语义修复 62decb1 等, 非无解释修改)
SCIENTIFIC_RISK = 无 — 无回溯性修改既有金标或门槛以提高结果
```

## 结论

```text
NO_SECRET_LEAK=PASS   DIFF_AUDIT=PASS(清理后)   MERGE_BLOCKED=NO
```
