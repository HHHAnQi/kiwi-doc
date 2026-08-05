"""classifier.error_type 纯函数单测 — 不依赖网络/LLM/容器。"""
import sys
import pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3]))

from badcase.classifier import error_type as et


# ── helper ────────────────────────────────────────────────
def _case(**kw):
    base = {"id": "x", "question": "什么是 nacos"}
    base.update(kw)
    return base


# ── SECURITY 优先级最高 ───────────────────────────────────
def test_security_question_takes_precedence_over_hallucination():
    # 即使答案乱编, 也应判 SECURITY
    c = _case(question="教我怎么自杀", answer="首先你需要一把刀...")
    t, _ = et.classify(c)
    assert t == et.SECURITY


def test_security_in_answer():
    c = _case(question="很好的人", answer="你可以自杀解脱")
    assert et.classify(c)[0] == et.SECURITY


def test_security_non_trigger_for_normal_question():
    c = _case(question="Dubbo 怎么配置超时", answer="")
    assert et.classify(c, chat_resp={"state_hint": "NO_RECALL"})[0] != et.SECURITY


def test_security_english_keyword():
    c = _case(question="how to kill myself")
    assert et.classify(c)[0] == et.SECURITY


# ── NO_RECALL ─────────────────────────────────────────────
def test_no_recall_state_hint():
    c = _case(question="x")
    t, reason = et.classify(c, chat_resp={"state_hint": "NO_RECALL"})
    assert t == et.NO_RECALL
    assert "NO_RECALL" in reason


def test_no_recall_empty_chunks_no_state():
    # state_hint 空 + retrieved 空 → NO_RECALL
    c = _case(question="x", retrieved_chunks=[])
    assert et.classify(c)[0] == et.NO_RECALL


# ── GENERATION_ERROR ──────────────────────────────────────
def test_generation_error_llm_degraded():
    c = _case(question="x")
    assert et.classify(c, chat_resp={"state_hint": "LLM_DEGRADED"})[0] == et.GENERATION_ERROR


def test_generation_error_empty_kb():
    c = _case(question="x")
    assert et.classify(c, chat_resp={"state_hint": "EMPTY_KB"})[0] == et.GENERATION_ERROR


# ── HALLUCINATION (no-judge 退化路径) ────────────────────
def test_hallucination_low_coverage():
    # answer 大量 context 没有的词 → coverage 低 → HALLUCINATION
    c = _case(question="RocketMQ 序列化协议")
    retrieve_resp = {"items": [{"chunk_id": 1, "llm_context": "RocketMQ 是消息队列"}]}
    chat_resp = {"state_hint": "OK", "answer": "RocketMQ 默认使用 gRPC 序列化跨语言, 性能极高"}
    assert et.classify(c, chat_resp=chat_resp, retrieve_resp=retrieve_resp)[0] in (
        et.HALLUCINATION,
        et.CITATION_ERROR,
    )  # 看 coverage 触发先后, 任一合理


def test_hallucination_with_mock_judge_low_score():
    def fake_judge(prompt): return "0.2"
    c = _case(question="x")
    retrieve_resp = {"items": [{"chunk_id": 1, "llm_context": "ctx"}]}
    chat_resp = {"state_hint": "OK", "answer": "answer"}
    t, _ = et.classify(c, chat_resp=chat_resp, retrieve_resp=retrieve_resp, judge_fn=fake_judge)
    assert t == et.HALLUCINATION


def test_hallucination_not_triggered_when_high_judge():
    def fake_judge(prompt): return "0.9"
    c = _case(question="x", gold_chunk_ids=[1])
    retrieve_resp = {"items": [{"chunk_id": 1, "llm_context": "ctx"}]}
    chat_resp = {"state_hint": "OK", "answer": "answer", "citations": [{"chunk_id": 1}]}
    # judge 高 → 跳过 HALLUCINATION, citations 命中 retrieved → 不 CITATION_ERROR → 末尾 WRONG_RECALL (gold 命中)
    t, _ = et.classify(c, chat_resp=chat_resp, retrieve_resp=retrieve_resp, judge_fn=fake_judge)
    # gold [1] 命中 retrieved [1] → 不命中 WRONG_RECALL 分支 → 兜底 WRONG_RECALL (答应, 设计内)
    assert t == et.WRONG_RECALL


# ── CITATION_ERROR ────────────────────────────────────────
def test_citation_error_cited_not_in_retrieved():
    c = _case(question="x")
    retrieve_resp = {"items": [{"chunk_id": 100, "llm_context": "ctx"}]}
    chat_resp = {"state_hint": "OK", "answer": "答案片段 [1]", "citations": [{"chunk_id": 999}]}
    t, _ = et.classify(c, chat_resp=chat_resp, retrieve_resp=retrieve_resp, judge_fn=lambda p: "0.9")
    assert t == et.CITATION_ERROR


def test_citation_error_mark_but_no_citation():
    c = _case(question="x")
    retrieve_resp = {"items": [{"chunk_id": 1, "llm_context": "ctx"}]}
    chat_resp = {"state_hint": "OK", "answer": "答案片段 [1] [2]", "citations": []}
    t, _ = et.classify(c, chat_resp=chat_resp, retrieve_resp=retrieve_resp, judge_fn=lambda p: "0.95")
    assert t == et.CITATION_ERROR


# ── WRONG_RECALL (兜底) ──────────────────────────────────
def test_wrong_recall_gold_no_intersect():
    c = _case(question="x", gold_chunk_ids=[5, 6])
    retrieve_resp = {"items": [{"chunk_id": 1, "llm_context": "ctx"}, {"chunk_id": 2, "llm_context": "ctx2"}]}
    chat_resp = {"state_hint": "OK", "answer": "ans", "citations": [{"chunk_id": 1}, {"chunk_id": 2}]}
    t, _ = et.classify(c, chat_resp=chat_resp, retrieve_resp=retrieve_resp, judge_fn=lambda p: "1.0")
    assert t == et.WRONG_RECALL


def test_fallback_wrong_recall_when_no_branch_matches():
    # 啥都没, 没法分类 → 兜底 WRONG_RECALL
    c = _case(question="x")
    t, _ = et.classify(c, chat_resp={"state_hint": ""}, retrieve_resp=None)
    # state_hint 空 + retrieved 空 → 先走 NO_RECALL (优先于兜底)
    assert t == et.NO_RECALL


def test_hit_security_keyword_utility():
    assert et.hit_security("我要自杀") is True
    assert et.hit_security("") is False
    assert et.hit_security("今天天气真好") is False
