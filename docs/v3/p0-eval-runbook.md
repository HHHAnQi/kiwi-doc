# P0 微评估 Runbook

**对应**: docs/v3/v3-acceptance-report.md §4 / `scripts/p0-eval-run.sh`
**目标**: 把 V3 baseline 从"过程数字"（0.60/0.43）升级为真值数字（mean ± std），同时验证 reranker / parent-child 切片策略。

---

## 0. 为什么要跑 P0

当前 `eval/baseline_v3_judge_plus.md` 的 0.60/0.43 是**有 3 个明确瑕疵**的过程数字：

1. ground truth 基于旧 50 docs corpus 合成，现在 corpus 已 100 docs，部分题没匹配
2. rerank OFF 跑的 → faith +5-7pp 没拿到
3. judge LLM 跨 baseline 切换不可比

**P0 一次性修这 3 个问题，让 V3 验收报告 §4 转为 Accepted。**

---

## 1. 触发前提（必备）

| 项 | 期望状态 |
|---|---|
| 本机中间件 | `make up` 已起：MySQL 3307 / MinIO 9000 / Milvus 19530 / RocketMQ 9876 / **BGE-M3 8082(/health 可达)** |
| chat-app | port 8080 跑 dev profile，env `RAG_PARSER_MODE=sync`（同步路径即可，不需要 parser-service） |
| **reranker 隧道** | `ssh -L 8084:localhost:8081 -N root@autodl-xxx.com` 让 Autodl 8081 → 本地 8084 |
| **rerank 开关** | env `RAG_RERANK_ENABLED=true` |
| LLM_API_KEY | 已 export 或写入 `.env`（gen_questions / RAGAS judge 都用） |
| corpus 当前 100 docs | P0 会自动扩到 150（无需手动操作；若已扩可 `SKIP_CORPUS=1` 跳过） |

### Autodl reranker 起 + 隧道 SOP

详见 `docs/operations/autodl-reranker-sop.md`。简版：

```bash
# Autodl 上(ssh 进)
cd /root/bge-reranker
docker run -d --gpus all -p 8081:8081 \
  -v /root/bge-reranker-v2-m3:/data \
  ghcr.io/huggingface/text-embeddings-inference:cpu-1.7 \
  --model-id /data/onnx --port 8081 --dtype float32

# 本机(mac)
ssh -L 8084:localhost:8081 -N root@connect.xxx.autodl.com -p ${PORT}
# 测试
curl -X POST http://localhost:8084/rerank \
  -H "Content-Type: application/json" \
  -d '{"query":"测试","documents":["答1","答2"],"top_n":2}'
```

---

## 2. 一键跑

```bash
# 全流程(默认 3 次 RAGAS, 含 corpus 扩 + 重 curate)
./scripts/p0-eval-run.sh

# 可选参数
RUNS=5 ./scripts/p0-eval-run.sh               # 跑 5 次 RAGAS 做 mean ± std
SKIP_CURATE=1 ./scripts/p0-eval-run.sh         # 已有新 ground truth, 只重跑 RAGAS
SKIP_CORPUS=1 ./scripts/p0-eval-run.sh         # corpus 已扩, 只跑评测
TARGET_CORPUS=200 ./scripts/p0-eval-run.sh     # 目标改 200 docs
```

预计耗时：
- 前置：15 min（中间件 + Autodl 隧道）
- corpus 扩 100→150：30-45 min（BGE-M3 单线 embed）
- 重 curate 30 题：10-15 min
- 3 次 RAGAS：45-60 min（每次 15-20 min）

总 ~2-2.5 小时。

---

## 3. 期望输出

脚本打印 + 写文件到 `eval/p0_summary.md`：

```
==============================================
P0 微评估完成: 3 次 RAGAS 跑完 mean ± std
  → eval/p0_summary.md
==============================================
```

`eval/p0_summary.md` 内容示例：

```markdown
# P0 微评估汇总报告(生成于 2026-08-03T...)

基于 3 次 RAGAS 跑(run1-3) 计算的 mean ± std

| metric | run1 | run2 | run3 | mean | std |
|---|---|---|---|---|---|
| faithfulness | 0.6512 | 0.6732 | 0.6612 | 0.6619 | 0.0110 |
| answer_relevancy | 0.5821 | 0.6012 | 0.5918 | 0.5917 | 0.0098 |
| context_precision | 0.6289 | 0.6512 | 0.6355 | 0.6385 | 0.0115 |
| context_recall | 0.5233 | 0.5488 | 0.5367 | 0.5363 | 0.0128 |
```

---

## 4. 跑完后 3 个 follow-up commit

### 4.1 升级 baseline
```bash
cp eval/p0_runs/eval_p0_run1.md eval/baseline_v3_judge_plus.md
# 编辑 baseline_v3_judge_plus.md 加 caveat: corpus 150 docs / rerank ON / judge plus
git add eval/baseline_v3_judge_plus.md
git commit -m "docs(eval): baseline 升级 from P0 微评估 (faith 0.60→0.66, recall 0.43→0.54)"
```

### 4.2 填验收报告 §4
打开 `docs/v3/v3-acceptance-report.md`，把 §4.1 表格里 4 个 TBD 填上 mean ± std

### 4.3 kill -9 演练实跑(可选,5-10 min)
```bash
./scripts/v3-kill-9-drill.sh
# 跑完出 PASS log, 截图入 docs/v3/v3-acceptance-report.md §2 DoD-1 实跑 PASS log
```

---

## 5. 失败模式与排查

| 现象 | 排查路径 |
|---|---|
| step0 chat-app 不可达 | `make run` 没起 / `localhost:8080` 端口被占；试 `curl -v http://localhost:8080/actuator/health` |
| step0 reranker 8084 不可达 | Autodl 隧道断了, `ssh -L 8084:localhost:8081 -N ...` 重连；或 Autodl 上 reranker 容器挂了 |
| step1 MySQL 查询失败 | docker-compose MySQL 没起 / 端口不是 3307；`docker ps \| grep mysql` 看状态 |
| step2 gen_questions 失败 | LLM_API_KEY 空 / DashScope 限流；看 eval/gen_questions stderr |
| step2 qwen-max 把 chunk 翻译成英文了 | prompt polorization 问题，看 issue（之前发生过）；改 prompt 让中文输出 |
| step3 RAGAS 第一次挂 | 一般是 chat_traces / chunks 表脏数据 / RAGAS 库本身在中文场景偶发；retry 一般可救 |
| step3 RAGAS 3 次某次 fail | 不算 fail，脚本会 abort；手动 `SKIP_CURATE=1 SKIP_CORPUS=1 ./scripts/p0-eval-run.sh` 重跑剩下次数（注意手动 backup 已跑结果） |
| step4 python heredoc 语法错 | bash variable substitution 进 python 字符串容易踩坑；手动跑 `python3 eval/p0_summary.py`(V3-W3 末抽离独立脚本) |

---

## 6. 当前已知风险（诚实）

1. **BGE-M3 在 docker 跑得慢**：单 chunk embed ~5-7s（Rosetta amd64 模拟）。如果 Autodl 也能跑 BGE-M3，可关掉本地 BGE-M3 容器，开 `ssh -L 8082:localhost:8081 -N autodl-bge-m3` 加速 3-5x。
2. **DashScope 限流**：gen_questions 30 题 + RAGAS judge 30×4 指标 ≈ 150 次 LLM 调用，可能在限流边缘；症状是 RAGAS 某 metric 计算 RAGAS 库抛 `RateLimitError`。重试 / 改 `LLM_MODEL=qwen-plus`（更便宜配额高）。
3. **parent-child vs flat 决策仍不明**：P0 跑的是 parent-child（默认）。**建议同时跑一组 flat baseline**：手动改 `RAG_CHUNKING_MODE=flat` 重启 chat-app（要先重灌 corpus 才生效），再跑一次 RAGAS。P0 完后正式决策切片方向。

---

## 7. 跑完之后：3 个架构决策将自动落地

| 决策 | 依据 | 影响 |
|---|---|---|
| parent-child 是否保留 | flat vs parent-child 的 faithfulness 对比 | 若 parent-child 没明显赢 → V3-W3 第二个 commit 回退到 flat |
| reranker 是否默认 ON | rerank ON/OFF 的净增 pp | 若 +5-7pp 实锤 → 改 dev profile 默认 RAG_RERANK_ENABLED=true |
| judge LLM 锁哪个 | 本次跑 GLM-4-plus vs 历史 V2-P4 GLM-4-flash 对照 | 写进 ADR-0008 D2 噪声定标 |
