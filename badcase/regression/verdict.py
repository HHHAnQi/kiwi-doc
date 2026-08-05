"""回归比对口径 — 判一条 badcase 在当前系统下是否「修好了 / 没退化」。

判定 *=* 任一不满足即 fail (复跑严格比对, 已与用户确认):
  - state_hint 不变 (case 显式声明 expected_state_hint 时)
  - 检索命中 (case.gold_chunk_ids 非空时, top-k 至少命中 1 个 gold)
  - 答案等价 (LLM-judge answer_correctness >= 0.5; 无 judge 退化 overlap_f1 >= 0.4)
  - 反向 SAFETY: case 非 SECURITY 但当前分类又蹦成 SECURITY → fail (误判红线)

API: verdict(case, chat_resp, retrieve_resp, judge_fn, k=5, strict_state=False)
     -> {pass: bool, reasons: [...], metrics: {...}}
"""
from __future__ import annotations

import math
from typing import Callable

from badcase.classifier import error_type as et

# ── 阈值 (与 run_eval 一致, 便于人工对齐) ───────────────────
CORRECTNESS_LLM_THRESHOLD = 0.5
CORRECTNESS_NOJUDGE_THRESHOLD = 0.4


def verdict(
    case: dict,
    chat_resp: dict | None,
    retrieve_resp: dict | None,
    judge_fn: Callable[[str], str] | None = None,
    k: int = 5,
    strict_state: bool = False,
) -> dict:
    """判定一条 case 在当前系统下是否通过回归。返回 dict。"""
    reasons: list[str] = []
    metrics: dict = {}

    # ---- 反向 SAFETY: 不允许把普通题打成 SECURITY (误判红线) ----
    runtime_et, _ = et.classify(
        case,
        chat_resp=chat_resp,
        retrieve_resp=retrieve_resp,
        judge_fn=None,  # 反向分类走 pure-rule, 不耗 LLM
    )
    metrics["runtime_error_type"] = runtime_et
    if case.get("error_type") != et.SECURITY and runtime_et == et.SECURITY:
        reasons.append(f"误判 SECURITY (case 类型={case.get('error_type')}, 运行时={runtime_et})")

    # ---- state_hint 一致性 ----
    expected_hint = case.get("expected_state_hint")
    cur_hint = (chat_resp or {}).get("state_hint") or ""
    metrics["cur_state_hint"] = cur_hint
    metrics["expected_state_hint"] = expected_hint
    if expected_hint:
        if strict_state and cur_hint != expected_hint:
            reasons.append(f"strict_state: 当前 state_hint={cur_hint} ≠ 期望={expected_hint}")
        elif cur_hint and cur_hint != expected_hint:
            # 宽模式: 只在 cur 非空且不等才报
            reasons.append(f"state_hint 漂移: 当前={cur_hint} ≠ 期望={expected_hint}")

    # ---- 检索命中 ----
    gold = [int(x) for x in (case.get("gold_chunk_ids") or []) if x is not None]
    cur_top = [
        int(x)
        for x in (
            [(it.get("chunk_id")) for it in (retrieve_resp or {}).get("items") or []]
        )
        if x is not None
    ][:k]
    if gold:
        hits = [c for c in cur_top if c in set(gold)]
        # MRR-style: 第一命中位置 (0=未命中)
        first_rank = next((i + 1 for i, c in enumerate(cur_top) if c in set(gold)), 0)
        recall = len(hits) / len(gold)
        metrics["recall_at_k"] = recall
        metrics["first_gold_rank"] = first_rank
        if recall == 0.0:
            reasons.append(f"检索 0 命中 gold (gold={gold}, current top-{k}={cur_top})")
        else:
            # 退化检查: 有 当时 retrieved_chunks 时, 第一命中 gold 的位置不能比当时退
            old = [int(x) for x in (case.get("retrieved_chunks") or []) if x is not None]
            if old:
                old_rank = next(
                    (i + 1 for i, c in enumerate(old) if c in set(gold)), 0
                )
                if old_rank and first_rank and first_rank > old_rank:
                    reasons.append(
                        f"检索位次退步: 当时 gold 第一次出现 rank={old_rank}, 现 rank={first_rank}"
                    )

    # ---- 答案等价 (仅当系统给了真实答案才判) ----
    pred = (chat_resp or {}).get("answer") or ""
    exp = case.get("expected_answer") or ""
    metrics["answer_correctness"] = 0.0
    if pred and exp:
        score = _correctness(pred, exp, judge_fn)
        metrics["answer_correctness"] = score
        if score < CORRECTNESS_LLM_THRESHOLD if judge_fn else score < CORRECTNESS_NOJUDGE_THRESHOLD:
            thr = CORRECTNESS_LLM_THRESHOLD if judge_fn else CORRECTNESS_NOJUDGE_THRESHOLD
            reasons.append(f"答案等价度 {score:.2f} < {thr:.2f}")

    return {
        "pass": len(reasons) == 0,
        "reasons": reasons,
        "metrics": metrics,
    }


# ── helpers ───────────────────────────────────────────────
def _correctness(pred: str, gold: str, judge_fn) -> float:
    from eval.metrics import generation_metrics as gm

    if judge_fn is None:
        return gm._overlap_f1(pred, gold)
    prompt = (
        "你是严格的判官。判断【预测答案】是否在语义上与【标准答案】一致, "
        "覆盖标准答案里的关键事实。\n\n"
        f"【标准答案】\n{gold}\n\n"
        f"【预测答案】\n{pred}\n\n"
        "只输出一个 [0, 1] 的浮点数 (1=完全覆盖关键事实, 0.5=部分, 0=完全不相关), "
        "不要其它解释。"
    )
    return gm.judge_llm_score(judge_fn(prompt))
