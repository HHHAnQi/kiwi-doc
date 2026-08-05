# Badcase Management

线上问题 → 分类 → 保存 → 回归测试。**零业务侵入**：全部新文件落在 `badcase/`，不改任何 Java/既存业务类，复用 `eval/runner/*` 的 retrieve/chat/judge 客户端。

## 目录

```
badcase/
├── dataset/
│   └── badcases.jsonl        # 标准格式存档 (一行一条)
├── classifier/
│   └── error_type.py         # 6 类分类器 (纯函数, 规则 + 可选 LLM judge)
├── regression/
│   ├── runner.py             # BadcaseRegressionRunner ★
│   ├── verdict.py            # 复跑严格比对口径
│   └── clients.py            # 复用 eval.runner (单一 LLM/认证来源)
└── README.md                 # 本文件
```

测试在 `eval/tests/badcase/`，复用 eval 的 venv。

## Badcase 数据格式 (`dataset/badcases.jsonl`)

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 回归索引 (如 `bc_001`) |
| `question` | ✅ | 用户原问题 |
| `error_type` | ✅ | 入档时标的分类（见下） |
| `expected_answer` | ✅ | 答案回归基线（LLM judge 用） |
| `expected_state_hint` |    | 期望 `OK`/`NO_RECALL`/`LLM_DEGRADED`/`EMPTY_KB`；不填则跳过 state 检查 |
| `gold_chunk_ids` |         | ground-truth chunk id；非空时跑「检索命中 + 位次不退化」 |
| `retrieved_chunks` |       | **当时** 线上的 retrieved chunk_id；用来判定「是否位次退化」 |
| `answer` |                 | 当时线上产出的答案，分类用 |
| `severity` |               | `P0`/`P1`/`P2`（可选） |
| `source` |                 | `online`/`imported`/`manual`（可选） |
| `trace_id` |               | 关联 chat_traces（可选） |

`error_type` 枚举 (大写)：

```
NO_RECALL          检索为空
WRONG_RECALL       检索全不命中 gold (兜底默认)
GENERATION_ERROR   LLM_DEGRADED / EMPTY_KB
HALLUCINATION      答案含 chunk 之外的事实
CITATION_ERROR     引用 [n] 与 retrieved 不一致 / 缺失
SECURITY           安全红线 (自残/暴力/色情/违法)
```

种子数据集（`badcase/dataset/badcases.jsonl`）每类各一条示例，覆盖 6 类。

## 如何新增一条 badcase

最简单：用编辑器 append 一行到 `badcases.jsonl`：

```json
{"id":"bc_007","question":"怎么用 Seata 处理回滚？","error_type":"WRONG_RECALL","expected_answer":"AT 模式通过 undo_log 反向补偿 italiital...","expected_state_hint":"OK","gold_chunk_ids":[1234],"retrieved_chunks":[1234,5678]}
```

或者借助别处已存的 feedback：把 `trace_id`/`corrected_answer`/当时的 chat 响应拼成上面格式即可。
拿到当时 `retrieved_chunks` / `answer` 的方式：调 `GET /api/v1/feedbacks`（既有）+
人工对照 `chat_traces` 表（业务已落表，本模块不重读以保持零侵入）。

## 分类器 (`classifier/error_type.py`)

纯函数 `classify(case, chat_resp, retrieve_resp, judge_fn)` 按 **判别优先级短路**：

```
SECURITY → NO_RECALL → GENERATION_ERROR → HALLUCINATION → CITATION_ERROR → WRONG_RECALL
```

- SECURITY 最高优先：把自残题判到 SECURITY 而非 HALLUCINATION
- HALLUCINATION：有 `judge_fn` 时跑 LLM judge；否则退化为 token-coverage（阈 0.3）
- WRONG_RECALL 是兜底

安全红线词表在 `_SECURITY_PATTERNS` 内（中文词面 + 英文词边界正则），按审计要求扩充即可，纯函数，无网络。

## BadcaseRegressionRunner (`regression/runner.py`)

跑 `dataset/badcases.jsonl` 每条 case：

1. `error_type == SECURITY` → **直接 PASS**，不把敏感题转发给线上
2. 其它 → `POST /api/v1/retrieve` + `POST /api/v1/chat`，调 `verdict.verdict`
3. 写 `badcase/badcase_report.json`

### 复跑严格比对（已和产品确认）

任一不满足即 FAIL：

| 检查 | 失败原因 |
|---|---|
| `state_hint` 不漂移 | cur 不等于 expected（expected 未填则跳过） |
| 检索命中 gold | top-k 内 0 命中（gold 非空时） |
| 检索位次不退步 | 当时 gold 第一出现 rank 比现在好 |
| 答案等价 | LLM judge `answer_correctness < 0.5`（无 judge 退化为 overlap_f1 < 0.4） |
| 反向 SAFETY | 非 SECURITY case 又被打成 SECURITY → 误判红线，FAIL |

`--strict-state`（默认关）则不论 expected 是否声明都强校验。

### `badcase_report.json`

```json
{
  "total": 6, "passed": 5, "failed": 1, "skipped": 1,
  "by_error_type": {"NO_RECALL": {"pass":1,"fail":0}, "...": {...}},
  "regressions": [{"id":"bc_003","error_type":"GENERATION_ERROR","reasons":["state_hint 漂移: 当前=NO_RECALL ≠ 期望=LLM_DEGRADED"]}],
  "per_case": [{...}],
  "timestamp": "...",
  "model_version": "glm-4-plus",
  "embedding_version": "BAAI/bge-m3",
  "rerank_model": "BAAI/bge-reranker-v2-m3",
  "rerank_enabled": true,
  "judge_model": "deepseek-chat"
}
```

## 运行（Makefile）

```bash
make badcase-test        # 纯函数单测（无需 backend / 网络）
make badcase-classify    # 仅分类 smoke，不调线上（backend 不在跑时用）
make badcase-run         # 跑一次回归 + 出 report（需 backend）
make badcase-regress     # CI 模式：任一 regression 非 0 退出，可入 pre-merge
```

直接跑：
```bash
eval/.venv/bin/python badcase/regression/runner.py --ci
eval/.venv/bin/python badcase/regression/runner.py --skip-remote   # backend 不在跑时
eval/.venv/bin/python badcase/regression/runner.py --no-judge       # 不用 LLM judge
```

## 自动触发约定

仓库非 git-repo，**不**装 pre-push hook（按产品约定）。CI 集成时把
`make badcase-regress` 接到 pre-merge pipeline 即可；本地开发可用 `make badcase-run`
查看 report 而 `make badcase-regress` 用于 commit 前自检。

## 与既有 eval/ 工具关系

- `eval/RETRIEVAL_EVAL.md` 的 Retrieval Framework 是「正向评分体系」（统计指标）
- 本模块是「负向防回归」（典例/线上失败），两者 **互补**
- 共用 `eval/runner/{retrieve,chat,judge}_client.py` 与 `.env` 的 JUDGE_LLM_PROVIDER_* 配置，单一修改点

## 零侵入承诺

- 不改任何 Java 类 / RetrieveController / eval.metrics / Retrieval Eval Framework
- 所有产物仅 `badcase/badcase_report.json`
- backend 不在跑：`badcase-classify` 仍能跑（仅做分类与格式校验），不阻塞开发
