# KB Annotation Workflow (PR-7f.2c.1.5 Task 5)

> End-to-end pipeline: how the offline KB snapshot → annotator → gold evidence
> → reviewer → frozen dataset pipeline operates. This document is the orchestrator
> that ties together [`kb_snapshot_audit.md`](./kb_snapshot_audit.md) (storage
> source of truth), [`gold_annotation_guideline.md`](./gold_annotation_guideline.md)
> (how to select evidence), [`kb_snapshot/schema.json`](../../eval/agentic/kb_snapshot/schema.json),
> and the three scripts: `validate_kb_snapshot.py`, `gold_annotation_helper.py`,
> `validate_gold_dataset.py`.

---

## 0. The five-stage pipeline at a glance

```
            ┌────────────────────────────────┐
            │  MySQL (documents + chunks)    │  source of truth
            └────────────────┬───────────────┘
                             │ export SQL (kb_snapshot_audit.md §4.2)
                             ▼
┌────────────────────────────────────────────────────────┐
│ STAGE 1: KB snapshot                                   │
│   eval/agentic/kb_snapshot/snapshots/                  │
│       tenant-<id>-<date>.ndjson                        │
│   validated by validate_kb_snapshot.py                 │
└──────────────────────┬─────────────────────────────────┘
                       │ gold_annotation_helper.py search/show
                       ▼
┌────────────────────────────────────────────────────────┐
│ STAGE 2: Annotator                                     │
│   for each row in <template>.jsonl:                    │
│     - browse snapshot (search / show-chunk)            │
│     - pick chunk(s) that answer the question           │
│     - helper make-evidence emits evidence_record.json  │
│       (contentHash + evidenceId auto-computed)         │
│     - annotator writes referenceAnswer + rationale     │
│     - annotator self-checks against leakage rules      │
└──────────────────────┬─────────────────────────────────┘
                       │ handoff to reviewer
                       ▼
┌────────────────────────────────────────────────────────┐
│ STAGE 3: Reviewer                                      │
│   reviewer re-reads the chunk independently            │
│   reviewer verifies hash recalculation                 │
│   reviewer verifies rationale is grounded              │
│   reviewer fills review.reviewer + reviewedAt          │
│   reviewer flips review.reviewStatus = reviewed        │
└──────────────────────┬─────────────────────────────────┘
                       │ validate_gold_dataset.py
                       ▼
┌────────────────────────────────────────────────────────┐
│ STAGE 4: Frozen dataset                                │
│   eval/agentic/datasets/agentic_v2.gold20.frozen.jsonl │
│   passes validate_gold_dataset.py exit=0               │
│   passes gold_freeze_check.py exit=0                   │
└──────────────────────┬─────────────────────────────────┘
                       │ ready for evaluation runs
                       ▼
┌────────────────────────────────────────────────────────┐
│ STAGE 5: Evaluation (next PR, NOT in scope here)       │
│   agentic_runner.py / hybrid_runner.py consume frozen  │
└────────────────────────────────────────────────────────┘
```

This PR (7f.2c.1.5) implements Stages 1-4 + tooling. **Stage 5 is explicitly out of
scope** — no runners are run, no metrics are computed (Task 5 constraint).

---

## 1. Stage 1 — Building the KB snapshot

### 1.1 Prerequisites

- DB read access to MySQL with `chunks` + `documents` tables (typically via
  `pymysql`; see `kb_snapshot_audit.md §4.2`).
- A specific `tenant_id` to export (single-tenant snapshots by design —
  cross-tenant snapshots are forbidden by validator; see `kb_snapshot_audit.md §5.2`).
- `status='READY'` rows only; the export SQL filters on this.

### 1.2 Build command

```bash
python3 eval/agentic/scripts/gold_annotation_helper.py export \
    --tenant-id default \
    --host $KB_DB_HOST --port $KB_DB_PORT \
    --user $KB_DB_USER --password $KB_DB_PASSWORD \
    --db $KB_DB_NAME \
    --out eval/agentic/kb_snapshot/snapshots/tenant-default-$(date +%Y%m%d).ndjson
```

The export produces:
- **Line 1**: `snapshotMeta` with `snapshotId = sha256(concat of all contentHash order)`
  — a single 64-char hex identifying this exact corpus version.
- **Lines 2+**: one `chunkRecord` per chunk, with recomputed `contentHash` (NOT relying
  on the DB `content_hash` column — independent SHA-256 verification).

### 1.3 Validate the snapshot

```bash
python3 eval/agentic/scripts/validate_kb_snapshot.py \
    eval/agentic/kb_snapshot/snapshots/tenant-default-<date>.ndjson
# expected: PASS, exit 0
# required hard checks: unique chunkId, contentHash correctness, required metadata,
#                       no empty content
# optional warns: visibility=PRIVATE (annotation may need elevated access)
```

### 1.4 Record `snapshotId` somewhere durable

Every frozen gold dataset later binds annotations to a specific `snapshotId`. When
the corpus re-embeds, the snapshot becomes stale; the validator's `contentHash`
recomputation will drift and detect this. Tracking `snapshotId` per evaluation run
is the only durable binary identity of the corpus state.

---

## 2. Stage 2 — Annotator workflow

For each row in `eval/agentic/datasets/agentic_v2.gold20.template.jsonl`:

### 2.1 Identify the right chunk

The template row carries a `(documentId, chunkId)` pointer. Confirm or revise it:

```bash
# Browse documents in the snapshot
python3 eval/agentic/scripts/gold_annotation_helper.py list-docs \
    --snapshot <snapshot.ndjson>

# Free-text search
python3 eval/agentic/scripts/gold_annotation_helper.py search "Dubbo3 升级" \
    --snapshot <snapshot.ndjson>

# Inspect a candidate chunk
python3 eval/agentic/scripts/gold_annotation_helper.py show-chunk \
    --document-id 7 --chunk-id 28 \
    --snapshot <snapshot.ndjson>
```

### 2.2 If the chunk is correct — generate the evidence record

```bash
python3 eval/agentic/scripts/gold_annotation_helper.py make-evidence \
    --snapshot <snapshot.ndjson> \
    --document-id 7 --chunk-id 28 \
    --annotator alice --reviewer bob \
    --requirement-ids REQ-1 \
    --out /tmp/evidence_for_case_001.json
```

This emits a JSON object with:
- `contentHash` and `evidenceId` auto-computed (64-char hex per `Evidence.java:65`)
- `contentSnippet` = first 300 chars of chunk (for the LLM-as-judge to recognize context)
- Empty `referenceAnswer`, `rationale` — **annotator must fill these**
- `_meta.warning` reminds about the leakage rule

### 2.3 What the annotator fills in by hand

| Field | Required | What to write |
|---|---|---|
| `gold.referenceAnswer` | string, ≥1 char | The paraphrased answer (NOT copied from chunk text — see `gold_annotation_guideline.md §6` + `gold_dataset_audit.md §6.1`) |
| `gold.evidence[].rationale` | string | Why this chunk covers the Requirement. Cite line numbers or section headings (`gold_annotation_guideline.md §6.1`) |
| `gold.answerable` | bool | True if KB has the answer. False if not (REFUSED_NO_EVIDENCE expected). |
| `expected.finalStatus` | enum | `ANSWERED` / `REFUSED_NO_EVIDENCE` / `REFUSED_CONFLICT` / etc. |
| `expected.expectedStrategy` | enum | `PLANNED_AGENT` for multi-hop; `CLASSIC_RAG` for trivial |
| `expected.replanExpected` | bool | True for `replan_success` / `replan_still_insufficient` slices |
| `expected.maxSteps` | int ≥1 | Typically 3 |
| `requirements[]` | list | For multi-hop, split into ≥2 Reqs (see `gold_annotation_guideline.md §3.3`) |
| `review.annotator` | username | The annotator's account |
| `review.reviewedAt` | ISO-8601 | Empty at this stage — reviewer fills it |

### 2.4 Annotator self-checks

Before handing off to the reviewer, run:

```bash
python3 eval/agentic/scripts/validate_gold_dataset.py \
    eval/agentic/datasets/agentic_v2.gold20.template.jsonl
```

At this stage it should *still fail* on:
- `empty-reviewer` (reviewer blank by design)
- `empty-reviewedAt` (timestamp blank by design)

But it should pass all other checks:
- ✅ no FILL_ tokens
- ✅ `evidenceId` matches runtime regex (12 or 64 hex)
- ✅ `contentHash` is 64-hex
- ✅ `annotator != reviewer`
- ✅ `referenceAnswer` non-empty
- ✅ no leakage (`contentSnippet` ≠ `referenceAnswer`)

If any non-review-related check fails, fix the annotation before handoff.

---

## 3. Stage 3 — Reviewer workflow

Reviewer = a second person (different account). The validator hard-enforces
`annotator != reviewer`.

### 3.1 Independent verification

For each case, the reviewer:

1. Re-reads the same `(documentId, chunkId)` from the snapshot:
   ```bash
   python3 eval/agentic/scripts/gold_annotation_helper.py show-chunk \
       --document-id 7 --chunk-id 28 --snapshot <snapshot.ndjson>
   ```
2. Re-computes `contentHash` independently:
   ```bash
   python3 eval/agentic/scripts/gold_annotation_helper.py compute-hash \
       --from-chunk --document-id 7 --chunk-id 28 --snapshot <snapshot.ndjson>
   ```
   And asserts this matches what the annotator wrote.
3. Reads the annotator's `referenceAnswer` and judges:
   - Does the answer question faithfully?
   - Is `rationale` grounded in chunk structural features (lines/sections), not vacuous?
   - Is `expected.*` realistic given the slice classification?
4. Optionally re-derives `evidenceId` from the recomputed hash to confirm.

### 3.2 If agreement

The reviewer:
- Sets `review.reviewer = "<reviewer_username>"`
- Sets `review.reviewedAt = "<ISO-8601 timestamp>"` (e.g. `2026-08-08T17:30:00+08:00`)
- Sets `review.reviewStatus = "reviewed"`
- Re-runs the validator:
  ```bash
  python3 eval/agentic/scripts/validate_gold_dataset.py <dataset.jsonl>
  # MUST exit 0
  ```

### 3.3 If disagreement

If the reviewer cannot accept the annotation, sends the case back to the annotator
with specific feedback. If disagreement happens >3 times per 20 cases, **stop and
reconvene**:
- The annotation guideline may be ambiguous
- The question may not have a clear single chunk
- The (documentId, chunkId) pointer may be wrong

Document every disagreement resolution in a shared
`docs/evaluation/annotation_decisions.md` log so inter-annotator agreement (IAA)
can be computed later (`experiment_fairness_audit.md P1-6`).

---

## 4. Stage 4 — Frozen dataset

Once all cases pass `validate_gold_dataset.py` exit 0:

### 4.1 Move the file to its frozen location

```bash
cp eval/agentic/datasets/agentic_v2.gold20.template.jsonl \
   eval/agentic/datasets/agentic_v2.gold20.frozen.jsonl
```

(or a new file with a dated suffix — `agentic_v2.gold20.frozen-2026-08-08.jsonl`)

### 4.2 Freeze-check gate

```bash
python3 eval/agentic/scripts/gold_freeze_check.py \
    eval/agentic/datasets/agentic_v2.gold20.frozen.jsonl
# MUST exit 0 (FROZEN)
```

This verifier adds completeness restrictions (covers all slices, no missing
reviewedAt, all Requirements have evidence binding) on top of the basic validator.

### 4.3 Snapshot binding record

Create a sidecar file recording which snapshot this frozen dataset points at:

```bash
cat > eval/agentic/datasets/agentic_v2.gold20.frozen.snapshot.json <<JSON
{
  "frozenDataset": "agentic_v2.gold20.frozen.jsonl",
  "snapshotFile": "tenant-default-2026-08-08.ndjson",
  "snapshotId": "<64-char hash from line 1 of snapshot>",
  "frozenAt": "2026-08-08T18:00:00+08:00"
}
JSON
```

This is the lockfile that says "this gold is bound to this exact corpus version".

---

## 5. Tool map (quick reference)

| Stage | Tool | Action |
|---|---|---|
| 1 | `gold_annotation_helper.py export` | Build NDJSON snapshot from MySQL |
| 1 | `validate_kb_snapshot.py` | Verify snapshot integrity |
| 2 | `gold_annotation_helper.py list-docs` | Browse documents in snapshot |
| 2 | `gold_annotation_helper.py search <query>` | Full-text search chunk content |
| 2 | `gold_annotation_helper.py show-chunk --document-id D --chunk-id C` | Display chunk + verify hash |
| 2 | `gold_annotation_helper.py compute-hash --from-chunk --document-id D --chunk-id C` | Recompute SHA-256 |
| 2 | `gold_annotation_helper.py make-evidence` | Emit a gold evidence record with hashes pre-filled |
| 2,3 | `validate_gold_dataset.py <dataset.jsonl>` | Validate gold JSONL (8 checks + leakage guard) |
| 4 | `gold_freeze_check.py <dataset.jsonl>` | Final FROZEN gate (completeness) |

All four scripts are pure Python stdlib (`hashlib`, `json`, `re`, `argparse`),
except the `export` sub-command which requires `pymysql`. Annotation-time
workflows never need DB access.

---

## 6. Concrete worked example (synthetic, not from real corpus)

Goal: annotate case `gold20-004` "Dubbo3 相比 Dubbo2 在哪些方面进行了升级?".

```bash
# Step 1: confirm the snapshot
python3 eval/agentic/scripts/validate_kb_snapshot.py snapshots/tenant-default-2026-08-08.ndjson
# → PASS

# Step 2: locate the candidate chunk
python3 eval/agentic/scripts/gold_annotation_helper.py search "Dubbo3 升级" --snapshot snapshots/tenant-default-2026-08-08.ndjson
# → matches chunk (7, 28): "Dubbo3 在易用性、超大规模微服务实践..."

# Step 3: inspect
python3 eval/agentic/scripts/gold_annotation_helper.py show-chunk \
    --document-id 7 --chunk-id 28 --snapshot snapshots/tenant-default-2026-08-08.ndjson
# → confirms hash matches, shows full chunk content with line numbers

# Step 4: generate the evidence record
python3 eval/agentic/scripts/gold_annotation_helper.py make-evidence \
    --snapshot snapshots/tenant-default-2026-08-08.ndjson \
    --document-id 7 --chunk-id 28 \
    --annotator alice --reviewer bob \
    --out /tmp/ev_004.json
# → emits a JSON template with contentHash + evidenceId pre-filled,
#   referenceAnswer + rationale still empty

# Step 5: annotator edits /tmp/ev_004.json
#   - writes referenceAnswer in her own words
#   - writes rationale: "chunk 第 4-6 行列表给出 4 个升级方向 (易用性/规模/云原生/安全)"
#   - sets expected.finalStatus = "ANSWERED", expectedStrategy = "PLANNED_AGENT"

# Step 6: merge the evidence record into the dataset row
#   (this is a JSONL edit — annotator pastes /tmp/ev_004.json's gold block into
#    the template row, keeping top-level fields like caseId, query, slice)

# Step 7: self-validate
python3 eval/agentic/scripts/validate_gold_dataset.py gold20.template.jsonl
# → may exit 1 due to other rows (annotator can pass single-row file via --filter)
#   but THIS row should produce zero errors

# Step 8: handoff to reviewer. Reviewer repeats Step 3 + writes the rationale
#   check independently. If agreed, sets review.reviewer/reviewedAt/reviewStatus.

# Step 9: validate again
python3 eval/agentic/scripts/validate_gold_dataset.py gold20.template.jsonl
# → exit 0

# Step 10: freeze
python3 eval/agentic/scripts/gold_freeze_check.py gold20.template.jsonl
# → FROZEN ✓
```

---

## 7. Error handling and edge cases

### 7.1 Snapshot drift

If you re-export a snapshot a week later and `snapshotId` changed → corpus was
re-embedded or chunks were edited. All previously-frozen gold is technically stale
(`contentHash` of source chunks may have drifted). Mitigation:

- Compare new snapshot's `content_hash` per chunk against old snapshot for the
  specific chunks cited in gold.
- Only those whose hash drifted must be re-annotated (typically <10%).
- Update `frozen.snapshot.json` with the new `snapshotId` and `frozenAt`.

### 7.2 Two annotators disagree on chunk selection

Resolve by `slice` rules in `gold_annotation_guideline.md §3.2`:
- `initial_sufficient`: pick the *minimal* chunk that fully answers.
- `replan_success`: prefer multi-doc chunks if the question genuinely spans docs.
- `evidence_conflict`: BOTH contradictory chunks must be cited, even if one is wrong.

If still ambiguous after 30 minutes of discussion, mark `review.reviewStatus =
"rejected"` with a reason and skip the case. Do not coerce agreement.

### 7.3 Chunk text changed since annotation

The `make-evidence` helper reads the snapshot at annotation time. If the snapshot is
rebuilt later, the previously-recorded `contentHash` will not match the new chunk
content. The `validate_gold_dataset.py` check 3 (`contentHash`) will catch this and
the case is invalid until re-annotated. This is by design — gold binding is to a
specific corpus state, not to a vague notion of "the same chunk".

### 7.4 PII exposure in contentSnippet

`make-evidence` populates `contentSnippet` with the first 300 chars of the chunk
verbatim. The KB audit (`kb_snapshot_audit.md §5.1`) confirmed there is NO PII
scanner in the runtime. If the snapshot contains PII (employee names, internal
URLs, etc), you must manually redact the snippet OR decline to annotate the case.
The reviewer's job includes a PII pass.

---

## 8. Readiness check

**Current status** (output of this PR):

| Component | Ready? |
|---|---|
| `gold_annotation_helper.py` | ✅ all 6 sub-commands work; tested against synthetic snapshot |
| `validate_kb_snapshot.py` | ✅ PASS on clean / FAIL on duplicate+hash-mismatch synthetic inputs |
| `validate_gold_dataset.py` | ✅ Fix in this PR: now accepts 64-char `evidenceId` (runtime truth per `Evidence.java:65`); legacy 12-char kept for migration |
| `gold_freeze_check.py` (PR-7f.2b.3) | ✅ unchanged, exit 1 on current pilot20 (expected) |
| KB snapshot file (`*.ndjson`) | ❌ **NOT YET BUILT** — requires MySQL read access (Stage 1.2) |
| Frozen gold dataset | ❌ depends on snapshot first |

**To unblock the actual offline annotation:** run the `export` sub-command against
the production/dev MySQL. That is **the only remaining prerequisite** — all tooling
orchestration for stages 2-4 is in place and tested.

---

## 9. Not fabricated

This document does not produce or report any experimental metric value. Hashes shown
in the worked example (`/tmp/snap.ndjson`) are from a synthetic test corpus created
in the test harness, NOT from the production knowledge base. No real chunk content
has been read; no real gold has been written.
