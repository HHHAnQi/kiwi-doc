# Metric Audit Report

> Scope: `eval/agentic/scripts/metrics.py` (the only metric module wired into the
> agentic runner) + `evaluation_result.schema.json` (the carrier schema).
> Read-only audit. No code modified. No fake data.

**Verdict per-metric**: ✅ correct · ⚠️ partial/buggy · ❌ missing.

---

## Summary table

| # | Metric | Implements | Correct? | Problem | Recommendation |
|---|---|---|---|---|---|
| 1 | gold_evidence_recall | recall = \|gold ∩ actual\| / \|gold\| | ✅ | Treats **partial recall = 0** when actual=[]; ignores order; no Recall@K | Acceptable for current schema; add Recall@K when runner exposes per-step evidence timeline |
| 2 | gold_document_recall | doc-level recall | ✅ | None; correctly returns `None` on empty gold | Acceptable. Keep. |
| 3 | requirement_coverage_f1 | macro avg of per-req COVERED vs gold covered | ⚠️ | **F1 == 2PR/(P+R) returns 0 when P+R==0**, not the conventional 1.0/None; mixes binary per-req classification with micro/MACRO inconsistency | Use explicit macro-F1 over the 2×2 AND return `None` when undefined; document macro vs micro choice |
| 4 | final_status_accuracy | exact str equality of `finalStatus` vs `expectedFinalStatus` | ✅ | None | Acceptable. Add stratified-by-slice variant (currently flat) |
| 5 | faithfulness | **NOT IMPLEMENTED** in agentic metrics | ❌ | Design doc `pr-7f.1 §3.2` lists it; metrics.py does not. Legacy `eval/metrics/generation_metrics.py:faithfulness` exists but is not wired | Wire `eval/metrics/generation_metrics.faithfulness(answer, ctx, judge_fn)` + RAGAS via `eval/ragas_pipeline.py` |
| 6 | answer_correctness | **NOT IMPLEMENTED** | ❌ | Same as #5. Legacy `eval/runner/run_eval.py:gm.answer_correctness` exists | Wire legacy impl; add token-F1 + judge F1 separately |
| 7 | unsupported_claim_rate | **NOT IMPLEMENTED** | ❌ | In design (`§3.2`), missing in code. RAGAS `context_precision` is the proxy | Compute via RAGAS; add to `evaluate_aggregate` return |
| 8 | tool_efficiency | averages toolCalls/realToolCalls/llmCalls | ⚠️ | Returns `{0.0}` when no executed records (line 150), should be `None`; mixes cost-dimension | Set None when 0 executed; separate tool/llm token cost |
| 9 | replan_metrics | attempt rate / success rate / trigger precision | ⚠️ | `replanTriggerPrecision` = "evidenceIds non-empty" (line 169). This is **wrong**: should measure "evidence in replan phase that was NOT in phase-0 and IS in Gold". Current logic gives precision=1.0 whenever ANY evidence exists | Track phase-of-evidence + gold delta; expose per-phase evidence timeline in schema |
| 10 | false_sufficient_rate | actual ANSWERED + gold.answerable=false | ⚠️ | Two non-equivalent definitions conflated: (a) gold-side answerable=false (annotation), (b) `falseSufficientLeak` runtime audit flag. Reports "count" of (a) + "leakCount" of (b) but the **rate** only divides (a) coeff. The hard-gate ≤2% set in PR-7f.1 §0.Q4 is **not computed** because (a) depends on 8 currently-unreviewed unanswerable cases | Report two separate gates: "annotation FP rate" AND "Guard leak rate / total SUFFICIENT signaled" |
| 11 | trajectory_safety | residue rate / SSE multi-terminal / cross-tenant leak | ⚠️ | Correct shape but ALL executed records needed. Cross-tenant leak is a counter, not a rate — denominator is "Run" not "case" — see §"Trajectory" below | Clarify denominator; tag per-Step not per-Case for residue |
| 12 | latency_stats | p50 / p95 of run latencyMs | ⚠️ | **p95 implementation bug**: line 247 `p95_idx = int(n*0.95)` — for n=20 gives idx=19 which is the MAX. Should be `int(0.95*(n-1))` or `ceil(0.95*n)-1` | Use `statistics.quantiles` or the standard linear-interpolation method; add stdev + n |
| — | **MRR / nDCG / precision@K** | **NOT in agentic metrics** | ❌ | Legacy `eval/metrics/retrieval_metrics.py` has all; not reused | Wire in for retrieval-quality baseline |
| — | **Plan-schema-valid / PlanValidator-pass / repeated-tool-sig** | **NOT IMPLEMENTED** | ❌ | In design (`§3.3`); code absent. Needed for A2-A5 ablation value-attribution | Pull from `run_planner_eval.py:PlannerMetrics` (already computes these in PR-7d) |
| — | **Citation precision / recall** | **NOT IMPLEMENTED** | ❌ | Schema has `citedEvidenceIds` but no metric computes precision/recall vs gold | Add per-case `len(cited ∩ gold_cited)/len(cited)` |
| — | **Tool selection accuracy / tool argument accuracy** | **NOT IMPLEMENTED** | ❌ | (§3.1) Needed to attribute A2 vs A6 diff | Compute from `executedToolSignatures` vs `planConstraints.acceptableInitialPlans[*].toolSequence` |
| — | **Cost-Per-Task (token × price)** | **MISSING** | ❌ | Schema carries `tokenUsage` but no cost computation | Add USD-cost with configurable model-pricing JSON |
| — | **Ablation deltas (A1→A2, A2→A5, etc.)** | **NOT IMPLEMENTED** | ❌ | Central to PR-7f.1 Q1/Q2/Q4 | Add a `compare_ablations(results_by_config)` function |

**Total**: of 12 audit-requested metrics, **5 fully present, 6 partial, 6 missing**.
Required-by-design-doc metrics **NOT implemented**: 9 of 26.

---

## 1. gold_evidence_recall ✅ (correct implementation, weak coverage)

**Definition (design)**: `len(actual ∩ gold) / len(gold)` — measures whether the agent's
final evidence accumulator contains the human-curated "should-cite" chunks.

**Formula in code** (`metrics.py:42-53`):
```
rec = |gold_set ∩ actual_set| / |gold_set|
  returns None if gold empty
  returns 0.0 if actual empty
```

**Edge cases**:
| Case | Behaviour | OK? |
|---|---|---|
| gold=[], actual=[a] | `None` (undefined, not 0) | ✅ |
| gold=[a], actual=[] | `0.0` | ✅ |
| gold=[a,b], actual=[a,b,c] | `1.0` | ✅ |
| gold=[a,b], actual=[a] | `0.5` | ✅ |
| gold has 1 chunk, actual has it from wrong phase (e.g. phase-0 vs phase-1) | `1.0` | ⚠️ — no temporal signal, conflates phase-0 with replan win |

**Alignment with Agentic RAG goal**: ✅. The whole Agentic hypothesis is "agent acquires
scarcer multi-hop evidence"; this metric measures that directly.

**What's missing for full coverage**: `Recall@K` (K = budget), precision@K, nDCG — none
exist in `metrics.py`. `eval/metrics/retrieval_metrics.py` has all of them but is not
wired in. Recommendation: wire it.

---

## 2. gold_document_recall ✅

`metrics.py:56-64`. Same shape as #1, doc-level. Coarser but useful for quickly
spotting "wrong document picked". Correct.

---

## 3. requirement_coverage_f1 ⚠️

**Definition**: For each required Requirement, the dataset has gold coverage. Did the
agent's `requirementCoverage[i].status` agree?

**Formula (`metrics.py:90-112`)**:
```
tp = (actual COVERED) ∧ (gold covered)
fp = (actual COVERED) ∧ (gold NOT covered)
fn = (actual NOT COVERED) ∧ (gold covered)
precision = tp / (tp + fp)  with safe_div
recall    = tp / (tp + fn)
f1        = 2PR/(P+R)  with safe_div → 0 when P+R==0
```

**Problems**:
1. **`f1 = 0` convention is wrong** for the all-tn / all-zero case (no tp, no fp, no fn
   because no required req has gold). Should be `1.0` (perfect agreement on "no
   applicable requirements") or `None` (undefined). Returning 0 will drag down macro
   averages on the `no_answer_refuse` slice (8 cases in pilot20 are exactly this).
2. Macro-F1 over **2-req cases** ignores per-req granularity. For 16/20 cases having
   only REQ-1, this collapses to binary MATCH. Should document or switch to micro-F1.
3. Schema enum includes `CONFLICTED` / `PARTIALLY_COVERED` (schema:41), but the calculator
   treats them as `NOT_COVERED` (only "COVERED" is counted as positive). Undocumented.

---

## 4. final_status_accuracy ✅

`metrics.py:119-139`. Exact str equality. Simple and correct.

Risk: only enum match matters (`ANSWERED` vs `REFUSED_NO_EVIDENCE` vs etc). For the
"REFUSED_PERMISSION" expected slice (1 case in pilot20), being slightly wrong is fine;
the slice breakdown will be needed for publication.

---

## 5-7. Faithfulness / answer correctness / unsupported claim rate ❌ NOT IMPLEMENTED

`metrics.py` defines **none** of these. Design `pr-7f.1 §3.2` lists all three.
**Critical gap** — these are the *only* metrics that evaluate "did the agent hallucinate",
which is the central faithfulness question for any Agentic RAG.

**Existing reusable implementations**:
- `eval/metrics/generation_metrics.py:faithfulness(answer, context, judge_fn, ...)` — custom LLM-judge based
- `eval/metrics/generation_metrics.py:answer_correctness(answer, gold, judge_fn, ...)` — same
- `eval/ragas_pipeline.py` — full RAGAS pipeline (`faithfulness`, `answer_relevancy`,
  `context_precision`, `context_recall`) on generated outputs

**Recommendation**: add an internal eval stage post-`evaluate_aggregate` that emits
RAGAS-style scores. Pass `answerText` + joined cited evidence snippets to the judge.
Output goes in a sibling report file `agentic_generation_report.json`.

---

## 8. tool_efficiency ⚠️

`metrics.py:146-155`. Returns averages. Two issues:

1. `executed = []` → `n = 1` (line 150), so empty sums become `0.0` not `None`.
   `tool_efficiency` is the *one* metric group that violates the NOT_EXECUTED policy
   (test confirms this). Should be `None` to be consistent with `latency_stats`,
   `replan_metrics`, etc.
2. It does not separate **cost dimensions**. Token cost (`inputTokens + outputTokens`),
   retrieval cost (milvus calls), LLM-AFF calls (planner + sufficiency + answer) — all
   conflated into 3 means. For Agentic vs Hybrid comparison, we need USD cost.
   `tokenUsage` is in schema but unused.

---

## 9. replan_metrics ⚠️ (calculation error)

**Definition (design §3.5)**:
- `Replan Trigger Precision` = 触发 Replan 后获 **new Gold Evidence (not in phase-0)** 的比例
- `Replan Trigger Recall` = expected-to-replan cases 中实际触发的比例 (2×2 against `expected.replanExpected`)
- `Replan Success Rate` = replan 后 SUFFICIENT (or ANSWERED) 的比例

**Implementation (`metrics.py:162-175`)**:
```python
attempted = [a for a in executed if a.get("replanCount", 0) > 0]
succeeded = [a for a in attempted if a.get("finalStatus") == "ANSWERED"]
new_evidence_replan = [a for a in attempted if len(a.get("evidenceIds", [])) > 0]   # ← BUG
replanTriggerPrecision = len(new_evidence_replan) / len(attempted)
```

The "new evidence" check is just "any evidence exists", not "new evidence added during
replan phase specifically". Every successful Run has ≥1 evidence so this is always 1.0
for any executed record that attempted replan. **This is a fabrication-by-design bug.**

**Also missing**: `replanTriggerRecall` (against `expected.replanExpected`), and the
`No-progress Refusal Precision` mentioned in `§3.5`. Without these, the Q3 question
("how to evaluate Replan") is **unanswerable with current metrics**.

**Fix**: `evaluation_result.schema.json` needs to carry per-phase evidence lists
(`phaseEvidence: [{phaseId: 0, evidenceIds: [...]}, {phaseId: 1, evidenceIds: [...]}]`).
Without that, recall precision is uncomputable.

---

## 10. false_sufficient_rate ⚠️

`metrics.py:182-213`. Two definitions conflated:

- (a) **annotation-based**: actual ANSWERED + gold.answerable=false → "FP"
- (b) **runtime-leak-based**: actual.falseSufficientLeak == true → "Guard caught false-sufficient slip"

`rate` uses (a)'s numerator; `leakCount` counts (b). Hard gate (≤2%) per
`pr-7f.1 §0.Q4` would use a different denominator: real-INSUFFICIENT rate. None of these
are actually computed as the gate requires.

Also: pilot20 has 8 cases with `answerable=false`, all `reviewStatus=candidate`. So
**rate is currently undefined** (returns `None` because no executed records exist).

---

## 11. trajectory_safety ⚠️

`metrics.py:220-233`. Three independent rates. Correct shape.

**Issue**: definition of "non-terminal residue" / "SSE multi-terminal" granularity.
The fields on schema (`nonTerminalStepResidue`, `sseTerminalEvents`,
`crossTenantEvidenceLeak`) are **per-Run** counters. Computing "rate = cases with >0
residue / total executed cases" is fine for case-level analysis but **should be 0
exactly**, not just "under threshold". Gate design needs hard-zero, not p95.

---

## 12. latency_stats ⚠️ (p95 bug)

`metrics.py:240-250`:
```python
n = len(latencies)
p50 = latencies[n//2]
p95_idx = int(n * 0.95)
p95 = latencies[min(p95_idx, n - 1)]
```

For n=20 → idx=19 (max). For n=80 → idx=76 (95%ile ≈ max) but standard percentile:
`p95 = latencies[ceil(0.95*n) - 1]` would give idx=75, or use linear interpolation.

p50 is fine (`n//2` for integer division).

Also missing: **n (sample count)** and **stdev**. Without n, a p50 of 800ms on n=3 vs
n=300 is not comparable. Add both.

---

## 13. Missing metrics (collected)

Pulled from design `§3` (24 metric definitions) — these are NOT in code:

| Missing | Designed at | Why needed |
|---|---|---|
| Recall@K / MRR / nDCG | §3.1 | Standard retrieval baselines; legacy lives at `eval/metrics/retrieval_metrics.py` |
| Tool Selection Accuracy | §3.1 | Q2 — Planner vs random selection |
| Tool Argument Accuracy | §3.1 | bearings on Planner quality |
| Answer F1 / EM | §3.2 | Standard generation |
| Faithfulness (custom + RAGAS) | §3.2 | Q4 — central |
| Citation Precision / Recall | §3.2 | Trust |
| Unsupported Claim Rate | §3.2 | Q4 |
| Plan-Schema-Valid | §3.3 | Q2 |
| PlanValidator Pass Rate | §3.3 | Q2 |
| Repeated Tool Signature Rate | §3.3 | Q2 quality signal |
| Initial Plan Acceptable Match | §3.3 | Q2 |
| Replan Plan Acceptable Match | §3.3 | Q3 |
| Sufficiency Precision / Recall | §3.4 | Q4 |
| False Insufficient Rate | §3.4 | Q4 |
| Guard Catch Rate | §3.4 | Q4 (proves Guard ≠ decorative) |
| Conflict Detection Accuracy | §3.4 | slice evidence_conflict (1 case) |
| Replan Trigger Recall | §3.5 | Q3 |
| No-progress Refusal Precision | §3.5 | Q3 |
| Illegal Tool Execution Rate | §3.6 | safety gate (must be 0) |
| Estimated Cost / Task | §3.6 | business |
| Ablation deltas A1→A2 etc | §3.7 | central claim-disaggregation |

**21 of 24** designed metric definitions are **unimplemented**. Only ~3 in code
(gold recall, doc recall, final-status acc, partial replan/safety/efficiency).

---

## Recommendations (priority)

- **P0 — blocks any Agentic-value claim**:
  - Implement **faithfulness + citation precision** (RAGAS reuse from `eval/ragas_pipeline.py`)
  - Fix **replan trigger precision** bug (`new_evidence_replan` semantics)
  - Add **ablation deltas** helper — without it, A0-vs-A5 is uncommunicable

- **P1 — needed for publication**: Recall@K / MRR / nDCG, Plan-metrics wiring
  (`eval/planner/run_planner_eval.py`), statistical significance (see
  `experiment_fairness_audit.md`)

- **P2 — quality polish**: latency p95 fix, tool_efficiency None policy, USD cost

---

## Not fabricated

This audit reports zero metric values. None of the above is computed from real data.
All findings are static analysis of `metrics.py` (+ design-doc cross-check), no
experiments run.
