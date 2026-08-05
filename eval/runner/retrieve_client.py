"""POST /api/v1/retrieve 的薄客户端 — 让离线评测拿到含 score 的原始召回结果。

不调 LLM, 单次开销 ~几十毫秒, 适合 batch 风。失败/空召回返回带 state 的结构,
由调用方决定如何 score (默认按 0 算 hit/recall)。
"""
from __future__ import annotations

import os

import requests

DEFAULT_URL = os.getenv("RETRIEVE_URL", "http://localhost:8080/api/v1/retrieve")
DEFAULT_TIMEOUT = float(os.getenv("RETRIEVE_TIMEOUT", "60"))


def retrieve(
    query: str,
    *,
    top_k: int = 5,
    doc_id: int | None = None,
    source: str | None = None,
    version: str | None = None,
    language: str | None = None,
    token: str | "None" = None,
    base_url: str = DEFAULT_URL,
    timeout: float = DEFAULT_TIMEOUT,
) -> dict:
    """同步调 /api/v1/retrieve, 返回原始 dict (含 items / score / rerank_state / *_version)。

    token 默认读 APP_DEV_TOKEN; admin-only 接口才需 APP_ADMIN_TOKEN (retrieve 不需要)。
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


def extracted(resp: dict) -> tuple[list[int], list[dict]]:
    """从响应里抽出 (chunk_id 顺序列表, citation dict 列表)。"""
    items = resp.get("items", []) or []
    ids = [it.get("chunk_id") for it in items if it.get("chunk_id") is not None]
    return ids, items
