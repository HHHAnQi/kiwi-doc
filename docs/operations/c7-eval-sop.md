# Phase 1 / C7 — 多轮对话 Eval SOP

> 关联: ADR-0011 §11 Evaluation Gates; eval/multi_turn/run_multi_turn_eval.py
> 适合: 服务器端 / 本机 docker-compose 全栈

## 0. 前置条件 (服务器本机执行)

```bash
# 0.1 拉最新 (C1-C8 都已 push)
cd /path/to/rag-doc-platform
git pull origin main

# 0.2 全栈启动 (docker-compose)
cd deploy
docker-compose up -d mysql redis minio milvus-etcd milvus
# 等 30s 让 Milvus healthy
sleep 30
docker ps --format "table {{.Names}}\t{{.Status}}" | grep ragdoc
# 必须: ragdoc-mysql/ragdoc-redis/ragdoc-milvus/ragdoc-etcd/ragdoc-minio 全 healthy
```

## 1. 配置 .env (本步骤 key 一定不能 commit)

在 `rag-doc-platform/.env` 末尾加:

```bash
# Phase 1 / C7 多轮 eval 启用
RAG_CONVERSATION_ENABLED=true
RAG_CONVERSATION_COMPRESS=true
RAG_TOPIC_SHIFT_DETECT=true

# fallback LLM (走 ConversationContextualizer rewrite + HistoryCompressor summary, 用 deepseek)
LLM_FALLBACK_BASE_URL=https://api.deepseek.com/v1
LLM_FALLBACK_API_KEY=sk-your_deepseek_key
LLM_FALLBACK_MODEL=deepseek-chat

# reranker (本机 tunnel 到 Autodl 或暂关)
# RAG_RERANK_ENABLED=true
# RAG_RERANK_BASE_URL=http://localhost:18080
RAG_RERANK_ENABLED=false  # C7 跑 eval 时可暂关, 防 tunnel 断影响 baseline
```

确认 env var 能被 Spring Boot 读到 (gradle spring-boot-devtools 自动 reload .env, 否则手动 export):

```bash
export $(grep -v '^#' .env | xargs)
```

## 2. 起 reranker SSH tunnel (可选, RAG_RERANK_ENABLED=true 时需要)

```bash
# Autodl 服务器: 49581 端口含 reranker 端口 6006
ssh -p 49581 -N -L 18080:localhost:6006 root@connect.nmb2.seetacloud.com &
# 验证
curl -s -X POST http://localhost:18080/rerank -H "Content-Type: application/json" \
  -d '{"query":"test","documents":["a"]}' | head -c 200
```

## 3. 起 Spring Boot app

```bash
cd /path/to/rag-doc-platform
./gradlew :platform-bootstrap:bootRun --args='--spring.profiles.active=dev'
# 等 60s
# 验证
curl -s http://localhost:8080/actuator/health
# 期望 {"status":"UP"}
```

启动 log 关键 checkpoint (看 stdout):
- `ConversationStore=Redis, ttl=24h, ...`  (RedisConversationStore 启用)
- `QueryContextualizer enabled, route=fallback, cb=rewrite-llm (state=CLOSED)`
- `chat.history_compressor_enabled=true`
- `chat.topic_shift_detector_enabled=true`

## 4. 跑 Eval Pipeline

```bash
cd /path/to/rag-doc-platform

# 4.1 install python deps
pip3 install requests

# 4.2 set judge LLM env (deepseek 作为第一判官, 复用 JUDGE_LLM_PROVIDER_1_*)
export OPENAI_API_KEY=$JUDGE_LLM_PROVIDER_1_API_KEY
export OPENAI_BASE_URL=$JUDGE_LLM_PROVIDER_1_BASE_URL
export OPENAI_MODEL=$JUDGE_LLM_PROVIDER_1_MODEL

# 4.3 launch (5 gate 全跑约 20-40 min, G4 sleep 60s × 5 session 占大头)
python3 eval/multi_turn/run_multi_turn_eval.py
```

预期输出 (stdout 节选):

```
[INFO] Phase 1 Multi-turn Eval starting...
[GATE G1] running...
[GATE G1] → PASS
[GATE G2] running...
[GATE G2] → PASS
[GATE G3] running...
[GATE G3] → PASS
[GATE G4] running...
[GATE G4] → PASS    # 占 5+ min (5 session × 60s compress wait)
[GATE G5] running...
[GATE G5] → PASS

[DONE] Report 写入: eval/multi_turn/report_20260804_xxxxxx.md
[DONE] JSON 写入:   eval/multi_turn/report_latest.json
[PASS] 所有 gate 通过
```

## 5. 判定结果

打开 `eval/multi_turn/report_<timestamp>.md`, 看 "概览" 表格:

| Gate | 期望 | 失败原因 + 排查 |
|---|---|---|
| G1 | PASS (10 题 smoke 不退化) | `degraded > 1` → GLM/DeepSeek 服务挂,  修 LLM 配置 |
| G2 | PASS (rate ≥ 0.85) | rate 0.5-0.85 → rewrite LLM prompt 需调; < 0.5 → ConversationStore 没存 |
| G3 | **必须** PASS (pollution_count = 0) | pollution > 0 → ChatService G3 抗污染硬 gate 没生效, 看 git log C4 commit |
| G4 | PASS (≥ 0.70) | 0 → 异步压缩没起; < 0.7 → summary prompt 需调 (调 HistoryCompressor SUMMARY_PROMPT_TEMPLATE) |
| G5 | PASS (rate ≥ 0.80) | < 0.8 → topic shift threshold 0.5 太低/太高, 改 rag.conversation.topic-shift-threshold |

## 6. 任意 gate FAIL 后的回退

```bash
# 6.1 找到具体 turn 失败 (见 report_<ts>.md 的 details)
# 6.2 调整参数 / prompt 后重启 app
# 6.3 重跑:
python3 eval/multi_turn/run_multi_turn_eval.py
```

或临时关闭多轮 (按 baseline 不变):

```bash
# .env 改 RAG_CONVERSATION_ENABLED=false, 重启 app
```

## 7. 通过后下一动作

- 把 `report_<timestamp>.md` 内容贴到对话
- 主开发者据此把 ADR-0011 status Proposed → Accepted (C9)
- commit + push
- 进 Phase 2 (Query Rewriting / Adaptive Retrieval)

## 附录 A: 5 道 gate 完整跑的预估时间和成本

| Gate | 调用数 | LLM token ~ | 时间 |
|---|---|---|---|
| G1 smoke | 10 chat + 0 judge | ~2K × 10 = 20K | 3 min |
| G2 | 60 chat (~3 turn × 20 sess) + 20 judge | 30K + 6K | 8 min |
| G3 | 25 chat + 10 judge | 12K + 3K | 5 min |
| G4 | 40 chat (8 turn × 5 sess) + 60s × 5 compress wait | 30K | **8 min (含 sleep)** |
| G5 | 50 chat (2 turn × 25 sess) + 25 judge | 25K + 8K | 10 min |
| **总计** | 185 LLM call + 65 judge | ~140K tokens (GLM + DeepSeek 各半) | **~35 min, ¥2-5** |

完整升级 (n=80 / 50 / 50 / 50):  ~80K + judge 50K = 130K, ~60 min, ¥5-10

## 附录 B: 单独跑某个 gate

编辑 `run_multi_turn_eval.py` 末尾 `GATES_FUNCS`, 注释掉不要的 gate, 只保留要跑的:

```python
GATES_FUNCS = [
    # ("G1", run_g1_smoke),
    ("G2", run_g2),  # 只跑 G2
]
```
