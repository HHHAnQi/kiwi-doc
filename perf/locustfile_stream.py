#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 10 SSE 流式问答 TTFT (Time To First Token) 测试。

/api/v1/chat/sse 返回 text/event-stream — 第一个 delta event 的时间即 TTFT,
反映"用户感受到的延迟"。

用法:
  locust -f perf/locustfile_stream.py --headless \\
      -u 50 -r 10 -t 60s \\
      --host http://localhost:8080 \\
      --csv perf/out/stream_50 --only-summary

注:
  - SSE 是流式响应, Locust 默认 stats 收的是完整响应时长 (= 整体 chat 时延),
    不是 TTFT。本脚本用 events.request 重新手动 fire 一个 "ttft" metric 拿真实 TTFT。
  - requests 库的 stream=True 需要 Locust 自定义 client (HttpUser 默认 fetch 完整 body);
    这里直接用底层 requests, 仍记入 Locust stats 让 dashboard 看见。
"""
from __future__ import annotations

import os
import random
import time
from typing import Any

import requests
from locust import HttpUser, between, events, task

TOKEN = os.getenv("PERF_TOKEN") or os.getenv("APP_DEV_TOKEN", "")
TOP_K = int(os.getenv("PERF_TOP_K", "5"))
DOC_ID_RAW = os.getenv("PERF_DOC_ID", "").strip()
DOC_ID = int(DOC_ID_RAW) if DOC_ID_RAW else None

_DEFAULT_QUERIES = [
    "Sentinel 是什么?",
    "Dubbo 默认端口是多少?",
    "Nacos 配置中心如何使用?",
    "RocketMQ 工作流程是什么?",
    "Seata AT 模式原理?",
]
POOL_RAW = os.getenv("PERF_QUERY_POOL", "")
QUERIES = (
    __import__("json").loads(POOL_RAW) if POOL_RAW.startswith("[") else _DEFAULT_QUERIES
)


@events.test_start.add_listener
def _on_start(environment, **kw):
    print(f"[PERF-STREAM] starting SSE TTFT test, host={environment.host}")


class StreamUser(HttpUser):
    """模拟 SSE 客户端, 拿 TTFT + 整体 latency。"""

    wait_time = between(1.0, 2.5)

    def on_start(self):
        self.headers = {"Content-Type": "application/json"}
        if TOKEN:
            self.headers["Authorization"] = f"Bearer {TOKEN}"

    @task
    def call_sse(self):
        q = random.choice(QUERIES)
        body: dict[str, Any] = {"query": q, "top_k": TOP_K}
        if DOC_ID is not None:
            body["doc_id"] = DOC_ID

        url = f"{self.host.rstrip('/')}/api/v1/chat/sse"
        t_start = time.perf_counter()
        ttft_ms: float | None = None
        full_end_ms: float | None = None
        error_msg: str | None = None

        try:
            # stream=True 让 requests 不缓冲, line iter 实时吐
            with requests.post(
                url, json=body, headers=self.headers, stream=True, timeout=60
            ) as r:
                if r.status_code != 200:
                    error_msg = f"http={r.status_code}"
                else:
                    for raw in r.iter_lines():
                        if not raw:
                            continue
                        line = raw.decode("utf-8", errors="ignore")
                        # SSE event: data: {...}
                        if line.startswith("data:") and line.find("delta") != -1:
                            # 第一次 delta 即 TTFT
                            if ttft_ms is None:
                                ttft_ms = (time.perf_counter() - t_start) * 1000.0
                            # 让 parser 继续吃完所有 event, 直到 chunk
                        if line.startswith("event:") and "done" in line:
                            full_end_ms = (time.perf_counter() - t_start) * 1000.0
                            break
                    if full_end_ms is None:
                        full_end_ms = (time.perf_counter() - t_start) * 1000.0
        except Exception as e:
            error_msg = type(e).__name__ + ": " + str(e)[:80]
            if full_end_ms is None:
                full_end_ms = (time.perf_counter() - t_start) * 1000.0

        # 把两次样本都推给 Locust stats, 让 CSV 里都有
        if error_msg:
            events.request.fire(
                request_type="SSE",
                name="POST /api/v1/chat/sse",
                response_time=int(full_end_ms or 0),
                response_length=0,
                exception=Exception(error_msg),
                context={},
            )
            return

        events.request.fire(
            request_type="SSE",
            name="POST /api/v1/chat/sse",
            response_time=int(full_end_ms or 0),  # 全链 (整段流完)
            response_length=0,
            context={},
        )
        if ttft_ms is not None:
            events.request.fire(
                request_type="SSE",
                name="POST /api/v1/chat/sse  [ttft]",
                response_time=int(ttft_ms),  # 首 token 时延
                response_length=0,
                context={},
            )
