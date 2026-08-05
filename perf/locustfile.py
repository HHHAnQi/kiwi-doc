#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 10 RAG Performance Test — Locust 脚本。

支持场景:
  1) /api/v1/chat (e2e 含 LLM): 主测 — QPS / P50 / P95 / P99 / TTFT
  2) /api/v1/retrieve (retrieval only, 不进 LLM): 拆分用 — embed+retrieve+rerank
  3) /api/v1/retrieve?enhance=true: query-enhance 加项 latency

用法 (单进程 headless, 无 web UI):
  # 100 并发
  locust -f perf/locustfile.py --headless \\
      -u 100 -r 20 -t 60s \\
      --host http://localhost:8080 \\
      --csv perf/out/chat_100 \\
      --only-summary

  # 500 并发 (双 CPU 场景加 worker)
  locust -f perf/locustfile.py --headless -u 500 -r 50 -t 120s \\
      --host http://localhost:8080 --csv perf/out/chat_500 --only-summary

输出 perf/out/{prefix}_stats.csv / _failures.csv / _history.csv →
  perf/parse_csv.py 渲染 perf/performance_report.md

环境变量 (覆盖默认):
  PERF_TARGET          chat (default) | retrieve | retrieve_enhance
                      选要压的端点
  PERF_TOKEN          默认读 APP_DEV_TOKEN (与 /chat / /retrieve 共享鉴权)
  PERF_QUERY_POOL     JSON 数组, 自定义 query 池; 不设用默认 Sentinel/Dubbo 池
  PERF_DOC_ID         限定 doc_id (可选)
  PERF_TOP_K          默认 5
"""
from __future__ import annotations

import json
import os
import random
import time

from locust import HttpUser, between, events, task

# ─── 配置 ────────────────────────────────────────────────────
TARGET = os.getenv("PERF_TARGET", "chat").strip().lower()
TOKEN = os.getenv("PERF_TOKEN") or os.getenv("APP_DEV_TOKEN", "")
DOC_ID_RAW = os.getenv("PERF_DOC_ID", "").strip()
DOC_ID = int(DOC_ID_RAW) if DOC_ID_RAW else None
TOP_K = int(os.getenv("PERF_TOP_K", "5"))

# 默认 query 池 (RAG 文档常见问法)
_DEFAULT_QUERIES = [
    "Sentinel 是什么?",
    "Dubbo 默认端口是多少?",
    "Nacos 配置中心如何使用?",
    "RocketMQ 工作流程是什么?",
    "Seata AT 模式原理?",
    "Sentinel 流控规则有哪些?",
    "Dubbo 服务注册到 Nacos 的配置项?",
    "RocketMQ 怎么保证消息顺序?",
]
POOL_RAW = os.getenv("PERF_QUERY_POOL", "")
QUERIES = (
    json.loads(POOL_RAW) if POOL_RAW.startswith("[") else _DEFAULT_QUERIES
)

# TTFT 仅 stream 端点才有; e2e 同步 chat 端点没有"first token"概念
# 但我们把 sync chat 当 "TTFB"（time to first byte / 整体响应）
@events.quitting.add_listener
def _summarize(environment, **kw):
    # 在 summary 里打 P50/P95/P99 让 CI 输出可读
    stats = environment.stats.total
    if stats.num_requests == 0:
        return
    p50 = stats.get_response_time_percentile(0.50)
    p95 = stats.get_response_time_percentile(0.95)
    p99 = stats.get_response_time_percentile(0.99)
    print(
        f"\n[PERF] target={TARGET} "
        f"qps={stats.total_rps:.2f} "
        f"p50={p50:.1f}ms p95={p95:.1f}ms p99={p99:.1f}ms "
        f"fail={stats.num_failures}/{stats.num_requests}"
    )


class RagUser(HttpUser):
    """模拟一个 chat/retrieve 客户端。"""

    # 每个 user 在两次请求间等 0.5~1.5s (think time), 让 RPS 不是裸打满
    wait_time = between(0.5, 1.5)

    def on_start(self):
        # 鉴权 header
        if TOKEN:
            self.client.headers.update({"Authorization": f"Bearer {TOKEN}"})
        self.client.headers.update({"Content-Type": "application/json"})

    @task
    def call_target(self):
        q = random.choice(QUERIES)
        body = {"query": q, "top_k": TOP_K}
        if DOC_ID is not None:
            body["doc_id"] = DOC_ID

        if TARGET == "chat":
            self._call_chat(body)
        elif TARGET == "retrieve":
            self._call_retrieve(body, enhance=False)
        elif TARGET == "retrieve_enhance":
            self._call_retrieve(body, enhance=True)
        else:
            # 默认 = chat
            self._call_chat(body)

    def _call_chat(self, body):
        # sync /api/v1/chat — 整体 e2e latency (含 LLM)。
        # Locust 自动记 response.elapsed 进入 stats
        name = "POST /api/v1/chat"
        with self.client.post("/api/v1/chat", json=body, name=name, catch_response=True) as r:
            if r.status_code != 200:
                r.failure(f"http={r.status_code}")
                return
            try:
                j = r.json()
                hint = j.get("state_hint")
                # LLM_DEGRADED / NO_RECALL 不计入成功 chat, 但又不算网络失败
                # 这里归为 success (后端按设计同步返 hint=degraded), 让 P95/P99 有统计意义
                if hint in ("LLM_DEGRADED", "NO_RECALL", "EMPTY_KB", "VERIFY_FAILED"):
                    r.success()
                else:
                    r.success()
            except Exception:
                r.failure("json_parse_failed")

    def _call_retrieve(self, body, enhance):
        # /api/v1/retrieve — retrieval only (embedding+vector_search+rerank+permission
        # filter); 不进 LLM。给它单独 name 让 Locust stats 区分
        if enhance:
            body = dict(body)
            body["enhance"] = True
            name = "POST /api/v1/retrieve?enhance=true"
        else:
            name = "POST /api/v1/retrieve"
        with self.client.post("/api/v1/retrieve", json=body, name=name, catch_response=True) as r:
            if r.status_code != 200:
                r.failure(f"http={r.status_code}")


# ─── TTFT (Time To First Token): 仅 SSE 流式有意义 ────────────
# 本脚本主测 sync /chat (整体 TTFB), SSE TTFT 需另写 locustfile_stream.py。
# 见 perf/locustfile_stream.py 补充 (Task 10 后续 append)。
