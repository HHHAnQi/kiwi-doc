#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 0.1: 异族 Judge 客户端工厂。

设计原则(不降级红线):
  **judge ≠ 业务 LLM**。业务 LLM 走 .env 的 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL,
  judge 必须走独立命名空间 JUDGE_LLM_PROVIDER_{1,2}_*, 永不复用业务 LLM 配置。
  ragas_pipeline.py 等评测脚本从这里取 judge client, 不再直接读 LLM_* 。

provider 1 = GLM-4-Flash(智谱)
provider 2 = DeepSeek-V3
provider 3+ 同公式扩展, 加 env 即可。

用法:
    from judge_client import build_judge_llm
    judge_llm, meta = build_judge_llm(provider_id=1)   # 单 judge
    judge_llms, metas = build_judge_ensemble()          # 多 judge(默认用所有已配置 provider)
"""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from functools import lru_cache


@dataclass
class JudgeProviderMeta:
    """单个 judge provider 元数据, 用于报告标注 + 调用统计。"""

    provider_id: int          # 1, 2, 3...
    base_url: str
    model: str
    family: str               # "glm" / "deepseek" / "qwen" / "claude" 等, 显式标记异族
    temperature: float        # 0.1 是 RAGAS 标准判分温度
    is_thinking: bool         # 思考模式(GLM-4.5/4.7), 需特殊处理

    def __repr__(self) -> str:
        return f"JudgeProviderMeta(id={self.provider_id}, family={self.family}, model={self.model})"


def _family_of(model: str) -> str:
    m = (model or "").lower()
    if "glm" in m:
        return "glm"
    if "deepseek" in m:
        return "deepseek"
    if "qwen" in m or "tongyi" in m:
        return "qwen"
    if "claude" in m:
        return "claude"
    if "gpt" in m or "o1" in m or "o3" in m:
        return "openai"
    return "unknown"


def _read_provider_env(provider_id: int) -> tuple[str, str, str] | None:
    """读 JUDGE_LLM_PROVIDER_{id}_BASE_URL/API_KEY/MODEL, 全部有值才算配置。

    任一缺失 → 视为该 provider 未启用, 返回 None(不抛, 让上层优雅报告)。
    """
    p = f"JUDGE_LLM_PROVIDER_{provider_id}"
    base_url = os.getenv(f"{p}_BASE_URL")
    api_key = os.getenv(f"{p}_API_KEY")
    model = os.getenv(f"{p}_MODEL")
    if base_url and api_key and model:
        return base_url, api_key, model
    return None


def _is_thinking_model(model: str) -> bool:
    """GLM-4.5/4.7 思考模式需传 extra_body, 影响 temperature 要求。"""
    m = (model or "").lower()
    return "glm-4.5" in m or "glm-4.7" in m or "glm-4.6" in m


@lru_cache(maxsize=4)
def get_provider_meta(provider_id: int) -> JudgeProviderMeta:
    """按 id 解析环境变量, 不构建 client(避免 LLM 依赖)。"""
    cfg = _read_provider_env(provider_id)
    if cfg is None:
        avail = []
        for i in range(1, 5):
            if _read_provider_env(i):
                avail.append(i)
        raise RuntimeError(
            f"JUDGE_LLM_PROVIDER_{provider_id}_* 未在 .env 完整配置 (BASE_URL+API_KEY+MODEL). "
            f"已配置的 provider: {avail or 'none'}. "
            "绝对禁止 fallback 到业务 LLM (LLM_BASE_URL) — 那是同源污染根因。"
        )
    base_url, api_key, model = cfg
    is_thinking = _is_thinking_model(model)
    # GLM 思考模式 temperature 必须为 1.0; 其余 provider 0.1
    temp = 1.0 if (is_thinking and "glm" in _family_of(model)) else 0.1
    return JudgeProviderMeta(
        provider_id=provider_id,
        base_url=base_url,
        model=model,
        family=_family_of(model),
        temperature=temp,
        is_thinking=is_thinking,
    )


def list_configured_providers() -> list[int]:
    """枚举当前 .env 里配了 JUDGE_LLM_PROVIDER_N_* 的所有 provider id(1-9 顺序)。"""
    ids = []
    for i in range(1, 10):
        if _read_provider_env(i):
            ids.append(i)
    return ids


def build_judge_llm(provider_id: int = 1):
    """构建单个 LangchainLLMWrapper(RAGAS 食用)。

    返回 (llm, meta)。llm 类型: ragas.llms.LangchainLLMWrapper。
    """
    meta = get_provider_meta(provider_id)

    # 延迟 import: 仅在此函数内部, 兼容未装 RAGAS 的环境(如仅跑 STOP 校验)
    from langchain_openai import ChatOpenAI
    from ragas.llms import LangchainLLMWrapper

    cfg = _read_provider_env(provider_id)
    assert cfg is not None
    base_url, api_key, model = cfg

    # GLM 思考模式特殊处理: extra_body + 长 timeout
    extra_body = {"thinking": {"type": "enabled"}} if meta.is_thinking else None

    raw_llm = ChatOpenAI(
        base_url=base_url,
        api_key=api_key,
        model=model,
        temperature=meta.temperature,
        extra_body=extra_body,  # None 自动忽略
        timeout=600,            # 思考模式 reasoning chain 长
        max_retries=3,
    )
    judge_llm = LangchainLLMWrapper(raw_llm)
    # Workaround: RAGAS 0.2.15 LangchainLLMWrapper.get_temperature(n=1) 返回 1e-8,
    # 智谱 glm-4-flash 拒 1e-8 → 400。patch 成约定 temp 值。
    judge_llm.get_temperature = lambda n: meta.temperature  # noqa: E731
    return judge_llm, meta


def build_judge_ensemble(provider_ids: list[int] | None = None):
    """构建多 judge 供 Phase 0.3 ensemble 用。

    provider_ids 默认 = 所有已配置 provider(至少 2 个), 否则抛错。
    返回 [(llm, meta), ...].
    """
    if provider_ids is None:
        provider_ids = list_configured_providers()
    if len(provider_ids) < 2:
        raise RuntimeError(
            f"ensemble 需要 >= 2 个 judge provider, 当前已配置: {provider_ids}. "
            "请在 .env 添加 JUDGE_LLM_PROVIDER_2_*"
        )
    return [build_judge_llm(pid) for pid in provider_ids]
