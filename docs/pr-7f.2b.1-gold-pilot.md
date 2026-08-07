# PR-7f.2b.1: Agentic RAG Gold Dataset Pilot

> Status: **Pilot scaffold complete** — 20 cases derived from `eval/golden/golden_v2_grounded.jsonl`
> (real Dubbo/Spring Cloud Alibaba corpus). All require human completion + dual-signoff before evaluators use them.
>
> **No fabricated evidence.** `evidenceId` = `"FILL_FROM_SHA256"`, `contentHash` = `"FILL_FROM_REAL_CHUNK"` — must be computed from real chunk text.
>
> Runtime frozen. No production code modified.

---

## 1. Source Corpus

Pilot cases are derived from the **existing production-like golden set**: `eval/golden/golden_v2_grounded.jsonl` (80 cases, Dubbo/Spring Cloud Alibaba technical Q&A with real `docId` + `chunkId` mappings).

| Source field | Pilot mapping |
|---|---|
| `question` | `question` |
| `new_ground_truth_answer` | `gold.goldAnswer` |
| `ground_truth_doc_id` | `gold.goldDocumentIds[0]` |
| `new_ground_truth_chunk_id` | `gold.goldEvidence[0].chunkId / documentId` |
| `question_type` | `intent` (multi_hop / procedural / config / factual / troubleshoot) |

**Key limitation**: The golden set provides `docId` + `chunkId` but **does not contain chunk text**.
Therefore:
- `goldEvidence.evidenceId` = `"FILL_FROM_SHA256"` — annotator must compute `sha256(tenantId|docId|chunkId|contentHash)` from real chunk
- `goldEvidence.contentHash` = `"FILL_FROM_REAL_CHUNK"` — annotator must compute `sha256(chunk_content)` from real chunk
- `goldEvidence.rationale` = placeholder — annotator writes why chunk covers the Requirement

---

## 2. 20 Pilot Cases

File: `eval/agentic/datasets/agentic_v2.pilot20.jsonl`

### Slice Distribution

| Slice | Count | Source Type | Expected Status |
|---|---:|---|---|
| initial_sufficient | 6 | multi_hop + procedural | ANSWERED |
| semantic_metadata_combo | 2 | procedural | ANSWERED |
| document_fetch_needed | 1 | procedural | ANSWERED |
| replan_success | 3 | config | ANSWERED |
| replan_still_insufficient | 2 | factual | REFUSED_NO_EVIDENCE |
| no_answer_refuse | 4 | ungroundable / troubleshoot | REFUSED_NO_EVIDENCE |
| permission_denied | 1 | adapted | REFUSED_PERMISSION |
| evidence_conflict | 1 | adapted | REFUSED_CONFLICT |
| (budget_timeout_edge) | 0 | — | (reserved for v2) |
| **Total** | **20** | | |

**Note**: Each case is `reviewStatus = "candidate"`; `annotator = "TODO"`, `reviewer = "TODO"`. The gold completeness validator reports **0 structural errors + 44 pending fills** that domain expert must complete.

### Sample Case (amh-001)

```json
{
  "caseId": "amh-001",
  "question": "在多注册中心订阅的场景下，Spring Cloud Alibaba提供了哪些选址策略？",
  "questionType": "MULTI_HOP",
  "intent": "MULTI_HOP",
  "requirements": [
    {"requirementId": "REQ-1", "type": "FACT", "required": true, "description": "回答主问题", ...}
  ],
  "gold": {
    "goldAnswer": "Spring Cloud Alibaba 提供了...",
    "goldEvidence": [{
      "evidenceId": "FILL_FROM_SHA256",
      "documentId": 23, "chunkId": 2126,
      "contentHash": "FILL_FROM_REAL_CHUNK",
      "bindsToRequirementIds": ["REQ-1"],
      "rationale": "待 domain expert 填写",
      "reviewer": "TODO"
    }],
    "goldCoverageByRequirement": {"REQ-1": ["FILL_FROM_SHA256"]},
    "answerable": true
  },
  "planConstraints": {
    "acceptableInitialPlans": [{"toolSequence": ["semantic_search"], "coveredReqIds": ["REQ-1"]}]
  },
  "expected": {"expectedFinalStatus": "ANSWERED", "replanExpected": false},
  "slice": "initial_sufficient",
  "review": {"reviewStatus": "candidate", "annotator": "TODO", "reviewer": "TODO"}
}
```

---

## 3. Human Annotation Template

### Step 1: Annotator (person A)

For each case:

```
□ 1. Read question; identify 1-3 Requirements (REQ-{N}) from question semantics
□ 2. Query production-like corpus by docId + chunkId → retrieve actual chunk text
□ 3. Compute contentHash = sha256(chunk_text_utf8)[:64]
□ 4. Compute evidenceId = sha256(tenantId + "|" + docId + "|" + chunkId + "|" + contentHash)[:64]
□ 5. Fill goldEvidence[0].evidenceId (hex)
□ 6. Fill goldEvidence[0].contentHash (hex)
□ 7. Fill goldEvidence[0].documentVersion (if known)
□ 8. Fill goldEvidence[0].rationale: "chunk {chunkId} 行 X-Y 包含 {entity} 的 {attribute}"
□ 9. Fill review.annotator = "your-name"
□ 10. If multi-hop: add REQ-2 + second goldEvidence from second chunk
□ 11. If replan slice: add acceptableReplanPlans covering REQ-2
□ 12. Fill for: forbiddenToolSignatures if applicable (e.g. citation_verify must not be initial tool)
□ 13. Run: python3 eval/agentic/scripts/validate_gold_completeness.py pilot20.jsonl --strict
     → Must see 0 errors before requesting review
```

### Step 2: Reviewer (person B, ≠ annotator)

```
□ 1. Independently query same docId + chunkId → verify chunk exists
□ 2. Verify contentHash matches sha256 of chunk text
□ 3. Verify rationale accurately describes why chunk covers Requirement
□ 4. Verify goldAnswer is grounded in the listed evidence (no speculation)
□ 5. If accept:
     - review.reviewer = "your-name"
     - review.reviewedAt = ISO-8601 timestamp
     - review.reviewStatus = "reviewed"
□ 6. If reject:
     - review.reviewStatus = "rejected"
     - notes = "rejection reason"
□ 7. Run: python3 eval/agentic/scripts/validate_dataset.py pilot20.jsonl --require-reviewed
     → Must see OK with N reviewed + 0 errors
```

### Step 3: Validator Gate

```bash
# Structural + cross-field (template phase)
python3 eval/agentic/scripts/validate_dataset.py eval/agentic/datasets/agentic_v2.pilot20.jsonl

# Gold completeness (FILL_ markers → warnings for candidate; errors for reviewed)
python3 eval/agentic/scripts/validate_gold_completeness.py eval/agentic/datasets/agentic_v2.pilot20.jsonl

# Strict gate (only reviewed cases, no FILL_ allowed)
python3 eval/agentic/scripts/validate_dataset.py pilot20.jsonl --require-reviewed
python3 eval/agentic/scripts/validate_gold_completeness.py pilot20.jsonl --strict
```

---

## 4. Gold Completeness Validator

File: `eval/agentic/scripts/validate_gold_completeness.py`

**13 completeness checks** (beyond structural schema):

| # | Check | Candidate (warning) | Reviewed (error) |
|---|---|---|---|
| 1 | Every goldEvidence field non-empty + non-FILL | ⚠ warning | ✗ error |
| 2 | evidenceId is ≥12 hex chars | ✗ error | ✗ error |
| 3 | contentHash is ≥12 hex chars | ✗ error | ✗ error |
| 4 | Every required Requirement has ≥1 goldEvidence | ⚠ warning | ✗ error |
| 5 | bindsToRequirementIds ≥1 | ✗ error | ✗ error |
| 6 | goldCoverageByRequirement covers required | ⚠ warning | ✗ error |
| 7 | acceptableInitialPlans cover required IDs | — | ✗ error |
| 8 | answerable=true → evidence + goldAnswer non-empty | ⚠ warning | ✗ error |
| 9 | answerable=false → evidence expected empty | ⚠ warning | ⚠ warning |
| 10 | evidenceIds unique within case | ✗ error | ✗ error |
| 11 | reviewer ≠ annotator (dual-signoff) | — | ✗ error |
| 12 | forbiddenToolSignatures disjoint from acceptable plans | ✗ error | ✗ error |
| 13 | slice → expectedFinalStatus mapping | ✗ error | ✗ error |

`--strict` mode: warnings become errors (for final gate before evaluation).

### Current Pilot Status

```
python3 eval/agentic/scripts/validate_gold_completeness.py pilot20.jsonl
→ Summary: 20 cases, 0 errors, 44 warnings
→ Result: OK (structural) — 44 fields need manual completion
```

14 of 20 cases have ≤3 pending fills (single-REQ initial_sufficient). 6 cases have ≤5 (multi-REQ
with REQ-2 needing second chunk annotation).

---

## 5. Tests

File: `eval/agentic/tests/test_gold_completeness.py` — **12 pytest 全绿**

| Test | Check |
|---|---|
| test_complete_case_no_errors_no_warnings | Happy path |
| test_fill_marker_is_warning_not_error | FILL_ → warning (candidate) |
| test_fill_marker_is_error_in_strict | --strict → error |
| test_fill_marker_is_error_when_reviewed | reviewed + FILL_ → error |
| test_non_hex_evidence_id_fails | Invalid format |
| test_required_req_without_evidence_warning | Missing coverage |
| test_answerable_false_with_evidence_warns | Expected empty |
| test_duplicate_evidence_id_fails | Uniqueness |
| test_forbidden_sig_overlap_fails | forbidden ∩ acceptable |
| test_dual_signoff_reviewed_same_person_fails | annotator == reviewer |
| test_slice_status_mismatch_fails | Mapping violation |
| test_pilot20_file_exists_and_validates | 20 cases, 0 structural errors |

---

## 6. Missing: Real Chunk Text

The pilot cases reference real `docId` + `chunkId` from `golden_v2_grounded.jsonl`, **but the golden
set does not include chunk text**. To compute `contentHash` and `evidenceId`, domain expert must:

1. Connect to the Milvus corpus used in `golden_v2_grounded` evaluation
2. Query `chunk {chunkId} in document {docId}`
3. Compute `sha256(chunk_text)`
4. Fill into `goldEvidence.contentHash` + `evidenceId`

**If real chunk text is not accessible,** all 20 pilot cases remain in `candidate` status with
`FILL_FROM_*` markers. The evaluator (PR-7f.2b.2) will skip any case with unfilled FILL_ markers.

---

## 7. Directory Update

```
eval/agentic/
├── schemas/
│   └── agentic_case_v2.schema.json
├── datasets/
│   ├── README_DATASETS.md
│   ├── agentic_v2.template.jsonl        # 60 placeholder (PR-7f.2a)
│   ├── agentic_v2.pilot20.jsonl         # 20 pilot cases (PR-7f.2b.1) ← NEW
│   └── agentic_v2.reviewed.jsonl        # (empty, domain expert fills)
├── scripts/
│   ├── validate_dataset.py              # Structural + cross-field
│   └── validate_gold_completeness.py    # Gold completeness + strict ← NEW
└── tests/
    ├── test_validate_dataset.py          # 13 pytest
    └── test_gold_completeness.py         # 12 pytest ← NEW
```

Total Agentic eval tests: **25 pytest 全绿** (structural 13 + completeness 12).

---

## 8. Constraints Maintained

- ✅ Runtime frozen — no Planner / Executor / Tool / Pipeline / StateMachine / Budget modified
- ✅ No fabricated gold evidence — all `evidenceId = FILL_FROM_SHA256`
- ✅ No production database calls — derived from existing `golden_v2_grounded` (offline JSONL)
- ✅ No automatic metrics — only dataset + validator tooling
- ✅ Dual-signoff enforced — `validator` rejects `annotator == reviewer`
- ✅ Evidence existence check — `validator` rejects non-hex `evidenceId` / `contentHash`
- ✅ Requirement coverage check — `validator` flags missing `required Requirement` coverage

---

## 9. Remaining Work (PR-7f.2b.2+)

1. Domain expert: fill 44 `FILL_` markers from real chunks → set `reviewStatus = reviewed`
2. Extend pilot: 20 → 60 reviewed gold cases (from remaining 40 template slots)
3. Implement evaluator: `run_agentic_eval.py` (Trajectory / Sufficiency / Plan metrics)
4. Run A0–A9 ablation on reviewed pilot set
5. Report results in `eval/agentic/reports/`

**Not done in this PR** — stops here. Does not enter evaluator implementation.
