# KB snapshots are NOT committed to git.

This directory holds generated NDJSON snapshots of the knowledge base chunks,
produced by:

```
python3 eval/agentic/scripts/gold_annotation_helper.py export \
    --tenant-id <tenantId> \
    --host <mysql-host> --port <mysql-port> \
    --user <user> --password <password> --db <db> \
    --out eval/agentic/kb_snapshot/snapshots/tenant-<id>-<date>.ndjson
```

## Why snapshots are git-ignored

1. **Contain real chunk text** — these are verbatim KB contents (potentially
   including internal/source-corpus content covered by license or privacy
   restrictions). Not appropriate to ship in a public-readable repo.
2. **Drift with KB** — every corpus re-ingest invalidates the snapshot. Tracking
   a stale snapshot in git gives false confidence.
3. **Size** — typical snapshot is ~10 MB of JSONL; multi-tenant could be 100s of MB.
   Git is the wrong transport.
4. **Reproducibility comes from `snapshotId`, not file** — every snapshot embeds
   `snapshotMeta.snapshotId = sha256(concat all contentHash)`. A gold dataset binds
   to a `snapshotId`; reviewers re-export locally and confirm the hash matches.

## How to regenerate

1. Stand up MySQL with the `ragdoc` schema (see project root README / docker-compose).
2. Run the `export` subcommand above. The script:
   - Joins `chunks JOIN documents ON documents.id = chunks.document_id`
   - Filters `documents.deleted_at IS NULL AND documents.status='READY' AND documents.tenant_id=:tenantId`
   - Recomputes `contentHash = sha256(content)` in Python (NOT trusting DB column)
   - Writes a `snapshotMeta` line followed by N chunk records
3. Validate:
   ```
   python3 eval/agentic/scripts/validate_kb_snapshot.py \
       eval/agentic/kb_snapshot/snapshots/tenant-<id>-<date>.ndjson
   ```
   Expected: PASS, exit 0, 0 duplicates, 0 hash mismatches.
4. Record the `snapshotId` in your gold dataset's sidecar JSON
   (e.g. `agentic_v2.gold20.frozen.snapshot.json`) — this is the binding that lets
   future reviewers detect drift.

## When to re-export

- Before any new annotation pass (so annotators see current chunks)
- Before any evaluation run (so runner has consistent grounding)
- After corpus re-ingest (the most recent snapshot supersedes; prior snapshots
  become invalid and should be deleted from this directory)
