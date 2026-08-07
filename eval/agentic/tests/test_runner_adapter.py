"""PR-7f.2b.3b: Runner adapter integration tests.

These tests exercise the runner adapters' live-mode mapping logic WITHOUT a
real Spring Boot runtime:

  * response mapping (success / no-strategy-trace / non-200 → NOT_EXECUTED)
  * unreachable runtime fallback
  * hybrid mapping
  * gold_freeze_check report on the real pilot dataset

No live LLM, no real HTTP. Tests are deterministic.
"""
from __future__ import annotations

import json
import sys
import subprocess
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = EVAL_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import agentic_runner as AR  # noqa: E402
import hybrid_runner as HR  # noqa: E402
import gold_freeze_check as GFC  # noqa: E402


CASE = {
    "caseId": "amh-test-1",
    "question": "q",
    "requirements": [{"requirementId": "REQ-1", "required": True}],
}


# ───────────── agentic response mapping ─────────────

def test_agentic_map_success_with_planned_agent_strategy():
    resp = {"answer": "A", "citations": [{"evidenceId": "ev1"}],
            "usage": {"inputTokens": 10, "outputTokens": 5},
            "sufficiencyStatus": "SUFFICIENT"}
    rec = AR._map_chat_response_to_result(
        CASE, resp, strategy="PLANNED_AGENT", latency_ms=400, http_status=200)
    assert rec["executed"] is True
    assert rec["finalStatus"] == "ANSWERED"
    assert rec["answerText"] == "A"
    assert rec["evidenceIds"] == ["ev1"]
    assert rec["tokenUsage"] == {"inputTokens": 10, "outputTokens": 5}
    assert rec["latencyMs"] == 400
    assert rec["errorMessage"] == ""
    assert rec["strategyTrace"] == "PLANNED_AGENT"
    assert rec["requirementCoverage"][0]["status"] == "COVERED"


def test_agentic_map_no_strategy_trace_blocks():
    """HTTP 200 but no strategyTrace → executed=False. Documents the
    plannedPipelineEnabled Java-side blocker; no fabrication."""
    resp = {"answer": "A", "citations": []}
    rec = AR._map_chat_response_to_result(CASE, resp, strategy=None, latency_ms=300, http_status=200)
    assert rec["executed"] is False
    assert "RUNTIME_NO_STRATEGY_TRACE" in rec["errorMessage"]
    assert rec["latencyMs"] == 0  # NOT_EXECUTED zeroes latency too
    assert rec["answerText"] == ""


def test_agentic_map_wrong_strategy_blocks():
    """HTTP 200 + strategy=CLASSIC_RAG -> not the agent path -> NOT_EXECUTED."""
    resp = {"answer": "A"}
    rec = AR._map_chat_response_to_result(CASE, resp, strategy="CLASSIC_RAG", latency_ms=300, http_status=200)
    assert rec["executed"] is False
    assert "RUNTIME_NOT_PLANNED_AGENT" in rec["errorMessage"]
    assert "CLASSIC_RAG" in rec["errorMessage"]


def test_agentic_map_non_200_blocks():
    resp = {"error": "boom"}
    rec = AR._map_chat_response_to_result(CASE, resp, strategy=None, latency_ms=50, http_status=503)
    assert rec["executed"] is False
    assert "HTTP 503" in rec["errorMessage"]
    assert rec["httpStatus"] == 503


def test_agentic_extract_strategy_finds_nested():
    # nested under "data" — snake_case (PR-7f.2c-pre 实际 Jackson 输出)
    nested = {"data": {"pipeline_type": "PLANNED_AGENT"}, "answer": "x"}
    assert AR._extract_strategy(nested) == "PLANNED_AGENT"
    # nested — legacy camelCase form 兼容
    nested_camel = {"data": {"pipelineType": "PLANNED_AGENT"}}
    assert AR._extract_strategy(nested_camel) == "PLANNED_AGENT"
    # flat snake_case / camelCase
    assert AR._extract_strategy({"execution_strategy": "CLASSIC_RAG"}) == "CLASSIC_RAG"
    assert AR._extract_strategy({"executionStrategy": "CLASSIC_RAG"}) == "CLASSIC_RAG"
    # missing
    assert AR._extract_strategy({"answer": "x"}) is None


def test_agentic_map_highlights_strategy_trace_now_visible():
    """PR-7f.2c-pre 修复 runtime gate 后, ChatResponse 自带 pipeline_type —
    Runner live mode 不再因 RUNTIME_NO_STRATEGY_TRACE 默认告NOT_EXECUTED。
    PLANNED_AGENT → executed=True; CLASSIC_RAG → RUNTIME_NOT_PLANNED_AGENT
    (区别于运行时根本没暴露策略的旧行为)。
    """
    resp = {"answer": "A", "pipeline_type": "PLANNED_AGENT",
            "citations": [{"evidenceId": "ev1"}]}
    rec = AR._map_chat_response_to_result(
        CASE, resp, strategy="PLANNED_AGENT", latency_ms=120, http_status=200)
    assert rec["executed"] is True
    assert rec["strategyTrace"] == "PLANNED_AGENT"


# ───────────── hybrid response mapping ─────────────

def test_hybrid_map_success_no_replan():
    resp = {"answer": "A", "citations": [{"chunkId": 9}],
            "usage": {"inputTokens": 4, "outputTokens": 2}}
    rec = HR._map_hybrid_response(CASE, resp, latency_ms=200, http_status=200)
    assert rec["executed"] is True
    assert rec["pipeline"] == "HYBRID_RAG"
    assert rec["replanCount"] == 0
    assert rec["evidenceIds"] == ["9"]
    assert rec["toolCalls"] == 1  # Hybrid is one retrieval
    assert rec["tokenUsage"] == {"inputTokens": 4, "outputTokens": 2}


def test_hybrid_map_non_200_blocks():
    rec = HR._map_hybrid_response(CASE, {}, latency_ms=10, http_status=500)
    assert rec["executed"] is False
    assert rec["errorMessage"].startswith("NOT_EXECUTED: HTTP 500")


# ───────────── unreachable runtime ─────────────

def test_agentic_invoke_live_unreachable_returns_not_executed():
    """Point at a port nothing listens on -> NOT_EXECUTED, no exception."""
    rec = AR.invoke_live(CASE, base_url="http://127.0.0.1:65535", timeout=2)
    assert rec["executed"] is False
    # Either RUNTIME_UNREACHABLE-equivalent or HTTP error message
    assert rec["errorMessage"].startswith("NOT_EXECUTED")


def test_hybrid_invoke_live_unreachable_returns_not_executed():
    rec = HR.invoke_live(CASE, base_url="http://127.0.0.1:65535", timeout=2)
    assert rec["executed"] is False


# ───────────── CLI stub mode ─────────────

def test_agentic_runner_cli_default_writes_stub(tmp_path: Path):
    ds = tmp_path / "ds.jsonl"
    ds.write_text(json.dumps(CASE) + "\n", encoding="utf-8")
    out = tmp_path / "out.jsonl"
    proc = subprocess.run(
        [sys.executable, str(SCRIPTS_DIR / "agentic_runner.py"),
         "--dataset", str(ds), "--out", str(out)],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    rec = json.loads(out.read_text(encoding="utf-8").strip())
    assert rec["executed"] is False
    assert rec["pipeline"] == "AGENTIC_FULL"


def test_hybrid_runner_cli_default_writes_stub(tmp_path: Path):
    ds = tmp_path / "ds.jsonl"
    ds.write_text(json.dumps(CASE) + "\n", encoding="utf-8")
    out = tmp_path / "out.jsonl"
    proc = subprocess.run(
        [sys.executable, str(SCRIPTS_DIR / "hybrid_runner.py"),
         "--dataset", str(ds), "--out", str(out)],
        capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    rec = json.loads(out.read_text(encoding="utf-8").strip())
    assert rec["executed"] is False
    assert rec["pipeline"] == "HYBRID_RAG"


# ───────────── gold_freeze_check ─────────────

def test_freeze_check_blocks_on_pilot20():
    """Real pilot20 dataset is not frozen: strict mode should BLOCK."""
    pilot_path = EVAL_ROOT / "datasets" / "agentic_v2.pilot20.jsonl"
    if not pilot_path.exists():
        return  # dataset not shipped
    report = GFC.assess(pilot_path)
    assert report.frozen is False
    assert report.candidate_cases == report.total_cases  # all 20 candidate
    assert report.fill_marker_count > 0  # ≥36 FILL_* markers
    assert any("FILL_* markers" in b for b in report.blockers)
    assert any("human dual-signoff" in b for b in report.blockers)


def test_freeze_check_helps_when_frozen(tmp_path: Path):
    """A fully-reviewed, no-FILL dataset should FROZEN=True."""
    case = {
        "schemaVersion": "v2",
        "caseId": "amh-1000",
        "question": "q", "questionType": "FACT", "intent": "FACT",
        "entities": ["x"], "filters": {},
        "requirements": [{"requirementId": "REQ-1", "type": "FACT",
                          "required": True, "description": "d",
                          "targetEntities": ["x"], "expectedFilters": {}}],
        "gold": {
            "goldAnswer": "ans",
            "goldEvidence": [{
                "evidenceId": "aabbccddeeff0011",
                "documentId": 1, "chunkId": 2,
                "documentVersion": "v1",
                "contentHash": "1122334455667788",
                "contentSnippet": "x",
                "bindsToRequirementIds": ["REQ-1"],
                "rationale": "covers", "reviewer": "bob",
                "reviewedAt": "2026-08-05T00:00:00Z",
            }],
            "goldDocumentIds": [1],
            "goldCoverageByRequirement": {"REQ-1": ["aabbccddeeff0011"]},
            "answerable": True,
        },
        "planConstraints": {
            "acceptableInitialPlans": [
                {"toolSequence": ["semantic_search"], "coveredReqIds": ["REQ-1"]}
            ],
            "acceptableReplanPlans": [], "forbiddenToolSignatures": [],
        },
        "expected": {"expectedFinalStatus": "ANSWERED", "replanExpected": False,
                     "maxSteps": 3, "maxToolCalls": 3},
        "slice": "initial_sufficient",
        "review": {"reviewStatus": "reviewed", "annotator": "alice",
                   "reviewer": "bob", "reviewedAt": "2026-08-05T00:00:00Z"},
    }
    p = tmp_path / "frozen.jsonl"
    p.write_text(json.dumps(case, ensure_ascii=False) + "\n", encoding="utf-8")
    report = GFC.assess(p)
    assert report.frozen is True, f"expected FROZEN, got blockers: {report.blockers}"
    assert report.fill_marker_count == 0
    assert report.candidate_cases == 0
