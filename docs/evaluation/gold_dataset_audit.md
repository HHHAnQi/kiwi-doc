# Gold Dataset Audit (PR-7f.2c.1 Task 1)

> Scope: `eval/agentic/datasets/`, `eval/golden/`, `eval/golden/golden_v2_grounded.jsonl`.
> Read-only audit. No experiments. No fabricated metrics.

**Final verdict**: **NOT READY.** Four structural blockers; the existing datasets cannot
be used as released gold for any Agentic vs Hybrid comparison.

---

## 1. Datasets inspected

| Path | Count | Profile | Used as |
|---|---|---|---|
| `eval/agentic/datasets/agentic_v2.pilot20.jsonl` | 20 | candidate, 12 answerable + 8 unanswerable | Pilot — currently blocked |
| `eval/agentic/datasets/agentic_v2.template.jsonl` | 60 | candidate, 9 slices balanced | Annotator workspace target |
| `eval/golden/golden_v2_grounded.jsonl` | 80 | Classic-RAG legacy gold | Pointer source for pilot (docId, chunkId) |
| `eval/golden/golden.jsonl`, `golden_v2.jsonl`, `golden.with_labels.jsonl` | (legacy) | Classic-RAG gold variants | Out of scope |
| `eval/planner/datasets/planner_benchmark_v1.seed.jsonl` | 4 | Seed candidate | PR-7d seed; not used |

---

## 2. Case counts per dataset

```
pilot20        : 20 cases   (12 answerable, 8 unanswerable)
template (60)  : 60 slots   (all candidate)
golden_v2_grounded : 80 cases   (0 ungroundable, classic single-hop only)
```

**Vs PR-7f.1 §4.3 minimum** ("≥80 reviewed, ≥5 per slice"):
- Need: ≥80 reviewed cases across 9 slices with ≥5 each.
- Have: 0 reviewed cases (pilot20 all candidate; template all candidate).

---

## 3. Slice distribution

### 3.1 pilot20

| slice | N | Notes |
|---|---|---|
| initial_sufficient | 6 | OK as seed |
| no_answer_refuse | 4 | OK as seed |
| replan_success | 3 | Too small to disentangle Planner success/failure |
| semantic_metadata_combo | 2 | Too small — version/metadata variants absent |
| replan_still_insufficient | 2 | Too small |
| document_fetch_needed | 1 | **N=1** — single-case governs slice claim |
| permission_denied | 1 | **N=1** |
| evidence_conflict | 1 | **N=1** |
| budget_timeout_edge | 0 | **MISSING** — never exercises `BUDGET_EXCEEDED` |
| tool_failure_recovery | 0 | **MISSING** — Agentic differentiator untested |
| **total** | **20** | 3 slices at N=1; 2 required slices entirely absent |

### 3.2 template (60)

| slice | N |
|---|---|
| initial_sufficient | 10 |
| semantic_metadata_combo | 8 |
| replan_success | 8 |
| no_answer_refuse | 8 |
| document_fetch_needed | 6 |
| replan_still_insufficient | 6 |
| budget_timeout_edge | 6 |
| permission_denied | 4 |
| evidence_conflict | 4 |

Template target distribution is balanced (≥4/slice). Still missing
`tool_failure_recovery` (Agentic differentiator).

---

## 4. Missing fields

### 4.1 pilot20 — placeholder census

Across 20 cases, the following fields are placeholders or empty:

| Field | Placeholder value | Occurrences |
|---|---|---|
| `goldEvidence[].evidenceId` | `FILL_FROM_SHA256` | 12/12 answerable |
| `goldEvidence[].contentHash` | `FILL_FROM_REAL_CHUNK` | 12/12 |
| `goldEvidence[].documentVersion` | `FILL_VERSION` | 12/12 |
| `goldEvidence[].reviewer` | `TODO` | 12/12 |
| `goldEvidence[].reviewedAt` | (empty) | 12/12 |
| `review.reviewStatus` | `candidate` | 20/20 |
| `review.annotator` | `TODO` | 20/20 |
| `review.reviewer` | `TODO` | 20/20 |
| `review.reviewedAt` | (empty) | 20/20 |

### 4.2 Valid evidenceId count

- Regex `[a-f0-9]{12,}` against `evidenceId`:
- **0/12** valid hex evidence IDs.
- All 12 use the literal placeholder string `FILL_FROM_SHA256`.

### 4.3 Fields absent in current schema vs PR-7f.2c.1 spec

The spec requires this minimal object shape per case:

```
{ caseId, query, slice, requirements[],
  gold: { referenceAnswer, evidence: [{documentId, chunkId, contentHash, evidenceId}] },
  expected: { finalStatus, expectedStrategy, maxSteps },
  review: { annotator, reviewer, reviewedAt } }
```

Mapping pilot20 field names → spec names:

| Spec field | pilot20 field name | Status |
|---|---|---|
| `caseId` | `caseId` | ✅ |
| `query` | `question` | ⚠️ rename needed |
| `slice` | `slice` | ✅ |
| `requirements[]` | `requirements[]` | ✅ |
| `gold.referenceAnswer` | `gold.goldAnswer` | ⚠️ rename needed |
| `gold.evidence[]` | `gold.goldEvidence[]` | ⚠️ rename + placeholder fill |
| `gold.evidence[].contentHash` | `goldEvidence[].contentHash` | ❌ FILL_ |
| `gold.evidence[].evidenceId` | `goldEvidence[].evidenceId` | ❌ FILL_ |
| `expected.finalStatus` | `expected.expectedFinalStatus` | ⚠️ rename |
| `expected.expectedStrategy` | (absent) | ❌ missing |
| `expected.maxSteps` | `expected.maxSteps` | ✅ |
| `review.annotator` | `review.annotator` | ✅ structurally |
| `review.reviewer` | `review.reviewer` | ✅ structurally |
| `review.reviewedAt` | `review.reviewedAt` | ✅ structurally (empty) |

---

## 5. Evidence integrity

### 5.1 Pointer traceability ✅

All 12 answerable cases' `(documentId, chunkId)` tuples are traceable to
`eval/golden/golden_v2_grounded.jsonl` (verified by intersection).

Examples:

| pilot caseId | documentId | chunkId | Found in golden? |
|---|---|---|---|
| amh-001 | 6 | 2235 | ✅ |
| amh-004 | 7 | 28 | ✅ |
| amh-005 | 38 | 75 | ✅ |
| amh-009 | 40 | 1077 | ✅ |
| amh-010 | 55 | 1330 | ✅ |

### 5.2 Hash integrity ❌

- 0/12 evidence entries have a valid `contentHash`.
- All use literal `FILL_FROM_REAL_CHUNK`.
- Computation depends on access to actual chunk text in MySQL+Milvus — not available
  in the eval workspace. This is the **freeze blocker**.
- Without `contentHash`, the runtime `evidenceId = sha256(tenantId|docId|chunkId|contentHash)[:12]`
  cannot be reproduced, so `gold_evidence_recall` (metrics.py:272) filters all
  placeholder IDs out → returns `None` for every case.

### 5.3 `documentVersion` ❌

12/12 use `FILL_VERSION`. Real chunk versions matter for the
`semantic_metadata_combo` slice (PR-7f.1 §1.4: `goldCoverageByRequirement[reqId]
requires documentVersion`); version-mismatch detection in `RuleSufficiencyJudge`
cannot operate without this.

---

## 6. Evaluation leakage audit

### 6.1 contentSnippet == goldAnswer leakage ❌ CRITICAL

In **12/12 answerable pilot20 cases**, the value of
`goldEvidence[].contentSnippet` is **identical** (character-for-character) to
`gold.goldAnswer`.

Example (`amh-004`):

```
gold.goldAnswer           = "Dubbo3 在易用性、超大规模微服务实践、云原生基础设施适配、安全设计..."
goldEvidence[0].contentSnippet = "Dubbo3 在易用性、超大规模微服务实践、云原生基础设施适配、安全设计..."
```

**Cause**: pilot20 was extracted mechanically from `golden_v2_grounded.jsonl`,
which itself was generated by `regen_ground_truth.py` with `regen_method:
direct_match`. The `ground_truth_answer` was produced by extracting the matching
sentence from the chunk — so the "source snippet" and the "gold answer" coincide.

**Impact on metrics**:
- A faithfulness metric `is_answer_grounded_in_cited_snippet` would trivially score 1.0
  for every case — falsely suggesting perfect grounding.
- A citation_precision metric that checks "content cited ⊆ content of cited
  evidence" would also trivially score 1.0.
- Any LLM-as-judge faithfulness asked to compare answer vs snippet would be primed
  by identical wording and likely over-score.

**Fix**: pull the **full original chunk text** (not the retrieved sentence) from
the KB and store as `contentSnippet`. The chunk usually has surrounding
headings, examples, and code blocks that differ from the gold-summary sentence,
restoring metric signal.

### 6.2 Question contains chunk-keyword leakage ⚠️

Many pilot20 questions literally encode the answer-keyword:
- `amh-010` "延迟连接配置对哪种 Dubbo 协议生效？" — the chunk heading is
  "延迟连接配置"; a lexical retriever trivially matches.
- `amh-011` "在配置 Dubbo 应用时，如何指定配置作用的应用粒度？" — chunk contains
  the exact phrase "配置作用的应用粒度".

**Cause**: questions in `golden_v2_grounded` were paraphrased from chunk content
during PR-1.

**Impact**: Hybrid/Classic RAG with simple lexical or dense retrieval gets
unfairly high scores; the Agentic advantage on these "hard" cases is masked.

**Fix**: introduce at least 30% "obfuscated query" cases per slice — paraphrase
the question using synonyms / indirect framing, avoiding chunk-keyword reuse.
This is a Phase 2 dataset growth task.

### 6.3 `acceptableInitialPlans` over-narrow ⚠️

For all 20 cases, `acceptableInitialPlans` is `[{"toolSequence": ["semantic_search"],
"coveredReqIds": ["REQ-1"]}]`. Any valid Planner output using
`metadata_search`, `document_fetch`, or a different ordering is rejected
by the (future) Plan-Acceptable-Match metric.

**Impact**: the A7 Oracle baseline (`pr-7f.1 §2.1`) becomes meaningless — Oracle
can only reproduce the same plan, so "Planner vs Oracle" gap is artificially
forced to 0.

**Fix**: per case, list ≥2 acceptable plans including alternative tool
signatures and bind covered reqs explicitly. Annotation-guideline work.

### 6.4 Gold answer leaks into evidence rationale ❌

In pilot20 every `goldEvidence[].rationale` is the literal string
`"待 domain expert 填写: 说明为什么该 chunk 覆盖 REQ-1"`. Once filled, the rationale
must NOT repeat the gold answer; it must cite line numbers / section headings
in the chunk. Validator (Task 3) should enforce
`rationale not contained in referenceAnswer`.

---

## 7. Summary of blockers

| # | Blocker | Files affected | Unblocks |
|---|---|---|---|
| B1 | 36 `FILL_*` placeholders; 0/12 valid evidenceId/contentHash | pilot20 + template | All retrieval metrics → return None |
| B2 | `contentSnippet == goldAnswer` in 12/12 answerable | pilot20 | Faithfulness metric would be trivially 1 |
| B3 | 0/20 cases reviewed (`review.reviewStatus=candidate`, annotator=reviewer=TODO) | pilot20 | Per validator policy, dataset cannot be used for runs |
| B4 | Missing required slices (`tool_failure_recovery`, `budget_timeout_edge`) and 3 slices at N=1 | pilot20 | Cannot make per-slice claim |

---

## 8. Readiness decision

**Not ready.** The pilot20 file is a useful *seed* (it indicates structure,
slice intent, and provides `(documentId, chunkId)` pointers), but it is **not
a usable gold dataset** by any of these criteria:

1. No valid evidence hashes → retrieval metrics cannot compute.
2. Annotation leakage → faithfulness metrics would inflate to 1.0.
3. Zero reviewed cases → cannot pass `validate_gold_dataset.py` (Task 3 below).
4. Slice imbalance → no slice-level claim is statistically supportable.

The minimum unblock path is documented in `gold_annotation_guideline.md`
(Task 4). Recommended target before any "internal benchmark" runs:
**60 reviewed cases** (filled template, dual-annotated, slice-balanced) per
PR-7f.1 §4.3.

---

## 9. Not fabricated

No metric values reported. No experiments run. All findings from static
inspection of dataset files and intersect with `golden_v2_grounded.jsonl`.
