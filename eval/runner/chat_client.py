"""POST /api/v1/chat 的薄客户端 — 拿到 LLM 答案 + citations 用于生成指标。
相对 /retrieve, 这里会真正调 LLM (慢且耗 token), 故生成评测是可选的。
"""
from __future__ import annotations

import os

import requests

DEFAULT_URL = os.getenv("CHAT_URL", "http://localhost:8080/api/v1/chat")
DEFAULT_TIMEOUT = float(os.getenv("CHAT_TIMEOUT", "120"))


def chat(
    query: str,
    *,
    top_k: int = 5,
    doc_id: int | None = None,
    source: str | None = None,
    version: str | None = None,
    language: str | None = None,
    token: str | None = None,
    base_url: str = DEFAULT_URL,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict:
    """同步调 /api/v1/chat, 返回 {answer, citations, state_hint, trace_id}。

    citations: 如有, 各对象含 chunk_id/snippet/llm_context。
    """
    headers = {"Content-Type": "application/json"}
    auth = token or os.getenv("APP_DEV_TOKEN")
    if auth:
        headers["Authorization"] = f"Bearer {auth}"
    body: dict = {"query": query, "top_k": top_k}
    if doc_id is not None:
        body["doc_id"] = doc_id
    if source:
        body["source"] = source
    if version:
        body["version"] = version
    if language:
        body["language"] = language
    r = requests.post(base_url, headers=headers, json=body, timeout=timeout)
    r.raise_for_status()
    return r.json()


def parse(resp: dict) -> tuple[str, list[int], str]:
    """从 chat 响应里抽出 (answer, cited_chunk_ids, state_hint)。"""
    answer = (resp.get("answer") or "").strip()
    cited = [
        c.get("chunk_id")
        for c in (resp.get("citations") or [])
        if c.get("chunk_id") is not None
    ]
    return answer, cited, resp.get("state_hint", "UNKNOWN")
