# Task 10: RAG 性能测试报告

- **生成时间**: 2026-08-05T07:48:46+00:00
- **后端 host**: `http://localhost:8080`
- **测试工具**: Locust (perf/locustfile.py + perf/locustfile_stream.py)
- **拆分来源**: Locust `--csv` (`perf/out/`)

> 任务要求: 禁止虚构结果。未跑场景或没出数据的 cell 一律标 _(unmeasured)_。
> 再生方法: `bash perf/run_perf.sh` (前置: 后端 `make run` 已起 + `pip install -r perf/requirements.txt`)。

## 1. QPS / 延迟分位 (主指标)

| 场景 | 并发 | QPS | P50 (ms) | P95 (ms) | P99 (ms) | 失败率 |
|---|---:|---:|---:|---:|---:|---:|
| chat (e2e 含 LLM) | 100 | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ |
| chat (e2e 含 LLM) | 500 | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ |
| retrieve (无 LLM) | 100 | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ |
| retrieve (无 LLM) | 500 | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ |

## 2. 各阶段拆分 latency (P50 ms)

> 拆分公式:
> - `Embed`    ≈ /retrieve 时延 (后端当前 rerank disabled, 这一行已含 embed+vector_search)
> - `Retrieve` = /retrieve P50 (含 permission filter + metadata filter)
> - `Rerank`   = /retrieve?rerank=on - /retrieve?rerank=off
>     * 当前后端 `rag.rerank.enabled=false` (跑 RERANK_ON 对比需开 flag 重测一次)
> - `LLM`      = /chat P50 - /retrieve P50 (含生成 + 答案后处理)

| 阶段 | 100 并发 (ms) | 500 并发 (ms) | 说明 |
|---|---:|---:|---|
| Embed (近似 /retrieve) | _(unmeasured)_ | _(unmeasured)_ | vector_search embed 内嵌, 不可独立拆 |
| Retrieve (全含) | _(unmeasured)_ | _(unmeasured)_ | 含 permission filter |
| Rerank (Δ) | _(unmeasured)_ | _(unmeasured)_ | 需跑 `RAG_RERANK_ENABLED=true` 重测, 见 §5 |
| LLM (chat-retrieve Δ) | _(unmeasured)_ | _(unmeasured)_ | 主 prompt+token usage |

## 3. TTFT (SSE 流式首 token) — perf/locustfile_stream.py

> _(unmeasured — 跑 `PERF_SKIP_STREAM=0 bash perf/run_perf.sh`)_

## 4. 失败模式 & 提示

- HTTP 5xx: 后端/LLM 异常, 看 application.log + Langfuse trace
- HTTP 4xx: 鉴权失败 (`PERF_TOKEN` 不对) 或 query 校验失败
- 超时 (Locust 默认不显式 timeout): LLM 卡死时 /chat 接近 60s, 实测时 建议设 `--expect-workers 1` 单进程看是否 LLM API rate-limit
- `state_hint=LLM_DEGRADED` 占比高 = LLM provider 不稳, 视为软失败 (HTTP 200 但 chat 失败)

## 5. 再生方法 (Rerank latency 拆分)

```bash
# 1. 装依赖
pip install -r perf/requirements.txt

# 2. 起后端 (在另一终端)
make run

# 3. 跑全场景
bash perf/run_perf.sh

# 4. (可选) 跑 Rerank latency 拆分:
#    开 rerank 后跑同样的 retrieve 对照
RAG_RERANK_ENABLED=true make run  # 重启 backend
PERF_TARGET=retrieve locust -f perf/locustfile.py --headless \
    -u 100 -r 20 -t 60s --host http://localhost:8080 \
    --csv perf/out/retrieve_rerank_on_100 --only-summary
# 然后 Δ(retrieve_rerank_on_100) - Δ(retrieve_100) = Rerank latency
```

## 6. CSV 文件清单 (locust 原生产物)

