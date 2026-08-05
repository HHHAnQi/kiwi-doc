"""badcase/regression/clients.py — 复用 eval/runner 的客户端。

不重复实现, 避免 LLM 配置/token 双份; 仅 re-export + 一层语义命名。
其它 badcase.* 模块都通过本文件拿客户端, 单一修改点。
"""
from __future__ import annotations

from eval.runner import chat_client, judge_client, retrieve_client

# 语义别名: badcase 回归 = "调一次 chat + 调一次 retrieve" + "judge 决议"
call_chat = chat_client.chat
call_retrieve = retrieve_client.retrieve
extracted_retrieved = retrieve_client.extracted
make_judge = judge_client.make_judge_fn
primary_judge_model = judge_client.primary_judge_model

__all__ = [
    "call_chat",
    "call_retrieve",
    "extracted_retrieved",
    "make_judge",
    "primary_judge_model",
]
