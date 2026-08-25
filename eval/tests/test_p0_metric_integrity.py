"""P0 指标可信度回归测试；全部离线，不调用 LLM、检索或 reranker。"""

from __future__ import annotations

import importlib.util
import sys
import types
from pathlib import Path

# 纯函数测试不应依赖运行时 .env 加载包；生产路径仍使用真实 python-dotenv。
if "dotenv" not in sys.modules:
    dotenv_stub = types.ModuleType("dotenv")
    dotenv_stub.load_dotenv = lambda *args, **kwargs: False
    sys.modules["dotenv"] = dotenv_stub

from eval.ragas_pipeline import (
    attach_per_sample_scores,
    bootstrap_mean_ci,
    compute_refusal_metrics,
    compute_confidence_intervals,
    is_refusal,
)
from eval.judge_calibration import calibration_report, cohen_kappa, prepare_rows
from eval.aggregate_ragas_runs import aggregate_metadata
from eval.analyze_ragas_badcases import analyze_samples, classify_question
from eval.multi_turn.run_multi_turn_eval import evaluate_g1_artifacts, overall_status
from eval.runner.run_eval import _ground_truth_answer


PROJECT_ROOT = Path(__file__).resolve().parents[2]
COMPARE_PATH = PROJECT_ROOT / "eval/agentic/scripts/compare_classic_vs_planned.py"
SPEC = importlib.util.spec_from_file_location("compare_classic_vs_planned", COMPARE_PATH)
COMPARE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(COMPARE)


class _ILoc:
    def __init__(self, values):
        self.values = values

    def __getitem__(self, index):
        return self.values[index]


class _Series:
    def __init__(self, values):
        self.values = values
        self.iloc = _ILoc(values)

    def tolist(self):
        return list(self.values)


class _Row:
    def __init__(self, data, index):
        self.data = data
        self.index = index

    def __getitem__(self, key):
        return self.data[key][self.index]


class _FrameILoc:
    def __init__(self, frame):
        self.frame = frame

    def __getitem__(self, index):
        return _Row(self.frame.data, index)


class _Frame:
    """仅实现被测函数需要的最小 pandas.DataFrame 协议。"""

    def __init__(self, data):
        self.data = data
        self.columns = list(data)
        self.iloc = _FrameILoc(self)

    def __len__(self):
        return len(next(iter(self.data.values()), []))

    def __getitem__(self, key):
        return _Series(self.data[key])


def test_short_correct_answer_is_not_refusal():
    assert not is_refusal("缺省用 160 份虚拟节点 [1]", "OK")


def test_current_corpus_corrected_answer_wins_over_legacy_answer():
    case = {
        "ground_truth_answer": "旧答案",
        "new_ground_truth_answer": "当前语料修订答案",
    }
    assert _ground_truth_answer(case) == "当前语料修订答案"


def test_structured_refusal_state_wins():
    assert is_refusal("服务当前不可用", "LLM_DEGRADED")
    assert is_refusal("任意文本", "NO_RECALL")


def test_legacy_refusal_marker_remains_supported():
    assert is_refusal("未在知识库中找到相关信息，请改写问题", None)


def test_refusal_metrics_use_state_not_answer_length():
    samples = [
        {"answer": "正确短答", "state_hint": "OK"},
        {"answer": "未找到", "state_hint": "NO_RECALL"},
    ]
    frame = _Frame({"faithfulness": [1.0, 0.0]})
    result = compute_refusal_metrics(samples, frame)
    assert result["refusal_rate"] == 0.5
    assert result["faith_on_answered"] == 1.0
    assert result["faith_on_refused"] == 0.0


def test_per_sample_scores_are_persistable():
    samples = [{"question": "q", "answer": "a", "contexts": []}]
    frame = _Frame({
        "faithfulness": [0.8],
        "answer_relevancy": [0.7],
        "context_precision": [0.6],
        "context_recall": [0.5],
    })
    enriched = attach_per_sample_scores(samples, frame)
    assert enriched[0]["metrics"]["faithfulness"] == 0.8
    assert enriched[0]["metrics"]["context_recall"] == 0.5


def test_bootstrap_ci_is_deterministic_and_contains_mean():
    first = bootstrap_mean_ci([0.0, 1.0, 1.0, 0.0], iterations=500, seed=7)
    second = bootstrap_mean_ci([0.0, 1.0, 1.0, 0.0], iterations=500, seed=7)
    assert first == second
    assert first["low"] <= 0.5 <= first["high"]
    assert first["n"] == 4


def test_confidence_intervals_include_refusal_split():
    samples = [
        {"answer": "正确短答", "state_hint": "OK"},
        {"answer": "未找到", "state_hint": "NO_RECALL"},
    ]
    frame = _Frame({
        "faithfulness": [1.0, 0.0],
        "answer_relevancy": [1.0, 0.0],
        "context_precision": [1.0, 0.0],
        "context_recall": [1.0, 0.0],
    })
    intervals = compute_confidence_intervals(samples, frame)
    assert intervals["refusal_rate"]["n"] == 2
    assert intervals["faith_on_answered"]["n"] == 1
    assert intervals["faith_on_refused"]["n"] == 1


def _result(verdict: str, latency: int, state: str = "OK") -> dict:
    return {"verdict": verdict, "latency_ms": latency, "citations": 2, "state": state}


def test_agentic_stdev_is_computed_across_runs():
    per_case = {
        "c1": [_result("PASS", 10), _result("PASS", 20), _result("FAIL", 30)],
        "c2": [_result("FAIL", 40), _result("PASS", 50), _result("FAIL", 60)],
    }
    result = COMPARE.aggregate_mode_results(per_case, 3)
    assert result["run_accuracies"] == [0.5, 1.0, 0.0]
    assert result["accuracy_mean"] == 0.5
    assert result["accuracy_stdev"] == 0.5
    assert result["latency_p95_ms"] == 60


def test_multi_turn_overall_requires_all_five_passes():
    all_pass = [{"gate": f"G{i}", "status": "PASS"} for i in range(1, 6)]
    partial = [*all_pass[:4], {"gate": "G5", "status": "SKIP"}]
    failed = [*all_pass[:4], {"gate": "G5", "status": "FAIL"}]
    assert overall_status(all_pass) == "PASS"
    assert overall_status(partial) == "INCOMPLETE"
    assert overall_status(failed) == "FAIL"


def _g1_artifact(scores=None):
    return {
        "schema_version": 2,
        "baseline_type": "multi_run",
        "run_count": 3,
        "experiment_id": "exp1",
        "generated_at": "2999-01-01T00:00:00+00:00",
        "questions_sha256": "abc",
        "sample_count": 100,
        "judge": {"model": "deepseek-chat"},
        "confidence_intervals_95": {
            metric: {"n": 100}
            for metric in ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
        },
        "scores": scores or {
            "faithfulness": 0.8,
            "answer_relevancy": 0.8,
            "context_precision": 0.8,
            "context_recall": 0.8,
        },
    }


def test_g1_requires_v2_fingerprints():
    result = evaluate_g1_artifacts({"scores": {}}, {"scores": {}})
    assert result["status"] == "INCOMPLETE"


def test_g1_blocks_quality_regression_over_three_points():
    baseline = _g1_artifact()
    current = _g1_artifact({
        "faithfulness": 0.75,
        "answer_relevancy": 0.8,
        "context_precision": 0.8,
        "context_recall": 0.8,
    })
    result = evaluate_g1_artifacts(current, baseline)
    assert result["status"] == "FAIL"
    assert result["failed_metrics"] == ["faithfulness"]


def test_g1_passes_compatible_non_regressing_artifact():
    result = evaluate_g1_artifacts(_g1_artifact(), _g1_artifact())
    assert result["status"] == "PASS"


def test_g1_rejects_incomplete_metric_samples():
    current = _g1_artifact()
    current["confidence_intervals_95"]["faithfulness"]["n"] = 99
    result = evaluate_g1_artifacts(current, _g1_artifact())
    assert result["status"] == "INCOMPLETE"
    assert "有效判分数不完整" in result["reason"]


def test_cohen_kappa_perfect_agreement():
    labels = ["SUPPORTED", "PARTIAL", "UNSUPPORTED"]
    assert cohen_kappa(labels, labels) == 1.0


def test_judge_calibration_gate_fails_disagreement():
    rows = [
        {"human_label": "SUPPORTED", "judge_label": "UNSUPPORTED"},
        {"human_label": "UNSUPPORTED", "judge_label": "SUPPORTED"},
        {"human_label": "PARTIAL", "judge_label": "SUPPORTED"},
    ]
    report = calibration_report(rows)
    assert report["status"] == "FAIL"
    assert report["agreement"] == 0.0


def test_prepare_calibration_rows_is_deterministic_and_stratified():
    rows = [
        {"question": f"q{i}", "metrics": {"faithfulness": score}}
        for i, score in enumerate([0.1, 0.2, 0.6, 0.7, 0.9, 1.0])
    ]
    first = prepare_rows(rows, sample_size=3, seed=7)
    second = prepare_rows(rows, sample_size=3, seed=7)
    assert first == second
    assert {row["judge_label"] for row in first} == {
        "SUPPORTED", "PARTIAL", "UNSUPPORTED"
    }


def _run_metadata(experiment_id: str, faithfulness: float) -> dict:
    return {
        "schema_version": 2,
        "experiment_id": experiment_id,
        "questions_file": "questions.jsonl",
        "questions_sha256": "abc",
        "sample_count": 100,
        "judge": {"model": "deepseek-chat"},
        "public_config": {"RAG_RERANK_ENABLED": "true"},
        "confidence_intervals_95": {
            metric: {"n": 100}
            for metric in ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
        },
        "scores": {
            "faithfulness": faithfulness,
            "answer_relevancy": 0.7,
            "context_precision": 0.6,
            "context_recall": 0.5,
        },
    }


def test_ragas_baseline_requires_three_runs():
    try:
        aggregate_metadata([_run_metadata("a", 0.7), _run_metadata("b", 0.8)])
        assert False, "should reject fewer than three runs"
    except ValueError as error:
        assert "至少需要3轮" in str(error)


def test_ragas_three_run_baseline_has_mean_and_stdev():
    baseline = aggregate_metadata([
        _run_metadata("a", 0.7),
        _run_metadata("b", 0.8),
        _run_metadata("c", 0.9),
    ])
    assert baseline["baseline_type"] == "multi_run"
    assert baseline["run_count"] == 3
    assert abs(baseline["scores"]["faithfulness"] - 0.8) < 1e-9
    assert abs(baseline["run_stdev"]["faithfulness"] - 0.1) < 1e-9


def test_ragas_baseline_rejects_partial_metric_samples():
    runs = [_run_metadata("a", 0.7), _run_metadata("b", 0.8), _run_metadata("c", 0.9)]
    runs[1]["confidence_intervals_95"]["context_recall"]["n"] = 99
    try:
        aggregate_metadata(runs)
        assert False, "should reject incomplete metric samples"
    except ValueError as error:
        assert "有效判分数不完整" in str(error)


def test_attach_retrieval_metrics_uses_ranked_chunk_ids():
    samples = [{
        "retrieved_chunk_ids": [9, 7, 5],
        "gold_chunk_ids": [7],
    }]
    enriched, aggregate, n = __import__(
        "eval.ragas_pipeline", fromlist=["attach_retrieval_metrics"]
    ).attach_retrieval_metrics(samples, k=3)
    assert n == 1
    assert enriched[0]["retrieval_metrics"]["recall@3"] == 1.0
    assert enriched[0]["retrieval_metrics"]["mrr@3"] == 0.5
    assert aggregate["hit_rate@3"] == 1.0


def test_question_slice_classifier():
    assert classify_question("如何配置 `dubbo.protocol.port`？") == "exact_identifier"
    assert classify_question("Sentinel和Hystrix有什么区别？") == "multi_part"
    assert classify_question("如何开启鉴权？") == "how_to"


def test_badcase_analysis_flags_retrieval_miss():
    report = analyze_samples([{
        "question": "如何开启鉴权？",
        "contexts": ["irrelevant"],
        "metrics": {"faithfulness": 0.8, "answer_relevancy": 0.8},
        "retrieval_metrics": {"hit_rate@5": 0.0, "recall@5": 0.0, "mrr@5": 0.0, "ndcg@5": 0.0},
        "retrieved_chunk_ids": [1],
        "gold_chunk_ids": [2],
    }])
    assert len(report["badcases"]) == 1
    assert report["badcases"][0]["severity"] == 3
    assert report["slices"]["how_to"]["recall@5"] == 0.0
