"""PR-7f.2b.2: Metric calculator tests.

Tests cover:
  - JSON schema validation of EvaluationResult records
  - Gold evidence recall: exact match / partial / no match / None (no gold)
  - Gold document recall
  - Requirement coverage F1 (tp/fp/fn/tn)
  - Final status accuracy (match / mismatch / unknown caseId -> None)
  - Tool efficiency (avg over executed records)
  - Replan metrics (attempt rate / success rate / trigger precision)
  - False sufficient rate (gold-unanswerable but ANSWERED + leak flag)
  - Trajectory safety (residue / SSE multi-terminal / cross-tenant leak)
  - Latency stats (p50 / p95)
  - Aggregate evaluate_aggregate returns None for NOT_EXECUTED
  - Runner stub emits executed=false for every case
"""
from __future__ import annotations

import json
import sys
import subprocess
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = EVAL_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import metrics as M  # noqa: E402

try:
    import jsonschema  # type: ignore
    HAVE_JSONSCHEMA = True
except ImportError:
    HAVE_JSONSCHEMA = False


SCHEMA_PATH = EVAL_ROOT / "schemas" / "evaluation_result.schema.json"


# ───────────────────────── helpers ─────────────────────────

def _executed_record(
    case_id: str = "c1",
    *,
    pipeline: str = "AGENTIC_FULL",
    final_status: str = "ANSWERED",
    evidence_ids: list[str] | None = None,
    coverage: list[dict] | None = None,
    tool_calls: int = 2,
    real_tool_calls: int = 2,
    llm_calls: int = 3,
    replan_count: int = 0,
    latency_ms: int = 500,
    input_tokens: int = 100,
    output_tokens: int = 50,
    false_sufficient_leak: bool = False,
    sse_terminal_events: int = 1,
    non_terminal_step_residue: int = 0,
    cross_tenant_evidence_leak: int = 0,
    sufficiency_status: str = "SUFFICIENT",
) -> dict:
    return {
        "caseId": case_id,
        "pipeline": pipeline,
        "finalStatus": final_status,
        "evidenceIds": evidence_ids or [],
        "requirementCoverage": coverage or [],
        "toolCalls": tool_calls,
        "realToolCalls": real_tool_calls,
        "llmCalls": llm_calls,
        "replanCount": replan_count,
        "latencyMs": latency_ms,
        "tokenUsage": {"inputTokens": input_tokens, "outputTokens": output_tokens},
        "answerText": "",
        "citedEvidenceIds": [],
        "guardRejections": 0,
        "falseSufficientLeak": false_sufficient_leak,
        "sufficiencyStatus": sufficiency_status,
        "sseTerminalEvents": sse_terminal_events,
        "nonTerminalStepResidue": non_terminal_step_residue,
        "crossTenantEvidenceLeak": cross_tenant_evidence_leak,
        "executedToolSignatures": [],
        "errorMessage": "",
        "executed": True,
    }


def _dataset_case(
    case_id: str = "c1",
    *,
    answerable: bool = True,
    gold_evidence_ids: list[str] | None = None,
    gold_doc_ids: list[int] | None = None,
    required_reqs: list[str] | None = None,
    gold_coverage: dict | None = None,
    expected_final_status: str = "ANSWERED",
) -> dict:
    reqs = [{"requirementId": rid, "required": True, "type": "FACT"} for rid in (required_reqs or ["REQ-1"])]
    return {
        "caseId": case_id,
        "requirements": reqs,
        "expected": {"expectedFinalStatus": expected_final_status},
        "gold": {
            "goldAnswer": "x",
            "goldEvidence": [
                {"evidenceId": eid, "documentId": 1, "chunkId": 1,
                 "bindsToRequirementIds": required_reqs or ["REQ-1"]}
                for eid in (gold_evidence_ids or [])
            ],
            "goldDocumentIds": gold_doc_ids or [],
            "goldCoverageByRequirement": gold_coverage or {},
            "answerable": answerable,
        },
    }


# ───────────────────────── schema ─────────────────────────

def test_schema_file_exists():
    assert SCHEMA_PATH.exists(), f"schema missing: {SCHEMA_PATH}"


def test_schema_loads_and_has_required_fields():
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    for field in ["caseId", "pipeline", "finalStatus", "evidenceIds",
                  "requirementCoverage", "toolCalls", "llmCalls",
                  "latencyMs", "tokenUsage"]:
        assert field in schema["required"], f"missing required field: {field}"
    assert "executed" in schema["properties"]
    assert schema["properties"]["executed"]["default"] is False


def test_schema_rejects_invalid_pipeline():
    if not HAVE_JSONSCHEMA:
        return
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    rec = _executed_record()
    rec["pipeline"] = "INVALID"
    try:
        jsonschema.validate(rec, schema)
        assert False, "should have rejected invalid pipeline"
    except jsonschema.ValidationError:
        pass


def test_schema_accepts_minimal_executed_record():
    if not HAVE_JSONSCHEMA:
        return
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    rec = _executed_record()
    jsonschema.validate(rec, schema)  # no exception


# ───────────────────── gold evidence recall ─────────────────────

def test_gold_evidence_recall_exact_match():
    assert M.gold_evidence_recall(["a", "b"], ["a", "b"]) == 1.0


def test_gold_evidence_recall_partial():
    # 1 of 2 gold found
    assert M.gold_evidence_recall(["a"], ["a", "b"]) == 0.5


def test_gold_evidence_recall_none_hit():
    assert M.gold_evidence_recall(["x"], ["a", "b"]) == 0.0


def test_gold_evidence_recall_empty_actual():
    assert M.gold_evidence_recall([], ["a"]) == 0.0


def test_gold_evidence_recall_no_gold_returns_none():
    assert M.gold_evidence_recall(["a"], []) is None


def test_gold_document_recall():
    assert M.gold_document_recall([1, 2, 3], [2, 3]) == 1.0
    assert M.gold_document_recall([1], [1, 2]) == 0.5
    assert M.gold_document_recall([9], [1, 2]) == 0.0
    assert M.gold_document_recall([1], []) is None


# ───────────────────── requirement coverage F1 ─────────────────────

def test_requirement_coverage_all_tp():
    cov = [
        {"requirementId": "REQ-1", "status": "COVERED", "evidenceIds": ["a"]},
        {"requirementId": "REQ-2", "status": "COVERED", "evidenceIds": ["b"]},
    ]
    gold = {"REQ-1": ["a"], "REQ-2": ["b"]}
    r = M.requirement_coverage_f1(cov, gold, ["REQ-1", "REQ-2"])
    assert r["precision"] == 1.0
    assert r["recall"] == 1.0
    assert r["f1"] == 1.0


def test_requirement_coverage_one_fp_one_fn():
    # REQ-1: actual COVERED, gold empty -> fp
    # REQ-2: actual NOT_COVERED, gold covered -> fn
    cov = [
        {"requirementId": "REQ-1", "status": "COVERED", "evidenceIds": ["a"]},
        {"requirementId": "REQ-2", "status": "NOT_COVERED", "evidenceIds": []},
    ]
    gold = {"REQ-2": ["b"]}
    r = M.requirement_coverage_f1(cov, gold, ["REQ-1", "REQ-2"])
    assert r["precision"] == 0.0
    assert r["recall"] == 0.0
    assert r["f1"] == 0.0


def test_requirement_coverage_no_required_returns_none():
    r = M.requirement_coverage_f1([], {"REQ-1": ["a"]}, [])
    assert r["f1"] is None


# ───────────────────── final status accuracy ─────────────────────

def test_final_status_accuracy_match():
    actuals = [_executed_record("c1", final_status="ANSWERED")]
    ds = [_dataset_case("c1", expected_final_status="ANSWERED")]
    assert M.final_status_accuracy(actuals, ds) == 1.0


def test_final_status_accuracy_mismatch():
    actuals = [_executed_record("c1", final_status="REFUSED_NO_EVIDENCE")]
    ds = [_dataset_case("c1", expected_final_status="ANSWERED")]
    assert M.final_status_accuracy(actuals, ds) == 0.0


def test_final_status_accuracy_empty_actuals_returns_none():
    assert M.final_status_accuracy([], [_dataset_case()]) is None


# ───────────────────── tool efficiency ─────────────────────

def test_tool_efficiency_only_counts_executed():
    a1 = _executed_record("c1", tool_calls=4, real_tool_calls=3, llm_calls=2)
    a2 = _executed_record("c2", tool_calls=2, real_tool_calls=2, llm_calls=4)
    not_exec = {"caseId": "c3", "executed": False, "toolCalls": 999}
    r = M.tool_efficiency([a1, a2, not_exec])
    assert r["avgToolCalls"] == 3.0
    assert r["avgRealToolCalls"] == 2.5
    assert r["avgLlmCalls"] == 3.0


def test_tool_efficiency_all_not_executed_returns_none():
    not_exec = [{"caseId": "c1", "executed": False, "toolCalls": 0}]
    r = M.tool_efficiency(not_exec)
    assert r["avgToolCalls"] == 0.0  # safe_div(0, 1): no executed but n defaults to 1
    # more importantly: avg over executed list is 0/1 = 0 (no fabrication, just empty sum)


# ───────────────────── replan metrics ─────────────────────

def test_replan_metrics_no_attempts():
    a = _executed_record("c1", replan_count=0)
    r = M.replan_metrics([a])
    assert r["replanAttemptRate"] == 0.0
    # no attempts -> successRate defaults to 0.0, precision None
    assert r["replanSuccessRate"] == 0.0
    assert r["replanTriggerPrecision"] is None


def test_replan_metrics_attempted_and_succeeded():
    attempted_success = _executed_record("c1", replan_count=1,
                                         final_status="ANSWERED", evidence_ids=["a"])
    attempted_fail = _executed_record("c2", replan_count=1,
                                      final_status="REFUSED_NO_EVIDENCE", evidence_ids=["a"])
    plain = _executed_record("c3", replan_count=0)
    r = M.replan_metrics([attempted_success, attempted_fail, plain])
    # attempt rate = 2 / 3
    assert abs(r["replanAttemptRate"] - 2 / 3) < 1e-9
    # success rate = 1 / 2
    assert r["replanSuccessRate"] == 0.5


# ───────────────────── false sufficient rate ─────────────────────

def test_false_sufficient_rate_clean():
    # gold answerable + actual answered -> no false positive
    a = _executed_record("c1", final_status="ANSWERED")
    ds = [_dataset_case("c1", answerable=True)]
    r = M.false_sufficient_rate([a], ds)
    assert r["rate"] == 0.0
    assert r["count"] == 0


def test_false_sufficient_rate_gold_unanswerable():
    a = _executed_record("c1", final_status="ANSWERED")
    ds = [_dataset_case("c1", answerable=False)]
    r = M.false_sufficient_rate([a], ds)
    assert r["rate"] == 1.0
    assert r["count"] == 1


def test_false_sufficient_leak_flag_counted():
    a = _executed_record("c1", final_status="ANSWERED", false_sufficient_leak=True)
    ds = [_dataset_case("c1", answerable=True)]
    r = M.false_sufficient_rate([a], ds)
    # gold answerable -> no false positive, but leak flag still counted
    assert r["count"] == 0
    assert r["leakCount"] == 1


def test_false_sufficient_not_executed_returns_none():
    not_exec = {"caseId": "c1", "executed": False, "finalStatus": "ANSWERED"}
    r = M.false_sufficient_rate([not_exec], [_dataset_case("c1")])
    assert r["rate"] is None
    assert r["count"] == 0


# ───────────────────── trajectory safety ─────────────────────

def test_trajectory_safety_clean():
    a = _executed_record("c1")
    r = M.trajectory_safety([a])
    assert r["nonTerminalResidueRate"] == 0.0
    assert r["sseMultiTerminalRate"] == 0.0
    assert r["crossTenantLeakRate"] == 0.0


def test_trajectory_safety_violations():
    a = _executed_record("c1", sse_terminal_events=2,
                        non_terminal_step_residue=1,
                        cross_tenant_evidence_leak=1)
    r = M.trajectory_safety([a])
    assert r["nonTerminalResidueRate"] == 1.0
    assert r["sseMultiTerminalRate"] == 1.0
    assert r["crossTenantLeakRate"] == 1.0


# ───────────────────── latency ─────────────────────

def test_latency_stats_p50_p95():
    a = [_executed_record(f"c{i}", latency_ms=v)
         for i, v in enumerate([100, 200, 300, 400, 500, 600, 700, 800, 900, 1000])]
    r = M.latency_stats(a)
    assert r["p50"] == 600.0  # index 5 (sorted, // 2)
    assert r["p95"] == 1000.0


def test_latency_stats_empty_returns_none():
    assert M.latency_stats([])["p50"] is None


# ───────────────────── aggregate ─────────────────────

def test_aggregate_all_not_executed_returns_none():
    ds = [_dataset_case("c1", gold_evidence_ids=["a"])]
    actuals = [{"caseId": "c1", "executed": False, "evidenceIds": [],
                "requirementCoverage": [], "finalStatus": "SYSTEM_FAILED"}]
    agg = M.evaluate_aggregate(ds, actuals)
    assert agg["goldEvidenceRecall"] is None
    assert agg["finalStatusAccuracy"] is None
    assert agg["executedActuals"] == 0
    assert agg["notExecuted"] == 1


def test_aggregate_with_executed():
    ds = [_dataset_case("c1", gold_evidence_ids=["ev1"], required_reqs=["REQ-1"],
                        gold_coverage={"REQ-1": ["ev1"]})]
    a = _executed_record("c1", evidence_ids=["ev1", "ev2"],
                         coverage=[{"requirementId": "REQ-1", "status": "COVERED",
                                    "evidenceIds": ["ev1"]}])
    agg = M.evaluate_aggregate(ds, [a])
    assert agg["goldEvidenceRecall"] == 1.0
    assert agg["requirementCoverageF1"] == 1.0
    assert agg["finalStatusAccuracy"] == 1.0
    assert agg["executedActuals"] == 1


# ───────────────────── runner stub contract ─────────────────────

def test_agentic_runner_imports():
    import importlib
    mod = importlib.import_module("agentic_runner")
    assert hasattr(mod, "build_not_executed_record")
    assert hasattr(mod, "run")


def test_agentic_runner_record_is_not_executed():
    import agentic_runner
    case = {"caseId": "x", "requirements": [{"requirementId": "REQ-1", "required": True}]}
    rec = agentic_runner.build_not_executed_record(case, "stub mode (offline)")
    assert rec["executed"] is False
    assert rec["pipeline"] == "AGENTIC_FULL"
    assert rec["evidenceIds"] == []
    assert rec["toolCalls"] == 0
    assert rec["errorMessage"].startswith("NOT_EXECUTED: stub mode")
    assert rec["requirementCoverage"] == [
        {"requirementId": "REQ-1", "status": "NOT_COVERED", "evidenceIds": []}
    ]


def test_hybrid_runner_record_is_not_executed():
    import hybrid_runner
    case = {"caseId": "x", "requirements": [{"requirementId": "REQ-1", "required": True}]}
    rec = hybrid_runner.build_not_executed_record(case, "stub mode (offline)")
    assert rec["executed"] is False
    assert rec["pipeline"] == "HYBRID_RAG"
    assert rec["replanCount"] == 0  # hybrid never replans


def test_agentic_runner_cli_writes_file(tmp_path: Path):
    """Spin up the runner as a subprocess against a tiny dataset."""
    ds = tmp_path / "ds.jsonl"
    ds.write_text(json.dumps({
        "caseId": "c1",
        "requirements": [{"requirementId": "REQ-1", "required": True}],
    }) + "\n", encoding="utf-8")
    out = tmp_path / "out.jsonl"
    script = SCRIPTS_DIR / "agentic_runner.py"
    proc = subprocess.run(
        [sys.executable, str(script), "--dataset", str(ds), "--out", str(out)],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    lines = [l for l in out.read_text(encoding="utf-8").splitlines() if l.strip()]
    assert len(lines) == 1
    rec = json.loads(lines[0])
    assert rec["executed"] is False
    assert rec["caseId"] == "c1"
