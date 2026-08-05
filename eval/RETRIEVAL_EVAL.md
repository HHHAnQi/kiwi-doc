# Retrieval Evaluation Framework

可复现的离线 RAG 检索评测体系：直接调真实 `RetrieveService`，禁止 mock retriever。

## 目录结构

```
eval/
├── datasets/
│   └── retrieval_eval.jsonl          # 标准评测数据 (jsonl, 每行 1 题)
├── metrics/
│   ├── retrieval_metrics.py          # Recall@K / Precision@K / HitRate / MRR / NDCG (纯函数)
│   └── generation_metrics.py         # Answer Correctness / Faithfulness / Citation Accuracy
├── runner/
│   ├── retrieve_client.py            # → POST /api/v1/retrieve (不进 LLM, 含 score)
│   ├── chat_client.py                # → POST /api/v1/chat     (生成指标用, 进 LLM)
│   ├── judge_client.py               # 复用 .env 的 JUDGE_LLM_PROVIDER_* 做 LLM-as-judge
│   └── run_eval.py                   # 主入口, 产出 eval_report.json
└── tests/
    └── test_metrics.py               # 纯函数单测 (pytest)
```

Java 侧（零业务侵入，未改任何现有类）：
- `POST /api/v1/retrieve` — `platform-bootstrap/.../interfaces/rest/RetrieveController.java`
- DTO — `interfaces/rest/dto/RetrieveRequest.java` / `RetrieveResponse.java`

## 评测数据格式 (`datasets/retrieval_eval.jsonl`)

每行一个 JSON，字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `question` | string | 必填。用户问题 (≤500 字) |
| `gold_chunk_ids` | int[] | 必填。ground-truth chunk id 列表 (支持多 gold) |
| `gold_answer` | string | 可选。生成指标 (Answer Correctness) 需要；检索段不需要 |
| `category` | string | 可选。如 `dubbo`/`nacos`/`seata`/`rocketmq`/`sentinel`/`policy` |
| `difficulty` | string | 可选。`easy`/`medium`/`hard` |

例：
```json
{"question":"退款期限是多少","gold_chunk_ids":[123,456],"gold_answer":"7 天无理由退款","category":"policy","difficulty":"easy"}
{"question":"Dubbo3 如何支持云原生","gold_chunk_ids":[37],"category":"dubbo","difficulty":"medium"}
{"question":"在多注册中心订阅的场景下，Spring Cloud Alibaba 提供了哪些选址策略？","gold_chunk_ids":[2126],"category":"spring-cloud-alibaba","difficulty":"easy"}
```

### 如何新增测试数据

直接 append 一行到 `eval/datasets/retrieval_eval.jsonl`（或新建 jsonl 文件，跑时 `--dataset` 指定）。要拿到 gold `chunk_id`：

1. 启动应用，跑一遍 `POST /api/v1/retrieve` 用候选 question，看 `items[*].chunk_id`；
2. 人工或借助既有 `eval/golden/regen_ground_truth.py` 校验这题对应哪个真 chunk；
3. 把校验过的 `(question, gold_chunk_ids)` 写进 jsonl 一行。

> 种子数据集来自 `eval/golden/golden_v2_grounded.jsonl` 转写（仅取已知组件覆盖题），共 20 题。
> 金标 chunk_id 注意：本项目 Phase 2.0 已修复 corpus 100% chunk_id 错位的历史问题，
> 但仅基于已 verified 的 golden 集合。新增题请用上面 3 步重新校验。

## 指标

### Retrieval (`metrics/retrieval_metrics.py`, 纯函数)

对单题 `(retrieved_ids, gold_ids, k)`：

| 指标 | 公式 |
|---|---|
| `Recall@K` | `|gold ∩ retrieved[:k]| / |gold|` |
| `Precision@K` | `|gold ∩ retrieved[:k]| / k` |
| `HitRate@K` | top-k 内任意命中即 1，否则 0 |
| `MRR@K` | 第一个相关 chunk 的 `1/rank`（1-based） |
| `NDCG@K` | 二元相关性 DCG/iDCG，仅次序敏感 |

### Generation (`metrics/generation_metrics.py`)

| 指标 | 实现方式 |
|---|---|
| `Citation Accuracy` | 纯函数：`|cited ∩ gold| / |cited|` |
| `Answer Correctness` | LLM-as-judge：predict answer vs gold_answer 打分 0~1 |
| `Faithfulness` | LLM-as-judge：答案中的断言是否全部由 retrieve 拿到的 `llm_context` 支持 |

`answer_correctness/faithfulness` 接受 `judge_fn` 依赖注入；为 None 时退化为字符串/coverage 近似
(供无 LLM 的快速冒烟，不是严谨指标)。判官 LLM 配置见下。

### 模型版本记录

`/api/v1/retrieve` 顶层附 `model_version` / `embedding_version` / `rerank_model` / `rerank_enabled`，
直接照搬到 `eval_report.json`，保证「这次报告是在什么模型栈下跑的」可追溯。

## 如何运行评测

### 前置

1. **Backend**：`make run`（自动 source `.env` 启动；确保 `RAG_RERANK_ENABLED` / `RAG_PROMPT_V2`
   设成你要评测的状态。检索评测会复用当前 JVM 的配置 — 这正是「在真实栈下评测」的初衷）。
2. **Reranker 隧道**（仅当 `.env` 里 `RAG_RERANK_ENABLED=true` 时需要）：
   ```bash
   ssh -p 49581 -N -L 18080:localhost:6006 root@connect.nmb2.seetacloud.com &
   curl http://127.0.0.1:18080/health   # 应返 status ok
   ```
3. **Judge LLM**：`.env` 里 `JUDGE_LLM_PROVIDER_1_*` (主, 推荐 deepseek-chat) 和
   `JUDGE_LLM_PROVIDER_2_*` (备, qwen-max)。检索段不需要 judge；生成段需要。

### Python 环境

```bash
python3 -m venv eval/.venv
eval/.venv/bin/pip install requests pytest
```

### 跑评测

```bash
# 完整跑（检索 + 生成, 生成段会消耗 LLM token）
eval/.venv/bin/python eval/runner/run_eval.py \
    --dataset eval/datasets/retrieval_eval.jsonl \
    --k 5 \
    --output eval/eval_report.json

# 只跑检索段 (省 LLM；含 Recall/Precision/HitRate/MRR/NDCG)
eval/.venv/bin/python eval/runner/run_eval.py --skip-generation

# 冒烟：只跑前 3 题
eval/.venv/bin/python eval/runner/run_eval.py --limit 3

# CI 门禁：与 baseline 比对，任一检索指标退超 3pp → 退出非零
eval/.venv/bin/python eval/runner/run_eval.py \
    --baseline eval/baseline.json --gate 0.03
```

### 产物 `eval_report.json`

```json
{
  "dataset_size": 20,
  "metrics": {
    "retrieval":   {"recall@5":0.78,"precision@5":0.61,"hit_rate@5":0.85,"mrr@5":0.71,"ndcg@5":0.74},
    "generation":  {"answer_correctness":0.79,"faithfulness":0.86,"citation_accuracy":0.72}
  },
  "timestamp": "2026-08-05T03:45:12+00:00",
  "model_version": "glm-4-plus",
  "embedding_version": "BAAI/bge-m3",
  "rerank_model": "BAAI/bge-reranker-v2-m3",
  "rerank_enabled": true,
  "judge_model": "deepseek-chat"
}
```

### `baseline.json` 格式（可选，给 `--baseline` 用）

```json
{"metrics": {"retrieval": {"recall@5": 0.80, "precision@5": 0.60, "hit_rate@5": 0.85,
                           "mrr@5": 0.70, "ndcg@5": 0.72}}}
```

把当前满意的一跑 `cp eval/eval_report.json eval/baseline.json` 后手工裁成上面形状即可。

## 测试

```bash
# Python 纯函数单测 (不依赖网络/容器)
eval/.venv/bin/python -m pytest eval/tests/test_metrics.py -q

# Java controller 切片测试 (MockMvc, 不需 MySQL/Milvus)
./gradlew :platform-bootstrap:test --tests "*RetrieveControllerWebMvcTest"
```

## 与既有 eval/ 工具的关系

本框架**互补**，不替代：
- `eval/ragas_pipeline.py` / `eval/multi_turn/run_multi_turn_eval.py`：固定选题集 + RAGAS 多维。
  G1–G5 多轮对话门禁仍由 `run_multi_turn_eval.py` 负责。
- 本框架：标准 `(question, gold_chunk_ids)` 数据集 → 直接拿 `score` 算 IR 经典指标 + 可选生成指标，
  面向算法迭代（hybrid/rerank/prompt 改动）做快速 A/B 与回归门禁。

两套可共享同一 backend、同一 judge LLM 配置。
