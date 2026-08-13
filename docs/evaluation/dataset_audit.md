# Dataset Audit

> Scope: `eval/agentic/schemas/agentic_case_v2.schema.json` (schema),
> `eval/agentic/datasets/agentic_v2.pilot20.jsonl` (20-entry pilot, candidate),
> `eval/agentic/datasets/agentic_v2.template.jsonl` (60-entry template, candidate),
> `eval/golden/golden_v2_grounded.jsonl` (80-entry classic-RAG gold — source for pilot).
> Read-only. No code/dataset edits. No experiments.

---

## 1. Schema coverage (`agentic_case_v2.schema.json`)

The schema is well-defined: 174 lines JSON-Schema-Draft-07. Required fields: caseId,
question, questionType, intent, requirements, gold, planConstraints, expected, slice,
review. Per-Requirement objects, per-evidence gold binding, slice enum (9 values),
review enum (`candidate|reviewed|rejected`).

**Coverage verdict**: schema is **structurally adequate** for the design in
`pr-7f.1 §1.2`. All defined cases pass `validate_dataset.py --require-reviewed` when
filled. Schema issues are scoped → see `evaluation_readiness_audit.md` P1 list.

---

## 2. Dataset quantity audit

| Dataset | Count | Reviewed | Used in current eval? |
|---|---|---|---|
| `agentic_v2.pilot20.jsonl` | 20 | 0/20 (all candidate) | Should not — `review.reviewStatus=candidate` excluded by `gold_freeze_check.py` |
| `agentic_v2.template.jsonl` | 60 | 0/60 | Same — all candidate, fill markers present |
| `golden_v2_grounded.jsonl` | 80 | partial (legacy review; schema differs) | Used as **pointer source** for pilot20, but no v2 evidenceId/contentHash backfill done |
| `planner_benchmark_v1.seed.jsonl` | 4 | 0/4 | Seed; not used in agentic eval |
| `router_cases.jsonl` | 100 | (legacy) | Router-only training/eval |

### Required dataset size — per-context

**Definition of "size" approach** (from spec): smoke-test / engineering validation /
algorithm comparison / publication.

| Goal | Minimum cases | Slice balance | Statistical bar | Current |
|---|---|---|---|---|
| **Engineering / smoke** — "system runs end-to-end" | 5–10 across ≥3 slices | flexible | none | ✅ pilot20 has 20 across 8 slices — adequate IF filled |
| **Algorithm comparison (A0 vs A5)** | The intent of project, ~80 reviewed, ≥5/slice | per-slice ≥5 (PR-7f.1 §4.3) | paired-point-estimate only | ❌ 20 candidate; 0 reviewed |
| **Statistical significance** (claim "Agentic > Hybrid") | ≥30 per slice (CLT), ≥100 total preferred | ≥30/slice of interest | McNemar / paired-t with bootstrap CI | ❌ way too small; 3 replan_success / 1 evidence_conflict / 1 permission_denied / 1 document_fetch_needed |
| **Publication-grade** | 200-500+ hand-curated, dual-annotated, with inter-annotator-agreement (IAA) reported | ≥30/slice + adversarial badcase slice | paired test + ablation all with CI | ❌ far below; no IAA |

**Verdict**: pilot20 is **engineering-smoke-tier** only. Statistical claims are
**not supportable** at this size. Three slices have N=1: **same single-case governs
publication-level judgment of "evidence_conflict detection", "permission denial", "document-fetch-needed"**.

---

## 3. Dataset coverage matrix

### 3.1 Retrieval difficulty

| Capability | Need? | Pilot20 coverage | Template (60) | Verdict |
|---|---|---|---|---|
| Single-hop (FACT, single retrieval) | required baseline | not isolated — 16 cases REQ-only but intent labeled MULTI_HOP/PROCEDURAL | template lacks single-hop slice | ⚠️ Mix with multi-hop; no clean single-hop "Hybrid may already be good" control |
| Multi-hop (multi-req join) | central Agentic claim | 4 cases have 2 requirements (amh-008, 010, 011, 012) | template has `replan_success` slice ~8 | ⚠️ **only 4/20** are true multi-hop; underpowered for the central claim |
| Long-context | optional | not covered | not covered | ❌ missing |
| Metadata filtering (version / source) | required (Planner differentiation) | 2 cases (`semantic_metadata_combo`) | 8 | ⚠️ only 2 cases; version-difference variants absent in pilot |
| Document fetch (`document_fetch_needed`) | required (Tool selection) | 1 case (`amh-007`) | 6 | ⚠️ 1 only |

### 3.2 Agent capability

| Capability | Need? | Pilot20 cases | Verdict |
|---|---|---|---|
| Initial-sufficient (no replan) | baseline | 6 (`initial_sufficient`) | ✅ adequate |
| Insufficient-requiring-replan | central Agentic claim | 3 (`replan_success`) + 2 (`replan_still_insufficient`) = 5 | ⚠️ only 5 across both outcomes — too small to disentangle Planner success vs failure |
| Conflict resolution (`evidence_conflict`) | required | 1 (`amh-016`) | ❌ single-case; publication impossible |
| Refusal / no-answer (`no_answer_refuse`) | safety | 4 | ⚠️ marginal |
| Permission-denied | required slice | 1 (`amh-015`) | ❌ single-case |
| Tool failure recovery | required (Agentic claim) | 0 | ❌ **completely absent** — no test of `TOOL_FAILED` finalStatus or recovery behavior |
| Budget / timeout limit (`budget_timeout_edge`) | required | 0 | ❌ present in template (6) but absent in pilot — never exercises `BUDGET_EXCEEDED` finalStatus |

### 3.3 Business scenario realism

- All 20 pilot questions drawn from `golden_v2_grounded`, which itself was curated from
  Spring Cloud Alibaba docs corpus (real enterprise RAG ✓).
- **Question origin**: 4 cases are template-like "X和Y差异" formula; the rest are
  procedurally reasonable. No genuinely hard/ambiguous user queries.
- **Freshness**: corpus is fixed classic SCA docs; no time-sensitive drift cases.
- **Ambiguity**: not represented; no case tests "ambiguous query → clarification /
  refuse" behaviour.

**Verdict**: business scenario is **monoculture** (Spring Cloud Alibaba Chinese tech
docs). May represent the current production corpus, but **does not test generality**.

---

## 4. Gold annotation quality audit

### 4.1 evidenceId / contentHash — blocked

Across pilot20's 20 cases:
- 12 answerable cases, each carrying ≥1 gold evidence
- **All 12 goldEvidence entries have `evidenceId="FILL_FROM_SHA256"`** (placeholder)
- **All 12 `contentHash="FILL_FROM_REAL_CHUNK"`** (placeholder)
- 12 `documentVersion="FILL_VERSION"` (placeholder)
- `goldEvidence[].reviewer="TODO"` (12 entries)
- `review.reviewedAt=""` (all 20)

`gold_freeze_check.py` exit code = 1 (BLOCKED). Confirmed in PR-7f.2b.3.

**Impact on metrics**:
- `gold_evidence_recall` is **uncomputable** — the metric filters out FILL_ evidence
  IDs at line 272 of metrics.py (`if not evidenceId.startswith("FILL_")`). So for all
  12 answerable cases the metric returns None.
- `goldCoverageByRequirement` values are also `["FILL_FROM_SHA256"]` → same filtering.

### 4.2 Reproducibility

Can we reproduce gold evidence?
- **Yes for pointer (docId, chunkId)**: pilot20 anchors verified traceable to
  golden_v2_grounded (all 20 (doc,chunk) tuples exist in source 80-case gold ✓).
- **No for hash (evidenceId, contentHash)**: requires access to the actual chunk text
  in the running knowledge base, which is MySQL/Milvus-coupled and not exported to eval
  workspace. This is the freeze blocker.

### 4.3 Inter-annotator agreement (IAA)

- **No IAA protocol exists.** Schema has only one `reviewer` field per evidence, plus a
  `review.reviewStatus` workflow. There is no double-annotation step.
- `pr-7f.1 §4.3` says "review gold 必须双签: 第 1 人写 gold + 第 2 人审核" — **not
  enforced** in schema (`annotator` and `reviewer` can both be empty / "TODO").
- `validate_gold_completeness.py` does not check `annotator ≠ reviewer`.

**Verdict**: gold annotation workflow does **not meet research-grade standard**.
Agreement cannot be reported because the schema cannot capture dual annotation.

### 4.4 Annotation leakage risk

Two leakage vectors identified:

1. **contentSnippet == goldAnswer phenomenon (12/12 answerable cases)**: in pilot20,
   the `goldEvidence[].contentSnippet` is **identical** to the gold_answer — i.e. the
   "source chunk snippet" is the same string as the gold answer text. This is
   **annotation leakage**: a metric that checks "answer is contained in cited snippet"
   will trivially score 1.0.
   - Confirmed by reading pilot20 lines 1-12: e.g. case `amh-004`, contentSnippet ==
     "Dubbo3 在易用性、超大规模微服务实践、云原生基础设施适配、安全设计等几大方向上进行了全面升级。"
     == goldAnswer verbatim.
   - Cause: pilot20 was extracted mechanically from `golden_v2_grounded` whose
     `ground_truth_answer` was generated from the chunk, so they coincide. Real chunk
     text usually contains surrounding scaffolding (headings, examples) that differs
     from the gold-summary sentence.
   - **Impact**: faithfulness-based metrics will be inflated. Solution: pull the actual
     chunk text from the KB (resolves both #4.1 and #4.4).

2. **Gold plan = initial acceptable plan (self-confirming)**: for cases with
   `acceptableInitialPlans`, the plan is always `[semantic_search]` covering REQ-1 only.
   Real Planner output can be different (and equally valid). The current schema marks
   non-listed plans as "fail" (Plan Acceptable Match metric), which is over-restrictive.

---

## 5. Specific dataset issues (P0..P2)

| Priority | Issue | Effect |
|---|---|---|
| P0 | 36 `FILL_*` markers; evidenceId/contentHash/version all placeholders | All retrieval metrics return None |
| P0 | `contentSnippet == goldAnswer` for all 12 answerable cases | Faithfulness inflated if used naively |
| P0 | 0/20 reviewed (all candidate) | Per `validate_dataset --require-reviewed`, dataset **cannot be used** for runs |
| P1 | No multi-turn true multi-hop (only 4 cases have 2 reqs; 0 have ≥3) | Cannot validate core Q1 hypothesis at scale |
| P1 | Slice N=1: `evidence_conflict`, `permission_denied`, `document_fetch_needed` | Cannot make any per-slice claim |
| P1 | `tool_failure_recovery` slice entirely missing | Untested Agentic differentiator |
| P1 | `budget_timeout_edge` slice in template but not pilot | `BUDGET_EXCEEDED` finalStatus never exercised |
| P1 | No IAA schema / `annotator != reviewer` not enforced | Gold reproducibility informal |
| P2 | All from SCA corpus — language/domain monoculture | Generality unknown |
| P2 | Question text often literally encodes "what config field": e.g. `延迟连接配置` (close to keyword in chunk) | Lexer-level retrieval will trivially succeed; underestimates Hybrid |

---

## 6. Recommended dataset growth plan

| Phase | Target | Pre-condition |
|---|---|---|
| Phase 0 — Smoke (current pilot20 + freeze) | 20 reviewed, FILL resolved, snippet pulled | Domain expert access to KB chunk text |
| Phase 1 — Engineering benchmark | 60 reviewed, all 9 slices ≥5 each, dual-annotated | Annotation guideline doc + 2 reviewers |
| Phase 2 — Algorithm comparison | 120 reviewed, target slices ≥30 each (initial_sufficient, replan_success, no_answer_refuse) | IAA computed (Cohen's kappa ≥ 0.7) |
| Phase 3 — Publication | 250+ reviewed + adversarial hard-negative slice + multi-corpus | Open annotation protocol |

**Today the dataset's maturity is between Phase 0 prep and Phase 0 — i.e. pre-smoke.
It cannot support an algorithmic comparison claim.**

---

## 7. Not fabricated

No metric values reported. No experiments run. All findings derive from the static
content of `agentic_v2.pilot20.jsonl`, `agentic_v2.template.jsonl`,
`golden_v2_grounded.jsonl`, schema, and validators.
