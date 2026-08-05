#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 10: 解析 Locust CSV → 渲染 performance_report.md。

输入: perf/out/ 目录, 含如下 CSV (locust --csv 产物):
  chat_100_stats.csv, chat_500_stats.csv
  retrieve_100_stats.csv, retrieve_500_stats.csv
  stream_50_stats.csv (可选)

输出: stdout 一段完整 Markdown。

拆分公式 (任务要求 Embed/Retrieve/Rerank/LLM 拆分):
  Embed      ≈ /retrieve 不带 rerank 时延 (近似, 实际后端 embed+vector 分不开)
              — 后端默认 rag.rerank.enabled=false 时 /retrieve 就是 embed+vector_search
  Retrieve   = /retrieve 整体 P50 (含 embed+vector_search+permission 过滤)
  Rerank     = /retrieve?rerank=on - /retrieve?rerank=off (本框架默认 off; 启用后差值即 rerank latency)
  LLM        = /chat - /retrieve (e2e 减去 retrieval = generate + answer postprocess)
  TTFT       = SSE first-token (locustfile_stream.py 单测)

任务禁止虚构: 没跑过的字段标 "(unmeasured — run perf/run_perf.sh)"。
"""
from __future__ import annotations

import csv
import os
import sys
from datetime import datetime, timezone


def load_row(csv_path: str) -> dict | None:
    """Locust _stats.csv 第二行是 'Aggregated' — 取它。"""
    if not os.path.exists(csv_path):
        return None
    with open(csv_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        return None
    # locust stats.csv 多行 (每 endpoint 一行 + 末尾 Aggregated), 找 Name 含 Aggregated
    for r in rows:
        if r.get("Name") == "Aggregated":
            return r
    return rows[-1]  # 退化: 取最后一行


def fmt(v, key="ms") -> str:
    if v is None or v == "" or v == "0":
        return "_(unmeasured)_"
    try:
        f = float(v)
        if key == "ms":
            return f"{f:.1f}"
        if key == "%":
            return f"{f:.2f}%"
        if key == "rpm":
            return f"{f:.2f}"
    except Exception:
        pass
    return str(v)


def main(out_dir: str) -> int:
    chat100 = load_row(os.path.join(out_dir, "chat_100_stats.csv"))
    chat500 = load_row(os.path.join(out_dir, "chat_500_stats.csv"))
    ret100 = load_row(os.path.join(out_dir, "retrieve_100_stats.csv"))
    ret500 = load_row(os.path.join(out_dir, "retrieve_500_stats.csv"))
    stream50 = load_row(os.path.join(out_dir, "stream_50_stats.csv"))

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    print(f"# Task 10: RAG 性能测试报告")
    print()
    print(f"- **生成时间**: {now}")
    print(f"- **后端 host**: `{os.getenv('PERF_HOST', 'http://localhost:8080')}`")
    print(f"- **测试工具**: Locust (perf/locustfile.py + perf/locustfile_stream.py)")
    print(f"- **拆分来源**: Locust `--csv` (`{out_dir}/`)")
    print()
    print("> 任务要求: 禁止虚构结果。未跑场景或没出数据的 cell 一律标 _(unmeasured)_。")
    print("> 再生方法: `bash perf/run_perf.sh` (前置: 后端 `make run` 已起 + `pip install -r perf/requirements.txt`)。")
    print()

    # ─── 主表: QPS / P50 / P95 / P99 ────────────────────────
    print("## 1. QPS / 延迟分位 (主指标)")
    print()
    print("| 场景 | 并发 | QPS | P50 (ms) | P95 (ms) | P99 (ms) | 失败率 |")
    print("|---|---:|---:|---:|---:|---:|---:|")

    def row(scenario: str, u: str, data: dict | None):
        if data is None:
            print(f"| {scenario} | {u} | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ | _(unmeasured)_ |")
            return
        qps = float(data.get("Requests/s", 0) or 0)
        p50 = data.get("50%", "")
        p95 = data.get("95%", "")
        p99 = data.get("99%", "")
        fail = float(data.get("Failure Count", 0) or 0)
        total = int(float(data.get("Request Count", 1) or 1))
        fr = 100.0 * fail / max(1, total)
        print(f"| {scenario} | {u} | {qps:.2f} | {fmt(p50)} | {fmt(p95)} | {fmt(p99)} | {fr:.2f}% |")

    row("chat (e2e 含 LLM)", "100", chat100)
    row("chat (e2e 含 LLM)", "500", chat500)
    row("retrieve (无 LLM)", "100", ret100)
    row("retrieve (无 LLM)", "500", ret500)
    print()

    # ─── 拆分 latency ───────────────────────────────────────
    print("## 2. 各阶段拆分 latency (P50 ms)")
    print()
    print("> 拆分公式:")
    print("> - `Embed`    ≈ /retrieve 时延 (后端当前 rerank disabled, 这一行已含 embed+vector_search)")
    print("> - `Retrieve` = /retrieve P50 (含 permission filter + metadata filter)")
    print("> - `Rerank`   = /retrieve?rerank=on - /retrieve?rerank=off")
    print(">     * 当前后端 `rag.rerank.enabled=false` (跑 RERANK_ON 对比需开 flag 重测一次)")
    print("> - `LLM`      = /chat P50 - /retrieve P50 (含生成 + 答案后处理)")
    print()
    print("| 阶段 | 100 并发 (ms) | 500 并发 (ms) | 说明 |")
    print("|---|---:|---:|---|")

    def diff(a, b):
        if not a or not b:
            return None
        try:
            return float(a) - float(b)
        except Exception:
            return None

    ret100_p50 = ret100.get("50%") if ret100 else None
    ret500_p50 = ret500.get("50%") if ret500 else None
    chat100_p50 = chat100.get("50%") if chat100 else None
    chat500_p50 = chat500.get("50%") if chat500 else None

    print(f"| Embed (近似 /retrieve) | {fmt(ret100_p50)} | {fmt(ret500_p50)} | vector_search embed 内嵌, 不可独立拆 |")
    print(f"| Retrieve (全含) | {fmt(ret100_p50)} | {fmt(ret500_p50)} | 含 permission filter |")
    print(f"| Rerank (Δ) | _(unmeasured)_ | _(unmeasured)_ | 需跑 `RAG_RERANK_ENABLED=true` 重测, 见 §5 |")
    llm100 = diff(chat100_p50, ret100_p50)
    llm500 = diff(chat500_p50, ret500_p50)
    print(f"| LLM (chat-retrieve Δ) | {fmt(llm100) if llm100 is not None else '_(unmeasured)_'} | {fmt(llm500) if llm500 is not None else '_(unmeasured)_'} | 主 prompt+token usage |")
    print()

    # ─── SSE TTFT ───────────────────────────────────────────
    print("## 3. TTFT (SSE 流式首 token) — perf/locustfile_stream.py")
    print()
    if stream50 is None:
        print("> _(unmeasured — 跑 `PERF_SKIP_STREAM=0 bash perf/run_perf.sh`)_")
    else:
        # _stats.csv 内 stream 测时, "POST /api/v1/chat/sse  [ttft]" 是一行
        ttft = None
        full = None
        with open(os.path.join(out_dir, "stream_50_stats.csv"), encoding="utf-8") as f:
            for r in csv.DictReader(f):
                name = r.get("Name", "")
                if "ttft" in name:
                    ttft = r
                elif name.endswith("/chat/sse"):
                    full = r
        print(f"- **TTFT P50**: {fmt(ttft.get('50%') if ttft else None)} ms")
        print(f"- **TTFT P95**: {fmt(ttft.get('95%') if ttft else None)} ms")
        print(f"- **整体流完 P50**: {fmt(full.get('50%') if full else None)} ms")
        print(f"- **QPS (50 并发)**: {float(stream50.get('Requests/s', 0) or 0):.2f}")
    print()

    # ─── 失败模式 ───────────────────────────────────────────
    print("## 4. 失败模式 & 提示")
    print()
    print("- HTTP 5xx: 后端/LLM 异常, 看 application.log + Langfuse trace")
    print("- HTTP 4xx: 鉴权失败 (`PERF_TOKEN` 不对) 或 query 校验失败")
    print("- 超时 (Locust 默认不显式 timeout): LLM 卡死时 /chat 接近 60s, 实测时",
          "建议设 `--expect-workers 1` 单进程看是否 LLM API rate-limit")
    print("- `state_hint=LLM_DEGRADED` 占比高 = LLM provider 不稳, 视为软失败",
          "(HTTP 200 但 chat 失败)")
    print()

    # ─── 再生方法 ───────────────────────────────────────────
    print("## 5. 再生方法 (Rerank latency 拆分)")
    print()
    print("```bash")
    print("# 1. 装依赖")
    print("pip install -r perf/requirements.txt")
    print()
    print("# 2. 起后端 (在另一终端)")
    print("make run")
    print()
    print("# 3. 跑全场景")
    print("bash perf/run_perf.sh")
    print()
    print("# 4. (可选) 跑 Rerank latency 拆分:")
    print("#    开 rerank 后跑同样的 retrieve 对照")
    print("RAG_RERANK_ENABLED=true make run  # 重启 backend")
    print("PERF_TARGET=retrieve locust -f perf/locustfile.py --headless \\")
    print("    -u 100 -r 20 -t 60s --host http://localhost:8080 \\")
    print("    --csv perf/out/retrieve_rerank_on_100 --only-summary")
    print("# 然后 Δ(retrieve_rerank_on_100) - Δ(retrieve_100) = Rerank latency")
    print("```")
    print()

    print("## 6. CSV 文件清单 (locust 原生产物)")
    print()
    for f in sorted(os.listdir(out_dir)) if os.path.isdir(out_dir) else []:
        if f.endswith(".csv"):
            sz = os.path.getsize(os.path.join(out_dir, f))
            print(f"- `out/{f}` ({sz} bytes)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: parse_csv.py <csv_dir>", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
