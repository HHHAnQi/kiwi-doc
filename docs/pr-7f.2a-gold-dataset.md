# PR-7f.2a: Agentic RAG Gold Benchmark Dataset

> Status: **Template + tooling complete** — 60 case templates created, all `reviewStatus=candidate`;
> domain expert annotates → `reviewStatus=reviewed` → validator enforces dual-signoff.
> **No fabricated gold evidence. No auto-generated metrics. No production code modified.**

---

## 1. Dataset Schema (v2)

File: `eval/agentic/schemas/agentic_case_v2.schema.json`

JSON Schema Draft-07, 60+ property validations. Key structure:

```
{
  schemaVersion:        "v2"
  caseId:               "amh-001"
  question:             "..."
  questionType:         MULTI_HOP | FACT | COMPARISON | ...
  intent:               (same enum)
  entities:             ["..."]
  filters:              {}

  requirements: [
    {
      requirementId:    "REQ-1"
      type:             FACT | ENTITY_ATTRIBUTE | RELATION | TEMPORAL | COMPARISON_SIDE | FOLLOW_UP_ENTITY
      required:         true|false
      description:      "..."
      targetEntities:   ["..."]
      expectedFilters:  {}
    }
  ]

  gold: {
    goldAnswer:               "..."
    goldEvidence: [
      {
        evidenceId:           sha256(tenantId|docId|chunkId|contentHash)[:12+]
        documentId:           1
        chunkId:              10
        documentVersion:      "v1"
        contentHash:          sha256_of_chunk_content
        contentSnippet:       "≤300 chars (truncated, redacted)"
        bindsToRequirementIds: ["REQ-1"]
        rationale:            "≤200 chars: why this evidence satisfies the req"
        reviewer:             "person B"
        reviewedAt:           "2026-08-05T..."
      }
    ]
    goldDocumentIds:            [1, 2]
    goldCoverageByRequirement:  {"REQ-1": ["evidenceId1"], ...}
    answerable:                 true|false
  }

  planConstraints: {
    acceptableInitialPlans:   [{toolSequence: [...], coveredReqIds: [...]}]
    acceptableReplanPlans:    [{toolSequence: [...], coveredReqIds: [...]}]
    forbiddenToolSignatures:  [...]
  }

  expected: {
    expectedFinalStatus:  ANSWERED | REFUSED_NO_EVIDENCE | REFUSED_CONFLICT | ...
    replanExpected:       true|false
    maxSteps:             1..5
    maxToolCalls:         1..10
  }

  slice:        initial_sufficient | document_fetch_needed | semantic_metadata_combo |
                replan_success | replan_still_insufficient | no_answer_refuse |
                permission_denied | evidence_conflict | budget_timeout_edge

  review: {
    reviewStatus: candidate | reviewed | rejected
    annotator:    "person A (gold author)"
    reviewer:     "person B (verifier — must differ from annotator)"
    reviewedAt:   "ISO-8601 UTC"
  }

  notes: "..."
}
```

## 2. Gold Evidence Annotation Format

每条 Gold Evidence 是 chunk-level **精确锚定**:

| Field | Required | Description |
|---|---|---|
| `evidenceId` | ✓ | sha256(tenantId\|docId\|chunkId\|contentHash) prefix ≥12 hex chars; 与运行时 `Evidence.evidenceId` 一致 |
| `documentId` | ✓ | 文档 ID (integer ≥1) |
| `chunkId` | ✓ | Chunk ID (integer ≥0) |
| `documentVersion` | ✓ | 文档版本 ("v1", "v2", ...) |
| `contentHash` | ✓ | sha256 hex of chunk content (≥12 chars) |
| `contentSnippet` | | ≤300 chars (截断 + 脱敏) |
| `bindsToRequirementIds` | ✓ | 该 Evidence 覆盖的 Requirement IDs (≥1) |
| `rationale` | | ≤200 chars: 为什么这条 evidence 满足相关 Requirement (reviewer 填写) |
| `reviewer` | ✓ | 审核人 ≠ gold annotator |
| `reviewedAt` | ✓ | ISO-8601 UTC |

### 不允许的 annotation 方式

1. **笼统 Q→A Gold** — 禁止仅给出 `goldAnswer` + `goldDocumentIds` 而不拆 chunk
2. **Question paraphrase 作为 rationale** — reviewer 必须写出"第 12-34 行包含确切的属性值"
3. **无 reviewer 的 gold** — 必须双签 (`annotator ≠ reviewer`)
4. **Fabricated evidence hash** — `evidenceId` 必须从真实 chunk content sha256 计算; 不允许手填

## 3. 60 Case Template

File: `eval/agentic/datasets/agentic_v2.template.jsonl`

60 cases, all `reviewStatus=candidate`, all fields empty/placeholder:

| Slice | Count | Expected Final Status |
|---|---:|---|
| initial_sufficient | 10 | ANSWERED |
| document_fetch_needed | 6 | ANSWERED |
| semantic_metadata_combo | 8 | ANSWERED |
| replan_success | 8 | ANSWERED |
| replan_still_insufficient | 6 | REFUSED_NO_EVIDENCE |
| no_answer_refuse | 8 | REFUSED_NO_EVIDENCE |
| permission_denied | 4 | REFUSED_PERMISSION |
| evidence_conflict | 4 | REFUSED_CONFLICT |
| budget_timeout_edge | 6 | TIMED_OUT |
| **Total** | **60** | |

每条 case 的关键字段 (`question`, `entities`, `requirements`, `gold`) 为空;
domain expert 必须从 Production-like corpus 填入真实数据.

## 4. Reviewer Workflow

```
┌──────────────────────────────────────────────────────────────────────┐
│  STEP 1: Annotator (person A)                                        │
│  - 选择一个 case (reviewStatus=candidate)                            │
│  - 从 production-like corpus 查找真实 chunk                          │
│  - 填入 question + requirements + gold evidence + goldAnswer         │
│  - 填入 planConstraints (acceptable plans / forbidden sigs)          │
│  - 填入 expected (finalStatus / replanExpected / budget)             │
│  - 把 review.annotator 设为自己的 id                                 │
│  - 保留 review.reviewStatus = "candidate"                            │
│                                                                      │
│  STEP 2: Reviewer (person B, ≠ annotator)                           │
│  - 读 annotator 的 gold                                              │
│  - 独立验证: chunk 确实存在 + 内容匹配 + rationale 合理              │
│  - 如果同意: 把 review.reviewer 设为 B + reviewedAt + reviewStatus="reviewed" │
│  - 如果不同意: reviewStatus="rejected" + notes 写理由                │
│  - **禁止 annotator == reviewer** (validator 把关)                   │
│                                                                      │
│  STEP 3: Validator                                                   │
│  python3 eval/agentic/scripts/validate_dataset.py                    │
│    eval/agentic/datasets/agentic_v2.reviewed.jsonl                   │
│    --require-reviewed                                                │
│  - 拒绝 candidate / rejected cases                                   │
│  - 拒绝单签 / 未填 reviewedAt                                         │
│  - 拒绝 placeholder / dummy evidence                                  │
│  - 拒绝 slice↔status 冲突 / answerable↔status 冲突                   │
│  - 拒绝 goldEvidence.binsTo unknown requirementId                    │
│                                                                      │
│  STEP 4: Eval-ready                                                  │
│  - 仅 reviewStatus=reviewed and validator 通过的 case → \             │
│    进入 `agentic_v2.reviewed.jsonl` 供 PR-7f.2b+ 评测使用             │
│  - 任何 rejected case → 标记 + negative examples (用于 evaluator 分析)│
└──────────────────────────────────────────────────────────────────────┘
```

## 5. Dataset Validator

File: `eval/agentic/scripts/validate_dataset.py`

Cross-field checks (beyond JSON Schema):

1. ✅ caseId 全局唯一
2. ✅ requirementId 案例内唯一 + pattern `^REQ-[0-9]+$`
3. ✅ goldEvidence.binsToRequirementIds ⊆ requirements
4. ✅ goldCoverageByRequirement keys ⊆ requirements
5. ✅ answerable ↔ expectedFinalStatus 一致
6. ✅ replanExpected ↔ acceptableReplanPlans 一致 (仅 reviewed case 强制)
7. ✅ slice → expectedFinalStatus 映射正确
8. ✅ maxSteps 1..5 / maxToolCalls 1..10
9. ✅ dual-signoff: annotator ≠ reviewer when reviewStatus=reviewed
10. ✅ placeholder/dummy 检测 (evidenceId / contentHash / rationale)
11. ✅ `--require-reviewed` 模式拒绝 candidate
12. ✅ candidate case 模板 (empty replan plans) 通过 (template phase)

Tests: `eval/agentic/tests/test_validate_dataset.py` — **13 pytest 全绿**

```
test_valid_case_passes             ✅
test_dup_case_id_fails             ✅
test_dup_requirement_id_fails      ✅
test_gold_evidence_binds_unknown_req_fails  ✅
test_answerable_status_conflict_fails       ✅
test_slice_status_conflict_fails   ✅
test_dual_signoff_annotator_eq_reviewer_fails  ✅
test_reviewed_without_reviewedAt_fails       ✅
test_placeholder_evidence_fails    ✅
test_require_reviewed_mode_rejects_candidate  ✅
test_reviewed_replan_expected_empty_plans_fails  ✅
test_candidate_replan_expected_empty_plans_ok    ✅
test_template_60_passes            ✅ (60 case template validation)
```

## 6. 目录结构

```
eval/agentic/
├── README.md (留 PR-7f.2b 写)
├── schemas/
│   └── agentic_case_v2.schema.json       # JSON Schema Draft-07
├── datasets/
│   ├── README_DATASETS.md                # annotation guide
│   ├── agentic_v2.template.jsonl         # 60 placeholder cases (candidate)
│   └── agentic_v2.reviewed.jsonl         # 空 — 待 domain expert 填
├── scripts/
│   └── validate_dataset.py               # cross-field validator
└── tests/
    └── test_validate_dataset.py          # 13 pytest
```

## 7. 约束

- **不修改运行时代码** — Runtime frozen
- **不生成伪造 gold evidence** — evidenceId 必须 sha256 of real chunk
- **不自动生成最终指标** — 仅 dataset schema + template + validator; 指标 in PR-7f.2b
- **不报告 NOT_EXECUTED 指标** — 等数据填充后 PR-7f.2b 跑
- **reviewed dataset 不允许 skip annotator ≠ reviewer check**

## 8. 完成判定

| # | 检查 | 状态 |
|---|---|---|
| 1 | Dataset JSON schema (v2) 已定义 | ✅ |
| 2 | Gold Evidence 标注格式定义 | ✅ |
| 3 | 60 case dataset template 已建 | ✅ (placeholder, all candidate) |
| 4 | Reviewer workflow 文档化 | ✅ |
| 5 | Dataset validator 已实现 | ✅ (cross-field + dual-signoff) |
| 6 | 13 pytest 全绿 (含 template 自校验) | ✅ |
| 7 | 不修改生产代码 | ✅ |
| 8 | 不伪造 evidence | ✅ (template 全空, 留 domain expert) |
| 9 | 不自动生成指标 | ✅ (仅 schema/validator, 无 evaluator) |

**PR-7f.2a: 已完成** (dataset framework complete; domain expert 待填 gold).
