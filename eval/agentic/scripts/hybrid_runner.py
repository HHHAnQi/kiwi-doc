#!/usr/bin/env python3
"""PR-7f.2b.2: Hybrid RAG Baseline Runner.

Loads the same gold dataset and emits EvaluationResult JSONL records
tagged `pipeline=HYBRID_RAG`. As with agentic_runner.py this is a stub
in the first PR: every record is NOT_EXECUTED so metrics remain None.

The live version of this runner will:
  1. invoke the existing Hybrid RAG pipeline (the legacy pipeline guarded
     behind the planned_agent feature flag when the flag is OFF)
  2. capture: retrieved chunks (as evidenceIds), final answer, latency,
     token usage, tool/retriever calls
  3. synthesise a requirementCoverage block by reusing the same
     RuleTemplateRequirementExtractor used by the Hybrid pipeline
  4. write the record in the SAME schema as the agentic runner so that
     metrics.compare_baselines(dataset, agentic_actuals, hybrid_actuals)
     can produce a head-to-head table.

Usage:
  python3 eval/agentic/scripts/hybrid_runner.py \\
      --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \\
      --out eval/agentic/results/hybrid_stub.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import load_jsonl  # noqa: E402

PIPELINE = "HYBRID_RAG"


def build_not_executed_record(case: dict[str, Any]) -> dict[str, Any]:
    """Build a NOT_EXECUTED EvaluationResult record tagged HYBRID_RAG.

    Same shape as agentic_runner.build_not_executed_record so that
    downstream metrics work uniformly across pipelines.
    """
    required_reqs = [r["requirementId"] for r in case.get("requirements", [])]
    return {
        "caseId": case.get("caseId", ""),
        "pipeline": PIPELINE,
        "finalStatus": "SYSTEM_FAILED",  # placeholder; ignored because executed=false
        "evidenceIds": [],
        "requirementCoverage": [
            {"requirementId": rid, "status": "NOT_COVERED", "evidenceIds": []}
            for rid in required_reqs
        ],
        "toolCalls": 0,
        "realToolCalls": 0,
        "llmCalls": 0,
        "replanCount": 0,  # Hybrid never replans
        "latencyMs": 0,
        "tokenUsage": {"inputTokens": 0, "outputTokens": 0},
        "answerText": "",
        "citedEvidenceIds": [],
        "guardRejections": 0,
        "falseSufficientLeak": False,
        "sufficiencyStatus": "NOT_RUN",
        "sseTerminalEvents": 0,
        "nonTerminalStepResidue": 0,
        "crossTenantEvidenceLeak": 0,
        "executedToolSignatures": [],
        "errorMessage": "NOT_EXECUTED: stub runner; Hybrid pipeline wiring deferred",
        "executed": False,
        "stubbedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }


def run(dataset_path: Path, out_path: Path) -> int:
    if not dataset_path.exists():
        raise FileNotFoundError(f"dataset not found: {dataset_path}")
    cases = load_jsonl(dataset_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    n = 0
    with out_path.open("w", encoding="utf-8") as fh:
        for case in cases:
            rec = build_not_executed_record(case)
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            n += 1
    print(f"[hybrid_runner] wrote {n} NOT_EXECUTED records -> {out_path}")
    return n


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Hybrid RAG evaluation baseline runner (stub)")
    p.add_argument(
        "--dataset",
        default="eval/agentic/datasets/agentic_v2.pilot20.jsonl",
        type=Path,
    )
    p.add_argument(
        "--out",
        default="eval/agentic/results/hybrid_stub.jsonl",
        type=Path,
    )
    args = p.parse_args(argv)
    run(args.dataset.resolve(), args.out.resolve())
    return 0


if __name__ == "__main__":
    sys.exit(main())
