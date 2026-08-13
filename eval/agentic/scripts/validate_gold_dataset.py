#!/usr/bin/env python3
"""PR-7f.2c.1 Task 3: Gold Dataset Validator.

Validates `agentic_v2.gold20.template.jsonl` (and any case file using the
PR-7f.2c.1 minimal schema) for annotation completeness.

8 required checks (per spec):
  1. All required fields present
  2. evidenceId format (12-char lowercase hex)
  3. contentHash format (64-char lowercase hex = sha256)
  4. (document, chunk) presence — pointer must be a positive int pair
  5. annotator != reviewer (dual signoff)
  6. reviewedAt present (ISO-8601)
  7. referenceAnswer non-empty
  8. No FILL_ / PLACEHOLDER / TODO leakage

Exit code:
  0  pass (file is annotation-complete and reviewable)
  1  fail (one or more checks failed; print structured report)
  2  CLI error (file not found, not JSONL, etc.)

Usage:
  python3 eval/agentic/scripts/validate_gold_dataset.py <dataset.jsonl>
  python3 eval/agentic/scripts/validate_gold_dataset.py <dataset.jsonl> --json
  python3 eval/agentic/scripts/validate_gold_dataset.py <dataset.jsonl> --strict
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

# ── Format regexes ────────────────────────────────────────────────
# evidenceId = sha256(tenantId|docId|chunkId|contentHash)  → full 64 lowercase hex
# (matches Evidence.java:65 EXACTLY — no truncation. Earlier 12-char regex was a bug;
#  see kb_snapshot_audit.md §3.2 for the runtime source of truth.)
EVIDENCE_ID_RE_64 = re.compile(r"^[a-f0-9]{64}$")
EVIDENCE_ID_RE_12 = re.compile(r"^[a-f0-9]{12}$")
EVIDENCE_ID_RE = re.compile(r"^[a-f0-9]{12}$|^[a-f0-9]{64}$")  # accept both for migration
# contentHash = sha256(chunk_text).hex()  → 64 lowercase hex
CONTENT_HASH_RE = re.compile(r"^[a-f0-9]{64}$")
# ISO-8601 (date or full timestamp with optional timezone)
ISO8601_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$"
)
# Forbidden placeholder tokens (case-insensitive substring match)
PLACEHOLDER_TOKENS = ("FILL_", "PLACEHOLDER", "<TODO>", "TODO", "TBD", "XX", "???")

# ── Required field tree (per spec) ───────────────────────────────
REQUIRED_TOP = ("caseId", "query", "slice", "requirements", "gold", "expected", "review")
REQUIRED_GOLD = ("referenceAnswer", "evidence")
REQUIRED_EVIDENCE = ("documentId", "chunkId", "contentHash", "evidenceId")
REQUIRED_EXPECTED = ("finalStatus", "expectedStrategy", "maxSteps")
REQUIRED_REVIEW = ("annotator", "reviewer", "reviewedAt")

# Allowed values
FINAL_STATUS_ENUM = {"ANSWERED", "REFUSED_NO_EVIDENCE", "REFUSED_CONFLICT",
                     "REFUSED_PERMISSION", "TOOL_FAILED", "BUDGET_EXCEEDED",
                     "TIMED_OUT", "CANCELLED", "SYSTEM_FAILED"}
EXPECTED_STRATEGY_ENUM = {"PLANNED_AGENT", "CLASSIC_RAG", "TARGETED_RAG",
                          "FIXED_WORKFLOW"}


def _contains_placeholder(value: Any) -> bool:
    """Recursive check for placeholder tokens in any string field."""
    if isinstance(value, str):
        upper = value.upper()
        return any(tok in upper for tok in PLACEHOLDER_TOKENS)
    if isinstance(value, dict):
        return any(_contains_placeholder(v) for v in value.values())
    if isinstance(value, list):
        return any(_contains_placeholder(v) for v in value)
    return False


def _is_positive_int(x: Any) -> bool:
    try:
        return int(x) > 0
    except (TypeError, ValueError):
        return False


def validate_case(case: dict[str, Any], idx: int, *, strict: bool = False) -> list[str]:
    """Run all 8 checks on a single case dict. Returns list of error messages."""
    errors: list[str] = []
    cid = case.get("caseId", f"<line {idx}>")

    def err(check: str, msg: str) -> None:
        errors.append(f"[{cid}] {check}: {msg}")

    # ─── Check 1: required fields present ──────────────────────
    for f in REQUIRED_TOP:
        if f not in case:
            err("missing-field", f"top-level '{f}' absent")
    if "gold" in case:
        for f in REQUIRED_GOLD:
            if f not in case["gold"]:
                err("missing-field", f"gold.{f} absent")
        ev = case.get("gold", {}).get("evidence")
        if not isinstance(ev, list) or len(ev) == 0:
            err("missing-field", "gold.evidence must be a non-empty list")
        else:
            for i, e in enumerate(ev):
                if not isinstance(e, dict):
                    err("missing-field", f"gold.evidence[{i}] not an object")
                    continue
                for f in REQUIRED_EVIDENCE:
                    if f not in e:
                        err("missing-field", f"gold.evidence[{i}].{f} absent")
    if "expected" in case:
        for f in REQUIRED_EXPECTED:
            if f not in case["expected"]:
                err("missing-field", f"expected.{f} absent")
    if "review" in case:
        for f in REQUIRED_REVIEW:
            if f not in case["review"]:
                err("missing-field", f"review.{f} absent")

    # Short-circuit: too broken to keep checking structural fields
    if errors:
        return errors

    # ─── Check 8: no FILL_ / PLACEHOLDER / TODO leakage ────────
    # (run early so subsequent checks can short-circuit on placeholder values)
    if _contains_placeholder(case):
        # find which field
        offending = []
        def walk(v: Any, path: str) -> None:
            if isinstance(v, str):
                upper = v.upper()
                for tok in PLACEHOLDER_TOKENS:
                    if tok in upper:
                        offending.append(f"{path}={v[:40]} ({tok})")
            elif isinstance(v, dict):
                for k, sub in v.items():
                    walk(sub, f"{path}.{k}")
            elif isinstance(v, list):
                for i, sub in enumerate(v):
                    walk(sub, f"{path}[{i}]")
        walk(case, "case")
        err("placeholder",
            "forbidden token(s) found: " + "; ".join(offending[:5])
            + ("..." if len(offending) > 5 else ""))

    # ─── Check 7: referenceAnswer non-empty ────────────────────
    ra = case.get("gold", {}).get("referenceAnswer", "")
    if not isinstance(ra, str) or not ra.strip():
        err("empty-referenceAnswer",
            "gold.referenceAnswer must be a non-empty string")

    # ─── Check 4: documentId + chunkId present and valid ───────
    for i, e in enumerate(case["gold"]["evidence"]):
        if not _is_positive_int(e.get("documentId")):
            err("invalid-doc",
                f"gold.evidence[{i}].documentId must be positive int, got {e.get('documentId')!r}")
        if not _is_positive_int(e.get("chunkId")):
            err("invalid-chunk",
                f"gold.evidence[{i}].chunkId must be positive int, got {e.get('chunkId')!r}")

    # ─── Check 2: evidenceId format ────────────────────────────
    for i, e in enumerate(case["gold"]["evidence"]):
        eid = e.get("evidenceId", "")
        if not EVIDENCE_ID_RE.match(str(eid)):
            err("bad-evidenceId",
                f"gold.evidence[{i}].evidenceId must be 12 OR 64-char lowercase hex "
                f"(runtime uses 64 per Evidence.java:65), got {str(eid)[:20]!r}")

    # ─── Check 3: contentHash format ───────────────────────────
    for i, e in enumerate(case["gold"]["evidence"]):
        chash = e.get("contentHash", "")
        if not CONTENT_HASH_RE.match(str(chash)):
            err("bad-contentHash",
                f"gold.evidence[{i}].contentHash must be 64-char lowercase hex (sha256), "
                f"got {str(chash)[:20]!r}...")

    # ─── Check 5: annotator != reviewer ────────────────────────
    rev = case.get("review", {})
    ann = rev.get("annotator", "")
    revr = rev.get("reviewer", "")
    if not ann or not revr:
        err("empty-reviewer", "review.annotator and review.reviewer both required")
    elif ann == revr:
        err("self-review",
            f"review.annotator ({ann!r}) must differ from review.reviewer")

    # ─── Check 6: reviewedAt present + ISO-8601 ────────────────
    reviewed_at = rev.get("reviewedAt", "")
    if not isinstance(reviewed_at, str) or not reviewed_at.strip():
        err("empty-reviewedAt", "review.reviewedAt required (ISO-8601 timestamp)")
    elif not ISO8601_RE.match(reviewed_at.strip()):
        err("bad-reviewedAt",
            f"review.reviewedAt not ISO-8601: got {reviewed_at!r}")

    # ─── Enum + extra structural checks (strict mode adds more) ─
    fs = case.get("expected", {}).get("finalStatus", "")
    if fs and fs not in FINAL_STATUS_ENUM:
        err("bad-finalStatus",
            f"expected.finalStatus {fs!r} not in enum {sorted(FINAL_STATUS_ENUM)}")
    es = case.get("expected", {}).get("expectedStrategy", "")
    if es and es not in EXPECTED_STRATEGY_ENUM:
        err("bad-strategy",
            f"expected.expectedStrategy {es!r} not in enum {sorted(EXPECTED_STRATEGY_ENUM)}")
    ms = case.get("expected", {}).get("maxSteps")
    if not _is_positive_int(ms):
        err("bad-maxSteps", f"expected.maxSteps must be positive int, got {ms!r}")

    # annotation leakage guard (gold_dataset_audit.md §6.4)
    for i, e in enumerate(case["gold"]["evidence"]):
        snip = e.get("contentSnippet", "")
        if snip and ra and snip.strip() == ra.strip():
            err("annotation-leakage",
                f"gold.evidence[{i}].contentSnippet identical to referenceAnswer "
                f"(see gold_dataset_audit.md §6.1)")
        rat = e.get("rationale", "")
        if rat and ra and rat.strip() in ra.strip():
            err("annotation-leakage",
                f"gold.evidence[{i}].rationale is contained in referenceAnswer "
                f"(rationale must cite chunk location, not copy answer)")

    if strict:
        # additional coverage checks
        reqs = case.get("requirements", [])
        if not reqs or not any(r.get("required") for r in reqs):
            err("no-required-req", "case has no required Requirement")
        cov = case.get("gold", {}).get("goldCoverageByRequirement", {})
        for r in reqs:
            rid = r.get("requirementId", "")
            if r.get("required") and not cov.get(rid):
                err("coverage-gap",
                    f"requirement {rid} is required but goldCoverageByRequirement has no evidence")

    return errors


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Gold dataset validator (PR-7f.2c.1)")
    p.add_argument("dataset", type=Path)
    p.add_argument("--json", action="store_true",
                   help="emit JSON report instead of human-readable")
    p.add_argument("--strict", action="store_true",
                   help="extra coverage + leakage checks")
    args = p.parse_args(argv)

    if not args.dataset.exists():
        print(f"dataset not found: {args.dataset}", file=sys.stderr)
        return 2

    cases: list[dict[str, Any]] = []
    try:
        for i, line in enumerate(args.dataset.open(encoding="utf-8"), 1):
            line = line.strip()
            if not line:
                continue
            cases.append(json.loads(line))
    except json.JSONDecodeError as e:
        print(f"JSON parse error at line {i}: {e}", file=sys.stderr)
        return 2

    per_case_errors: list[tuple[str, list[str]]] = []
    total_errors = 0
    check_counter: dict[str, int] = {}
    for idx, case in enumerate(cases, 1):
        errs = validate_case(case, idx, strict=args.strict)
        if errs:
            per_case_errors.append((case.get("caseId", f"<line {idx}>"), errs))
            total_errors += len(errs)
            for e in errs:
                # extract check name between [cid] and :
                m = re.match(r"^\[[^\]]+\]\s+([\w-]+):", e)
                if m:
                    check_counter[m.group(1)] = check_counter.get(m.group(1), 0) + 1

    if args.json:
        report = {
            "dataset": str(args.dataset),
            "totalCases": len(cases),
            "casesWithErrors": len(per_case_errors),
            "totalErrors": total_errors,
            "checkBreakdown": check_counter,
            "perCase": [
                {"caseId": cid, "errors": errs} for cid, errs in per_case_errors
            ],
            "valid": total_errors == 0,
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        status = "PASS" if total_errors == 0 else "FAIL"
        print(f"{status}  {args.dataset}  ({len(cases)} cases, "
              f"{len(per_case_errors)} with errors, {total_errors} total)")
        if check_counter:
            print("\nerror breakdown:")
            for check, n in sorted(check_counter.items(), key=lambda x: -x[1]):
                print(f"  {n:4d}  {check}")
        if per_case_errors:
            print("\nper-case (first 5):")
            for cid, errs in per_case_errors[:5]:
                print(f"  [{cid}]")
                for e in errs:
                    print(f"    - {e}")

    return 0 if total_errors == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
