# PR-7f.2b.2: Agentic RAG Evaluation Harness

> Status: **Harness scaffold complete** — unified `EvaluationResult` schema, metric
> calculators, two stub runners, and pytest coverage. All runners emit
> `executed=false` (NOT_EXECUTED); metrics return `None` for any un-executed
> record so no number is ever fabricated.
>
> Runtime remains frozen. No production code modified.

---

## 0. Goal

PR-7f.2b.1 produced a 20-case gold pilot with FILL_ markers. Before domain experts
fill those markers (PR-7f.2b.3) we need the *measurement* layer in place so that
once gold is ready, the harness can immediately produce comparable Agentic vs
Hybrid metrics. PR-7f.2b.2 builds that layer.

This PR delivers:

1. A **unified result schema** both pipelines write into.
2. **Metric calculators** that consume dataset + actuals and emit aggregate KPIs.
3. **Two runners** (Agentic + Hybrid) — stubs now, live later.
4. **pytest** covering schema, every metric, and runner contracts.
5. A strict **NOT_EXECUTED policy** that makes fabrication impossible.

---

## 1. Files

| Path | Purpose |
|---|---|
| `eval/agentic/schemas/evaluation_result.schema.json` | JSON Schema (Draft-07) for one result record |
| `eval/agentic/scripts/metrics.py` | metric calculators + `evaluate_aggregate` |
| `eval/agentic/scripts/agentic_runner.py` | Agentic stub runner (`pipeline=AGENTIC_FULL`) |
| `eval/agentic/scripts/hybrid_runner.py` | Hybrid RAG stub runner (`pipeline=HYBRID_RAG`) |
| `eval/agentic/tests/test_metrics.py` | 34 pytest cases |

Nothing outside `eval/agentic/` was modified. Runtime code in
`platform-bootstrap` / `platform-common` is untouched.

---

## 2. EvaluationResult Schema

`evaluation_result.schema.json` — single record per `(caseId, pipeline, run)`.
Both runners emit *exactly* this shape so downstream metrics ignore which
pipeline produced the record.

Required fields: `caseId, pipeline, finalStatus, evidenceIds,
requirementCoverage, toolCalls, llmCalls, latencyMs, tokenUsage`.

Notable fields:

| Field | Why |
|---|---|
| `pipeline` | enum `AGENTIC_FULL / AGENTIC_PLANNER_ONLY / AGENTIC_NO_SUFFICIENCY / HYBRID_RAG / HYBRID_RAG_TOPK10 / CLASSIC_RAG / ORACLE_PLAN` — supports the A0–A9 ablation matrix from PR-7f.1 |
| `executed` | **boolean, default false.** `false` = NOT_EXECUTED. Every metric checks this flag first. |
| `realToolCalls` | Replayed (REPLAY mode) calls don't count; only LIVE ones do. |
| `falseSufficientLeak` | Set by the SufficiencyDecisionGuard audit hook when it catches a false-sufficient attempt — counted separately from gold-based false-positive rate. |
| `sufficiencyStatus` | `SUFFICIENT / INSUFFICIENT / CONFLICTED / UNDETERMINED / NOT_RUN` |
| `sseTerminalEvents` | Must be exactly 1 for a legal terminal. >1 is a bug. |
| `nonTerminalStepResidue` | >0 means a Step was left in non-terminal state when the Run terminated — invariant violation. |
| `crossTenantEvidenceLeak` | >0 is a hard security violation. |
| `executedToolSignatures` | Ordered list — used to verify `forbiddenToolSignatures` from the dataset. |
| `replanCount` | Must equal actual Replan cycles; >1 violates the max-1-Replan rule. |

`additionalProperties: true` so live runners can attach extra trace fields
without bumping the schema version.

---

## 3. Runners

### 3.1 Agentic Runner — `agentic_runner.py`

```
python3 eval/agentic/scripts/agentic_runner.py \
    --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \
    --out     eval/agentic/results/agentic_stub.jsonl
```

**Current behavior**: for each case, emit a NOT_EXECUTED record with:
- `executed=false`
- `pipeline="AGENTIC_FULL"`
- all numeric fields = 0, all lists = `[]`
- `requirementCoverage` = one entry per required Requirement with status `NOT_COVERED`
- `errorMessage="NOT_EXECUTED: stub runner; live pipeline wiring deferred"`

**Live behavior (deferred)**: invoke the Java `PlannedAgentPipeline` over SSE,
collect the terminal event + trajectory, map to the schema. Explicitly:

1. `./gradlew :platform-bootstrap:bootRun` with flags
   `--planner.mode=AGENTIC --sufficiency.enabled=true`
2. POST the case question to the agent SSE endpoint
3. Stream terminal event + per-step traces
4. Map to `EvaluationResult`, set `executed=true`

The live wiring depends on (a) the gold dataset being fully reviewed (no
`FILL_` markers — PR-7f.2b.3) and (b) the runtime harness ports being opened,
which is post-PR-7c work.

### 3.2 Hybrid Runner — `hybrid_runner.py`

```
python3 eval/agentic/scripts/hybrid_runner.py \
    --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \
    --out     eval/agentic/results/hybrid_stub.jsonl
```

Same contract, `pipeline="HYBRID_RAG"`, `replanCount=0` (Hybrid never replans).
Live version will invoke the legacy pipeline (the path taken when the
`planned_agent` feature flag is OFF) and reuse the same
`RuleTemplateRequirementExtractor` so coverage semantics match.

---

## 4. Metrics — `metrics.py`

Every function returns `None` (or a dict of `None`s) when no executed records
exist. This is the **NOT_EXECUTED policy**: it is mechanically impossible to
publish a fabricated number through this layer.

| Function | Returns | Inputs |
|---|---|---|
| `gold_evidence_recall` | `float \| None` | actual evidence IDs, gold evidence IDs |
| `gold_document_recall` | `float \| None` | actual doc IDs, gold doc IDs |
| `requirement_coverage_f1` | `{f1, precision, recall, perReq}` | actual coverage list, gold coverage map, required req IDs |
| `final_status_accuracy` | `float \| None` | actuals, dataset |
| `tool_efficiency` | `{avgToolCalls, avgRealToolCalls, avgLlmCalls}` | actuals (only counts `executed=true`) |
| `replan_metrics` | `{replanAttemptRate, replanSuccessRate, replanTriggerPrecision}` | actuals |
| `false_sufficient_rate` | `{rate, count, leakCount, totalChecked}` | actuals + dataset |
| `trajectory_safety` | `{nonTerminalResidueRate, sseMultiTerminalRate, crossTenantLeakRate}` | actuals |
| `latency_stats` | `{p50, p95}` | actuals |
| `evaluate_aggregate` | all-of-the-above | dataset + actuals |

### 4.1 Key invariants

- `gold_evidence_recall([], gold)` → `0.0` (real execution found nothing)
- `gold_evidence_recall(actual, [])` → `None` (no gold → metric undefined, not 0)
- `tool_efficiency` skips `executed=false` records; `safe_div` guards div-by-zero
- `false_sufficient_rate` counts both gold-unanswerable-but-answered **and**
  the `falseSufficientLeak` flag (3rd-layer SufficiencyDecisionGuard audit)
- `trajectory_safety` produces **rates**, not booleans — every non-zero rate is
  an invariant violation worth blocking release

### 4.2 False Sufficient Rate

The single most important defence metric. Two signals, reported separately:

1. **Gold false positive** (`count`): actual `finalStatus=ANSWERED` but
   `gold.answerable=false`. This is the model judging "sufficient" when the
   gold says the case is unanswerable.
2. **Guard leak** (`leakCount`): the runtime `SufficiencyDecisionGuard` flagged
   a false-sufficient attempt that the lower-layer judges (Rule + Model)
   missed. This measures the third defence layer's catch rate.

PR-7f.1 hard gate: **rate ≤ 2%**. The harness reports the raw number; the
release decision is made by humans against that gate.

---

## 5. Tests — `test_metrics.py`

34 pytest cases, all passing. Coverage groups:

1. **Schema** (4): file exists, required fields present, invalid pipeline
   rejected, minimal valid record accepted. (jsonschemaeature-gated — skipped
   if `jsonschema` not installed.)
2. **Gold recall** (7): exact / partial / none / empty-actual / no-gold-None /
   document-recall variants.
3. **Requirement coverage F1** (3): all-tp=1.0, mixed fp/fn=0.0,
   no-required→None.
4. **Final status accuracy** (3): match=1.0, mismatch=0.0, empty→None.
5. **Tool efficiency** (2): only counts executed, all-not-executed.
6. **Replan metrics** (2): no-attempts vs attempted-success-fail mix.
7. **False sufficient** (4): clean, gold-unanswerable, leak-flag,
   not-executed→None.
8. **Trajectory safety** (2): clean vs all-three-violations.
9. **Latency** (2): p50/p95 over 10 samples, empty→None.
10. **Aggregate** (2): all-not-executed→all-None, executed happy path.
11. **Runner contracts** (3): both runners emit `executed=false` + correct
    schema fields; agentic_runner CLI subprocess writes valid output file.

### 5.1 Pre-existing failure (not in scope)

`eval/agentic/tests/test_validate_dataset.py::test_placeholder_evidence_fails`
fails — committed broken in PR-7f.2a (`d4a9214`). The validator intentionally
skips placeholder checks for `candidate` cases (line 99-112) but the test sets
`reviewStatus=candidate` and expects a placeholder error. Out of scope for
PR-7f.2b.2; flagged for PR-7f.2b.3 to fix alongside `FILL_` cleanup.

---

## 6. NOT_EXECUTED Policy

Three mechanical guarantees that make fabrication impossible:

1. **Runner**: emits `executed=false` + zero/empty for every numeric/list field,
   with `errorMessage="NOT_EXECUTED: ..."`.
2. **Metric functions**: filter `[a for a in actuals if a.get("executed", False)]`
   *before* any computation. Empty subset → `None`/dict-of-`None`.
3. **Test**: `test_aggregate_all_not_executed_returns_none` asserts every metric
   returns `None` when the input contains only NOT_EXECUTED records.

This means the harness reports `GoldEvidenceRecall: None` until a real
execution happens — there is no code path that turns a stub into a 0 or 1.

---

## 7. Completion Judgment

PR-7f.2b.2 is **complete** when:

- [x] Schema committed and used by both runners
- [x] `metrics.py` covers all 9 KPI groups from PR-7f.1 §4
- [x] Both runners exist and emit NOT_EXECUTED records
- [x] 34 pytest pass; no live LLM/Java invocation in test suite
- [x] No production code modified
- [x] Documentation (this file)

Deferred to PR-7f.2b.3+:

- [ ] Live Java pipeline subprocess wiring (requires runtime harness ports)
- [ ] Domain expert fills 44 `FILL_` markers in pilot dataset
- [ ] First real A0-vs-A5 head-to-head comparison run
- [ ] A0–A9 ablation matrix execution
