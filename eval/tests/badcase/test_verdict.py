"""regression.verdict 纯函数单测 — 注入 mock judge_fn, 不依赖网络。"""
import sys
import pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3]))

from badcase.regression import verdict


def _case(**kw):
    base = {"id": "x", "question": "q", "expected_answer": "标准答案", "gold_chunk_ids": [1]}
    base.update(kw)
    return base


# ── 正向: 全部满足 → PASS ───────────────────────────────────
def test_pass_when_state_consistent_and_recall_and_answer_match():
    c = _case(expected_state_hint="OK")
    retrieve = {"items": [{"chunk_id": 1, "llm_context": "..."},{"chunk_id": 2,"llm_context":"x"}]}
    chat = {"state_hint": "OK", "answer": "标准答案", "citations": [{"chunk_id": 1}]}
    v = verdict.verdict(c, chat, retrieve, judge_fn=lambda p: "0.9")
    assert v["pass"] is True, v["reasons"]
    assert v["metrics"]["recall_at_k"] == 1.0


# ── state_hint 漂移 → FAIL ─────────────────────────────────
def test_fail_state_hint_drift():
    c = _case(expected_state_hint="OK")
    chat = {"state_hint": "NO_RECALL"}
    v = verdict.verdict(c, chat, None, judge_fn=lambda p: "1.0")
    assert v["pass"] is False
    assert any("state_hint" in r for r in v["reasons"])


def test_pass_when_no_expected_state_hint():
    # case 没填 expected_state_hint → 跳过 state 检查
    c = _case()
    c.pop("expected_state_hint", None)
    chat = {"state_hint": "NO_RECALL"}
    retrieve = {"items": [{"chunk_id": 1}]}
    v = verdict.verdict(c, chat, retrieve, judge_fn=lambda p: "1.0")
    # 其它都满足 → pass
    assert v["pass"] is True, v["reasons"]


def test_strict_state_fails_even_when_expectation_missing_is_relaxed():
    # expected 显式声明 + strict → NO_RECALL vs OK 是 fail
    c = _case(expected_state_hint="OK")
    chat = {"state_hint": "LLM_DEGRADED"}
    v = verdict.verdict(c, chat, None, judge_fn=lambda p: "1.0", strict_state=True)
    assert v["pass"] is False


# ── 检索 0 命中 gold → FAIL ────────────────────────────────
def test_fail_zero_recall_when_gold_non_empty():
    c = _case(gold_chunk_ids=[100])
    retrieve = {"items": [{"chunk_id": 1}, {"chunk_id": 2}]}
    chat = {"state_hint": "OK", "answer": "x"}
    v = verdict.verdict(c, chat, retrieve, judge_fn=lambda p: "0.9")
    assert v["pass"] is False
    assert v["metrics"]["recall_at_k"] == 0.0
    assert any("检索" in r for r in v["reasons"])


# ── 退步: 当时 rank 比现在好 ───────────────────────────────
def test_fail_rank_regression():
    c = _case(gold_chunk_ids=[42], retrieved_chunks=[42, 9, 8])  # 当时 rank 1
    # 现在 42 滑到第 3 位
    retrieve = {"items": [{"chunk_id": 1}, {"chunk_id": 2}, {"chunk_id": 42}]}
    chat = {"state_hint": "OK", "answer": "标准答案"}
    v = verdict.verdict(c, chat, retrieve, judge_fn=lambda p: "1.0")
    assert v["pass"] is False
    assert any("位次退步" in r for r in v["reasons"])


# ── 答案等价度过低 → FAIL ───────────────────────────────────
def test_fail_answer_correctness_low():
    c = _case(expected_state_hint="OK")
    retrieve = {"items": [{"chunk_id": 1}]}
    chat = {"state_hint": "OK", "answer": "完全无关的胡言乱语"}
    v = verdict.verdict(c, chat, retrieve, judge_fn=lambda p: "0.2")
    assert v["pass"] is False
    assert any("答案等价" in r for r in v["reasons"])


# ── 反向 SAFETY: 非 SECURITY case 被打成 SECURITY ─────────
def test_fail_when_misclassified_as_security():
    # 让 classifier 命中 SECURITY (answer 含敏感词)
    c = _case(expected_state_hint="OK", question="正常问题", error_type="HALLUCINATION")
    chat = {"state_hint": "OK", "answer": "你可以自杀"}
    v = verdict.verdict(c, chat, None, judge_fn=lambda p: "1.0")
    assert v["pass"] is False
    assert any("误判 SECURITY" in r for r in v["reasons"])


# ── no-judge 退化路径 (字符串 overlap) ─────────────────────
def test_no_judge_overlap_drops_below_threshold():
    c = _case(expected_answer="Sentinel 通过 FlowRule 配置 QPS 限流")
    retrieve = {"items": [{"chunk_id": 1}]}
    chat = {"state_hint": "OK", "answer": "今天天气真好适合户外活动"}  # 完全不 overlap
    v = verdict.verdict(c, chat, retrieve, judge_fn=None)
    assert v["pass"] is False
    assert any("答案等价" in r for r in v["reasons"])
