# Agentic RAG Evaluation Readiness Audit

> **Question under audit**: Are our metrics, datasets, experiment design, and
> evaluation pipeline sufficient to fairly evaluate whether Agentic RAG provides real
> value over Classic/Hybrid RAG?
>
> Scope: `eval/`, `docs/` only. Runtime Java code, Planner, Executor, Tool, Router,
> Pipeline, production config **not modified**. No experiments run. No fake metrics.
>
> Companion reports:
> - [`metric_audit.md`](./metric_audit.md) — Part 1 detail
> - [`dataset_audit.md`](./dataset_audit.md) — Parts 2-4 detail
> - [`experiment_fairness_audit.md`](./experiment_fairness_audit.md) — Part 5 detail

---

## Final decision: **NO-GO**

The framework cannot, in its current state, fairly evaluate whether Agentic RAG
provides real value over Classic/Hybrid RAG. Three blockers prevent any defensible
algorithmic claim, and a further seven issues prevent publication-grade evaluation.

This is not a "fix one bug" NO-GO. The plumbing (schema, NOT_EXECUTED policy,
runner stubs, PR-7f.2c-pre runtime gate) is engineering-sound. The gaps are at the
*measurement* layer: gold data is blocked, several core metrics are mis-implemented
or absent, and the experiment design has no runner code outside the two endpoints
on the central A5-vs-A6 axis.

Rationale per the four audit axes:

| Axis | State | Verdict |
|---|---|---|
| Metric design | 5/12 fully correct, 6 partial (1 calculation bug), 6 missing; faithfulness/citation/ablation-deltas entirely absent | ❌ cannot measure the central claim |
| Dataset | 20 candidate cases (0/20 reviewed); 36 FILL_ placeholders; 3 slices at N=1; annotation leakage in 12/12 answerable cases | ❌ cannot be used for any metric |
| Experiment design | 2/10 baselines wired (A5, A8); the central A6 More-Tool-Calls control is missing; no config-envelope echo; no statistical machinery | ❌ not a fair comparison |
| Evaluation pipeline | NOT_EXECUTED policy sound; PR-7f.2c-pre runtime gate just wired; LLM connexity confirmed | 🟡 plumbing ok, measurements not |

---

## 1. Top-line findings (P0 blockers)

These must be addressed before any internal claim, let alone publication.

### P0-1. Gold dataset is BLOCKED
- `agentic_v2.pilot20.jsonl`: 20 cases, all `reviewStatus=candidate`, 0/20 reviewed.
- 36 `FILL_*` markers: `evidenceId=FILL_FROM_SHA256`, `contentHash=FILL_FROM_REAL_CHUNK`,
  `documentVersion=FILL_VERSION` (12 answerable cases × 3 fields).
- `gold_freeze_check.py` returns exit 1 BLOCKED.
- All retrieval-side metrics (`goldEviDenceRecall`, `requirementCoverageF1`) return
  `None` because metrics.py:272 explicitly filters FILL_ evidence IDs from recall.
- Annotation leakage: in 12/12 answerable cases `goldEvidence[].contentSnippet` is
  **identical** to `goldAnswer`. A faithfulness metric on these would trivially score 1.
- Source: pilot20 was extracted mechanically from `eval/golden/golden_v2_grounded.jsonl`
  (80 entries). (docId, chunkId) pointers are traceable; hashes are not materialized
  because the actual chunk text lives in MySQL/Milvus, not in the eval workspace.

### P0-2. Faithfulness / Citation / Answer-correctness are not computed
- `eval/agentic/scripts/metrics.py` implements **none** of:
  `faithfulness`, `answer_correctness`, `unsupported_claim_rate`, `citation_precision`,
  `citation_recall`.
- `pr-7f.1 §3.2` design lists all five as Required. Without them, "did the agent
  hallucinate" is unanswerable — and that is the central faithfulness question.
- The legacy implementations exist but are not wired:
  - `eval/metrics/generation_metrics.py:faithfulness(answer, context, judge_fn, ...)` — custom LLM-judge
  - `eval/metrics/generation_metrics.py:answer_correctness(answer, gold, judge_fn, ...)`
  - `eval/ragas_pipeline.py` — full RAGAS (`faithfulness`, `answer_relevancy`,
    `context_precision`, `context_recall`)
- The schema carries `answerText` and `citedEvidenceIds` so the data is there; only
  the calculator is missing.

### P0-3. A6 More-Tool-Calls Control baseline has no runner
- `pr-7f.1 §0.Q1` explicitly identifies A6 (Hybrid with topK = Agentic budget ×
  avg-evidence-per-call) as the fairness control that prevents the "Planner is just
  retrieving more" critique.
- Current code:
  - `agentic_runner.py` invokes the agent with `mode=AUTO` (A5).
  - `hybrid_runner.py` invokes classic with `mode=RAG` (A8, sort of).
  - **No A6 runner** parameterizes Classic topK and locks the budget to Agentic's
    `expected.maxToolCalls`.
- Consequence: even if A5 produces higher recall than A8, we cannot distinguish
  "Planner intelligence" from "more retrieval".

### P0-4. Replan trigger precision has a calculation bug
- `metrics.py:169`: `new_evidence_replan = [a for a in attempted if len(a.get("evidenceIds", [])) > 0]`
- This treats **any evidence at all** as "new evidence in replan phase". Every
  successful Agentic run has ≥1 evidence, so the precision is 1.0 by construction.
- The design (`pr-7f.1 §3.5`) requires "new Gold evidence not present in phase-0".
  The schema has no per-phase evidence timeline, so even a correct formula cannot
  be computed from the current EvaluationResult shape.

---

## 2. Important issues (P1 — required for scientific/publication-grade)

### P1-1. Statistical testing infrastructure is absent
- Grep across `eval/`, `docs/`, all `.py` and `.md` for `bootstrap`, `confidence
  interval`, `wilcoxon`, `paired t-test`, `mcnemar`, `p-value`, `significance`:
  zero non-trivial matches (all hits are pygments lexer keywords or
  Spring-Boot-startup references).
- No `scipy.stats` import in eval/agentic.
- Without paired McNemar / Wilcoxon / bootstrap CI on A5-vs-A6 case pairs, "Agentic
  beats Hybrid by X%" is unsupportable.

### P1-2. Per-slice sample sizes are too small even for point estimates
- pilot20 distribution: 6 / 3 / 2 / 4 / 2 / 1 / 1 / 1 / 0 across the 9 slices.
- Three slices at N=1: `evidence_conflict`, `permission_denied`,
  `document_fetch_needed`.
- The four multi-hop cases (`replan_success` + 2-req cases) = 4. A 5pp improvement
  is undetectable at α=0.05 with this sample.

### P1-3. Fair comparison dimensions are not locked at runner level
- Same dataset: ✅
- Same KB / same corpus version: ⚠️ implicit only — no readback in result envelope
- Same LLM (model, endpoint, temperature, max_tokens): ⚠️ env vars only, not snapshot
- Same embedding model: ❌ not echoed
- Same reranker flag: ❌ not echoed
- Same retrieval budget (topK vs maxToolCalls): **❌ not enforced** — Fairness Lock
  mandated by `pr-7f.1 §0.Q1` is absent in code
- Same token budget: ❌
- Deterministic retrieval seed: ❌ (HNSW may differ across boots)
- Multi-run averaging (≥3 runs): ❌ single-shot only

### P1-4. Missing baselines
- A1 Router RAG, A2 Planner only, A3 Planner+Rule-Suff, A4 Planner+Rule/Model-Suff,
  A7 Oracle Plan, A9 Agentic w/o Sufficiency: **none have runners**.
- These six are required to attribute value to each component (the central Q1-Q4
  of `pr-7f.1`). Without them, only total Agentic-vs-Classic remains.

### P1-5. Missing slices
- `tool_failure_recovery`: 0 cases anywhere. The Agentic differentiator
  "recovers from `TOOL_FAILED`" is untested.
- `budget_timeout_edge`: present in template (6), absent in pilot. The
  `BUDGET_EXCEEDED` terminal status is never exercised.

### P1-6. Inter-annotator agreement (IAA) is not captured
- Schema has only one `reviewer` per evidence. `annotator != reviewer` is not enforced
  by any validator.
- `pr-7f.1 §4.3` says dual-signoff required; not encoded structurally.
- Cohen's kappa cannot be reported.

### P1-7. Plan-acceptable-match is over-restrictive
- `acceptableInitialPlans` in pilot is always `[{toolSequence:[semantic_search],
  coveredReqIds:[REQ-1]}]`. A real Planner producing equally-valid plans using
  `metadata_search` would be marked "fail". The A7 Oracle semantics break on this.

---

## 3. Optional improvements (P2 — quality polish)

### P2-1. Latency p95 calculation is wrong
- `metrics.py:247`: `p95_idx = int(n*0.95)`. For n=20 → idx=19 (max). Should use
  standard linear-interpolation or NumPy percentile.
- Also: missing sample-size `n` and stdev in the report — without `n`, a p50 of
  800ms on n=3 vs n=300 is not comparable.

### P2-2. tool_efficiency returns 0.0 instead of None on empty input
- `metrics.py:150` sets `n = 1` when no executed records, then divides by 1. This
  is the **only metric in the file that violates the NOT_EXECUTED policy**.
- Should return `None` consistently with `latency_stats`, `replan_metrics`, etc.

### P2-3. False-sufficient rate conflates two definitions
- `rate` uses annotation-side FP (`gold.answerable=false`); `leakCount` uses
  runtime-side `falseSufficientLeak` flag. Hard gate `≤2%` from `pr-7f.1 §0.Q4`
  is a third definition (real-INSUFFICIENT FP rate). None of these actually map.
- Split into two clearly-named metrics with their own gates.

### P2-4. Corpus / domain monoculture
- All pilot20 questions from Spring Cloud Alibaba docs (Java/Chinese tech). No
  cross-corpus generality test, no time-sensitive drift cases, no ambiguous
  queries.

### P2-5. Trajectory_safety is reported as rate but should be hard-zero
- `nonTerminalStepResidue`, `sseTerminalEvents > 1`, `crossTenantEvidenceLeak`
  are invariant violations. Reporting them as "rate ≤ X" hides the fact that any
  non-zero value means a bug. Gate should be `== 0`, not `< threshold`.

---

## 4. Missing evaluation components (Part 6)

| Component | Status | Recommendation |
|---|---|---|
| **Human evaluation** | Absent | Not required for the algorithmic comparison (faithfulness + recall is sufficient); required for publication-grade subjective judgment. Recommendation: 100-case sample, 2 raters, IAA reported |
| **LLM-as-Judge** | Partial — `eval/metrics/generation_metrics.py` callable interface exists; `eval/judge_ensemble.py` does multi-judge mean; `eval/runner/judge_client.py` is a separate (incompatible) impl | Wire ONE judge harness for agentic eval (recommend `judge_ensemble.py` which already does multi-judge + disagreement detection); calibrate against 30-case human-judgment gold before reporting any number |
| **Statistical analysis** | Absent | Add bootstrap CI (≥1000 resamples) on every aggregate; paired McNemar for the `final_status_accuracy` 2×2; paired Wilcoxon for `gold_evidence_recall`; report n per slice always |
| **Error analysis / badcase taxonomy** | Partial — `eval/tests/badcase/test_verdict.py` parses verdicts, but no taxonomy feeding back into dataset growth | Define 5-category badcase taxonomy (retrieval-miss / plan-wrong / sufficiency-false-positive / answer-hallucination / system-failure); tag every failure in eval and grow adversarial cases per category |
| **Reproducibility artifacts** | Partial — runner emits JSONL but does NOT echo config snapshot | Add a `run_manifest.json` per arm with: LLM model, embedding model, rerank flag, topK or maxToolCalls, temperature, KB-version stamp, run_id, repeat_index |

---

## 5. The four design questions revisited

`pr-7f.1 §0` posed four core questions. With the current state, each is answerable
as follows:

| Question | Design answer exists? | Today measurable? |
|---|---|---|
| Q1 How to prove Agentic better than Hybrid? | ✅ Same-gold + A6 More-Tool-Calls + Per-slice decomposition | ❌ A6 runner missing; gold blocked; per-slice N too small |
| Q2 How to prove Planner ≠ just more tool calls? | ✅ A6 Hybrid topK bump; A7 Oracle Plan | ❌ A6 runner missing; A7 unimplementable with narrow `acceptableInitialPlans` |
| Q3 How to evaluate Replan? | ✅ Trigger P/R + Success + A2→A5 marginal + No-progress refusal | ❌ Trigger precision has calculation bug; Trigger recall needs `expected.replanExpected` matching (not in metrics); No-progress refusal metric missing |
| Q4 How to evaluate Sufficiency? | ✅ False Sufficient ≤2% + Guard Catch + 3-layer comparison | ❌ Faithfulness not computed; False Sufficient rate conflates 3 definitions; no Slice for `conflict_detection` (N=1) |

**All four are blocked.** None can be answered today with the existing code or data.

---

## 6. What IS solid (avoid overcorrecting)

The framework has substantive engineering value; not all is wrong:

| Component | State |
|---|---|
| `NOT_EXECUTED` policy (3 mechanical guarantees) | ✅ impossible to fabricate metrics; verified end-to-end |
| `evaluation_result.schema.json` (unified carrier) | ✅ structurally sound; covers per-req / safety / token-compat fields |
| `agentic_case_v2.schema.json` (instance schema) | ✅ captures Requirements + Gold + Plan + Slice + Review |
| 12 cross-field validators (`validate_dataset.py`) | ✅ correct; correctly distinguishes candidate vs reviewed placeholder policy |
| 13 gold-completeness strict checks (`validate_gold_completeness.py`) | ✅ strict-mode catches every gap |
| 74 Python tests on metrics + adapter + freeze-check | ✅ green; fixtures are deterministic |
| `gold_freeze_check.py` BLOCKED gate | ✅ refuses to fabricate; exit 1 on pilot20 |
| Runtime activation gate (PR-7f.2c-pre) | ✅ just wired (`plannedPipelineEnabled` bound), default false zero-diff |
| Strategy trace exposure (`ChatResponse.pipeline_type`) | ✅ runner can now classify RUNTIME_NOT_PLANNED_AGENT vs RUNTIME_NO_STRATEGY_TRACE |
| Live LLM connectivity (GLM via OpenAI-compat) | ✅ `OpenAiCompatibleLlmClient` IT passed: answer_len=52, tokens=418 prompt/20 completion |
| Live Reranker connectivity (bge-reranker-v2-m3 on GPU) | ✅ `/health` 200, score separation 0.999 vs 1.6e-05 |

**Implication**: the engineering plumbing is 80% there. What is missing is the
measurement layer (metrics + gold + statistical analysis). Fixing this is tractable
but requires real work — not a one-PR patch.

---

## 7. Recovery plan (minimum viable path to internal benchmark)

This is a sequenced recovery plan. Each row is a unit of work; depends on rows above.

| # | Work | Unblocks | Estimated effort |
|---|---|---|---|
| 1 | **Freeze gold** — backfill evidenceId/contentHash/contentSnippet from real chunk text in MySQL/Milvus; require `annotator != reviewer` | P0-1, P1-6 | medium (domain access) |
| 2 | **Wire faithfulness/citation** into `evaluate_aggregate` (reuse `eval/ragas_pipeline.py` + `eval/metrics/generation_metrics.py`) | P0-2 | medium |
| 3 | **Fix `replanTriggerPrecision`** + add per-phase evidence timeline in schema; add `replanTriggerRecall`, `noProgressRefusalPrecision` | P0-4 | medium |
| 4 | **A6 More-Tool-Calls runner** + Fairness Lock (topK = sum of `expected.maxToolCalls` across cases) | P0-3, P1-4 | medium |
| 5 | **Config snapshot in result envelope** (LLM/embed/rerank/KB-version) | P1-3 | small |
| 6 | **3× runs per arm** + bootstrap CI + paired McNemar/Wilcoxon | P1-1 | medium (scipy) |
| 7 | **Grow to 60 reviewed cases** with all 9 slices ≥5 each, dual-annotated, IAA ≥0.7 | P1-2, P1-5 | large (domain + reviewers) |
| 8 | A1/A2/A3/A4 + A7 Oracle + A9 Guard-bypass-isolated-JVM | P1-4 | large |
| 9 | Badcase taxonomy + adversarial slice growth + cross-corpus | P2 | ongoing |

**Sequencing**: items 1-3 unblock internal confidence in numbers; items 4-6 unblock
the central A5-vs-A6 fairness claim; item 7 unblocks publication-grade benchmark;
items 8-9 are stretch goals.

**Today's state**: item 1 BLOCKED (no expert/KB access completed in eval workspace).
No items below item 1 can meaningfully run.

---

## 8. Approval / rejection

**Decision: NO-GO.**

- No real experiment may be run claiming "Agentic > Hybrid" until P0-1 through P0-4
  are resolved.
- Internal smoke runs (single-config latency, retrieval hit-rate sanity) are
  permissible but their output must be clearly labeled "engineering smoke" not
  "evaluation result".
- Publication must additionally resolve P1-1, P1-2, P1-3, P1-6, P1-7.

**Re-audit gates** (what would change the decision):
1. `gold_freeze_check.py` returns exit 0 on the actual released dataset.
2. `evaluate_aggregate` returns a non-None `faithfulness` for every executed case.
3. A6 runner shipped, runs, and its result envelope carries `topK` equal to A5's
   `maxToolCalls`.
4. 3× repeated runs produce non-overlapping bootstrap CI for at least one metric
   on the `initial_sufficient` slice with N≥30.

Until these four conditions hold, no metric value should be reported as evidence.

---

## 9. Not fabricated

This audit reports **zero** experimental metric values. No pipelines were run for
the purpose of this report. All findings derive from:
- static code reading (`eval/agentic/scripts/*.py`, `eval/agentic/schemas/*.json`,
  `eval/agentic/datasets/*.jsonl`, `eval/metrics/*.py`, `eval/runner/*.py`)
- structural inspection (`pr-7f.1-agentic-eval-design.md`, `pr-7f.2b.*.md`,
  `pr-7f.2c-pre-runtime-activation.md`)
- mechanical examination of dataset content (slice distribution, FILL_ counts,
  annotation leakage in pilot20 lines 1-12)
- prior agent-confirmed facts (GLM key live test, reranker GPU connectivity) used
  only to assess live-endpoint readiness, **not** as metric values.

Any prior session that produced "live IT passed" is referenced as plumbing
verification, not as an evaluation result.
