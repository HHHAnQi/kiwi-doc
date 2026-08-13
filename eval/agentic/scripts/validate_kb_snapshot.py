#!/usr/bin/env python3
"""PR-7f.2c.1.5 Task 3: Offline KB Snapshot Validator.

Validates an NDJSON KB snapshot file produced by `gold_annotation_helper.py export`
(or by directly running the recommended SQL — see kb_snapshot_audit.md §4.2).

Required checks (per spec):
  1. Unique chunkId           — no duplicate chunk_id across the file
  2. contentHash correctness  — sha256(content) matches the contentHash on each row
  3. Required metadata        — tenantId, chunkType, seq present on every chunk
  4. No empty content         — content field is a non-empty string

Additional structural checks (free):
  - Each line is valid JSON
  - Required top-level fields present (documentId, chunkId, documentVersion,
    content, contentHash, metadata)
  - Each metadata.required nested field validated
  - contentHash is 64-char lowercase hex (matches runtime Evidence.sha256 / Evidence.java:85)
  - tenantId non-empty, matches snapshotMeta.tenantId when meta line is present
  - PRIVATE visibility WARNed (not failed — may be intentional for security eval)

Exit codes:
  0  snapshot valid
  1  snapshot invalid (one or more checks failed)
  2  CLI / IO error

Usage:
  python3 eval/agentic/scripts/validate_kb_snapshot.py <snapshot.ndjson>
  python3 eval/agentic/scripts/validate_kb_snapshot.py <snapshot.ndjson> --json
  python3 eval/agentic/scripts/validate_kb_snapshot.py <snapshot.ndjson> --strict
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

# Hex pattern: 64 lowercase chars — matches Evidence.java:85-93 + TikaParsingTrigger.sha256Hex
HEX64_RE = re.compile(r"^[a-f0-9]{64}$")
HEX_ANY64_RE = re.compile(r"^[a-f0-9]{64}$")

REQUIRED_TOP_FIELDS = ("documentId", "chunkId", "documentVersion", "content", "contentHash", "metadata")
REQUIRED_METADATA_FIELDS = ("tenantId", "chunkType", "seq")
CHUNK_TYPE_ENUM = {"TEXT", "PARENT", "CHILD"}
VISIBILITY_ENUM = {"PRIVATE", "TENANT", "PUBLIC"}


def sha256_hex(s: str) -> str:
    """Match Evidence.sha256 (Evidence.java:85-93) and TikaParsingTrigger.sha256Hex (L405)."""
    if s is None:
        s = ""  # match Evidence.of null-safety at L63 (safeContent = content==null?"":content)
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def _is_int(x: Any, *, positive: bool = False) -> bool:
    try:
        v = int(x)
        return v > 0 if positive else True
    except (TypeError, ValueError):
        return False


def validate_meta_line(obj: dict) -> list[str]:
    errors: list[str] = []
    meta = obj.get("snapshotMeta", {})
    if not isinstance(meta, dict):
        return ["snapshotMeta must be an object"]
    if "snapshotId" not in meta or not HEX64_RE.match(str(meta.get("snapshotId", ""))):
        errors.append("snapshotMeta.snapshotId must be 64-char lowercase hex")
    if not meta.get("exportedAt"):
        errors.append("snapshotMeta.exportedAt required")
    if not meta.get("tenantId"):
        errors.append("snapshotMeta.tenantId required (single-tenant snapshot — see kb_snapshot_audit.md §5.2)")
    return errors


def validate_chunk_line(obj: dict, line_no: int, *, strict: bool = False) -> list[str]:
    errors: list[str] = []

    def err(check: str, msg: str) -> None:
        errors.append(f"[line {line_no}, chunk {obj.get('chunkId','?')}] {check}: {msg}")

    # ── Check missing top-level fields ───────────────────────
    for f in REQUIRED_TOP_FIELDS:
        if f not in obj:
            err("missing-field", f"top-level '{f}' absent")
    # short-circuit if structurally broken
    if errors:
        return errors

    # ── Check 3: required metadata present ───────────────────
    meta = obj.get("metadata", {})
    if not isinstance(meta, dict):
        err("bad-metadata", "metadata must be an object")
        return errors
    for f in REQUIRED_METADATA_FIELDS:
        if f not in meta or meta[f] in (None, ""):
            err("missing-metadata", f"metadata.{f} required")

    # ── Check 4: no empty content ────────────────────────────
    content = obj.get("content", "")
    if not isinstance(content, str) or not content:
        err("empty-content", "content must be a non-empty string")
        # cannot proceed to hash check
        return errors

    # ── Check 2: contentHash correctness ─────────────────────
    declared_hash = obj.get("contentHash", "")
    if not isinstance(declared_hash, str) or not HEX64_RE.match(declared_hash):
        err("bad-contentHash",
            f"contentHash must be 64-char lowercase hex, got {str(declared_hash)[:20]!r}")
    else:
        recomputed = sha256_hex(content)
        if recomputed != declared_hash:
            err("contentHash-mismatch",
                f"declared {declared_hash[:12]}... != recomputed {recomputed[:12]}... "
                f"(content len={len(content)})")

    # ── Type / value structural checks ───────────────────────
    if not _is_int(obj.get("documentId"), positive=True):
        err("bad-documentId", f"documentId must be positive int, got {obj.get('documentId')!r}")
    if not _is_int(obj.get("chunkId"), positive=True):
        err("bad-chunkId", f"chunkId must be positive int, got {obj.get('chunkId')!r}")
    if not isinstance(obj.get("documentVersion"), str):
        err("bad-version", "documentVersion must be a string (use 'unknown' if DB null)")

    if isinstance(meta, dict):
        ct = meta.get("chunkType")
        if ct not in CHUNK_TYPE_ENUM:
            err("bad-chunkType", f"metadata.chunkType {ct!r} not in {sorted(CHUNK_TYPE_ENUM)}")
        if not _is_int(meta.get("seq")):
            err("bad-seq", f"metadata.seq must be int, got {meta.get('seq')!r}")
        if "visibility" in meta and meta.get("visibility") not in VISIBILITY_ENUM:
            err("bad-visibility",
                f"metadata.visibility {meta.get('visibility')!r} not in {sorted(VISIBILITY_ENUM)}")
        tid = meta.get("tenantId")
        if not isinstance(tid, str) or not tid.strip():
            err("bad-tenant", "metadata.tenantId must be non-empty string")
        if "page" in meta and not _is_int(meta.get("page")):
            err("bad-page", f"metadata.page must be int, got {meta.get('page')!r}")
        if "parentChunkId" in meta and meta.get("parentChunkId") is not None \
                and not _is_int(meta.get("parentChunkId"), positive=True):
            err("bad-parent", f"metadata.parentChunkId must be positive int or null, got {meta.get('parentChunkId')!r}")

    if strict:
        # in strict mode, also assert the document status
        st = meta.get("status") if isinstance(meta, dict) else None
        if st and st != "READY":
            err("non-ready", f"metadata.status={st!r} (only READY rows are evaluable)")

    # Warn (not fail) on PRIVATE visibility
    vis = meta.get("visibility") if isinstance(meta, dict) else None
    if vis == "PRIVATE":
        err("WARN-private", "visibility=PRIVATE — annotator may need elevated access")

    return errors


def validate_snapshot(
    path: Path, *, strict: bool = False
) -> tuple[int, list[str], dict[str, Any]]:
    """Validate the NDJSON snapshot. Returns (exit_code, errors, stats)."""
    errors: list[str] = []
    seen_chunk_ids: set[int] = set()
    seen_tenant_ids: set[str] = set()
    total_lines = 0
    chunk_lines = 0
    meta_line: dict | None = None
    duplicate_count = 0
    hash_mismatch_count = 0
    warn_count = 0

    with path.open("r", encoding="utf-8") as fh:
        for i, raw in enumerate(fh, 1):
            raw = raw.strip()
            if not raw:
                continue
            total_lines += 1
            try:
                obj = json.loads(raw)
            except json.JSONDecodeError as e:
                errors.append(f"[line {i}] json-parse: {e}")
                continue

            # Optional first-line meta
            if isinstance(obj, dict) and "snapshotMeta" in obj:
                if meta_line is not None:
                    errors.append(f"[line {i}] duplicate snapshotMeta — only one allowed (typically first line)")
                    continue
                meta_line = obj
                meta_errs = validate_meta_line(obj)
                errors.extend(meta_errs)
                continue

            chunk_lines += 1
            line_errs = validate_chunk_line(obj, i, strict=strict)
            errors.extend(line_errs)

            # ── Check 1: unique chunkId ────────────────────────
            cid = obj.get("chunkId")
            if _is_int(cid, positive=True):
                if cid in seen_chunk_ids:
                    duplicate_count += 1
                    errors.append(f"[line {i}, chunk {cid}] duplicate-chunkId: chunkId {cid} already seen")
                else:
                    seen_chunk_ids.add(cid)

            # collect tenantIds
            tid = obj.get("metadata", {}).get("tenantId") if isinstance(obj.get("metadata"), dict) else None
            if isinstance(tid, str) and tid:
                seen_tenant_ids.add(tid)

            # tally mismatches/warns
            for e in line_errs:
                if "contentHash-mismatch" in e:
                    hash_mismatch_count += 1
                if "WARN-private" in e:
                    warn_count += 1

    stats = {
        "totalLines": total_lines,
        "chunkLines": chunk_lines,
        "uniqueChunkIds": len(seen_chunk_ids),
        "duplicateChunkIds": duplicate_count,
        "hashMismatches": hash_mismatch_count,
        "warns": warn_count,
        "tenantIds": sorted(seen_tenant_ids),
        "hasMeta": meta_line is not None,
    }

    # Cross-tenant guard
    if len(seen_tenant_ids) > 1:
        errors.append(
            f"cross-tenant: snapshot contains {len(seen_tenant_ids)} tenant IDs "
            f"({sorted(seen_tenant_ids)}) — single-tenant required (kb_snapshot_audit.md §5.2)"
        )

    # Hard errors only (ignore WARN-private counts which are warnings, not failures)
    hard_errors = [e for e in errors if "] WARN-" not in e]
    exit_code = 0 if not hard_errors else 1
    return exit_code, errors, stats


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="KB snapshot validator (PR-7f.2c.1.5 Task 3)")
    p.add_argument("snapshot", type=Path)
    p.add_argument("--json", action="store_true",
                   help="emit structured JSON report")
    p.add_argument("--strict", action="store_true",
                   help="also enforce metadata.status=READY")
    args = p.parse_args(argv)

    if not args.snapshot.exists():
        print(f"snapshot not found: {args.snapshot}", file=sys.stderr)
        return 2

    exit_code, errors, stats = validate_snapshot(args.snapshot, strict=args.strict)

    if args.json:
        print(json.dumps({
            "snapshot": str(args.snapshot),
            "valid": exit_code == 0,
            "stats": stats,
            "errors": [e for e in errors if "] WARN-" not in e],
            "warns":  [e for e in errors if "] WARN-" in e],
        }, ensure_ascii=False, indent=2))
    else:
        status = "PASS" if exit_code == 0 else "FAIL"
        print(f"{status}  {args.snapshot}")
        print(f"  lines={stats['totalLines']}  chunks={stats['chunkLines']}  "
              f"uniqueChunkIds={stats['uniqueChunkIds']}")
        print(f"  duplicates={stats['duplicateChunkIds']}  "
              f"hashMismatches={stats['hashMismatches']}  warns={stats['warns']}")
        print(f"  tenantIds={stats['tenantIds']}  hasMeta={stats['hasMeta']}")
        if errors:
            hard = [e for e in errors if "] WARN-" not in e]
            warns = [e for e in errors if "] WARN-" in e]
            if hard:
                print(f"\n{len(hard)} hard error(s) (first 10):")
                for e in hard[:10]:
                    print(f"  - {e}")
            if warns:
                print(f"\n{len(warns)} warning(s) (first 3):")
                for e in warns[:3]:
                    print(f"  - {e}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
