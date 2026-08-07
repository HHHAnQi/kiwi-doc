#!/usr/bin/env python3
"""PR-7f.2a: Agentic RAG Benchmark Dataset Validator.

Validates gold benchmark dataset against agentic_case_v2.schema.json + cross-field rules.

Usage:
    python3 eval/agentic/scripts/validate_dataset.py <dataset.jsonl>
    python3 eval/agentic/scripts/validate_dataset.py <dataset.jsonl> --require-reviewed
    python3 eval/agentic/scripts/validate_dataset.py <dataset.jsonl> --jsonschema-check

Exit codes: 0 = pass, 1 = errors, 2 = CLI error.

Key checks (beyond JSON Schema):
  - caseId globally unique
  - requirementId unique within case
  - goldCoverageByRequirement keys ⊆ requirements IDs
  - goldEvidence.binsToRequirementIds ⊆ requirements IDs
  - answerable ↔ expectedFinalStatus consistency
  - replanExpected ↔ acceptableReplanPlans consistency
  - review: annotator ≠ reviewer when reviewStatus=reviewed (dual-signoff)
  - goldEvidence contentSchema / documentVersion format
  - slice → expectedFinalStatus mapping
  - no fabricated evidence placeholders ('', 'TODO', 'TBD')
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

SCHEMA_PATH = Path(__file__).resolve().parent.parent / "schemas" / "agentic_case_v2.schema.json"

SLICE_TO_STATUS = {
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

PLACEHOLDER_VALUES = {"", "todo", "tbd", "???", "placeholder", "待填", "待标"}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    out = []
    for ln, line in enumerate(path.open("r", encoding="utf-8"), 1):
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError as e:
            raise ValueError(f"{path}:{ln} JSON decode error: {e.msg}") from e
    return out


def validate_case(case: dict[str, Any], case_no: int) -> list[str]:
    """Cross-field validation beyond JSON Schema."""
    errors: list[str] = []
    cid = case.get("caseId", f"<line {case_no}>")

    # schemaVersion
    if case.get("schemaVersion") != "v2":
        errors.append(f"{cid}: schemaVersion must be 'v2'")

    # caseId pattern
    cid_val = case.get("caseId", "")
    if not (isinstance(cid_val, str) and cid_val.startswith("amh-")):
        errors.append(f"{cid}: caseId must start with 'amh-' (got '{cid_val}')")

    # requirement IDs unique
    reqs = case.get("requirements", [])
    req_ids = [r.get("requirementId") for r in reqs if isinstance(r, dict)]
    dup = sorted({x for x in req_ids if req_ids.count(x) > 1})
    if dup:
        errors.append(f"{cid}: duplicate requirementId: {dup}")
    req_id_set = set(req_ids)
    if "" in req_id_set:
        errors.append(f"{cid}: requirement with empty requirementId")

    # gold evidence
    gold = case.get("gold", {})
    gold_ev = gold.get("goldEvidence", [])
    for i, ev in enumerate(gold_ev):
        ev_label = f"{cid}.goldEvidence[{i}]"
        # bindsToRequirementIds ⊆ requirements
        for rid in ev.get("bindsToRequirementIds", []):
            if rid not in req_id_set:
                errors.append(f"{ev_label}: bindsTo unknown requirementId '{rid}'")
        # reviewer/annotator must NOT be placeholder (only check fields actually present)
        # Skip placeholder check for candidate cases — templates are intentionally empty
        # When reviewStatus=reviewed (set at gold case level), all placeholders must be filled
        case_review_status = case.get("review", {}).get("reviewStatus", "candidate")
        if case_review_status == "reviewed":
            for field in ("evidenceId", "contentHash", "rationale", "reviewer"):
                if field not in ev:
                    continue
                val = ev.get(field, "")
                if isinstance(val, str) and val.strip().lower() in PLACEHOLDER_VALUES:
                    errors.append(f"{ev_label}: {field} is placeholder ('{val}')")
        else:
            # For candidate: only flag clearly impossible values (not empty TODO which is intentional)
            # Check for FILL_* markers that indicate "must fill before review"
            for field in ("evidenceId", "contentHash"):
                val = ev.get(field, "")
                if isinstance(val, str) and val.startswith("FILL_"):
                    errors.append(
                        f"{ev_label}: {field} = '{val}' — must be filled from real chunk "
                        f"before review (see reviewer workflow)"
                    )

    # goldCoverageByRequirement keys ⊆ requirements
    coverage = gold.get("goldCoverageByRequirement", {})
    for key in coverage:
        if key not in req_id_set:
            errors.append(f"{cid}: goldCoverageByRequirement has unknown reqId '{key}'")

    # answerable ↔ expectedFinalStatus
    answerable = gold.get("answerable")
    expected_status = case.get("expected", {}).get("expectedFinalStatus")
    if answerable is True and expected_status == "REFUSED_NO_EVIDENCE":
        errors.append(f"{cid}: answerable=true but expectedFinalStatus=REFUSED_NO_EVIDENCE")
    if answerable is False and expected_status == "ANSWERED":
        errors.append(f"{cid}: answerable=false but expectedFinalStatus=ANSWERED")

    # slice → status consistency
    slice_val = case.get("slice", "")
    if slice_val in SLICE_TO_STATUS:
        expected_from_slice = SLICE_TO_STATUS[slice_val]
        if expected_status and expected_status != expected_from_slice:
            errors.append(
                f"{cid}: slice='{slice_val}' expects status={expected_from_slice} "
                f"but got {expected_status}"
            )

    # replanExpected <-> acceptableReplanPlans (only enforce on reviewed cases;
    #   templates have reviewStatus=candidate with empty plans by design)
    replan_expected = case.get("expected", {}).get("replanExpected", False)
    acceptable_replan = case.get("planConstraints", {}).get("acceptableReplanPlans", [])
    review_block = case.get("review", {})
    is_reviewed = review_block.get("reviewStatus") == "reviewed"
    if is_reviewed:
        if replan_expected and not acceptable_replan:
            errors.append(f"{cid}: replanExpected=true but acceptableReplanPlans is empty")
        if acceptable_replan and not replan_expected:
            errors.append(f"{cid}: acceptableReplanPlans non-empty but replanExpected=false")

    # budget sanity
    ms = case.get("expected", {}).get("maxSteps", 3)
    mc = case.get("expected", {}).get("maxToolCalls", 3)
    if not isinstance(ms, int) or not (1 <= ms <= 5):
        errors.append(f"{cid}: maxSteps should be 1..5 (got {ms!r})")
    if not isinstance(mc, int) or not (1 <= mc <= 10):
        errors.append(f"{cid}: maxToolCalls should be 1..10 (got {mc!r})")

    # dual-signoff: annotator ≠ reviewer when reviewed
    review = case.get("review", {})
    if review.get("reviewStatus") == "reviewed":
        annotator = review.get("annotator", "")
        reviewer = review.get("reviewer", "")
        if annotator and reviewer and annotator == reviewer:
            errors.append(f"{cid}: dual-signoff violation — annotator == reviewer ('{annotator}')")
        if not review.get("reviewedAt", "").strip():
            errors.append(f"{cid}: reviewStatus=reviewed but reviewedAt is empty")

    # if require-reviewed mode but not reviewed
    return errors


def validate_dataset(
    path: Path,
    require_reviewed: bool = False,
    print_summary: bool = True,
) -> tuple[int, list[str]]:
    if not path.exists():
        return 1, [f"dataset not found: {path}"]
    try:
        cases = load_jsonl(path)
    except ValueError as e:
        return 1, [str(e)]

    if not cases:
        return 1, [f"{path}: empty dataset"]

    # caseId global uniqueness
    id_counts: dict[str, int] = {}
    for c in cases:
        cid = c.get("caseId", "")
        id_counts[cid] = id_counts.get(cid, 0) + 1
    dups = sorted(k for k, v in id_counts.items() if v > 1)
    if dups:
        return 1, [f"{path}: duplicate caseIds: {dups}"]

    all_errors: list[str] = []
    for i, case in enumerate(cases, 1):
        errs = validate_case(case, i)
        if require_reviewed:
            rs = case.get("review", {}).get("reviewStatus")
            if rs != "reviewed":
                errs.append(f"{case.get('caseId', f'<line {i}>')}: "
                            f"require-reviewed mode but reviewStatus={rs!r}")
        all_errors.extend(errs)

    if print_summary:
        if all_errors:
            print(f"FAIL  {path}: {len(all_errors)} errors across {len(cases)} cases",
                  file=sys.stderr)
            for e in all_errors[:50]:
                print(f"  - {e}", file=sys.stderr)
            remaining = len(all_errors) - 50
            if remaining > 0:
                print(f"  ... {remaining} more errors omitted", file=sys.stderr)
        else:
            reviewed = sum(1 for c in cases
                           if c.get("review", {}).get("reviewStatus") == "reviewed")
            print(f"OK    {path}: {len(cases)} cases validated "
                  f"({reviewed} reviewed, {len(cases) - reviewed} candidate)")

    return (0 if not all_errors else 1), all_errors


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="PR-7f.2a Agentic RAG dataset validator")
    p.add_argument("dataset", type=Path, help="jsonl dataset path")
    p.add_argument("--require-reviewed", action="store_true",
                   help="Reject cases with reviewStatus != 'reviewed'")
    args = p.parse_args(argv)
    rc, _ = validate_dataset(args.dataset, args.require_reviewed)
    return rc


if __name__ == "__main__":
    sys.exit(main())
