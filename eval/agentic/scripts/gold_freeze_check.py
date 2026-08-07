#!/usr/bin/env python3
"""PR-7f.2b.3a: Gold Freeze Gate.

Mechanically answers: "Is pilot20 ready for live evaluation runs?"

A dataset is FROZEN (ready for live runs) iff ALL of:
  1. Every case has reviewStatus == "reviewed" (human dual-signoff complete)
  2. Zero FILL_* markers remain in goldEvidence
  3. validate_dataset --require-reviewed passes (no placeholder leakage)
  4. validate_gold_completeness --strict passes (every required Requirement covered)

If any of the above fails, this script exits with code 1 and emits a structured
BLOCKED report. Per the PR-7f.2b.3 spec:

  > 如果缺人工标注数据，不自动生成，明确阻塞。

This script is the "明确阻塞" — it refuses to fabricate gold labels.

Usage:
  python3 eval/agentic/scripts/gold_freeze_check.py <dataset.jsonl>
  python3 eval/agentic/scripts/gold_freeze_check.py \\
      eval/agentic/datasets/agentic_v2.pilot20.jsonl --json

Exit codes: 0 = FROZEN, 1 = BLOCKED, 2 = CLI error.
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

SCRIPTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIR))
import validate_dataset as VD  # noqa: E402
import validate_gold_completeness as VGC  # noqa: E402


@dataclass
class FreezeReport:
    dataset: str
    frozen: bool
    total_cases: int
    reviewed_cases: int
    candidate_cases: int
    fill_marker_count: int
    reviewed_at_missing: int
    dataset_validator_errors: int = 0
    gold_completeness_errors: int = 0
    blockers: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "dataset": self.dataset,
            "frozen": self.frozen,
            "totalCases": self.total_cases,
            "reviewedCases": self.reviewed_cases,
            "candidateCases": self.candidate_cases,
            "fillMarkerCount": self.fill_marker_count,
            "reviewedAtMissing": self.reviewed_at_missing,
            "datasetValidatorErrors": self.dataset_validator_errors,
            "goldCompletenessErrors": self.gold_completeness_errors,
            "blockers": self.blockers,
        }


def assess(dataset_path: Path) -> FreezeReport:
    cases = [json.loads(l) for l in dataset_path.open(encoding="utf-8") if l.strip()]
    total = len(cases)
    reviewed = sum(1 for c in cases
                   if c.get("review", {}).get("reviewStatus") == "reviewed")
    candidate = total - reviewed

    fill_markers = 0
    reviewed_at_missing = 0
    for c in cases:
        for ev in c.get("gold", {}).get("goldEvidence", []):
            for f in ("evidenceId", "contentHash", "documentVersion", "contentSnippet"):
                v = ev.get(f, "")
                if isinstance(v, str) and v.startswith("FILL_"):
                    fill_markers += 1
            if not str(ev.get("reviewedAt", "")).strip():
                reviewed_at_missing += 1

    # 1. dataset validator in --require-reviewed mode
    rc_ds, errs_ds = VD.validate_dataset(
        dataset_path, require_reviewed=True, print_summary=False
    )
    # 2. gold completeness strict (per-case loop; module exposes check_completeness)
    errs_gc: list[str] = []
    for i, case in enumerate(cases, 1):
        case_errs, _warns = VGC.check_completeness(case, strict=True)
        errs_gc.extend(case_errs)
    rc_gc = 1 if errs_gc else 0

    blockers: list[str] = []
    if candidate > 0:
        blockers.append(
            f"BLOCKED: {candidate}/{total} cases still reviewStatus=candidate "
            f"— human dual-signoff incomplete (annotator + reviewer)"
        )
    if fill_markers > 0:
        blockers.append(
            f"BLOCKED: {fill_markers} FILL_* markers remain — domain expert must "
            f"compute evidenceId/contentHash from real chunk text"
        )
    if reviewed_at_missing > 0:
        blockers.append(
            f"BLOCKED: {reviewed_at_missing} goldEvidence entries missing reviewedAt"
        )
    if rc_ds != 0:
        blockers.append(
            f"BLOCKED: validate_dataset --require-reviewed failed with "
            f"{len(errs_ds)} errors (placeholder leakage or review holes)"
        )
    if rc_gc != 0:
        blockers.append(
            f"BLOCKED: validate_gold_completeness --strict failed with "
            f"{len(errs_gc)} errors (required Requirements not covered)"
        )

    frozen = not blockers
    return FreezeReport(
        dataset=str(dataset_path),
        frozen=frozen,
        total_cases=total,
        reviewed_cases=reviewed,
        candidate_cases=candidate,
        fill_marker_count=fill_markers,
        reviewed_at_missing=reviewed_at_missing,
        dataset_validator_errors=len(errs_ds),
        gold_completeness_errors=len(errs_gc),
        blockers=blockers,
    )


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Gold dataset freeze gate")
    p.add_argument("dataset", type=Path)
    p.add_argument("--json", action="store_true",
                   help="Emit structured JSON report instead of human text")
    args = p.parse_args(argv)

    if not args.dataset.exists():
        print(f"dataset not found: {args.dataset}", file=sys.stderr)
        return 2

    report = assess(args.dataset.resolve())

    if args.json:
        print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
    else:
        status = "FROZEN  ✓" if report.frozen else "BLOCKED  ✗"
        print(f"{status}  {report.dataset}")
        print(f"  cases:              {report.total_cases}")
        print(f"  reviewed:           {report.reviewed_cases}")
        print(f"  candidate:          {report.candidate_cases}")
        print(f"  FILL_* markers:     {report.fill_marker_count}")
        print(f"  reviewedAt missing: {report.reviewed_at_missing}")
        if report.blockers:
            print("  blockers:")
            for b in report.blockers:
                print(f"    - {b}")

    return 0 if report.frozen else 1


if __name__ == "__main__":
    sys.exit(main())
