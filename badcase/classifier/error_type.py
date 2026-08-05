"""Badcase 错误类型分类器 — 纯函数, 不发起网络/LLM 调用本身 (judge_fn 由调用方注入)。

6 类 (用户固定 spec, 大写枚举):
  NO_RECALL / WRONG_RECALL / GENERATION_ERROR / HALLUCINATION /
  CITATION_ERROR / SECURITY

classify(): 给一条已落档的 badcase + 可选当时的 chat/retrieve 响应,
按判别优先级 (SECURITY → NO_RECALL → GENERATION_ERROR → HALLUCINATION →
CITATION_ERROR → WRONG_RECALL) 输出 (error_type, reason)。

短路顺序是设计选择 — SECURITY 必须最先命中避免把自残问题误判成 HALLUCINATION;
GENERATION_ERROR 在 HALLUCINATION 前是因为 LLM 一旦 degrade 就谈不上幻觉。
"""
from __future__ import annotations

import re
from typing import Callable

# ─── 安全红线词表 ──────────────────────────────────────────
# 用关键字正则; 中文按词面 / 英文按词边界。本表偏保守, 上线按审计要求扩充。
_SECURITY_PATTERNS = [
    r"自杀|自残|自伤|想死|轻生|割(腕|自己)",
    r"伤害自己|伤害别人|杀(了|死)?(自己|人|他人)",
    r"\b(suicide|self[- ]?harm|kill myself|end my life)\b",
    r"炸弹|制毒|毒品制作|枪支改装|开.progressBar.*FBI",  # 暴力/违法占位, 实际按业务红线补
    r"\b(porn|cp|csam|child sexual)\b",
]
_SECURITY_RE = re.compile("|".join(_SECURITY_PATTERNS), re.IGNORECASE)


def hit_security(text: str) -> bool:
    if not text:
        return False
    return bool(_SECURITY_RE.search(text))


# ─── 枚举 ──────────────────────────────────────────────────
NO_RECALL = "NO_RECALL"
WRONG_RECALL = "WRONG_RECALL"
GENERATION_ERROR = "GENERATION_ERROR"
HALLUCINATION = "HALLUCINATION"
CITATION_ERROR = "CITATION_ERROR"
SECURITY = "SECURITY"

ALL = (NO_RECALL, WRONG_RECALL, GENERATION_ERROR, HALLUCINATION, CITATION_ERROR, SECURITY)


# ─── HALLUCINATION 阈值 (供单测/调整) ───────────────────────
HALLUCINATION_FAITH_THRESHOLD = 0.5
NO_JUDGE_COVERAGE_THRESHOLD = 0.3


def classify(
    case: dict,
    chat_resp: dict | None = None,
    retrieve_resp: dict | None = None,
    judge_fn: Callable[[str], str] | None = None,
) -> tuple[str, str]:
    """返回 (error_type, reason)。case 必含 question; 其他字段可空。

    chat_resp / retrieve_resp: 当时实际的 /chat 与 /retrieve 响应; 缺省读 case 自带
    `state_hint` / `retrieved_chunks` / `answer` 做退化判定。
    judge_fn: 注入的 LLM judge; 为 None 时 HALLUCINATION 退化为 token-coverage 判定。
    """
    q = case.get("question") or ""
    answer = (chat_resp or {}).get("answer") or case.get("answer") or ""
    state_hint = (chat_resp or {}).get("state_hint") or case.get("state_hint") or ""

    # 1. SECURITY (最高优先; 把自残/暴力识别到 SECURITY 而非 HALLUCINATION)
    if hit_security(q) or hit_security(answer):
        return SECURITY, "命中安全红线词 (question/answer)"

    # 2. NO_RECALL
    retrieved_chunks = _extract_chunks(retrieve_resp) or case.get("retrieved_chunks") or []
    if state_hint == "NO_RECALL" or (state_hint == "" and not retrieved_chunks):
        return NO_RECALL, f"state_hint={state_hint or '∅'}, 无 retrieved chunk"

    # 3. GENERATION_ERROR
    if state_hint in ("LLM_DEGRADED", "EMPTY_KB"):
        return GENERATION_ERROR, f"state_hint={state_hint}"

    # 4. HALLUCINATION
    if answer and retrieved_chunks:
        ctx = _ctx_from(retrieve_resp) or answer
        faith = _judge_faith(answer, ctx, judge_fn)
        if faith < HALLUCINATION_FAITH_THRESHOLD:
            return HALLUCINATION, f"faithfulness/coverage={faith:.2f} < {HALLUCINATION_FAITH_THRESHOLD}"

    # 5. CITATION_ERROR
    #   - 答案非空 / retrieved 非空, 但 cited chunk id 全不在 retrieved set
    cited = _extract_cited(chat_resp)
    retrieved_set = {int(x) for x in (retrieved_chunks or []) if x is not None}
    if cited and retrieved_set and not (set(cited) & retrieved_set):
        return CITATION_ERROR, f"cited {cited} 与 retrieved {list(retrieved_set)} 无交集"
    #   - 或答案明显引用 ([1][2]...) 但 cited key 缺失
    if re.search(r"\[\d+\]", answer or "") and not cited:
        return CITATION_ERROR, "answer 含 [n] 引用标记但 citations 为空"

    # 6. WRONG_RECALL (兜底)
    gold = case.get("gold_chunk_ids") or []
    if retrieved_chunks and gold and not (set(map(int, retrieved_chunks)) & set(map(int, gold))):
        return WRONG_RECALL, f"retrieved {retrieved_chunks} 与 gold {gold} 无交集"

    # 没命中任何分支 → 默认 WRONG_RECALL (line-level 入库前应已分类, 退化标识)
    return WRONG_RECALL, "未匹配任何明确分支, 兜底 WRONG_RECALL"


# ─── helpers ───────────────────────────────────────────────
def _extract_chunks(retrieve_resp: dict | None) -> list[int]:
    if not retrieve_resp:
        return []
    return [it.get("chunk_id") for it in (retrieve_resp.get("items") or []) if it.get("chunk_id") is not None]


def _ctx_from(retrieve_resp: dict | None) -> str:
    if not retrieve_resp:
        return ""
    parts = [(it.get("llm_context") or "") for it in (retrieve_resp.get("items") or [])]
    return "\n\n".join(p for p in parts if p).strip()


def _extract_cited(chat_resp: dict | None) -> list[int]:
    if not chat_resp:
        return []
    return [
        c.get("chunk_id")
        for c in (chat_resp.get("citations") or [])
        if c.get("chunk_id") is not None
    ]


def _judge_faith(answer: str, ctx: str, judge_fn) -> float:
    """LLM judge 若可用走 faithfulness; 否则退化到 token coverage。"""
    if not answer:
        return 0.0
    # 局部 import 避免纯函数测试被一并加 sys.path 时失败
    from eval.metrics import generation_metrics as gm

    if judge_fn is None:
        cov = gm._context_coverage(answer, ctx)
        # 把 coverage 阈值折算到与 LLM judge 同空间 (低门槛)
        return 1.0 if cov >= NO_JUDGE_COVERAGE_THRESHOLD else cov
    return gm.judge_llm_score(judge_fn(_faith_prompt(answer, ctx)))


def _faith_prompt(answer: str, ctx: str) -> str:
    return (
        "判断【答案】中的断言是否全部由【上下文】支持。任何 context 没有的具体事实"
        " (版本号/数值/类名/步骤) 即视为幻觉。\n\n"
        f"【上下文】\n{ctx}\n\n"
        f"【答案】\n{answer}\n\n"
        "只输出一个 [0, 1] 的浮点数 (1=完全由上下文支持, 0.5=部分, 0=大量幻觉), "
        "不要其它解释。"
    )
