"""PR-7d Python tests: dataset validator + planner evaluator + aggregator.

用临时 jsonl 不依赖真实数据集 / actuals.
"""
from __future__ import annotations

import json
import tempfile
from pathlib import Path

from eval.planner import aggregate_report, run_planner_eval, validate_dataset


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def _valid_case(case_id: str = "mh-100", answerable: bool = False,
                expected: str = "REFUSED_NO_EVIDENCE") -> dict:
    return {
        "schemaVersion": "v1",
        "caseId": case_id,
        "question": "x?",
        "intent": "MULTI_HOP",
        "entities": [],
        "filters": {},
        "requirements": [
            {"requirementId": "REQ-1", "type": "FACT", "required": True,
             "description": "x"}
        ],
        "acceptableInitialPlans": [],
        "acceptableReplanPlans": [],
        "forbiddenToolSignatures": [],
        "goldDocumentIds": [],
        "goldEvidenceIds": [],
        "answerable": answerable,
        "replanExpected": False,
        "expectedFinalStatus": expected,
        "maxSteps": 3,
        "maxToolCalls": 3,
        "slice": "no_answer_refuse",
        "reviewStatus": "candidate",
        "reviewer": "tester",
        "reviewedAt": "2026-08-05T00:00:00Z",
    }


def test_validate_dataset_ok_unreviewed(tmp_path):
    p = tmp_path / "ds.jsonl"
    _write_jsonl(p, [_valid_case()])
    rc, errors = validate_dataset.validate_dataset(p, require_reviewed=False,
                                                   print_summary=False)
    assert rc == 0
    assert errors == []


def test_validate_dataset_blocks_when_require_reviewed(tmp_path):
    p = tmp_path / "ds.jsonl"
    _write_jsonl(p, [_valid_case()])
    rc, errors = validate_dataset.validate_dataset(p, require_reviewed=True,
                                                   print_summary=False)
    assert rc == 1
    assert any("reviewed" in e for e in errors)


def test_validate_dataset_blocks_answerable_no_gold(tmp_path):
    p = tmp_path / "ds.jsonl"
    case = _valid_case(answerable=True, expected="ANSWERED")  # 无 gold 同时为空
    _write_jsonl(p, [case])
    rc, errors = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("goldEvidenceIds" in e or "goldDocumentIds" in e for e in errors)


def test_validate_dataset_blocks_conflicting_answerable_final(tmp_path):
    p = tmp_path / "ds.jsonl"
    case = _valid_case(answerable=False, expected="ANSWERED")  # 矛盾
    _write_jsonl(p, [case])
    rc, errors = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("answerable=false" in e for e in errors)


def test_validate_dataset_dup_case_id(tmp_path):
    p = tmp_path / "ds.jsonl"
    _write_jsonl(p, [_valid_case("mh-001"), _valid_case("mh-001")])
    rc, errors = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("caseId 重复" in e for e in errors)


def test_evaluator_no_actuals_returns_zero_completion(tmp_path):
    ds = tmp_path / "ds.jsonl"
    _write_jsonl(ds, [_valid_case("mh-001"), _valid_case("mh-002")])
    report = run_planner_eval.evaluate(
        run_planner_eval._load_jsonl(ds), {})
    m = report["metrics"]
    assert m["datasetSize"] == 2
    assert m["completedActuals"] == 0
    assert m["missingActuals"] == 2
    assert m["finalStatusAccuracy"] == 0.0
    assert m["answerCorrectness"] is None  # NOT_EXECUTED 明确


def test_evaluator_final_status_match(tmp_path):
    ds = tmp_path / "ds.jsonl"
    _write_jsonl(ds, [_valid_case("mh-001"),
                      _valid_case("mh-002", answerable=True,
                                  expected="ANSWERED")])
    # 给 ANSWERED case 加 goldDocumentIds 避开 validator (不影响 evaluator)
    jsonl = run_planner_eval._load_jsonl(ds)
    jsonl[1]["goldDocumentIds"] = [1]
    actuals = {
        "mh-001": {"finalStatus": "REFUSED_NO_EVIDENCE", "replanCount": 0,
                   "toolCalls": 2, "llmCallsTotal": 1, "realToolCalls": 2,
                   "sseTerminalEventCount": 1},
        "mh-002": {"finalStatus": "ANSWERED", "replanCount": 0,
                   "toolCalls": 3, "llmCallsTotal": 2, "realToolCalls": 3,
                   "answerCalls": 1, "sseTerminalEventCount": 1},
    }
    report = run_planner_eval.evaluate(jsonl, actuals)
    m = report["metrics"]
    assert m["completedActuals"] == 2
    assert m["finalStatusAccuracy"] == 1.0
    assert m["avgToolCallsPerTask"] == 2.5
    assert m["avgLlmCallsPerTask"] == 1.5


def test_evaluator_false_sufficient_leak_counted(tmp_path):
    ds = tmp_path / "ds.jsonl"
    _write_jsonl(ds, [_valid_case("mh-001")])
    jsonl = run_planner_eval._load_jsonl(ds)
    actuals = {
        "mh-001": {"finalStatus": "ANSWERED",
                   "falseSufficientLeak": 1,
                   "replanCount": 0, "sseTerminalEventCount": 1,
                   "toolCalls": 1, "llmCallsTotal": 1, "realToolCalls": 1},
    }
    report = run_planner_eval.evaluate(jsonl, actuals)
    assert report["metrics"]["falseSufficientRate"] == 1.0


def test_evaluator_sse_multiple_terminal_counted(tmp_path):
    ds = tmp_path / "ds.jsonl"
    _write_jsonl(ds, [_valid_case("mh-001")])
    jsonl = run_planner_eval._load_jsonl(ds)
    actuals = {
        "mh-001": {"finalStatus": "SYSTEM_FAILED",
                   "sseTerminalEventCount": 2,  # 同时 completed+failed
                   "replanCount": 0, "toolCalls": 1, "llmCallsTotal": 1,
                   "realToolCalls": 1},
    }
    report = run_planner_eval.evaluate(jsonl, actuals)
    assert report["metrics"]["sseMultipleTerminalRate"] == 1.0


def test_aggregate_empty_input(tmp_path):
    md_path = tmp_path / "r.md"
    rep = aggregate_report.aggregate([])
    assert rep["rows"] == []
    md = aggregate_report.render_markdown(rep)
    assert "no input reports" in md
    md_path.write_text(md, encoding="utf-8")
    assert md_path.exists()


def test_aggregate_renders_table(tmp_path):
    inputs = []
    for s, fin_acc in [("A0_baseline", 0.7), ("A5_full", 0.92)]:
        path = tmp_path / f"{s}.json"
        path.write_text(json.dumps({
            "scenario": s,
            "metrics": {
                "datasetSize": 80,
                "completedActuals": 80,
                "finalStatusAccuracy": fin_acc,
                "replanAttemptRate": 0.2,
                "replanSuccessRate": 0.4,
                "falseSufficientRate": 0.01,
                "sseMultipleTerminalRate": 0.0,
                "nonTerminalResidueRate": 0.0,
                "crossTenantLeakRate": 0.0,
            }
        }), encoding="utf-8")
        inputs.append(path)
    md_path = tmp_path / "r.md"
    rc = aggregate_report.main(["--inputs", *map(str, inputs),
                                "--markdown", str(md_path)])
    assert rc == 0
    md = md_path.read_text(encoding="utf-8")
    assert "A0_baseline" in md
    assert "A5_full" in md
    assert "Ablation" in md
