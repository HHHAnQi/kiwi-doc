# Agentic RAG Evaluation Readiness Decision (PR-7f.2c.2 Task 5)

> Final decision audit consolidating:
> - [`evidence_integrity_audit.md`](./evidence_integrity_audit.md)
> - [`gold_leakage_audit.md`](./gold_leakage_audit.md)
> - [`slice_coverage_audit.md`](./slice_coverage_audit.md)
> - [`schema_migration_audit.md`](./schema_migration_audit.md)
> - [`kb_snapshot_audit.md`](./kb_snapshot_audit.md) (from PR-7f.2c.1.5 Task 1)
> - [`gold_dataset_audit.md`](./gold_dataset_audit.md) (from PR-7f.2c.1 Task 1)
>
> **Read-only audit. No code/dataset/snapshot modified. No experiments run.**

---

## Final decision

### A. Agentic RAG vs Hybrid RAG comparison → **NOT_READY**

### B. Planner Ablation → **NOT_READY**

### C. Replan Ablation → **NOT_READY**

### D. Sufficiency Ablation → **NOT_READY**

**All four experiment classes are blocked.** No benchmark number from any of these
today (or until P0 list is resolved) can be reported as evidence of Agentic value.

---

## P0 blockers (must resolve before any benchmark)

### P0-1 — Evidence pointers corrupted (`evidence_integrity_audit.md` §1)

- 12/12 pilot20 evidence rows have all `FILL_*` placeholders.
- **All 12 are also `doc_mismatch=True`**: pilot20's `documentId` claims point to
  the wrong document when checked against the current KB snapshot.
- All 20 gold20 chunks **exist** in snapshot, but **20/20 `documentId` claims are
  wrong** — gold20 inherited the corruption from `golden_v2_grounded.jsonl`.
- **Impact**: any `gold_evidence_recall` / `requirement_coverage_f1` metric
  returns meaningless values. Even after `FILL_*` is fixed, the `(doc, chunk)`
  pointers will resolve to evidence from the wrong document.
- **Fix path**: re-source pointers from `golden_v2_grounded.jsonl`. For each
  `(question, gold_answer)`, run snapshot full-text search to find the actual
  chunk; the new `(documentId, chunkId)` pair becomes the ground truth.

### P0-2 — 100% reference-answer leakage (`gold_leakage_audit.md` §1)

- 12/12 pilot20 answerable cases have `goldAnswer` **character-for-character
  identical** to `contentSnippet`.
- Bigram-Jaccard mean = **1.000** across all 12.
- **Impact**: every faithfulness / citation metric trivially saturates to 1.0.
  No evidence-grounding claim can be made on this dataset.
- **Fix path**: re-author every `referenceAnswer` in annotator's own words
  (enforced by `validate_gold_dataset.py`'s leakage check, once P0-1 done).

### P0-3 — Slice coverage inadequate (`slice_coverage_audit.md` §2)

- 3 Agentic differentiator slices **completely absent**: `tool_failure_recovery`,
  `multi_hop`, `replan_failure`.
- Pilot20 best comparative slice `replan_success` N=3; gold20 N=1.
- Multiple required slices at N=1: `evidence_conflict`, `permission_denied`,
  `document_fetch_needed`.
- **Impact**: no per-slice statistical claim is supportable. Even with perfect
  data, sample sizes are too small to detect medium effects at α=0.05.
- **Fix path**: grow gold20 to ≥30 cases per critical slice (per
  `slice_coverage_audit.md` §6 Phase-2 target), including **new slice enum
  entries** for `tool_failure_recovery` and `multi_hop`.

### P0-4 — Missing baselines (deferred-from `experiment_fairness_audit.md` P0)

- A6 More-Tool-Calls Control runner — central fairness check that Planner isn't
  just retrieving more. **No runner exists.**
- A7 Oracle Plan runner — not implementable as designed because pilot20's
  `acceptableInitialPlans` are too narrow (every case is just `[semantic_search]`
  on REQ-1).
- **Fix path**: implement A6 runner + expand `acceptableInitialPlans` per case.
  Out of scope of this audit PR (Task constraint) — flagged for future.

---

## P1 issues (required for scientific/publication-grade)

### P1-1 — No `multi_hop` slice, intent-based multi-hop is structurally weak

Multi-hop is currently inferred from `intent=MULTI_HOP` field (pilot20 sets this
for all 20 cases) but only 4 cases have ≥2 Requirements. Without a dedicated slice
and ≥5 cases per slice, Q1 ("does Planner help on multi-hop?") is unanswerable.

### P1-2 — Dual schema coexistence (no decision taken)

`agentic_v2.pilot20.jsonl` uses legacy field names (`question`, `goldAnswer`,
`goldEvidence`, `expectedFinalStatus`); `agentic_v2.gold20.template.jsonl` uses
new names (`query`, `referenceAnswer`, `evidence`, `finalStatus`). Tool set is
forked (some tools only support one schema). `schema_migration_audit.md` §4
recommends **Option C (formal migration)** with Phase 1 immediate.

### P1-3 — Statistical machinery absent

Repo-wide search for `bootstrap`, `wilcoxon`, `mcnemar`, `paired t-test`,
`p-value` returns zero non-trivial matches. No confidence intervals can be reported.

### P1-4 — No inter-annotator agreement (IAA) protocol

Schema has single `reviewer` field; no `annotator ≠ reviewer` enforcement
structurally (only at validator level). Cohen's kappa cannot be computed.

### P1-5 — `expected.expectedStrategy` not populated

Even in gold20 template (where the field exists), all values are empty. Ablation
routing needs this to be `PLANNED_AGENT` vs `CLASSIC_RAG`.

---

## P2 improvements (polish)

### P2-1 — Latency p95 calculation bug in `metrics.py:247` (PR-7f.2b.2 issue)

`p95_idx = int(n*0.95)` returns max for n=20. Use linear interpolation. Also add
`n` + `stdev` to the latency report.

### P2-2 — `tool_efficiency` violates NOT_EXECUTED policy

`metrics.py:150` returns `0.0` (not `None`) on empty execution list. Should
return `None` like every other metric.

### P2-3 — `replan_trigger_precision` has calculation bug

`metrics.py:169` checks "any evidence exists" instead of "new evidence from
replan phase". Returns 1.0 by construction for any successful Agentic run.
Needs schema change `phaseEvidence[]` to compute correctly.

### P2-4 — `false_sufficient_rate` conflates three definitions

Annotation FPs, runtime leak flag, and hard-gate denominator all use different
formulas. Split into clearly named metrics.

### P2-5 — Faithfulness / citation / answer-correctness metrics absent

`eval/agentic/scripts/metrics.py` has none. Legacy `eval/ragas_pipeline.py` +
`eval/metrics/generation_metrics.py` exist but are not wired in.

### P2-6 — Snapshot drift detection absent

Once gold is frozen with a `snapshotId`, runtime evaluation should detect if
current snapshot differs. No tool exists for this yet.

---

## Verification status (PR-7f.2c.2 audit completeness)

| Task | Output | Status |
|---|---|---|
| 1 Evidence integrity | `evidence_integrity_audit.md` | ✅ |
| 2 Dataset leakage | `gold_leakage_audit.md` | ✅ |
| 3 Slice coverage | `slice_coverage_audit.md` | ✅ |
| 4 Schema consistency | `schema_migration_audit.md` | ✅ |
| 5 Readiness decision | (this file) | ✅ |
| 6 Snapshot handling (.gitignore + README) | `eval/agentic/kb_snapshot/snapshots/` | ✅ |

Audit-constraint compliance:

- ✅ No Java runtime edited
- ✅ No Spring / DB / Milvus touch
- ✅ Gold dataset content NOT modified (pilot20 corrupt pointers left in place)
- ✅ Snapshot content NOT modified
- ✅ No evidence/referenceAnswer auto-generated
- ✅ No Agentic / Hybrid benchmark run
- ✅ No experimental metrics fabricated
- ✅ Audit-only files added under `eval/**` (`.gitignore`, `README.md`) and
  `docs/evaluation/**` (5 audit reports + this readiness doc)

---

## Recovery roadmap (consolidated)

Phased plan unblocking each class:

| Phase | Target | Unblocks |
|---|---|---|
| **0. Schema decision** (1h) | Pick Option C; add "frozen" marker to pilot20 | P1-2 |
| **1. Pointer re-sourcing** (~half day) | Script `find_correct_chunk_in_snapshot.py` for each pilot20/gold20 (question,answer) pair; rewrite `(documentId, chunkId)` | P0-1 |
| **2. Reference answer re-authoring** (~half day, manual) | For each case, write paraphrased answer; clear leakage | P0-2 |
| **3. Slice growth to ≥60** (~2-3 days, manual annotation) | Per `slice_coverage_audit.md` §6 Phase-2; add `multi_hop` / `tool_failure_recovery` slices | P0-3, P1-1, P1-4 |
| **4. A6 / A7 runner implementation** (~3-5 days) | Per `experiment_fairness_audit.md` §5; needs separate PR | P0-4 |
| **5. Faithfulness / RAGAS wire-up** (~1 day) | Reuse `eval/ragas_pipeline.py` against Agentic outputs | P2-5 |
| **6. Statistical harness** (~half day) | `bootstrap_ci` + `paired_mcnemar` modules | P1-3 |
| **7. A5-vs-A6 / A5-vs-A8 live run** | First real comparison experiment | (gated by 1-6) |

Today's status: **Phase 0 not yet started**. Everything below depends on it.

---

## TL;DR

The Agentic RAG Runtime and Evaluation Framework are architecturally ready.
The **datasets are not** — they are corrupted at the data layer (P0-1, P0-2)
and under-structured for the experiment design (P0-3, P0-4).

Said differently: this is a data problem, not a code problem. Fixing the
plumbing more won't help; the gold needs to be re-built from a clean KB
source before any meaningful comparison can be claimed.

---

## Not fabricated

Zero experimental metric values reported across all 6 audit files. All numerical
findings are direct outputs of static analysis (file reading, hash recomputation,
chunk lookup) against the actual project files. No benchmark was run.
