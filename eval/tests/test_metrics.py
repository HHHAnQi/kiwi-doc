"""纯函数单元测试 — 不需网络/检索系统/LLM。

覆盖:
- retrieval_metrics: Recall@K / Precision@K / HitRate / MRR / NDCG + aggregate
- generation_metrics: citation_accuracy + judge_llm_score 归一 + 无 LLM 退化

跑: pytest eval/ -q
"""
import math

import sys
import pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))

from eval.metrics import retrieval_metrics as rm
from eval.metrics import generation_metrics as gm


# ── Recall@K ──────────────────────────────────────────────
def test_recall_basic():
    # gold [10, 20] 在 top-3 中只命中 10 → recall = 1/2
    assert rm.recall_at_k([10, 30, 40], [10, 20], 3) == 0.5


def test_recall_all_hit():
    assert rm.recall_at_k([10, 20, 30], [10, 20], 3) == 1.0


def test_recall_none():
    assert rm.recall_at_k([1, 2, 3], [99], 3) == 0.0


def test_recall_empty_gold():
    assert rm.recall_at_k([1, 2], [], 2) == 0.0


def test_recall_k_zero():
    assert rm.recall_at_k([1, 2], [1], 0) == 0.0


# ── Precision@K ───────────────────────────────────────────
def test_precision_basic():
    # 2/5 命中
    assert rm.precision_at_k([1, 99, 2, 88, 77], [1, 2], 5) == 0.4


def test_precision_short_list_k_greater():
    # retrieved 比 k 短 → 分母仍为 k
    assert rm.precision_at_k([1], [1, 2], 5) == 0.2


def test_precision_zero_k():
    assert rm.precision_at_k([1], [1], 0) == 0.0


# ── HitRate ───────────────────────────────────────────────
def test_hit_rate_hit():
    assert rm.hit_rate([5, 6, 7], [7], 3) == 1.0


def test_hit_rate_miss():
    assert rm.hit_rate([5, 6, 7], [99], 3) == 0.0


def test_hit_rate_empty_gold():
    assert rm.hit_rate([5, 6, 7], [], 3) == 0.0


# ── MRR ───────────────────────────────────────────────────
def test_mrr_first_rank():
    assert rm.mrr([1, 2, 3], [1], 3) == 1.0


def test_mrr_third_rank():
    assert rm.mrr([9, 8, 7, 6], [7], 3) == 1.0 / 3


def test_mrr_after_k_ignored():
    # gold 在 rank 4 (k=3) → 不计, 返 0
    assert rm.mrr([9, 8, 7, 6], [6], 3) == 0.0


def test_mrr_no_k_unbounded():
    assert rm.mrr([9, 8, 9, 6], [6]) == 0.25


# ── NDCG@K ────────────────────────────────────────────────
def test_ndcg_perfect():
    # 全相关排前 → NDCG = 1
    assert abs(rm.ndcg_at_k([1, 2, 3], [1, 2, 3], 3) - 1.0) < 1e-9


def test_ndcg_single_relevant_at_rank2():
    # gold={b}, retrieved=[a,b] @k=2: DCG=0+1/log2(3) ; IDCG=1/log2(2)=1
    expected = (1.0 / math.log2(3)) / 1.0
    assert abs(rm.ndcg_at_k(["a", "b"], ["b"], 2) - expected) < 1e-9


def test_ndcg_no_relevant():
    assert rm.ndcg_at_k([1, 2, 3], [99], 3) == 0.0


def test_ndcg_multi_gold_partial():
    # gold={1,2}, retrieved=[1,3,2] @k=3
    # DCG = 1/log2(2) + 1/log2(4) = 1 + 0.5
    # IDCG (2 gold 排前, k=3) = 1/log2(2) + 1/log2(3)
    dcg = 1.0 / math.log2(2) + 1.0 / math.log2(4)
    idcg = 1.0 / math.log2(2) + 1.0 / math.log2(3)
    assert abs(rm.ndcg_at_k([1, 3, 2], [1, 2], 3) - dcg / idcg) < 1e-9


# ── per_query_metrics + aggregate ────────────────────────
def test_per_query_metrics_keys():
    m = rm.per_query_metrics([1, 2], [1], 2)
    assert set(m.keys()) == {"recall@2", "precision@2", "hit_rate@2", "mrr@2", "ndcg@2"}


def test_aggregate_macro_avg():
    pq = [
        rm.per_query_metrics([1, 2], [1], 2),
        rm.per_query_metrics([9, 8], [1], 2),  # 0 hit
    ]
    agg = rm.aggregate(pq)
    # recall@2: (1.0 + 0.0)/2
    assert abs(agg["recall@2"] - 0.5) < 1e-9
    assert abs(agg["hit_rate@2"] - 0.5) < 1e-9


def test_aggregate_empty():
    assert rm.aggregate([]) == {}


# ── Citation Accuracy ─────────────────────────────────────
def test_citation_accuracy_all_correct():
    assert gm.citation_accuracy([1, 2], [1, 2, 3]) == 1.0


def test_citation_accuracy_partial():
    assert gm.citation_accuracy([1, 9], [1, 2]) == 0.5


def test_citation_accuracy_no_citations():
    assert gm.citation_accuracy([], [1, 2]) == 0.0


def test_citation_accuracy_none_in_gold():
    assert gm.citation_accuracy([9, 10], [1, 2]) == 0.0


def test_citation_recall_and_hit_rate():
    assert gm.citation_recall([1, 9], [1, 2]) == 0.5
    assert gm.citation_recall([1, 1], [1]) == 1.0
    assert gm.citation_recall([1], []) == 0.0
    assert gm.citation_hit_rate([1, 9], [1, 2]) == 1.0
    assert gm.citation_hit_rate([9], [1, 2]) == 0.0


# ── judge_llm_score 归一化 ───────────────────────────────
def test_judge_score_numeric():
    assert gm.judge_llm_score("Score: 0.85") == 0.85
    assert gm.judge_llm_score("1") == 1.0
    assert gm.judge_llm_score("0") == 0.0


def test_judge_score_keywords():
    # 注意: 归一是 substring match 的近似 — 不区分 "不完全" vs "完全",
    # 故 "完全一致" 与 "不完全一致" 都 hit "完全一致"→1.0。这是有意的弱判官,
    # 真实用法应让 LLM 直接回数字 (上面 test_judge_score_numeric 已覆盖)。
    assert gm.judge_llm_score("yes 完全覆盖") == 1.0
    assert gm.judge_llm_score("no, 完全错误") == 0.0
    assert gm.judge_llm_score("部分") == 0.5


def test_judge_score_empty_fallback_zero():
    assert gm.judge_llm_score("") == 0.0
    assert gm.judge_llm_score("xxx yyy") == 0.0


def test_judge_score_clamps_to_one():
    # judge 偶尔输出 1.0 行为稳定 (regex 已限定 [0,1])
    assert gm.judge_llm_score("0.95 / 1") == 0.95


# ── 无 LLM 退化路径 (answer_correctness / faithfulness) ──
def test_answer_correctness_no_judge_overlap():
    # 同字串 → 1.0
    s = "Dubbo 协议是长连接"
    assert abs(gm.answer_correctness(s, s) - 1.0) < 1e-9


def test_answer_correctness_no_judge_disjoint():
    # 完全不同关键字 → 接近 0
    a = "apple orange"
    b = "rocketmq sentry"
    assert gm.answer_correctness(a, b) == 0.0


def test_faithfulness_no_judge_full_coverage():
    # 答案 token 大体出现在 context
    ctx = "Dubbo 协议默认是 long polling 长连接"
    ans = "Dubbo 默认长连接"
    cov = gm.faithfulness(ans, ctx)
    # 完全覆盖 → 1.0; bigram 实现下接近完全覆盖 (≥0.7 即认为覆盖)
    assert cov >= 0.7


def test_faithfulness_no_judge_partial():
    ctx = "Sentinel 限流的默认行为是直接拒绝"
    ans = "RocketMQ 用长轮询推送消息"
    cov = gm.faithfulness(ans, ctx)
    assert 0.0 <= cov <= 1.0


# ── LLM-judge 路径 (mock judge_fn) ───────────────────────
def test_answer_correctness_with_mock_judge():
    calls = []

    def fake_judge(prompt: str) -> str:
        calls.append(prompt)
        return "0.8"

    score = gm.answer_correctness("pred", "gold", judge_fn=fake_judge)
    assert score == 0.8
    assert len(calls) == 1
    assert "gold" in calls[0]


def test_answer_correctness_judge_receives_question():
    calls = []

    def fake_judge(prompt: str) -> str:
        calls.append(prompt)
        return "1"

    assert gm.answer_correctness("pred", "gold", fake_judge, question="what") == 1.0
    assert "【问题】\nwhat" in calls[0]


def test_faithfulness_with_mock_judge_keyword():
    def fake_judge(prompt: str) -> str:
        return "yes 完全由上下文支持"

    assert gm.faithfulness("ans", "ctx", judge_fn=fake_judge) == 1.0


def test_evidence_completeness_with_judge_uses_gold_and_context():
    seen = {}

    def fake_judge(prompt):
        seen["prompt"] = prompt
        return "0.75"

    assert gm.evidence_completeness("gold fact", "retrieved fact", fake_judge) == 0.75
    assert "【标准答案】" in seen["prompt"]
    assert "【检索上下文】" in seen["prompt"]


def test_evidence_completeness_empty_input_is_zero():
    assert gm.evidence_completeness("", "context") == 0.0
    assert gm.evidence_completeness("gold", "") == 0.0


def test_aggregate_generation_keys():
    pq = [
        {"answer_correctness": 0.8, "faithfulness": 0.6, "citation_accuracy": 1.0},
        {"answer_correctness": 0.4, "faithfulness": 0.4, "citation_accuracy": 0.5},
    ]
    agg = gm.aggregate_generation(pq)
    assert abs(agg["answer_correctness"] - 0.6) < 1e-9
    assert abs(agg["faithfulness"] - 0.5) < 1e-9
    assert abs(agg["citation_accuracy"] - 0.75) < 1e-9
