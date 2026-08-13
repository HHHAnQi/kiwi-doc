# Gold Annotation Guideline (PR-7f.2c.1 Task 4)

> Audience: domain annotators and reviewers filling
> `eval/agentic/datasets/agentic_v2.gold20.template.jsonl`.
>
> Goal: turn each unfilled template row into a `reviewed` case that passes
> `eval/agentic/scripts/validate_gold_dataset.py` with zero errors.
>
> This document does NOT fabricate evidence. It defines how a human looks at the real
> knowledge base and computes a reproducible hash.

---

## 0. Why we annotate (and what "good" looks like)

The Agentic RAG claim is "agent acquires the *right* evidence for multi-hop questions".
To test that claim we need hand-curated Ground Truth: for each question, the exact
document + chunk that a domain expert agrees is the source of truth.

A "good" annotation has three properties:

1. **Reproducible** — another annotator reading the same KB agrees on the same chunk.
2. **Cryptographically anchored** — the `contentHash` binds the annotation to a specific
   version of the chunk text, so a future corpus change will be detected.
3. **Non-leaking** — the gold `referenceAnswer` does NOT appear verbatim inside the
   cited chunk text (otherwise faithfulness metrics trivially score 1 — see
   `gold_dataset_audit.md §6.1`).

---

## 1. Prerequisites

Before you start annotating, confirm you have:

- ✅ Read-write access to the production-like knowledge base (MySQL `chunks` table +
  Milvus collection). Chunk text must be reachable by `(tenant_id, document_id, chunk_id)`.
- ✅ `tenant_id` checked once at session start (usually `"tA"` or `"default"` for the
  eval corpus — your reviewer will confirm).
- ✅ Python 3.10+ with `hashlib` and `json` (stdlib only; no extra packages).
- ✅ Two distinct accounts: one for **annotator** (you), one for **reviewer** (someone
  else). The validator rejects self-review (`annotator != reviewer`).
- ✅ A copy of `agentic_v2.gold20.template.jsonl`. Each line is one case.

---

## 2. Reading a knowledge-base chunk

The template pre-fills the pointer (`documentId`, `chunkId`) — these come from the
traceable classic-RAG gold (`eval/golden/golden_v2_grounded.jsonl`) and point to a
real chunk in the corpus. Your job is to **load that chunk's actual text**.

### 2.1 How to load (example, adjust to your runtime)

If you have DB read access:
```sql
SELECT document_id, chunk_id, document_version, content
FROM chunks
WHERE tenant_id = 'tA' AND document_id = 16 AND chunk_id = 697;
```

If only Milvus is available, query by primary key:
```python
from pymilvus import Collection
col = Collection("ragdoc_chunks")
col.load()
res = col.query(f'document_id == 16 and chunk_id == 697', output_fields=["content", "document_version"])
```

If neither is reachable from your workstation, request a chunk dump from the
platform on-call. Do NOT guess the chunk content.

### 2.2 What to record as the snippet

Set `gold.evidence[].contentSnippet` to the **first 200-400 characters of the actual
chunk content** (with personally-identifiable info redacted if any). Keep
formatting/whitespace intact so the hash matches what the runtime stores.

> ⚠️ **Leakage rule** (critical): if the chunk's first 200 chars happen to equal
> the answer sentence you plan to write in `referenceAnswer`, you must either
> (a) choose a different answer phrasing, or (b) take the snippet from a
> different region of the chunk (e.g. lines 5-12 instead of lines 1-4).
> `validate_gold_dataset.py` will reject `contentSnippet == referenceAnswer`.

---

## 3. Selecting evidence that supports the answer

This is the heart of annotation. You are answering: *"Which chunk(s) must the agent
retrieve for this question to be answerable correctly?"*

### 3.1 Selection principles

| Principle | Why it matters |
|---|---|
| **Specificity** — pick the *minimal* chunk set that answers the question | Agentic strategy is "specific retrieval over bulk retrieval"; rewarding minimal correct sets lets us measure efficiency |
| **Necessity** — every cited chunk must contain information the answer depends on | Padding inflates recall denominator; punish by annotation |
| **Multi-source** — for multi-hop (`replan_success`, `semantic_metadata_combo`) cases, evidence should span ≥2 documents if the question genuinely requires it | This is the Agentic differentiator; single-doc "multi-hop" is fake multi-hop |
| **No answer copy** — do not paste the question's expected answer text into any field except `referenceAnswer` | Avoids self-fulfilling annotation |

### 3.2 Per-slice selection rules

| Slice | Evidence count | Multi-doc? | Notes |
|---|---|---|---|
| `initial_sufficient` | 1 | no | Single chunk fully answers — the baseline easy case |
| `document_fetch_needed` | 1 | no | The chunk is reachable via metadata-search first, not semantic-search — flag in `rationale` |
| `semantic_metadata_combo` | 1–2 | optional | Version-bound question; `documentVersion` MUST be set on the evidence |
| `replan_success` | 2 | yes (≥2 docs preferred) | Initial plan finds 1 chunk; the second is reachable only after replan |
| `replan_still_insufficient` | 0 → REFUSED | n/a | KB genuinely lacks the info; `answerable=false`; finalStatus=REFUSED_NO_EVIDENCE |
| `no_answer_refuse` | 0 | n/a | KB lacks info; `answerable=false`; no evidence |
| `permission_denied` | 0 | n/a | Answer exists in KB but agent should refuse for tenant permission reasons (mock only) |
| `evidence_conflict` | 2+ | yes | Two chunks give contradictory answers; agent must detect conflict and set finalStatus=REFUSED_CONFLICT |
| `budget_timeout_edge` | ≥1 | optional | Designed to hit max_steps / max_tool_calls; cheaper to annotate after the engine is live — defer if unsure |

### 3.3 Multi-hop case decomposition

For any case where the question truly requires joining information across chunks
(e.g. "Sentinel vs Hystrix 在熔断策略上的差异" needs reading both Sentinel and Hystrix
docs), do this:

1. In `requirements[]`, **split into ≥2 Reqs** — `REQ-1`, `REQ-2` etc. Each Req
   should be answerable from a single chunk.
2. In `gold.evidence[]`, list one entry per Req.
3. In each evidence entry, set `bindsToRequirementIds: ["REQ-1"]` (or whichever it
   satisfies).
4. In `gold.goldCoverageByRequirement`, list `{"REQ-1": [<evidenceId>], "REQ-2": [<evidenceId>]}`.
5. Set `expected.replanExpected: true`.

---

## 4. Computing `contentHash`

`contentHash` is the **sha256 hex digest of the exact chunk content string** that the
runtime stores. It must be reproducible by anyone running the eval later.

### 4.1 The exact rule

```python
import hashlib
content_hash = hashlib.sha256(chunk_content_str.encode("utf-8")).hexdigest()
# 64 lowercase hex chars, e.g. "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
```

**Critical detail**: the `chunk_content_str` you hash must be the **same bytes the
runtime hashes**. Check the runtime's `Evidence.evidenceId` definition
(`docs/pr-7f.1-agentic-eval-design.md §1.4`):

```
evidenceId = sha256(tenantId|docId|chunkId|contentHash)[:12]
```

So `contentHash` is itself `sha256(content)`. Two layers.

### 4.2 Avoid these mistakes

| Mistake | Symptom |
|---|---|
| Hashing after stripping whitespace / newlines | Runtime hash differs by whitespace → recall=0 |
| Hashing JSON-escaped string (e.g. `\n` literal) | Mismatch |
| Hashing the snippet (first 200 chars) instead of full chunk | The runtime hashes the full chunk, snippet is human-readable only |
| Copying a hash from another case | Validator unique check will reject (planned) |

### 4.3 Verification step

After filing, re-load the chunk from the KB and re-hash. If your hash ≠ freshly
computed hash, your annotation is stale (someone changed the corpus).

A helper script is recommended — but per Task 5 we run no experiment tools here.
Place any helper you write under `eval/agentic/scripts/` (it can use `hashlib`).

---

## 5. Computing `evidenceId`

Once `contentHash` is in hand:

```python
import hashlib
def evidence_id(tenant_id: str, doc_id: int, chunk_id: int, content_hash: str) -> str:
    raw = f"{tenant_id}|{doc_id}|{chunk_id}|{content_hash}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]
```

Validator rule: `^[a-f0-9]{12}$` (12 lowercase hex).

Paste the result into `gold.evidence[].evidenceId`. Also paste it into
`gold.goldCoverageByRequirement[reqId]` so coverage binding is anchored.

---

## 6. Binding evidence to requirements

For each evidence entry, fill `bindsToRequirementIds`:

- Single-hop (`initial_sufficient`): `["REQ-1"]` (one entry binding to the single req).
- Multi-hop (`replan_success`): one evidence per req, each binding to its own req:
  - entry 0 → `["REQ-1"]`
  - entry 1 → `["REQ-2"]`
- Conflict (`evidence_conflict`): both entries bind to the same req, signaling
  contradictory coverage; finalStatus=REFUSED_CONFLICT.

### 6.1 The `rationale` field — quality bar

`rationale` is your 1-2 sentence explanation of **why** this chunk covers this Req.
**Do not copy the answer into the rationale.** Instead cite structural features:

✅ Good rationales:
- `"chunk 第12-18行列表中的第3项明确给出 sentinel.stable 版本号"`
- `"table on chunk line 7 maps 'broadcast.fail.percent' key to integer [0,100]"`
- `"section '迁移到 Dubbo3' 第3段列举了4个升级方向,REQ-1 的'升级方向'直接对应"`

❌ Bad rationales (will be rejected by leakage check):
- `"Dubbo3 在易用性... 等几大方向上升级"` ← bad: this IS the answer
- `"该 chunk 覆盖 REQ-1"` ← bad: vacuous
- `"根据该 chunk 可得到答案"` ← bad: vacuous

---

## 7. The review workflow

Two-account dual signoff. The validator hard-enforces `annotator != reviewer`.

### 7.1 Roles

| Role | Responsibility |
|---|---|
| **Annotator** | Selects chunk(s), computes hashes, writes `referenceAnswer`, fills all `gold.*` |
| **Reviewer** | Independently re-reads the chunk, verifies (a) hash correctness, (b) evidence really answers the question, (c) rationale cites real chunk lines, (d) no leakage |

### 7.2 Steps

1. Annotator fills every empty field in the template row.
2. Annotator sets `review.annotator = "<username>"`, leaves `review.reviewer = ""`.
3. Annotator runs:
   ```
   python3 eval/agentic/scripts/validate_gold_dataset.py \
       eval/agentic/datasets/agentic_v2.gold20.template.jsonl
   ```
   At this stage it will still FAIL on `empty-reviewer` and `empty-reviewedAt` — that
   is expected; the other checks must all pass.
4. Annotator hands the file (or a PR with their changes) to the reviewer.
5. Reviewer reads each case, optionally re-computes hashes from KB. If disagreeing,
   reviewer sends back to annotator. Otherwise:
   - reviewer sets `review.reviewer = "<reviewer_username>"`
   - sets `review.reviewedAt = "<ISO-8601 timestamp>"` (e.g. `2026-08-08T17:30:00+08:00`)
   - sets `review.reviewStatus = "reviewed"`
6. Reviewer re-runs the validator. **Exit code must be 0** for the row to ship.

### 7.3 Disagreement handling

If annotator and reviewer disagree on evidence selection more than 3 times per 20 cases,
**stop**. The annotation protocol or the question itself is ambiguous; reconvene and
write the disagreement down in `docs/evaluation/annotation_decisions.md`. This is
required to compute inter-annotator agreement (IAA) later.

---

## 8. Edge cases (FAQ)

**Q: The chunk text has been edited since the source gold was made (PR-1 era). What do I do?**

A: Use the *current* chunk text. Update `documentVersion` to whatever the DB shows now.
The hash will differ from any old hash — that is correct; we are anchoring to today's
corpus.

**Q: The question is genuinely unanswerable from the current KB. What slice?**

A: `no_answer_refuse` or `replan_still_insufficient`. Set `gold.answerable = false`,
leave `gold.evidence = []`, and `expected.finalStatus = REFUSED_NO_EVIDENCE`. The
validator still requires `referenceAnswer` non-empty — write a brief "无可引用证据,
应拒答" sentence; it is the **expected refusal text**, not a factual answer.

**Q: Can I copy `ground_truth_answer` from `golden_v2_grounded.jsonl` directly into
`referenceAnswer`?**

A: **No.** That is the source of the leakage documented in `gold_dataset_audit.md §6.1`.
Paraphrase the answer in your own words. The reviewer should reject verbatim copies.

**Q: Do I need to fill `expected.expectedStrategy`?**

A: Yes, per spec. For most `initial_sufficient` and `no_answer_refuse` cases, set
`"CLASSIC_RAG"` — both pipelines can handle them. For `replan_success` /
`replan_still_insufficient` / `semantic_metadata_combo`, set `"PLANNED_AGENT"` — these
are the cases that should differentiate the agent. The validator enforces enum values.

**Q: One of the pre-filled `(documentId, chunkId)` pointers in the template is wrong.**

A: Open an issue in `docs/evaluation/annotation_decisions.md` with the case ID and
correct pointer. Do not silently overwrite.

---

## 9. Final checklist (per case, before marking reviewed)

- [ ] `chunk` loaded from KB and content inspected
- [ ] `contentSnippet` is real chunk text, NOT the answer sentence
- [ ] `contentHash` = `sha256(full chunk content).hex()` — verified by re-load
- [ ] `evidenceId` = `sha256(tenant|docId|chunkId|contentHash)[:12]` — 12 lowercase hex
- [ ] `referenceAnswer` paraphrased (not copied from any source field)
- [ ] `rationale` cites chunk structural location (lines / section / table row)
- [ ] Multi-hop cases: ≥2 Reqs and ≥2 evidence entries bound correctly
- [ ] `expected.finalStatus` ∈ enum; `expected.expectedStrategy` ∈ enum
- [ ] `review.annotator != review.reviewer` (different people, different accounts)
- [ ] `review.reviewedAt` ISO-8601, set **by the reviewer at signoff time**
- [ ] `validate_gold_dataset.py` exits 0

---

## 10. Not fabricated

This guideline describes a process. It does not produce or report any
experimental metric. Hashes and answer texts are produced by the human annotator
on real corpus content; the guideline only specifies how to do so reproducibly.
