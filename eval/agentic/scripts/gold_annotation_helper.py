#!/usr/bin/env python3
"""PR-7f.2c.1.5 Task 4: Gold Annotation Helper.

Reads an offline KB snapshot NDJSON, lets the annotator search and display chunks,
and generates a complete gold evidence record (contentHash + evidenceId) ready to
paste into a JSONL gold dataset row.

Sub-commands:

  export          Run the export SQL against MySQL and write the snapshot NDJSON.
                  Requires `pymysql` and DB env. Optional — the snapshot can be
                  produced by any means (kb_snapshot_audit.md §4.2).

  list-docs       List distinct documentId + filenames in a snapshot, with chunk counts.

  search          Free-text search across chunk content. Case-insensitive substring.

  show-chunk      Display a chunk by (documentId, chunkId), full text + metadata.

  compute-hash    Recompute sha256(content) for verification.

  make-evidence   Print a fully-filled evidence record (content_hash + evidence_id
                  + evidence object). The annotator copies this into the gold row.

Hash derivation matches the runtime EXACTLY (Evidence.java:64-65):
    contentHash = sha256(content bytes UTF-8)                          # 64 lowercase hex
    evidenceId  = sha256(tenantId|documentId|chunkId|contentHash)     # 64 lowercase hex

No fake evidence is generated. The helper only reads chunks and emits hashes;
the annotator decides what to keep and writes the rationale / answer text.

Usage examples:
  # Build the snapshot (run once; requires DB access)
  python3 eval/agentic/scripts/gold_annotation_helper.py export \\
      --tenant-id default \\
      --out eval/agentic/kb_snapshot/snapshots/tenant-default-2026-08-08.ndjson

  # Validate it (delegates to validate_kb_snapshot.py)
  python3 eval/agentic/scripts/validate_kb_snapshot.py <snapshot.ndjson>

  # Browse / select / hash
  python3 eval/agentic/scripts/gold_annotation_helper.py list-docs        --snapshot <snap>
  python3 eval/agentic/scripts/gold_annotation_helper.py search "Dubbo3"  --snapshot <snap>
  python3 eval/agentic/scripts/gold_annotation_helper.py show-chunk --document-id 7 --chunk-id 28 --snapshot <snap>
  python3 eval/agentic/scripts/gold_annotation_helper.py make-evidence --document-id 7 --chunk-id 28 --snapshot <snap> \\
      --annotator alice --reviewer bob
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

# ── Hashing — matches Evidence.java:85-93 EXACTLY ──────────────────────
def sha256_hex(s: str | None) -> str:
    """sha256 → 64-char lowercase hex. None/empty treated as '' per Evidence.of L63."""
    if s is None:
        s = ""
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def content_hash(content: str | None) -> str:
    return sha256_hex(content)


def evidence_id(tenant_id: str, doc_id: int, chunk_id: int, c_hash: str) -> str:
    """Match Evidence.java:65: sha256(tenantId|documentId|chunkId|contentHash). No truncation."""
    raw = f"{tenant_id}|{doc_id}|{chunk_id}|{c_hash}"
    return sha256_hex(raw)


# ── Snapshot I/O ───────────────────────────────────────────────────────
def load_snapshot(path: Path) -> tuple[dict | None, list[dict]]:
    """Return (meta_record_or_None, [chunk_record, ...])."""
    if not path.exists():
        print(f"snapshot not found: {path}", file=sys.stderr)
        sys.exit(2)
    meta = None
    chunks: list[dict] = []
    for i, raw in enumerate(path.open(encoding="utf-8"), 1):
        raw = raw.strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError as e:
            print(f"[line {i}] JSON parse error: {e}", file=sys.stderr)
            sys.exit(2)
        if isinstance(obj, dict) and "snapshotMeta" in obj:
            if meta is not None:
                print(f"[line {i}] multiple snapshotMeta lines", file=sys.stderr)
                sys.exit(2)
            meta = obj
        elif isinstance(obj, dict):
            chunks.append(obj)
    return meta, chunks


def find_chunk(chunks: Iterable[dict], doc_id: int, chunk_id: int) -> dict | None:
    for c in chunks:
        if c.get("documentId") == doc_id and c.get("chunkId") == chunk_id:
            return c
    return None


# ── Sub-command: export ───────────────────────────────────────────────
EXPORT_SQL = """
SELECT
    d.tenant_id          AS tenant_id,
    c.document_id        AS document_id,
    c.id                 AS chunk_id,
    COALESCE(d.version, 'unknown') AS document_version,
    c.chunk_type         AS chunk_type,
    c.seq                AS seq,
    c.page               AS page,
    c.content            AS content,
    c.content_hash       AS content_hash_db,
    c.section_path       AS section_path,
    c.parent_chunk_id    AS parent_chunk_id,
    d.source             AS source,
    d.language           AS language,
    d.original_filename  AS original_filename,
    d.status             AS status
FROM chunks c
JOIN documents d ON d.id = c.document_id
WHERE d.deleted_at IS NULL
  AND d.status = 'READY'
  AND d.tenant_id = %(tenant_id)s
ORDER BY c.document_id, c.seq, c.chunk_type
"""

def cmd_export(args: argparse.Namespace) -> int:
    try:
        import pymysql  # type: ignore
        import pymysql.cursors  # type: ignore
    except ImportError:
        print("ERROR: `export` requires pymysql. Install with: pip install pymysql",
              file=sys.stderr)
        return 2

    tenant_id = args.tenant_id or os.environ.get("KB_TENANT_ID", "default")
    host = args.host or os.environ.get("KB_DB_HOST", "localhost")
    port = int(args.port or os.environ.get("KB_DB_PORT", "3306"))
    user = args.user or os.environ.get("KB_DB_USER", "root")
    password = args.password or os.environ.get("KB_DB_PASSWORD", "")
    db = args.db or os.environ.get("KB_DB_NAME", "ragdoc")

    print(f"[export] connecting to {user}@{host}:{port}/{db} tenant={tenant_id}",
          file=sys.stderr)
    conn = pymysql.connect(
        host=host, port=port, user=user, password=password, db=db,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.SSDictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute(EXPORT_SQL, {"tenant_id": tenant_id})
            args.out.parent.mkdir(parents=True, exist_ok=True)
            chunk_count = 0
            doc_ids: set[int] = set()
            accumulated_hash_input = hashlib.sha256()
            with args.out.open("w", encoding="utf-8") as fh:
                # Stream rows: write chunk records first, accumulate hash seed
                rows = []
                for row in cur:
                    content = row["content"] or ""
                    # Recompute hash in Python — independent of DB content_hash column
                    c_hash = content_hash(content)
                    accumulated_hash_input.update(c_hash.encode("utf-8"))
                    rec = {
                        "documentId": row["document_id"],
                        "chunkId": row["chunk_id"],
                        "documentVersion": row["document_version"],
                        "content": content,
                        "contentHash": c_hash,
                        "metadata": {
                            "tenantId": row["tenant_id"],
                            "chunkType": row["chunk_type"],
                            "seq": row["seq"],
                            "page": row["page"],
                            "sectionPath": _parse_json_or_none(row.get("section_path")),
                            "parentChunkId": row.get("parent_chunk_id"),
                            "source": row.get("source"),
                            "language": row.get("language"),
                            "status": row.get("status"),
                            "originalFilename": row.get("original_filename"),
                        },
                    }
                    fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
                    chunk_count += 1
                    doc_ids.add(row["document_id"])
                snapshot_id = accumulated_hash_input.hexdigest()
                meta = {
                    "snapshotMeta": {
                        "snapshotId": snapshot_id,
                        "exportedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                        "tenantId": tenant_id,
                        "chunkCount": chunk_count,
                        "documentCount": len(doc_ids),
                        "sourceDbDsn": f"{host}:{port}/{db}",
                        "exportedBy": user,
                    }
                }
                # Prepend meta line: rewrite file with meta first
                # (simpler than buffering; exports are typically <50 MB)
            # re-open and prepend
            original_text = args.out.read_text(encoding="utf-8")
            with args.out.open("w", encoding="utf-8") as fh:
                fh.write(json.dumps(meta, ensure_ascii=False) + "\n")
                fh.write(original_text)
            print(f"[export] wrote {chunk_count} chunks, {len(doc_ids)} docs → {args.out}",
                  file=sys.stderr)
            print(f"[export] snapshotId = {snapshot_id}")
    finally:
        conn.close()
    return 0


def _parse_json_or_none(s: str | None) -> Any:
    if not s:
        return None
    try:
        return json.loads(s)
    except (json.JSONDecodeError, TypeError):
        return s


# ── Sub-command: list-docs ────────────────────────────────────────────
def cmd_list_docs(args: argparse.Namespace) -> int:
    _, chunks = load_snapshot(args.snapshot)
    docs: dict[int, dict] = {}
    for c in chunks:
        did = c["documentId"]
        meta = c.get("metadata", {})
        d = docs.setdefault(did, {
            "documentId": did, "chunks": 0,
            "filename": meta.get("originalFilename", "?"),
            "source": meta.get("source", "?"),
            "version": c.get("documentVersion", "?"),
            "firstSection": None,
        })
        d["chunks"] += 1
        if d["firstSection"] is None and meta.get("sectionPath"):
            d["firstSection"] = " > ".join(meta["sectionPath"][:3])
    print(f"{'docId':>6}  {'chunks':>6}  {'source':<10}  {'version':<10}  filename / first section")
    print("-" * 100)
    for did in sorted(docs):
        d = docs[did]
        print(f"{did:>6}  {d['chunks']:>6}  {d['source'][:10]:<10}  {d['version'][:10]:<10}  "
              f"{d['filename'] or ''}  |  {d['firstSection'] or ''}")
    return 0


# ── Sub-command: search ───────────────────────────────────────────────
def cmd_search(args: argparse.Namespace) -> int:
    _, chunks = load_snapshot(args.snapshot)
    needle = args.query.lower()
    hits = [c for c in chunks if needle in c.get("content", "").lower()]
    if not hits:
        print(f"no chunks match {args.query!r}", file=sys.stderr)
        return 0
    print(f"{len(hits)} chunk(s) match {args.query!r}  (showing top {args.limit}):")
    for c in hits[:args.limit]:
        meta = c.get("metadata", {})
        preview = (c.get("content", "")[:120] + "...").replace("\n", " ")
        print(f"  doc={c['documentId']:>4}  chunk={c['chunkId']:>5}  "
              f"sec={' > '.join(meta.get('sectionPath') or [])[:50]:<50}  "
              f"| {preview}")
    return 0


# ── Sub-command: show-chunk ───────────────────────────────────────────
def cmd_show_chunk(args: argparse.Namespace) -> int:
    _, chunks = load_snapshot(args.snapshot)
    c = find_chunk(chunks, args.document_id, args.chunk_id)
    if not c:
        print(f"chunk not found: documentId={args.document_id} chunkId={args.chunk_id}",
              file=sys.stderr)
        return 1
    meta = c.get("metadata", {})
    print(f"┌─ chunk {c['chunkId']} (doc {c['documentId']}, version {c['documentVersion']})")
    print(f"│  tenant     : {meta.get('tenantId')}")
    print(f"│  source     : {meta.get('source')}  language: {meta.get('language')}  "
          f"visibility: {meta.get('visibility')}")
    print(f"│  type       : {meta.get('chunkType')}  seq: {meta.get('seq')}  page: {meta.get('page')}")
    if meta.get("sectionPath"):
        print(f"│  section    : {' > '.join(meta['sectionPath'])}")
    if meta.get("parentChunkId"):
        print(f"│  parent     : chunk {meta['parentChunkId']}")
    print(f"│  filename   : {meta.get('originalFilename', '?')}")
    print(f"│  bytes      : {len(c.get('content', ''))}")
    print(f"│  hash (decl): {c.get('contentHash')}")
    local_hash = content_hash(c.get("content"))
    match_mark = "✓" if local_hash == c.get("contentHash") else "✗"
    print(f"│  hash (comp): {local_hash}  {match_mark}")
    print(f"├─ content ─────────────────────────────────────────────────")
    for ln, line in enumerate(c.get("content", "").splitlines(), 1):
        print(f"│ {ln:>4} │ {line}")
    print(f"└───────────────────────────────────────────────────────────")
    return 0


# ── Sub-command: compute-hash ─────────────────────────────────────────
def cmd_compute_hash(args: argparse.Namespace) -> int:
    if args.from_chunk:
        _, chunks = load_snapshot(args.snapshot)
        c = find_chunk(chunks, args.document_id, args.chunk_id)
        if not c:
            print(f"chunk not found", file=sys.stderr)
            return 1
        text = c.get("content", "")
    elif args.text:
        text = args.text
    elif args.file:
        text = Path(args.file).read_text(encoding="utf-8")
    else:
        text = sys.stdin.read()
    h = content_hash(text)
    print(h)
    return 0


# ── Sub-command: make-evidence ────────────────────────────────────────
def cmd_make_evidence(args: argparse.Namespace) -> int:
    """Generate a gold evidence record from a snapshot chunk.

    The annotator still writes `referenceAnswer`, `rationale`, `requirementId` binding,
    and decides whether the chunk is appropriate. This helper only automates the
    hash + ID generation + schema shape.
    """
    _, chunks = load_snapshot(args.snapshot)
    c = find_chunk(chunks, args.document_id, args.chunk_id)
    if not c:
        print(f"chunk not found: documentId={args.document_id} chunkId={args.chunk_id}",
              file=sys.stderr)
        return 1

    meta = c.get("metadata", {})
    tenant_id = meta.get("tenantId") or args.tenant_id or "default"
    content = c.get("content", "")

    # Hashes
    c_hash = content_hash(content)
    eid = evidence_id(tenant_id, args.document_id, args.chunk_id, c_hash)

    # Verify content hash matches what snapshot declares
    declared = c.get("contentHash")
    if declared != c_hash:
        print(f"WARN: snapshot contentHash {declared} != recomputed {c_hash}",
              file=sys.stderr)

    # Build snippet — NOT the answer, just enough text for the LLM-as-judge to recognize context
    snippet = content[:300] + ("..." if len(content) > 300 else "")

    req_ids = args.requirement_ids or ["REQ-1"]

    evidence_obj = {
        "documentId": args.document_id,
        "chunkId": args.chunk_id,
        "contentHash": c_hash,
        "evidenceId": eid,
        "documentVersion": c.get("documentVersion", "unknown"),
        "contentSnippet": snippet,
        "bindsToRequirementIds": req_ids,
        "rationale": "",  # ← annotator MUST fill (gold_annotation_guideline.md §6.1)
    }
    gold_record = {
        "gold": {
            "referenceAnswer": "",  # ← annotator MUST fill, NOT copied from content
            "answerable": True,
            "evidence": [evidence_obj],
            "goldCoverageByRequirement": {rid: [eid] for rid in req_ids},
        },
        "review": {
            "annotator": args.annotator or "",
            "reviewer": args.reviewer or "",
            "reviewedAt": "",
            "reviewStatus": "candidate",
        },
        "_meta": {
            "sourceSnapshot": str(args.snapshot),
            "tenantId": tenant_id,
            "evidenceIdFormat": "sha256(tenant|doc|chunk|contentHash) — full 64 hex, no truncation",
            "warning": "DO NOT paste contentSnippet into referenceAnswer — leakage guard in "
                       "validate_gold_dataset.py will reject (gold_dataset_audit.md §6.1)",
        }
    }

    out = json.dumps(gold_record, ensure_ascii=False, indent=2)
    if args.out:
        Path(args.out).write_text(out, encoding="utf-8")
        print(f"wrote evidence record → {args.out}", file=sys.stderr)
    else:
        print(out)
    return 0


# ── CLI ───────────────────────────────────────────────────────────────
def build_argparser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Gold annotation helper (PR-7f.2c.1.5 Task 4)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    # export
    pe = sub.add_parser("export", help="Build snapshot NDJSON from MySQL")
    pe.add_argument("--tenant-id", default=None)
    pe.add_argument("--host", default=None)
    pe.add_argument("--port", type=int, default=None)
    pe.add_argument("--user", default=None)
    pe.add_argument("--password", default=None)
    pe.add_argument("--db", default=None)
    pe.add_argument("--out", type=Path, required=True)
    pe.set_defaults(func=cmd_export)

    # list-docs
    pld = sub.add_parser("list-docs", help="List distinct documents in snapshot")
    pld.add_argument("--snapshot", type=Path, required=True)
    pld.set_defaults(func=cmd_list_docs)

    # search
    ps = sub.add_parser("search", help="Full-text search across chunk content")
    ps.add_argument("query")
    ps.add_argument("--snapshot", type=Path, required=True)
    ps.add_argument("--limit", type=int, default=20)
    ps.set_defaults(func=cmd_search)

    # show-chunk
    psc = sub.add_parser("show-chunk", help="Display chunk full content + metadata")
    psc.add_argument("--document-id", type=int, required=True)
    psc.add_argument("--chunk-id", type=int, required=True)
    psc.add_argument("--snapshot", type=Path, required=True)
    psc.set_defaults(func=cmd_show_chunk)

    # compute-hash
    pch = sub.add_parser("compute-hash", help="Recompute sha256 of text/file/chunk")
    pch.add_argument("--snapshot", type=Path, default=None)
    pch.add_argument("--from-chunk", action="store_true")
    pch.add_argument("--document-id", type=int, default=None)
    pch.add_argument("--chunk-id", type=int, default=None)
    pch.add_argument("--text", default=None)
    pch.add_argument("--file", default=None)
    pch.set_defaults(func=cmd_compute_hash)

    # make-evidence
    pme = sub.add_parser("make-evidence", help="Generate a gold evidence record from a chunk")
    pme.add_argument("--snapshot", type=Path, required=True)
    pme.add_argument("--document-id", type=int, required=True)
    pme.add_argument("--chunk-id", type=int, required=True)
    pme.add_argument("--annotator", default="")
    pme.add_argument("--reviewer", default="")
    pme.add_argument("--tenant-id", default=None, help="Override tenant_id (defaults to snapshot metadata)")
    pme.add_argument("--requirement-ids", nargs="*", default=None,
                     help="Requirement IDs this evidence binds to (default: REQ-1)")
    pme.add_argument("--out", type=Path, default=None,
                     help="Write JSON to file instead of stdout")
    pme.set_defaults(func=cmd_make_evidence)

    return p


def main(argv: list[str] | None = None) -> int:
    args = build_argparser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
