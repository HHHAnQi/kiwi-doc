# Schema Migration Audit (PR-7f.2c.2 Task 4)

> Scope: `eval/agentic/datasets/agentic_v2.pilot20.jsonl` (legacy PR-7f.2a),
> `eval/agentic/datasets/agentic_v2.gold20.template.jsonl` (new PR-7f.2c.1 Task 2),
> `eval/agentic/scripts/validate_*.py`, `eval/agentic/schemas/agentic_case_v2.schema.json`,
> `eval/agentic/kb_snapshot/schema.json`.
>
> Read-only. **No file modified.**

---

## Final verdict: dual-schema coexistence is brittle. **Recommend Option C (formal migration)** with phased rollout.

Three options analyzed below; each has pros/cons. The recommendation (Option C) is
the only one that produces a single source of truth without breaking the existing
pilot20 audit trail.

---

## 1. Field-by-field diff

| Concept | pilot20 (legacy) | gold20 (new) | Equivalent? | Notes |
|---|---|---|---|---|
| Question text | `question` | `query` | rename | gold20 aligns with PR-7f.2c.1 Task 2 spec wording |
| Reference answer | `gold.goldAnswer` | `gold.referenceAnswer` | rename | gold20 clearer |
| Evidence list | `gold.goldEvidence[]` | `gold.evidence[]` | rename | gold20 drops redundant `gold` prefix inside `gold.` |
| Evidence ID | `gold.goldEvidence[].evidenceId` | `gold.evidence[].evidenceId` | same | ok |
| Content hash | `gold.goldEvidence[].contentHash` | `gold.evidence[].contentHash` | same | ok |
| Document ID | `gold.goldEvidence[].documentId` | `gold.evidence[].documentId` | same | ok |
| Chunk ID | `gold.goldEvidence[].chunkId` | `gold.evidence[].chunkId` | same | ok |
| Document version | `gold.goldEvidence[].documentVersion` | `gold.evidence[].documentVersion` | same | ok |
| Coverage map | `gold.goldCoverageByRequirement` | `gold.goldCoverageByRequirement` | same | gold20 keeps it |
| Answerability | `gold.answerable` | `gold.answerable` | same | ok |
| Expected final status | `expected.expectedFinalStatus` | `expected.finalStatus` | rename | gold20 drops `expected` prefix inside `expected.` |
| Expected strategy | absent | `expected.expectedStrategy` | added | gold20 adds; needed for ablation routing |
| maxSteps | `expected.maxSteps` | `expected.maxSteps` | same | ok |
| Plan constraints | `planConstraints.acceptableInitialPlans[]` | absent | removed | gold20 expects this on a separate ablation-config file |
| Review | `review.{annotator,reviewer,reviewedAt,reviewStatus}` | same | same | ok |
| Slice | `slice` | `slice` | same | ok |
| Schema version | `schemaVersion: "v2"` | absent | removed | gold20 tracks via filename |

**Net effect**: 5 renames + 1 added field + 2 removed optional fields. No deep
incompatibility. The renames are the friction point for tools.

---

## 2. Tool impact

| Tool | Accepts pilot20? | Accepts gold20? | Why |
|---|---|---|---|
| `agentic_case_v2.schema.json` (jsonschema) | ✓ | ✗ | schema requires `question`/`goldAnswer` etc. |
| `validate_dataset.py` (PR-7f.2b.3a) | ✓ | ✗ | hardcoded legacy field names |
| `validate_gold_completeness.py` (PR-7f.2b.1) | ✓ | ✗ | reads `goldEvidence`, `goldAnswer` |
| `validate_gold_dataset.py` (PR-7f.2c.1) | ✗ | ✓ | reads `query`, `referenceAnswer`, `finalStatus` |
| `gold_freeze_check.py` (PR-7f.2b.3) | ✓ | ✗ | delegates to `validate_dataset.py` |
| `agentic_runner.py`, `hybrid_runner.py` | ✓ | ✗ | read `question`/`expectedFinalStatus` for case shape |
| `metrics.py` (`evaluate_aggregate`) | ✓ (doesn't read these fields) | ✓ | unaffected |
| `gold_annotation_helper.py` (PR-7f.2c.1.5) | (emits both shapes via `make-evidence`) | ✓ make-evidence emits `evidence[]`+`referenceAnswer` shape | dual-mode |

---

## 3. Three options (analyzed, not yet recommended)

### Option A — Roll back to legacy schema (use pilot20's field names everywhere)

**What changes**:
- Update `eval/agentic/datasets/agentic_v2.gold20.template.jsonl` field names: `query`→`question`, `gold.evidence`→`gold.goldEvidence`, `gold.referenceAnswer`→`gold.goldAnswer`, `expected.finalStatus`→`expected.expectedFinalStatus`
- Update `validate_gold_dataset.py` (PR-7f.2c.1) to read legacy names
- Update `gold_annotation_helper.py make-evidence` to emit legacy shape
- Keep `agentic_case_v2.schema.json` as the canonical schema

**Pros**:
- Single schema, single validator chain
- Reuse the entire legacy toolset (`validate_dataset.py`, `gold_freeze_check.py`)
- Backward-compatible

**Cons**:
- Retains the awkward `gold.goldEvidence[]` / `expected.expectedFinalStatus`
  redundancy
- Drops `expected.expectedStrategy` (ablation routing key)
- Inverts the spec that PR-7f.2c.1 explicitly established
- Discards the cleaner PR-7f.2c.1 Task 2 design

**Effort**: small (~30 minutes, mechanical rename in ~5 files)

---

### Option B — Dual-compatible validators

**What changes**:
- Both `validate_dataset.py` and `validate_gold_dataset.py` gain field-name aliases
  (e.g. `case.get("query") or case.get("question")`)
- `gold_freeze_check.py`, `validate_gold_completeness.py` similarly tolerant
- `schema.json` becomes a `oneOf` between legacy and new shapes

**Pros**:
- Works with both old pilot20 and new gold20 simultaneously
- Smoother transition: pilot20 can stay frozen while gold20 grows
- Annotators can author either schema until consensus

**Cons**:
- Adds branches to every validator — long-term maintenance burden
- `@MockBean`/dataset-dependent code gains `if/else` everywhere
- Risk of silent drift: one tool accidentally enforcing one side breaks the other
- Schema documents more complex; harder to onboard new annotators

**Effort**: medium (~2 hours across 4 validators + 2 runners)

---

### Option C — Formal migration (new schema becomes canonical after pilot20 freeze)

**What changes**:
- Phase 1 (now): keep dual-schema coexistence, but mark legacy as **frozen** — no new datasets in pilot20 schema
- Phase 2 (next PR): migrate `pilot20.jsonl` content into the new schema via a one-time converter script (saved as `scripts/migrate_legacy_gold.py`); archive pilot20 to `datasets/_archive/`
- Phase 3 (post-freeze): delete legacy branches from `validate_dataset.py`, `gold_freeze_check.py`, `validate_gold_completeness.py`

**Pros**:
- Clean end-state: single schema, single tool set
- No tool debt carried forward
- Pilot20 stays intact during migration (audit-trail preservation); archival not deletion

**Cons**:
- Migration script needed (~half day)
- Requires approval commitment to avoid "dual forever" trap
- During Phase 1, two validators still need to be runnable

**Effort**: medium-to-large (Phase 1 ~1 hour to document; Phase 2 ~half day; Phase 3 ~1 hour)

---

## 4. Recommendation: **Option C, with active Phase 1 commitment**

**Reasoning**:

1. Option A is the smallest change but **abandons technical improvement**. Reviewing
   the field-name diff in §1: 4 of 5 renames are objective improvements (dropping
   redundant prefix, consistent verb-noun form). PR-7f.2c.1 already committed to them.
2. Option B's "dual forever" risk is real — every new metric added later inherits
   the fork. Long-term cost is higher than Option C.
3. Option C captures the cleanup with a clear plan. Phase 1 is just "stop adding
   legacy schema"; no actual code changes are blocked.

**Acceptance gate for Phase 2 migration**: only after Task 6 (`snapshots/.gitignore`)
is in place. Otherwise the migration script risks running against drifted KB pointers
(see `evidence_integrity_audit.md` — pilot20 pointers are already stale).

### Concrete recommendation list

| Decision | Value |
|---|---|
| Recommended option | **Option C** |
| Canonical schema (after Phase 2) | `gold20` (PR-7f.2c.1 Task 2) |
| Phase-1 immediate doc | add README note to `agentic_v2.pilot20.jsonl` saying "frozen, do not extend" |
| Migration script (Phase 2) | `scripts/migrate_legacy_gold.py` reads pilot20 → writes pilot20.gold20schema.jsonl |
| Phase 3 deletion gate | after 1 round of dual-validation with no Drift findings |

---

## 5. Risk of inaction

If we keep status quo (dual schemas, no decision):

- Every new validator/runner must handle two paths → maintenance tax
- Schema-document drift compounds: `agentic_case_v2.schema.json` won't match
  `gold20` template's actual fields
- Reviewer onboarding confusion: which schema does the dataset use?
- Audit reports (this one, and prior `evaluation_readiness_audit.md`) keep
  listing the dual issue indefinitely

The longer migration is deferred, the higher the eventual cutover cost. Phase 1
(checkpoint: stop adding legacy schema) should be done *now*, before any new
annotation work begins.

---

## 6. Not fabricated

Field list from reading the actual JSONL header lines + validator source.
Tool-impact matrix derived from grep on actual files. No experiments run.
