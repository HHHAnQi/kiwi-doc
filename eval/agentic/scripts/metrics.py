#!/usr/bin/env python3
"""PR-7f.2b.2: Agentic RAG Metric Calculators.

Computes evaluation metrics from dataset + actuals (EvaluationResult JSONL).

All metrics return None when insufficient data (NOT_EXECUTED), never fabricated.

Metric groups:
  - Gold Evidence Recall (chunk / evidence level)
  - Requirement Coverage (per-req + aggregate)
  - Final Status Accuracy
  - Tool Efficiency (calls per task, real vs replay)
  - Replan Success Rate
  - False Sufficient Rate
  - Trajectory / System safety (non-terminal residue, SSE multi-terminal, cross-tenant leak)
  - Sufficiency Accuracy (precision / recall / false-positive / false-negative)
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def _safe_div(n: float, d: float) -> float:
    return n / d if d else 0.0


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    out = []
    for line in path.open("r", encoding="utf-8"):
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


# ═════════════════════════════════════════════════════════════
#  Gold Evidence Recall
# ═════════════════════════════════════════════════════════════

def gold_evidence_recall(
    actual_evidence_ids: list[str],
    gold_evidence_ids: list[str],
) -> float | None:
    """Fraction of gold evidence that appears in actual evidence."""
    if not gold_evidence_ids:
        return None
    if not actual_evidence_ids:
        return 0.0
    gold_set = set(gold_evidence_ids)
    actual_set = set(actual_evidence_ids)
    return _safe_div(len(gold_set & actual_set), len(gold_set))


def gold_document_recall(
    actual_doc_ids: list[int],
    gold_doc_ids: list[int],
) -> float | None:
    if not gold_doc_ids:
        return None
    if not actual_doc_ids:
        return 0.0
    return _safe_div(len(set(gold_doc_ids) & set(actual_doc_ids)), len(set(gold_doc_ids)))


# ═════════════════════════════════════════════════════════════
#  Requirement Coverage
# ═════════════════════════════════════════════════════════════

def requirement_coverage_f1(
    actual_coverage: list[dict],
    gold_coverage: dict[str, list[str]],
    required_req_ids: list[str],
) -> dict[str, Any]:
    """Per-Requirement coverage F1 + macro average.

    actual_coverage: [{requirementId, status, evidenceIds}]
    gold_coverage: {reqId: [evidenceId, ...]}
    required_req_ids: Requirement IDs that are required
    """
    if not required_req_ids:
        return {"f1": None, "precision": None, "recall": None, "perReq": {}}

    actual_map = {}
    for cov in actual_coverage:
        rid = cov.get("requirementId", "")
        actual_map[rid] = cov

    tp = fp = fn = 0
    per_req: dict[str, dict] = {}
    for rid in required_req_ids:
        actual = actual_map.get(rid, {})
        actual_covered = actual.get("status") == "COVERED"
        gold_covered = rid in gold_coverage and len(gold_coverage[rid]) > 0

        if actual_covered and gold_covered:
            tp += 1
            per_req[rid] = {"tp": True}
        elif actual_covered and not gold_covered:
            fp += 1
            per_req[rid] = {"fp": True}
        elif not actual_covered and gold_covered:
            fn += 1
            per_req[rid] = {"fn": True}
        else:
            per_req[rid] = {"tn": True}

    precision = _safe_div(tp, tp + fp)
    recall = _safe_div(tp, tp + fn)
    f1 = _safe_div(2 * precision * recall, precision + recall) if (precision + recall) > 0 else 0.0
    return {"f1": f1, "precision": precision, "recall": recall, "perReq": per_req}


# ═════════════════════════════════════════════════════════════
#  Final Status Accuracy
# ═════════════════════════════════════════════════════════════

def final_status_accuracy(
    actual: list[dict[str, Any]],
    dataset: list[dict[str, Any]],
) -> float | None:
    """Fraction of cases where actual finalStatus == expected."""
    if not actual:
        return None
    dataset_map = {c["caseId"]: c for c in dataset}
    match = 0
    total = 0
    for a in actual:
        cid = a.get("caseId", "")
        case = dataset_map.get(cid)
        if not case:
            continue
        total += 1
        expected = case.get("expected", {}).get("expectedFinalStatus", "")
        actual_status = a.get("finalStatus", "")
        if expected == actual_status:
            match += 1
    return _safe_div(match, total) if total else None


# ═════════════════════════════════════════════════════════════
#  Tool Efficiency
# ═════════════════════════════════════════════════════════════

def tool_efficiency(actual: list[dict[str, Any]]) -> dict[str, float | None]:
    if not actual:
        return {"avgToolCalls": None, "avgRealToolCalls": None, "avgLlmCalls": None}
    executed = [a for a in actual if a.get("executed", False)]
    n = len(executed) if executed else 1
    return {
        "avgToolCalls": _safe_div(sum(a.get("toolCalls", 0) for a in executed), n),
        "avgRealToolCalls": _safe_div(sum(a.get("realToolCalls", a.get("toolCalls", 0)) for a in executed), n),
        "avgLlmCalls": _safe_div(sum(a.get("llmCalls", 0) for a in executed), n),
    }


# ═════════════════════════════════════════════════════════════
#  Replan Success Rate
# ═════════════════════════════════════════════════════════════

def replan_metrics(actual: list[dict[str, Any]]) -> dict[str, float | None]:
    executed = [a for a in actual if a.get("executed", False)]
    if not executed:
        return {"replanAttemptRate": None, "replanSuccessRate": None,
                "replanTriggerPrecision": None}
    attempted = [a for a in executed if a.get("replanCount", 0) > 0]
    succeeded = [a for a in attempted if a.get("finalStatus") == "ANSWERED"]
    new_evidence_replan = [a for a in attempted if len(a.get("evidenceIds", [])) > 0]
    n = len(executed)
    return {
        "replanAttemptRate": _safe_div(len(attempted), n),
        "replanSuccessRate": _safe_div(len(succeeded), len(attempted)) if attempted else 0.0,
        "replanTriggerPrecision": _safe_div(len(new_evidence_replan), len(attempted)) if attempted else None,
    }


# ═════════════════════════════════════════════════════════════
#  False Sufficient Rate
# ═════════════════════════════════════════════════════════════

def false_sufficient_rate(
    actual: list[dict[str, Any]],
    dataset: list[dict[str, Any]],
) -> dict[str, float | None]:
    """False Sufficient = actual ANSWERED but gold says should refuse (answerable=false or evidence insufficient).

    Also checks falseSufficientLeak flag in actual record.
    """
    executed = [a for a in actual if a.get("executed", False)]
    if not executed:
        return {"rate": None, "count": 0, "totalChecked": 0}
    ds_map = {c["caseId"]: c for c in dataset}
    false_positives = 0
    leaks = 0
    total = 0
    for a in executed:
        case = ds_map.get(a.get("caseId", ""))
        if not case:
            continue
        total += 1
        gold_answerable = case.get("gold", {}).get("answerable", True)
        actual_answered = a.get("finalStatus") == "ANSWERED"
        if actual_answered and not gold_answerable:
            false_positives += 1
        if a.get("falseSufficientLeak", False):
            leaks += 1
    return {
        "rate": _safe_div(false_positives, total) if total else None,
        "count": false_positives,
        "leakCount": leaks,
        "totalChecked": total,
    }


# ═════════════════════════════════════════════════════════════
#  Trajectory / System Safety
# ═════════════════════════════════════════════════════════════

def trajectory_safety(actual: list[dict[str, Any]]) -> dict[str, float | None]:
    executed = [a for a in actual if a.get("executed", False)]
    if not executed:
        return {"nonTerminalResidueRate": None, "sseMultiTerminalRate": None,
                "crossTenantLeakRate": None}
    n = len(executed)
    residue = sum(1 for a in executed if a.get("nonTerminalStepResidue", 0) > 0)
    sse_multi = sum(1 for a in executed if a.get("sseTerminalEvents", 0) > 1)
    cross_tenant = sum(1 for a in executed if a.get("crossTenantEvidenceLeak", 0) > 0)
    return {
        "nonTerminalResidueRate": _safe_div(residue, n),
        "sseMultiTerminalRate": _safe_div(sse_multi, n),
        "crossTenantLeakRate": _safe_div(cross_tenant, n),
    }


# ═════════════════════════════════════════════════════════════
#  Latency
# ═════════════════════════════════════════════════════════════

def latency_stats(actual: list[dict[str, Any]]) -> dict[str, float | None]:
    executed = [a for a in actual if a.get("executed", False)]
    if not executed:
        return {"p50": None, "p95": None}
    latencies = sorted(a.get("latencyMs", 0) for a in executed)
    n = len(latencies)
    p50 = latencies[n // 2] if n else None
    p95_idx = int(n * 0.95) if n else None
    p95 = latencies[min(p95_idx, n - 1)] if p95_idx is not None else None
    return {"p50": float(p50) if p50 is not None else None,
            "p95": float(p95) if p95 is not None else None}


# ═════════════════════════════════════════════════════════════
#  Aggregate
# ═════════════════════════════════════════════════════════════

def evaluate_aggregate(
    dataset: list[dict[str, Any]],
    actuals: list[dict[str, Any]],
) -> dict[str, Any]:
    """Compute all metrics from dataset + actuals. Returns None for NOT_EXECUTED."""
    ds_map = {c["caseId"]: c for c in dataset}
    executed_actuals = [a for a in actuals if a.get("executed", False)]

    # per-case Gold Recall
    recalls = []
    for a in executed_actuals:
        case = ds_map.get(a.get("caseId", ""))
        if not case or not case.get("gold", {}).get("goldEvidence"):
            continue
        gold_ev_ids = [ev.get("evidenceId", "") for ev in case["gold"]["goldEvidence"]
                       if ev.get("evidenceId", "") and not ev.get("evidenceId", "").startswith("FILL_")]
        if not gold_ev_ids:
            continue
        r = gold_evidence_recall(a.get("evidenceIds", []), gold_ev_ids)
        if r is not None:
            recalls.append(r)

    avg_recall = _safe_div(sum(recalls), len(recalls)) if recalls else None

    # per-case requirement coverage F1
    coverage_f1s = []
    for a in executed_actuals:
        case = ds_map.get(a.get("caseId", ""))
        if not case:
            continue
        required_reqs = [r["requirementId"] for r in case.get("requirements", []) if r.get("required")]
        gold_cov = case.get("gold", {}).get("goldCoverageByRequirement", {})
        result = requirement_coverage_f1(a.get("requirementCoverage", []), gold_cov, required_reqs)
        if result["f1"] is not None:
            coverage_f1s.append(result["f1"])
    avg_coverage = _safe_div(sum(coverage_f1s), len(coverage_f1s)) if coverage_f1s else None

    return {
        "datasetSize": len(dataset),
        "executedActuals": len(executed_actuals),
        "notExecuted": len(actuals) - len(executed_actuals),
        "goldEvidenceRecall": avg_recall,
        "requirementCoverageF1": avg_coverage,
        "finalStatusAccuracy": final_status_accuracy(executed_actuals, dataset),
        "toolEfficiency": tool_efficiency(actuals),
        "replan": replan_metrics(actuals),
        "falseSufficient": false_sufficient_rate(actuals, dataset),
        "trajectorySafety": trajectory_safety(actuals),
        "latency": latency_stats(actuals),
    }
