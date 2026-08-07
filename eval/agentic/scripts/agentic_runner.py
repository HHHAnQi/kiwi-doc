#!/usr/bin/env python3
"""PR-7f.2b.2: Agentic RAG Runner.

Loads a dataset (default: agentic_v2.pilot20.jsonl) and produces an
EvaluationResult JSONL file with ONE record per case.

In this first PR the runner is a *stub*: it emits a NOT_EXECUTED record
(executed=false) for every case. This preserves the contract that the
metrics layer never fabricates numbers: every metric function returns
None when no actuals are present.

The actual invocation of the Java PlannedAgentPipeline (via a subprocess
bootstrapping `platform-bootstrap` with the AGENTIC feature flag) is
deferred to a later PR once:

  * the gold dataset is fully reviewed (PR-7f.2b.3 removes FILL_ markers)
  * the runtime is unfrozen for harness wiring (currently the runtime is
    sealed after PR-7c)

When the runner becomes live it must:
  1. call `./gradlew :platform-bootstrap:bootRun` (or a Spring Boot
     launcher) with `--planner.mode=AGENTIC --sufficiency.enabled=true`
  2. POST the case question to the agent SSE endpoint
  3. collect the terminal SSE event + trajectory traces
  4. map the trajectory to the EvaluationResult schema

Usage:
  python3 eval/agentic/scripts/agentic_runner.py \\
      --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \\
      --out eval/agentic/results/agentic_stub.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Make sibling modules importable when executed as a script.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import load_jsonl  # noqa: E402

SCHEMA_PATH = Path(__file__).resolve().parents[1] / "schemas" / "evaluation_result.schema.json"
PIPELINE = "AGENTIC_FULL"


def build_not_executed_record(case: dict[str, Any]) -> dict[str, Any]:
    """Build a NOT_EXECUTED EvaluationResult for a single dataset case.

    Every numeric field is zero, every list is empty, `executed` is False.
    This guarantees metrics functions return None (NOT_EXECUTED) rather
    than fabricating favourable numbers.
    """
    required_reqs = [r["requirementId"] for r in case.get("requirements", [])]
    return {
        "caseId": case.get("caseId", ""),
        "pipeline": PIPELINE,
        "finalStatus": "SYSTEM_FAILED",  # placeholder; metrics ignore NOT_EXECUTED
        "evidenceIds": [],
        "requirementCoverage": [
            {"requirementId": rid, "status": "NOT_COVERED", "evidenceIds": []}
            for rid in required_reqs
        ],
        "toolCalls": 0,
        "realToolCalls": 0,
        "llmCalls": 0,
        "replanCount": 0,
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
        "errorMessage": "NOT_EXECUTED: stub runner; live pipeline wiring deferred",
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
    print(f"[agentic_runner] wrote {n} NOT_EXECUTED records -> {out_path}")
    return n


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Agentic RAG evaluation runner (stub)")
    p.add_argument(
        "--dataset",
        default="eval/agentic/datasets/agentic_v2.pilot20.jsonl",
        type=Path,
    )
    p.add_argument(
        "--out",
        default="eval/agentic/results/agentic_stub.jsonl",
        type=Path,
    )
    args = p.parse_args(argv)
    run(args.dataset.resolve(), args.out.resolve())
    return 0


if __name__ == "__main__":
    sys.exit(main())
