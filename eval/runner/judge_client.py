"""LLM-as-judge 客户端 — 用于 answer_correctness / faithfulness。

读取 .env 里已有的 judge 配置 (复用现有 G1-G5 / RAGAS pipeline 同套 judge 角色,
不做新 provider):
  JUDGE_LLM_PROVIDER_1_BASE_URL / API_KEY / MODEL  (主, 推荐 deepseek-chat, 异源)
  JUDGE_LLM_PROVIDER_2_BASE_URL / API_KEY / MODEL  (备, qwen-max)
任一 provider 失败自动 fallback 到下一个; 全失败返回空串 (指标函数会归 0)。

约定: judge_fn(prompt)->raw_str 注入到 generation_metrics 的指标函数。
"""
from __future__ import annotations

import os
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[2] / ".env", override=False)

_PROVIDERS: list[dict] | None = None


def _load_providers() -> list[dict]:
    global _PROVIDERS
    if _PROVIDERS is not None:
        return _PROVIDERS
    provs: list[dict] = []
    for i in (1, 2):
        base = os.getenv(f"JUDGE_LLM_PROVIDER_{i}_BASE_URL")
        key = os.getenv(f"JUDGE_LLM_PROVIDER_{i}_API_KEY")
        model = os.getenv(f"JUDGE_LLM_PROVIDER_{i}_MODEL")
        if base and key and model:
            provs.append({"base_url": base, "api_key": key, "model": model})
    # 兜底: 单个 OPENAI_* (run_multi_turn_eval 旧约定)
    if not provs:
        base = os.getenv("OPENAI_BASE_URL")
        key = os.getenv("OPENAI_API_KEY")
        model = os.getenv("OPENAI_MODEL", "deepseek-chat")
        if base and key:
            provs.append({"base_url": base, "api_key": key, "model": model})
    _PROVIDERS = provs
    return provs


def make_judge_fn(
    timeout: float = 60.0,
    provider_index: int | None = None,
    max_tokens: int = 256,
):
    """返回 callable(prompt) -> raw_str。

    provider_index 为 1-based；指定后只调用该 Judge，便于做真正的异源一致性复核。
    默认仍按配置顺序失败回退，保持生产评测行为不变。
    """
    provs = _load_providers()
    if provider_index is not None:
        if provider_index < 1 or provider_index > len(provs):
            raise ValueError(f"judge provider {provider_index} 不存在；已配置 {len(provs)} 个")
        provs = [provs[provider_index - 1]]

    def judge(prompt: str) -> str:
        last_exc: Exception | None = None
        for p in provs:
            try:
                r = requests.post(
                    f"{p['base_url'].rstrip('/')}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {p['api_key']}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": p["model"],
                        "messages": [{"role": "user", "content": prompt}],
                        "temperature": 0,
                        "max_tokens": max_tokens,
                    },
                    timeout=timeout,
                )
                r.raise_for_status()
                return r.json()["choices"][0]["message"]["content"].strip()
            except Exception as e:
                last_exc = e
                continue
        raise RuntimeError(f"all judge providers failed: {last_exc}")

    return judge


def primary_judge_model() -> str:
    """供 eval_report.json 记录用。"""
    provs = _load_providers()
    return provs[0]["model"] if provs else "none"


def judge_model(provider_index: int) -> str:
    """返回指定 1-based provider 的模型名。"""
    provs = _load_providers()
    if provider_index < 1 or provider_index > len(provs):
        return "none"
    return provs[provider_index - 1]["model"]
