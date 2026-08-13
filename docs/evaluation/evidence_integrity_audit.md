# Evidence Integrity Audit (PR-7f.2c.2 Task 1)

> Scope: `eval/agentic/datasets/agentic_v2.pilot20.jsonl` (legacy pilot),
> `eval/agentic/datasets/agentic_v2.gold20.template.jsonl` (new gold candidate).
>
> Cross-checked against `eval/agentic/kb_snapshot/snapshots/tenant-default-2026-08-08.ndjson`
> (2208 chunks / 100 docs, exported from local `ragdoc-mysql` on 2026-08-08).
>
> Method: NDJSON fields parsed, every `(documentId, chunkId)` pair looked up in the
> snapshot; placeholder strings detected by regex; hash formats verified.
> Read-only. **No dataset content modified.**

---

## Final verdict: **FAIL — evidence not integrity-safe**

Both datasets contain **structural data corruption** that predates this PR. The
pilot20 and gold20 evidence pointers (`documentId`, `chunkId`) are inconsistent
with the actual KB state. Any recall metric computed against these datasets today
is meaningless.

---

## 1. Cross-check matrix

| Dataset | Cases | Total evidence | Placeholders | Doc/chunk mismatch | Format valid |
|---|---|---|---|---|---|
| **pilot20** | 20 | 12 | **12/12 (100%)** | **12/12 (100%)** | 0/12 |
| **gold20** | 20 | 20 | 20/20 (intentional template) | **20/20 (100%)** | n/a (template) |
| **snapshot** | — | 2208 chunks | 0 | n/a | 2208/2208 |

### Doc mismatch interpretation

For pilot20 (12 answerable cases), each case carries `(documentId, chunkId)` from
`golden_v2_grounded.jsonl`. But:

| caseId | pilot `docId` | `chunkId` | snapshot says chunk belongs to doc |
|---|---|---|---|
| amh-001 | 6 | 2235 | doc 109 |
| amh-002 | 32 | 28 | doc 1 |
| amh-003 | 20 | 1155 | doc 47 |
| amh-004 | 7 | 28 | doc 1 |
| amh-005 | 38 | 75 | doc 21 |
| amh-006 | 13 | 373 | doc 86 |
| … all 12 | corrupted | corrupted | different doc per row |

For gold20 (extracted from the same `golden_v2_grounded.jsonl` in PR-7f.2c.1 Task 2),
**20/20 chunks exist in snapshot**, but **20/20 `documentId` claims are wrong**:

| caseId | gold20 claims `doc=` | `chunk=` | snapshot says actual doc= |
|---|---|---|---|
| gold20-001 | 16 | 697 | **31** |
| gold20-003 | 51 | 1782 | **86** |
| gold20-005 | 7 | 38 | **1** |
| gold20-013 | 7 | 28 | **1** |
| gold20-016 | 6 | 2235 | **109** |
| gold20-017 | 32 | 28 | **1** |
| gold20-020 | 7 | 36 | **1** |
| … all 20 | wrong | ok | different |

**Root cause** (only reasonable explanation): `golden_v2_grounded.jsonl` was
generated on an older corpus version (100 → 109 document rows and re-indexed
chunks). When the corpus was re-ingested, `document.id` and `chunk.id` were
reassigned (auto-increment went through different paths), but `golden_v2_grounded`
was never refreshed. *Chunks are still findable by text (via full-text search),
but the numeric pointers are dangling.*

---

## 2. Placeholder audit

| Placeholder type | pilot20 count | gold20 count | Status |
|---|---|---|---|
| `evidenceId = "FILL_FROM_SHA256"` | 12/12 | 0/20 (empty OK in template) | pilot BLOCKED |
| `contentHash = "FILL_FROM_REAL_CHUNK"` | 12/12 | 0/20 (empty OK in template) | pilot BLOCKED |
| `documentVersion = "FILL_VERSION"` | 12/12 | 0/20 (empty OK in template) | pilot BLOCKED |
| `reviewer = "TODO"` | 12/12 | 20/20 (empty, expected) | template stage |
| `annotator = "TODO"` | 20/20 | 20/20 (empty, expected) | template stage |
| `reviewedAt = ""` | 20/20 | 20/20 (empty, expected) | template stage |

**Pilot20 evidence is unusable as-is** — every evidence entry has FILL_ markers
AND wrong documentId. **Gold20 is structurally a template** (correct by design —
Task 2 deliverable), so blank fields are expected, not failures.

---

## 3. contentHash verification (computable only for gold20 chunks)

For the **20/20 gold20 chunks that exist in snapshot**, recomputing `sha256(content)`
against `chunks.content_hash` (DB column) shows perfect agreement on the snapshot
side (2208/2208 validator PASS — see PR-7f.2c.1.5 E2E test).

So the snapshot is **hash-trustworthy**; the gold datasets **point to the wrong
rows** in the snapshot.

### contentHash format audit

| Pattern | Expected | pilot20 status | Runtime source |
|---|---|---|---|
| `^[a-f0-9]{64}$` | 64 lowercase hex (matches `Evidence.sha256` L85-93, `TikaParsingTrigger` L405) | 0/12 match — all use literal `FILL_FROM_REAL_CHUNK` | `Evidence.java:64` |
| `^[a-f0-9]{12}$` | legacy 12-hex (accidentally used in some PR-7f.2a annotations) | 0/12 | superseded |
| `^[a-f0-9]{12,64}$` | gold20 evidenceId tolerant form (PR-7f.2c.1.5 fix) | 0/12 → FAIL by design | n/a |

The runtime truth (from `Evidence.java:64-65`) is **64-hex contentHash + 64-hex
evidenceId**. Confirmed by reading chunk content from MySQL and recomputing
sha256: matches `chunks.content_hash` byte-for-byte in 2208/2208 cases.

---

## 4. evidenceId runtime conformance

Pilot20 evidence: **0/12** valid evidenceId. Gold20 evidence: **all empty strings**
(by template design, sentinel that means "annotator must fill").

For any future-filled gold row, the evidenceId MUST be:
```
evidenceId = sha256(tenantId + "|" + documentId + "|" + chunkId + "|" + contentHash)
             → full 64-char lowercase hex
```
NOT truncated to 12 chars. PR-7f.2c.1.5 fixed `validate_gold_dataset.py` to
accept both `12` and `64` for migration tolerance, but annotation MUST emit 64.

---

## 5. snapshot completeness against gold needs

The 2208-chunk snapshot (single-tenant, status=READY, exported 2026-08-08) covers
only the local Docker MySQL. Production KB may have additional tenants or larger
document counts.

- Snapshot is structurally valid (`validate_kb_snapshot.py` PASS, 0 dup / 0 hash mismatch)
- All 20 gold20 chunk pointers resolve to a chunk *somewhere* in the snapshot
- But the snapshot lacks the `(documentId, chunkId)` ordering that pilot
  assumes — that ordering was lost when the corpus was re-indexed

---

## 6. Blocker enumeration

| # | Blocker | Impact |
|---|---|---|
| B1 | 12/12 pilot20 evidence slots use `FILL_*` placeholders | recall/coverage metrics return None |
| B2 | 32/32 (pilot20 + gold20) documentId pointers are wrong vs current snapshot | Any retrieval metric using these pointers returns misleading results |
| B3 | Pilot20 hashes are all literal placeholders, none recomputable | Cannot anchor annotation to corpus version |
| B4 | `golden_v2_grounded.jsonl` itself has stale `(doc,chunk)` pairs | Source of contamination — see Recommendation R1 |

---

## 7. Recommendations

| # | Recommendation | Priority |
|---|---|---|
| R1 | **Re-source `golden_v2_grounded` pointers OR re-build gold from scratch** — current source is contaminated. Easiest path: for each (question, answer_text) in golden_v2_grounded, run snapshot full-text search; the chunk hit becomes the new ground truth pointer. | P0 (blocks all eval) |
| R2 | Once R1 done, regenerate `gold20` template from the cleaned source | P0 |
| R3 | Annotator workflow: for each case, use `gold_annotation_helper.py make-evidence` (PR-7f.2c.1.5) to materialize real hashes + evidenceId | P1 |
| R4 | Add a "snapshot drift" check: before any eval run, verify every gold `(doc,chunk)` still exists with matching `contentHash` against current snapshot | P1 |
| R5 | Reject `FILL_*` literal at validator level (already done in `validate_gold_dataset.py`) | P2 — done |

---

## 8. Not fabricated

No metric values produced. No dataset rows modified. Cross-match counts are
direct outputs of `python3 /tmp/cross_audit.py` against the actual files.
