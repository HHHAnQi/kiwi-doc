# Phase 0 - 评测脱污(评测算子校准)

> **目标**: 让 faith/recall 数字成为可解释的尺子, 而不是同源 LLM 自产自评的假数字。
>
> **DoD**: noise baseline 4 列对照(梯度 empty < random < no_rerank < normal), 切换 judge 时 Δfaith ≤ 3pp。

---

## 为什么先做 Phase 0

后续 Phase 1-4 的每一项决策(算法升级、生产工程改动)都用 faith/recall 数字做判据。
同源污染(GT 与 judge 同 LLM)的尺子量身高 = 升级好或坏看不出。
**1.5 周撬动后续 14 周测量有效性的最高 ROI。**

---

## 文件清单

### 新增

| 文件 | 用途 |
|---|---|
| `eval/judge_client.py` | 异族 judge provider 工厂(绝对禁止 fallback 业务 LLM) |
| `eval/noise_injector.py` | 3 档 noise(empty/random_distractor/no_rerank)对照测尺子刻度 |
| `eval/judge_ensemble.py` | 多 judge ensemble + badcase 落盘(Phase 0 STOP 校验实证) |
| `eval/label_questions.py` | 题库启发式打 question_type 标签(Phase 2 分组评测用) |
| `eval/golden/golden.jsonl` | 100 题 canonical(来源原始 long_gt_100) |
| `eval/golden/golden.with_labels.jsonl` | + question_type |
| `eval/golden/schema.md` | 字段定义 |

### 修改

| 文件 | 改动 |
|---|---|
| `eval/ragas_pipeline.py` | judge 走 `JUDGE_LLM_PROVIDER_*`, 加 `--judge-provider`, `--questions` flag |
| `.env.example` | 加 `JUDGE_LLM_PROVIDER_1_*/2_*` 示例(DeepSeek / Qwen) |
| `.github/workflows/eval-regression.yml` | 加 noise baseline 跑步 + judge ensemble 跑步 + 完整 artifacts 上传 |

---

## 不降级红线(已实装, 不可省略)

1. **judge ≠ 业务 LLM**: judge 必须走 `JUDGE_LLM_PROVIDER_*`, `judge_client.py` 含 fail-fast 守卫, 不向业务 LLM env fallback。
2. **noise 三档**: `empty_context` + `random_distractor` + `no_rerank` 全在(后者需 chat-app env 切换)。
3. **badcase 落盘**: `judge_ensemble.py` 输出 `eval/badcases/badcases_<date>.jsonl`, Phase 2 算法升级反馈源。
4. **题库可重跑**: `eval/label_questions.py --in X --out Y` 独立工具, 不固化在脚本内。

---

## 如何跑(本地完整一次)

### 前置准备(.env)

```bash
# 1) 业务 LLM (chat-app 业务路径用, 已有)
# LLM_BASE_URL, LLM_API_KEY, LLM_MODEL 不变

# 2) Judge LLM (评测专用, 必须异族)
JUDGE_LLM_PROVIDER_1_BASE_URL=https://api.deepseek.com/v1
JUDGE_LLM_PROVIDER_1_API_KEY=sk-...           # 你的 DeepSeek key
JUDGE_LLM_PROVIDER_1_MODEL=deepseek-chat

# 3) (可选, Phase 0.3 ensemble 用) 第二 judge, 必须 ≠ provider 1 的家族
JUDGE_LLM_PROVIDER_2_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
JUDGE_LLM_PROVIDER_2_API_KEY=sk-...
JUDGE_LLM_PROVIDER_2_MODEL=qwen-max
```

### 步骤

```bash
cd rag-doc-platform

# A. 正常单 judge 跑(默认 DeepSeek provider 1)
python3 eval/ragas_pipeline.py --judge-provider 1

# B. STOP 校验 + ensemble (两 judge 跑同批, 出 badcase 队列)
python3 eval/judge_ensemble.py --providers 1,2

# C. noise 三档对照
python3 eval/noise_injector.py --judge-provider 1
# 单独某档:
python3 eval/noise_injector.py --judge-provider 1 --mode empty_context

# D. 重新打题库标签(题库修改后)
python3 eval/label_questions.py
```

### 输出落盘

```
eval/
  ├── eval_ragas_report.md         # 主评测报告(含 judge provider 注释)
  ├── noise_baseline_<date>.json   # 3 档 noise 原始数据
  ├── noise_baseline_<date>.md     # 3 档 noise 报告 + 梯度校验
  ├── judge_ensemble_<date>.json   # 两 judge per-sample 数据
  ├── judge_ensemble_<date>.md     # ensemble 报告
  └── badcases/
      └── badcases_<date>.jsonl    # 判官分歧>20pp 的样本(Phase 2 改进源)
```

---

## 已知限制(诚实标注)

1. **GT 同源污染未消除**: Phase 0 只修 judge 侧。GT 仍是 LLM-extractive, 彻底消除要等 human-collected query。
2. **no_rerank 模式**需 chat-app env 切换重启(RAG_RERANK_ENABLED=false), 不像其他模式纯脚本侧。CI 只跑 empty/random 两档, no_rerank 手动跑(文档 inline)。
3. **ensemble 成本** = 单 judge * N。CI 默认不开 ensemble(secret 未配跳过)。
4. **启发式标签**有 5% 误差(other 类), Phase 2 时人工抽样校正。
