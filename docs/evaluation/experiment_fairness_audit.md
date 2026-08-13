# Experiment Fairness Audit

> Question under audit: **Can current experiment design prove "Agentic RAG is better
> than Hybrid RAG"?**
> Scope: ablation design (A0-A9, design `pr-7f.1 §2.1`), runners
> (`agentic_runner.py`, `hybrid_runner.py`), config surface
> (`RAG_RERANK_*`, `LLM_*`, `rag.agent.planner.*`), evaluation pipeline.
> Read-only. No experiments run.

**Short answer**: **NO.** Baselines are *defined* but **not implemented in runner
code**, and **no ablation can run today** because (1) gold is BLOCKED, (2) runtime
gate for PLANNED_AGENT was just wired in PR-7f.2c-pre, (3) A6/A7/A9 controls
have no runner code.

---

## 1. Baseline coverage audit

### 1.1 What the design specifies (A0-A9, `pr-7f.1 §2.1`)

| ID | Name | Has runner code today? | Has reviewed gold? | Outcome measurable now? |
|---|---|---|---|---|
| A0 | Classic RAG | ⚠️ exists in `eval/eval_pipeline.py` (`_samples_80`) — but Classic pipeline ≠ planned agentic runner | ❌ | only legacy samples can be replayed |
| A1 | Router RAG | ❌ no runner | ❌ | no |
| A2 | Planner only | ❌ no runner | ❌ | no |
| A3 | Planner + Rule Suff | ❌ no runner | ❌ | no |
| A4 | Planner + Rule/Model Suff | ❌ no runner | ❌ | no |
| **A5** | **Full PR-7 (Agentic)** | ✅ `agentic_runner.py --mode live` wired (PR-7f.2c-pre) | ❌ gold BLOCKED | runtime reachable, gold not |
| A6 | More-Tool-Calls Control | ❌ no runner; needs parameterized Classic topK=10 | ❌ | no |
| A7 | Oracle Plan | ❌ no runner; needs `acceptableInitialPlans` honored by PlannerProvider | ❌ (and `acceptableInitialPlans` is overly narrow) | no |
| A8 | Hybrid + Rerank | ✅ `hybrid_runner.py --mode live` wired (PR-7f.2c-pre) | ❌ gold BLOCKED | runtime reachable, gold not |
| A9 | Agentic w/o Sufficiency | ❌ no runner; needs evaluation-only Guard bypass | ❌ | no |

**Verdict**: of 10 designed baselines, **only 2 (A5, A8) are technically runnable
today** and both depend on gold that is FILL_-blocked. The **most important** baseline
— **A6 More-Tool-Calls Control** (the central safety against the
"agent is just retrieving more" critique) — has no runner.

### 1.2 What "Hybrid RAG" means in this project

Per `pr-7f.1 §0.Q1`, "Hybrid RAG" denotes "Hybrid + Rerank" (A8). The hybrid_runner.py
(`PR-7f.2b.3b`) routes via `mode=RAG` to the existing classic pipeline (forced
`CLASSIC_RAG`) **not actually Hybrid**. This is a definitional mismatch.

The project has `RAG_RERANK_ENABLED` flag: when `true` it is "dense + rerank" (not
hybrid dense+BM25). True Hybrid (dense + BM25 RRF) is documented in `application-dev.yml`
(`rag.retrieve.dense|hybrid`) but the runner does not toggle it.

**Misalignment risk**: A5 vs A8 comparison may end up being
"Agentic vs Classic-with-rerank" rather than "Agentic vs Hybrid-dense-BM25-with-rerank".
Need explicit flag-pin policy.

---

## 2. Fair comparison audit

### 2.1 Fairness checklist

| Dimension | Locked by design? | Locked in runner code? | Verdict |
|---|---|---|---|
| Same **dataset** | ✅ (same `agentic_v2.reviewed.jsonl`) — when frozen | ✅ (same `--dataset` arg) | OK |
| Same **knowledge base** (corpus + version) | ✅ implicit, single Milvus collection | ⚠️ NOT enforced — runner has no KB-version readback; tenant mismatch possible | needs runtime KB-version stamp |
| Same **LLM** | partial | ⚠️ env `LLM_BASE_URL`/`LLM_MODEL` only — runner does not freeze or report resolved values | must echo in result envelope |
| Same **embedding model** | partial | ❌ no embedding variant-lock in runner | add to result envelope |
| Same **reranker** | partial (`RAG_RERANK_ENABLED`) | ❌ runner does not freeze flag | echo config |
| Same **retrieval budget** (topK for Hybrid vs maxToolCalls for Agentic) | ✅ spec (`pr-7f.1 §0.Q1` "maxToolCalls <= topK") | ❌ **NOT enforced**; Fairness Lock absent in runner | needs explicit budget-equality wall |
| Same **LLM token budget** | partial (`tokenUsage` in schema) | ❌ no check that paths used the same model-pricing | needs configuration snapshot |
| Same **temperature / max_tokens** | partial (in `LlmProperties`) | ❌ runner does not snapshot resolved LLM call params | needs snapshot |
| Deterministic retrieval (fixed seed) | partial (Milvus HNSW may differ across boots) | ❌ no seed-pin | mediocre — retrieval variance across runs untracked |
| **Multi-run averaging** (≥3 runs to absorb LLM nondeterminism) | ✅ required by science-standard | ❌ single-shot runner | misses variance estimate |

**Verdict**: of 10 fairness dimensions, **only 1 (dataset) is robustly locked**. The
remaining 9 either exist as documentation-only OR are partially locked at config layer
with no echo in result envelope. An A0-A9 comparison cannot currently be claimed as
"fair" because we cannot prove equivalence from result files alone.

### 2.2 Specific risk: A6 inverse-baseline is missing

`pr-7f.1 §0.Q1` says: "If Hybrid RAG with K=20 reaches the same Gold Recall as Agentic
with K=10, Planner is not adding value, just retrieving more."

Currently:
- Agentic budget: hard-coded via `expected.maxToolCalls=3` (pilot20)
- Hybrid baseline: topK is whatever the production retriever uses (default 5 per
  `RAG_RERANK_CANDIDATE_POOL`)
- **A6 (Hybrid topK=10 or 20) has no runner.**

This means even if A5 wins, the central fairness concern is **not addressed**.

### 2.3 Specific risk: A7 Oracle is not implementable as-is

`A7 Oracle Plan` requires running the agent with `PlannerProvider` returning the gold
plan from `acceptableInitialPlans`. Per `dataset_audit.md §4.4`, the
`acceptableInitialPlans` are narrow (`[semantic_search]` only, REQ-1 only), so Oracle
would not even cover multi-req cases properly. The Oracle baseline as specified can
give a misleading "Planner ≈ Oracle" result.

### 2.4 Specific risk: A9 Guard-bypass has no safe isolation

`A9 Agentic w/o Sufficiency` requires bypassing `SufficiencyDecisionGuard`. Design
§6.3 mandates "bypass must run in a separate eval-only JVM, not share bean graph
with production runtime". No such isolation harness exists. The single JVM with
profile toggle would risk production-path contamination.

---

## 3. Statistical reliability audit

### 3.1 Sample size and significance

| Slice | Pilot20 N | Per-arm needed for paired t-test (α=0.05, β=0.2, small effect) | Gap |
|---|---|---|---|
| `initial_sufficient` | 6 | ≥30 | -24 |
| `replan_success` | 3 | ≥30 | -27 |
| `replan_still_insufficient` | 2 | ≥30 | -28 |
| `no_answer_refuse` | 4 | ≥30 | -26 |
| single-req cases (N=1 req) | 16 | — | most pile into "easy retrieval" |
| multi-req cases (N=2 reqs) | 4 | ≥30 for true multi-hop claim | -26 |

Even on the best slice (initial_sufficient, N=6), a 5pp improvement between Agentic
and Hybrid is **undetectable** at standard α=.05. The Cohen's d needed would have to
be >2 (i.e., a huge effect).

### 3.2 Missing statistical machinery

Searched repo (incl `eval/`, `docs/`) for `bootstrap`, `confidence interval`,
`wilcoxon`, `paired t-test`, `mcnemar`, `p-value`, `significance`:

**Zero statistical-testing code**. Search hits:
- All `bootstrap` matches are either pygments Stata-keyword lists
  (`eval/.venv/.../pygments/lexers/_stata_builtins.py`) or
  Spring-Boot-startup references (`platform-bootstrap`).
- All `wilcoxon` / `mcnemar_test` are pygments Matlab/Igor lexer tokens.
- No `scipy.stats` import anywhere in eval/agentic.
- `judge_ensemble.py` has badcase-disagreement detection (>0.2) — a heuristic, **not a test**.

**Verdict**: project has **no infrastructure to compute the confidence interval or
p-value of any metric**. Without this, "Agentic > Hybrid by X%" is unsupportable.

---

## 4. Fairness conclusion

To fairly evaluate Agentic vs Hybrid, the project needs:

| Required | State today |
|---|---|
| ≥30/slice reviewed dual-annotated gold | ❌ |
| A6 More-Tool-Calls runner | ❌ |
| A7 Oracle runner with exact `acceptableInitialPlans` semantics | ❌ |
| A9 isolated-JVM Guard-bypass | ❌ |
| Per-arm config snapshot in result envelope | ❌ |
| ≥3 repeated runs per arm | ❌ |
| Bootstrap CI / paired test infrastructure | ❌ |
| RAGAS or LLM-judge faithfulness baseline | ❌ (legacy exists, not wired) |

The 2 wired runners (A5, A8) can produce **single-shot numbers**, but those numbers
are not interpretable as scientific comparison.

---

## 5. Recommendation — minimum path to a fair comparison

This is not "all the science" but a plausible 4-6 week plan to move from blocked to
a defensible internal benchmark:

1. **Freeze gold** (P0 BLOCKER) — fill FILL_ in pilot20, then grow to 60 reviewed
   dual-annotated cases with annotated guidelines and IAA ≥ 0.7.
2. **Wire A6 runner** + force `topK` parameterization — non-negotiable for any Planner
   value claim.
3. **Echo config envelope** in every EvaluationResult: `{LLM_base, LLM_model,
   embed_model, rerank_enabled, topK_or_maxToolCalls, temperature, KB_version,
   run_id, repeat_index}`.
4. **3× runs per arm** with seed-pinned retrieval (or document variance).
5. **Wire `eval/metrics/retrieval_metrics.py` + `eval/ragas_pipeline.py`** into
   agentic_report.
6. **Bootstrap CI** + paired McNemar for matched caseId pairs across A5 vs A6 vs A8.
7. (Optional for internal) A7 Oracle + A9 Guard-bypass after the above are clean.

Items 1-6 take the comparison from "plausible narrative" to "internally defensible".
Item 7 takes it to "publishable". Neither is achievable today.

---

## 6. Not fabricated

No metric or comparison numbers reported. All findings derive from static analysis of
runner code, config files, and design docs.
