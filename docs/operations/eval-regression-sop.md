# ADR-0008 D3 RAGAS CI 门禁 SOP

**关联**: ADR-0008 / docs/v3/v3-acceptance-report.md §4 / .github/workflows/eval-regression.yml
**生效日**: 2026-08-02 (V3-W3 落地)

---

## 0. 目的

让"baseline 不退化"从口号变成代码守护. 任何改动切片算法 / 检索参数 / embedding 模型 / corpus 的 PR, 都必须自动跑一次 RAGAS 评测,
对比当前 baseline; 任一指标降幅超 `threshold` (默认 3pp) 自动阻断合并 + 开 issue.

ADR-0008 D3 的"CI 门禁"概念, 之前是纸面规则, 本 workflow 是它的可执行实现.

---

## 1. Workflow 触发条件

| 触发源 | 场景 | 用途 |
|---|---|---|
| `schedule` (cron '0 3 * * *') | 每天 UTC 03:00 (北京 11:00) | nightly 回归, 平时默默跑, OK 不打扰, regression 开 issue |
| `workflow_dispatch` 手动触发 | corpus 扩充后 / baseline 升级前 | 关键变更前后跑 baseline 一遍 |
| `pull_request` 带 `eval-impact` label | 改动切片/检索/embedding/corpus 的 PR | 真实阻断场景 |

**不放在每个 PR 自动跑**, 因为评测链路需 docker-compose 全栈 + BGE-M3 推理 ~5-10 分钟, PR 反馈太慢 + noise ±1.7pp 易误报.

---

## 2. 触发判定规则

`.github/workflows/eval-regression.yml` 的 `gate` job 实现:

```
should_run = true if:
  - event_name in (schedule, workflow_dispatch)
  - OR (event_name == pull_request AND 'eval-impact' in labels)
otherwise should_run = false (跳过整个 ragas-eval job)
```

**PR 加 label 方式**: GitHub UI 右侧 Labels 面板, 搜 `eval-impact` 勾上; 或 PR 描述含 [SKIP_EVAL] 跳过 workflow.

---

## 3. 必须打 `eval-impact` label 的改动清单

按 ADR-0008 D3 baseline 升级时机:

| 改动 | 必须打 eval-impact? | 理由 |
|---|---|---|
| corpus 扩 / 减 | ✅ | recall / precision 直接受影响 |
| 切片模式切(flat ↔ parent_child) | ✅ | 整套切片结果变 |
| rerank 开关切 | ✅ | faith / precision 受影响 |
| ChunkingService 参数(800/400/40 等) | ✅ | chunk 边界变 |
| EmbeddingClient 换模型 | ✅ | dense 向量重算 |
| MilvusVectorStore 检索逻辑改 | ✅ | 召回排序变 |
| ChatMessages prompt 改 | ✅ | LLM 行为变 |
| 仅 chat-app / parser-service 内部重构 | ❌ | 业务行为不变, 走单测导引即可 |
| 仅文档 / ADR / README 改动 | ❌ | 不影响 RAG 数字 |
| 仅测试代码改动 | ❌ | 同上 |

PR review 时 reviewer 负责判断 + 加 label, 不依赖 reviewer 的话用 CODEOWNERS 自动通知.

---

## 4. CI runner 资源预算

GitHub Actions ubuntu-latest 单 runner 限额:
- 7 GB RAM
- 14 GB 磁盘
- 2 CPU core

风险:
- Milvus 2.5 (~1.5GB) + MySQL 8.4 (~500MB) + MinIO + BGE-M3 container ~2GB → **可能撞 7GB 限**
- 镜像拉取 milvus + bge-m3 共 ~5GB → 接近磁盘阈值

兜底:
- timeout-minutes: 45 让长时任务有缓冲
- BGE-M3 启动 start_period 240s 默认, runner 等不下来 → 考虑切更小 model 或 prebuilt image
- 第一次跑挂通属正常, 截 log 调配置 (降 max-per-source / 改 milvus standalone)

---

## 5. Secrets 必须配置

仓库 Settings → Secrets and variables → Actions:

| Secret 名 | 用途 | 获取方式 |
|---|---|---|
| `LLM_API_KEY` | DashScope API key | https://dashscope.console.aliyun.com (主答案 + judge 共用) |
| `LLM_BASE_URL` | DashScope OpenAI 兼容 URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` |

未配置则 workflow 跑到 RAGAS 那步会因为 LLM 调用 401 失败, 出 issue 提示补配 secret.

---

## 6. Baseline 升级流程(踩到 D3 触发条件时)

```
1. 改完代码 / corpus / 配置
2. 推主分支(commit 含 trigger note: 'trigger: rerun baseline')
3. GitHub Actions UI 手动触发 workflow_dispatch, 跑一次
4. 跑完 download artifact: ragas-report-XXX
5. 看新的 eval_ragas_report.md, 数字涨了才升 baseline
6. 更新 eval/baseline_v3_judge_plus.md 4 个数字
7. commit: 'docs(eval): baseline 升级原因 + 真数字 (faith 0.60→0.65)'
8. 之后回归比对就用新 baseline
```

**禁止**: baseline 故意写低避门禁触发. 数字必须是真跑出来的(ragas_pipeline 输出的 md 直接 cp 进 baseline).

---

## 7. 失败排查

| 现象 | 排查路径 |
|---|---|
| `gate` job 输出 `should_run=false` 但你想跑 | PR 没加 `eval-impact` label, 或直接走 workflow_dispatch 手动触发 |
| `ragas-eval` job 在 docker compose up 挂 | runner OOM 或镜像拉不下; 看哪一步 Exit 非 0; 减 max-per-source 试 |
| RAGAS 跑挂(LLM 401) | LLM_API_KEY secret 没配 / 失效 |
| compare_baseline.py 返回 exit 2 | 这就是 regression 命中, 看 regression_check.md 哪些指标降幅过大 |
| compare_baseline 报 baseline=N/A | baseline_v3_judge_plus.md 格式坏了; 重看 ADR-0008 D2 格式约定 |
| regression_check.md 写出来但 GHA 没把它当 fail | compare step 的 `continue-on-error: true` + 下一步 `if: steps.compare.outputs.exit_code == '2'` 显式判定; 检查 step id 写对 |

---

## 8. 后续(V3.5+)

- **V3.5**: noise 定标跑 ≥3 次取 mean ± std, threshold 校准到 2pp (当前 ±1.7pp, threshold 3pp 是保守 buffer)
- **V4 真流量来**: PR 跑评测改回 manual dispatch, nightly 拉真用户 Q 跑(用户真实问题库, 不用 curated)
- **V4 + Langfuse**: nightly 自动从 Langfuse trace 取样跑 RAGAS, 不再用 curated question

---

## 9. 当前已知限制(诚实标注)

1. **CI runner 跑完整 RAG 链路首次大概率失败** — Milvus 镜像 + BGE-M3 loading 在 GHA Slow/资源不够. 第一次跑挂没关系, 改配置再跑.
2. **noisy ±1.7pp 阈值未真校准** — 当前 V3 P2 baseline 数字是过程数字(rerank OFF, ground truth 基于旧 corpus), 真值未定下来, threshold 3pp 是 placeholder. P0 微评估 + corpus 扩充 150 后跑 mean ± std 校真值.
3. **workflow_dispatch 的 baseline_file 变量** — 默认指向 `eval/baseline_v3_judge_plus.md`, baseline 升级后改默认即可, 老数据进 git history 回溯.
