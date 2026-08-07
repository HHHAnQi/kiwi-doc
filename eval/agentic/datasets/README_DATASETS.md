# PR-7f.2a Gold Benchmark Dataset Template

> **ALL CASES ARE PLACEHOLDER TEMPLATES** — `review.reviewStatus = "candidate"`,
> `review.annotator = "TODO"`, `review.reviewer = "TODO"`, `review.reviewedAt = ""`.
>
> **NO gold evidence is fabricated.** Gold fields are empty-string or zero-length arrays.
> Domain expert must:
>   1. Fill `question`, `entities`, `requirements`
>   2. Annotate `gold.goldEvidence` with real chunk content from the production-like corpus
>   3. Fill `gold.goldAnswer`, `gold.goldCoverageByRequirement`
>   4. Set `review.reviewStatus = "reviewed"` (annotator ≠ reviewer, dual-signoff enforced by validator)
>
> **DO NOT** auto-generate fake evidence IDs; evidence IDs are sha256(tenantId|docId|chunkId|contentHash)
> computed at evaluation time from actual chunk content.
>
> Slice distribution (60 cases):
>   initial_sufficient: ............. 10  | document_fetch_needed: ...... 6
>   semantic_metadata_combo: ........ 8  | replan_success: .............. 8
>   replan_still_insufficient: ...... 6  | no_answer_refuse: ............ 8
>   permission_denied: .............. 4  | evidence_conflict: ........... 4
>   budget_timeout_edge: ............ 6
>   ────────────────────────────────────
>   Total: ................................ 60
