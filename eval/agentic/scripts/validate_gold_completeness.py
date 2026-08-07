#!/usr/bin/env python3
"""PR-7f.2b.1: Gold Completeness Validator.

Checks whether candidate pilot cases are ready for review by verifying gold
evidence completeness beyond structural schema validation.

Usage:
    python3 eval/agentic/scripts/validate_gold_completeness.py <dataset.jsonl> [--strict]

Strict mode: also fail on FILL_ markers (non-strict = warnings, strict = errors).

Checks (existence + coverage + trajectory + forbidden):
  1. Every goldEvidence requires eid/docId/chunkId/version/contentHash non-empty
  2. Every goldEvidence.evidenceId is a hex sha256 candidate (≥12 hex chars) — not FILL_*
  3. Every goldEvidence.contentHash matches hex pattern — not FILL_*
  4. Every required Requirement has ≥1 goldEvidence binding it
  5. Every goldEvidence.bidsToRequirementIds has ≥1 entry
  6. goldCoverageByRequirement has entries for all required Requirements
  7. acceptableInitialPlans cover all required Requirement IDs (when reviewed)
  8. answerable=true → goldEvidence non-empty + goldAnswer non-empty
  9. answerable=false → goldEvidence empty (or contains only rejected)
 10. evidenceIds unique within case (no duplicated gold evidence)
 11. reviewer ≠ annotator (cross-check review block)
 12. forbiddenToolSignatures are distinct from acceptableInitialPlans sequences
 13. expectedFinalStatus consistent with slice mapping
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

SLICE_STATUS_MAP = {
    "initial_sufficient": "ANSWERED",
    "document_fetch_needed": "ANSWERED",
    "semantic_metadata_combo": "ANSWERED",
    "replan_success": "ANSWERED",
    "replan_still_insufficient": "REFUSED_NO_EVIDENCE",
    "no_answer_refuse": "REFUSED_NO_EVIDENCE",
    "permission_denied": "REFUSED_PERMISSION",
    "evidence_conflict": "REFUSED_CONFLICT",
    "budget_timeout_edge": "TIMED_OUT",
}

HEX_PATTERN = re.compile(r"^[0-9a-f]{12,64}$", re.IGNORECASE)


def check_completeness(case: dict[str, Any], strict: bool = False) -> tuple[list[str], list[str]]:
    """Return (errors, warnings). strict mode treats warnings as errors."""
    errors: list[str] = []
    warnings: list[str] = []
    cid = case.get("caseId", "<unknown>")
    is_reviewed = case.get("review", {}).get("reviewStatus") == "reviewed"

    reqs = case.get("requirements", [])
    required_ids = {r.get("requirementId") for r in reqs if r.get("required")}
    gold = case.get("gold", {})
    gold_evs = gold.get("goldEvidence", [])
    answerable = gold.get("answerable", False)

    # 1-3. Gold evidence field completeness
    for i, ev in enumerate(gold_evs):
        label = f"{cid}.goldEvidence[{i}]"
        for field in ("evidenceId", "documentId", "chunkId", "contentHash", "reviewer", "reviewedAt"):
            val = ev.get(field)
            if val is None or val == "" or (isinstance(val, str) and val.startswith("FILL_")):
                (errors if strict or is_reviewed else warnings).append(
                    f"{label}: {field} is empty or FILL_* (must fill from real chunk)"
                )
        # evidenceId hex check
        eid = ev.get("evidenceId", "")
        if eid and not eid.startswith("FILL_") and not HEX_PATTERN.match(eid):
            errors.append(f"{label}: evidenceId '{eid}' is not ≥12-char hex sha256")
        # contentHash hex check
        ch = ev.get("contentHash", "")
        if ch and not ch.startswith("FILL_") and not HEX_PATTERN.match(ch):
            errors.append(f"{label}: contentHash '{ch}' is not ≥12-char hex sha256")

    # 4. Every required Requirement must have ≥1 goldEvidence binding it
    covered_by_evidence: set[str] = set()
    for ev in gold_evs:
        covered_by_evidence.update(ev.get("bindsToRequirementIds", []))
    for rid in required_ids:
        if rid not in covered_by_evidence:
            if answerable:
                (errors if is_reviewed else warnings).append(
                    f"{cid}: required Requirement '{rid}' has no gold evidence covering it"
                )

    # 5. binds ≥1
    for i, ev in enumerate(gold_evs):
        if not ev.get("bindsToRequirementIds"):
            errors.append(f"{cid}.goldEvidence[{i}]: bindsToRequirementIds empty")

    # 6. goldCoverageByRequirement covers required
    coverage = gold.get("goldCoverageByRequirement", {})
    for rid in required_ids:
        if rid not in coverage and answerable:
            (errors if is_reviewed else warnings).append(
                f"{cid}: goldCoverageByRequirement missing required '{rid}'"
            )

    # 7. acceptableInitialPlans cover required Req IDs (only reviewed)
    if is_reviewed:
        plans = case.get("planConstraints", {}).get("acceptableInitialPlans", [])
        if plans:
            all_covered = set()
            for p in plans:
                all_covered.update(p.get("coveredReqIds", []))
            missing = required_ids - all_covered
            if missing:
                errors.append(
                    f"{cid}: acceptableInitialPlans cover {all_covered}, "
                    f"but required {missing} not covered"
                )

    # 8. answerable → evidenceusha non-empty
    if answerable and not gold_evs:
        (errors if is_reviewed else warnings).append(
            f"{cid}: answerable=true but goldEvidence is empty"
        )
    if answerable and not gold.get("goldAnswer", "").strip():
        (errors if is_reviewed else warnings).append(
            f"{cid}: answerable=true but goldAnswer is empty"
        )
    # 9. answerable=false → no gold evidence expected
    if not answerable and gold_evs:
        warnings.append(
            f"{cid}: answerable=false but goldEvidence has {len(gold_evs)} records "
            f"(usually expect empty for refuse cases)"
        )

    # 10. evidenceIds unique within case
    eids = [ev.get("evidenceId", "") for ev in gold_evs]
    seen = set()
    dups = []
    for eid in eids:
        if eid and not eid.startswith("FILL_"):
            if eid in seen:
                dups.append(eid)
            seen.add(eid)
    if dups:
        errors.append(f"{cid}: duplicate evidenceId within case: {sorted(set(dups))}")

    # 11. reviewer ≠ annotator (checked at review level)
    review = case.get("review", {})
    if is_reviewed:
        ann = review.get("annotator", "")
        rev = review.get("reviewer", "")
        if ann and rev and ann == rev:
            errors.append(f"{cid}: dual-signoff violation — annotator == reviewer ('{ann}')")

    # 12. forbidden sigs distinct from acceptable plans
    forbidden = set(case.get("planConstraints", {}).get("forbiddenToolSignatures", []))
    if forbidden:
        plan_sigs = set()
        for p in case.get("planConstraints", {}).get("acceptableInitialPlans", []):
            plan_sigs.update(p.get("toolSequence", []))
        overlap = forbidden & plan_sigs
        if overlap:
            errors.append(
                f"{cid}: forbiddenToolSignatures overlap with acceptableInitialPlans: {sorted(overlap)}"
            )

    # 13. slice ↔ status
    slice_val = case.get("slice", "")
    expected_status = case.get("expected", {}).get("expectedFinalStatus", "")
    if slice_val in SLICE_STATUS_MAP:
        if expected_status and expected_status != SLICE_STATUS_MAP[slice_val]:
            errors.append(
                f"{cid}: slice='{slice_val}' expects status={SLICE_STATUS_MAP[slice_val]} "
                f"but got '{expected_status}'"
            )

    if strict:
        errors.extend(warnings)
    return errors, (warnings if not strict else [])


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    out = []
    for ln, line in enumerate(path.open("r", encoding="utf-8"), 1):
        line = line.strip()
        if not line:
            continue
        out.append(json.loads(line))
    return out


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="PR-7f.2b.1 Gold Completeness Validator")
    p.add_argument("dataset", type=Path, help="jsonl dataset")
    p.add_argument("--strict", action="store_true",
                   help="Treat FILL_/empty as errors (not warnings). Use for final gate.")
    args = p.parse_args(argv)

    cases = load_jsonl(args.dataset)
    total_errors = 0
    total_warnings = 0
    for case in cases:
        cid = case.get("caseId", "?")
        errs, warns = check_completeness(case, strict=args.strict)
        if errs:
            print(f"ERROR  {cid}: {len(errs)} errors", file=sys.stderr)
            for e in errs:
                print(f"         - {e}", file=sys.stderr)
        if warns:
            print(f"WARN   {cid}: {len(warns)} pending fills")
            for w in warns:
                print(f"         - {w}")
        total_errors += len(errs)
        total_warnings += len(warns)

    print(f"\nSummary: {len(cases)} cases, {total_errors} errors, {total_warnings} warnings")
    if total_errors > 0:
        print(f"Result: FAIL ({'strict' if args.strict else 'structural'})")
        return 1
    if total_warnings > 0:
        print(f"Result: OK (structural) — {total_warnings} fields need manual completion")
    else:
        print("Result: COMPLETE — all gold evidence filled and ready for review")
    return 0


if __name__ == "__main__":
    sys.exit(main())
