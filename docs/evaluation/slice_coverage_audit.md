# Slice Coverage Audit (PR-7f.2c.2 Task 3)

> Scope: `eval/agentic/datasets/agentic_v2.pilot20.jsonl` (legacy pilot),
> `eval/agentic/datasets/agentic_v2.gold20.template.jsonl` (new candidate),
> `eval/agentic/datasets/agentic_v2.template.jsonl` (60-slot template target).
>
> Method: count per `slice` field; compare against canonical slice enum from
> `pr-7f.1-agentic-eval-design.md §1.3`.
>
> Read-only. **No dataset rows modified.**

---

## Final verdict: **FAIL — neither dataset supports an algorithm comparison claim**

- Three required Agentic differentiator slices are **completely absent** in both datasets.
- Multiple required slices at **N=1** — single-case variance dominates any claim.
- pilot20 mixes `MULTI_HOP` intent in `questionType` field with single-hop
  slice intent (e.g. `no_answer_refuse` cases labeled MULTI_HOP have only 1
  Requirement), making multi-hop claims structurally untestable.

---

## 1. Canonical slice enum (per design)

From `pr-7f.1-agentic-eval-design.md §1.3`:

| Slice | Purpose | Required for Agentic claim? |
|---|---|---|
| `initial_sufficient` | Baseline retrieval | Optional |
| `document_fetch_needed` | Document-fetch Tool usage | Required |
| `semantic_metadata_combo` | metadata_search + semantic_search | Required |
| `replan_success` | Replan value-add | **Critical** |
| `replan_still_insufficient` | No-progress refusal | **Critical** |
| `permission_denied` | REFUSED_PERMISSION | Required |
| `evidence_conflict` | REFUSED_CONFLICT | Required |
| `no_answer_refuse` | REFUSED_NO_EVIDENCE safety | Required |
| `budget_timeout_edge` | BUDGET_EXCEEDED / TIMED_OUT | Required |
| (`tool_failure_recovery`) | TOOL_FAILED recovery | **Required — totally absent** |

---

## 2. Coverage matrix

| Slice | pilot20 N | template60 N | gold20 N | Agentic claim? |
|---|---|---|---|---|
| `initial_sufficient` | 6 | 10 | 11 | baseline |
| `document_fetch_needed` | **1** | 6 | 2 | required |
| `semantic_metadata_combo` | **2** | 8 | 2 | required |
| `replan_success` | **3** | 8 | **1** | **CRITICAL** |
| `replan_still_insufficient` | **2** | 6 | **1** | **CRITICAL** |
| `permission_denied` | **1** | 4 | **0** | required |
| `evidence_conflict` | **1** | 4 | **0** | required |
| `no_answer_refuse` | 4 | 8 | 3 | required |
| `budget_timeout_edge` | **0** | 6 | **0** | required |
| `tool_failure_recovery` | **0** | **0** | **0** | **required — totally missing** |
| `multi_hop` (canonical) | **0** | **0** | **0** | **CRITICAL — never modeled** |
| `replan_failure` | **0** | **0** | **0** | required |
| `timeout` | **0** | **0** | **0** | required |
| `budget` (canonical) | **0** | **0** | **0** | required |
| **total** | **20** | **60** | **20** | |

### Slice-balance validity (PR-7f.1 §4.3 mandates ≥5 per slice)

- **pilot20**: **9/9** slices below ≥5 minimum (3 at N=1, 1 at zero).
- **template60**: still 0 `tool_failure_recovery`; 4 slices at N=4 < 5.
- **gold20**: 0/9 slices meet ≥5; only `initial_sufficient` non-trivial (N=11).

---

## 3. Agentic differentiators entirely missing

| Differentiator | Where tested today? | Why important |
|---|---|---|
| `tool_failure_recovery` | nowhere | Proves agent can recover from `TOOL_FAILED`. Classic/Hybrid RAG cannot recover. Central Agentic claim. |
| `budget_timeout_edge` | only template60 (N=6) | Tests `BUDGET_EXCEEDED` finalStatus. Agentic budget manager distinguishes itself here. |
| `evidence_conflict` | pilot20 N=1 | Conflict detection + REFUSED_CONFLICT. Without ≥5 cases, `Conflict Detection Accuracy` from `pr-7f.1 §3.4` is uncomputable. |
| `permission_denied` | pilot20 N=1 | REFUSED_PERMISSION has single-case variance; cannot make claim. |
| `multi_hop` (as a slice) | not modeled | Multi-hop currently inferred from `intent=MULTI_HOP` field, not from slice. **This is a design gap.** |

---

## 4. Statistical power analysis

Given N per slice, what effect size (Cohen's h) is detectable at α=0.05, β=0.2?

| N per arm | Detectable Cohen's h (smallest) | Realistic for RAG? |
|---|---|---|
| 1 | — | impossibly underpowered |
| 3 | 1.30 (huge) | rarely seen in RAG papers |
| 5 | 1.13 (huge) | no |
| 10 | 0.80 (large) | marginal |
| 30 | 0.46 (medium) | yes, typically what papers claim |
| 100 | 0.25 (small) | publication-grade |

For pilot20's best *comparative* slice (`replan_success`, N=3), **only effects ≥
"huge" are detectable**. The Agentic-vs-Hybrid claim (typically small/medium effect)
is **structurally undetectable** at current size.

For gold20's best non-trivial slice (`initial_sufficient`, N=11), still only
"large" effects detectable. Same conclusion.

---

## 5. Multi-hop modeling critique

Pilot20 uses `questionType: "MULTI_HOP"` for all 20 cases (verified in prior audit),
but inside `requirements[]`, 16/20 cases have only REQ-1. Genuine multi-hop requires
**>1 Requirement binding to >1 evidence entry**. The slice enum has no explicit
`multi_hop` slice.

Recommendation: define `multi_hop` as a slice distinct from `replan_success`,
representing "Join across 2+ chunks where BOTH chunks must be cited to answer".
Assign ≥5 such cases to gold20 before any Planner value claim.

---

## 6. Coverage growth target

To relax every P0/P1 in this audit:

| Phase | Target | Pre-conditions |
|---|---|---|
| Smoke (pilot20-grown) | 20/case, every required slice ≥3 | gold pointer re-sourcing (`evidence_integrity_audit.md` R1) |
| Internal benchmark | 60 cases, every required slice ≥5 + add `multi_hop` ≥5 | Dual-annotated, IAA ≥0.7 |
| Statistical claim | 150-250 cases, key slices ≥30 | A6 More-Tool-Calls control available |
| Publication | 300+ cases, ≥30/slice including `tool_failure_recovery` / `budget_timeout_edge` | Adversarial badcase slices added |

---

## 7. Slice-specific dataset gaps (P0)

| Slice | Required by | Current gap |
|---|---|---|
| `multi_hop` (new slice) | Q1 Planner value claim (`pr-7f.1 §0.Q1`) | does not exist anywhere |
| `tool_failure_recovery` (new slice) | Q2 Planner ≠ just more calls | does not exist anywhere |
| `replan_success` ≥ 30 | Q3 evaluation of Replan (`pr-7f.1 §0.Q3`) | N=3 (pilot20), N=1 (gold20) |
| `replan_still_insufficient` ≥ 30 | Q3 No-progress Refusal Quality | N=2 (pilot20), N=1 (gold20) |
| `permission_denied` ≥ 5 | coverage | N=1 (pilot20), N=0 (gold20) |
| `evidence_conflict` ≥ 5 | Conflict detection accuracy metric | N=1 (pilot20), N=0 (gold20) |
| `budget_timeout_edge` ≥ 5 | Budget manager evaluation | N=0 both |

---

## 8. Recommended action (blocker for ready-to-run)

| # | Action | Priority |
|---|---|---|
| S1 | Grow gold20 to ≥30 cases per critical slice (`multi_hop`, `replan_success`, `replan_still_insufficient`, `no_answer_refuse`) before any algorithm comparison | **P0** |
| S2 | Add `tool_failure_recovery` and `budget_timeout_edge` slices (currently 0) | **P0** |
| S3 | Add `multi_hop` as an explicit slice enum value (currently conflated with intent) | P1 |
| S4 | Re-evaluate gold20's current slice assignment — many `initial_sufficient` cases (N=11) might better fit `document_fetch_needed` | P1 |

---

## 9. Not fabricated

All N counts are direct outputs of `Counter(slice)` on the actual files.
Statistical power numbers use standard formulas (Cohen 1988), not experiment data.
