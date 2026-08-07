# PR-7f.2b.3: Real Evaluation Harness Loop + Gold Freeze Gate

> Status: **Closed-loop scaffold complete; GOLD IS BLOCKED**. Real-runner REST
> adapter is wired and integration-tested; pilot20 Gold freeze is mechanically
> blocked because FILL_* markers require human chunk-text labelling we cannot
> generate. No runtime code modified.

---

## 0. Goal

Close the loop opened by PR-7f.2b.2: turn the stub harness into a real
execution path that can drive `PlannedAgentPipeline` from outside the JVM
without editing Java, and gate the gold dataset so it cannot be used live
until humans finish labelling.

Two parts per spec:

- **PR-7f.2b.3a** — validator test semantics + pilot20 gold freeze assessment
- **PR-7f.2b.3b** — real Runner Adapter (REST) + integration tests

---

## 1. Files

| Path | Section | Status |
|---|---|---|
| `eval/agentic/tests/test_validate_dataset.py` | 3a-1 | Replaced broken `test_placeholder_evidence_fails` with 3 semantic cases (candidate Allows, FILL_\* always rejected, reviewed Rejects) |
| `eval/agentic/scripts/gold_freeze_check.py` | 3a-3 | New — emits BLOCKED report; exit 1 if any blocker |
| `eval/agentic/scripts/agentic_runner.py` | 3b | Rewritten with `--mode live` REST adapter + strategy-trace gate |
| `eval/agentic/scripts/hybrid_runner.py` | 3b | Same: `--mode live` (mode=RAG forces CLASSIC_RAG) |
| `eval/agentic/tests/test_runner_adapter.py` | 3b | New — 13 integration tests; no live LLM, no real boot |
| `docs/pr-7f.2b.3.md` | (this file) | New |

Runtime touched: none (`platform-bootstrap`, `platform-common` unchanged).

---

## 2. PR-7f.2b.3a — Validator Semantics + Gold Freeze

### 2.1 Test semantics fixed

`eval/agentic/scripts/validate_dataset.py:99-116` enforces intentionally
asymmetric rules (committed in PR-7f.2a but never tested correctly):

| case state | generic placeholder (`TODO`/`TBD`/empty) | `FILL_*` marker |
|---|---|---|
| `candidate` | **allowed** (templates are stubs) | **rejected** (must-fill-before-review flag) |
| `reviewed`  | **rejected** (humans signed off) | (subset of rejected above) |

`test_validate_dataset.py` previously had `test_placeholder_evidence_fails`
which expected a candidate case to be rejected for `"TODO"` — contradicting
the validator's intentional behaviour. Replaced with three pinned tests:

- `test_candidate_placeholder_evidence_ok` — `TODO` on candidate → OK
- `test_candidate_fill_marker_still_rejected` — `FILL_*` on candidate → reject
- `test_reviewed_placeholder_evidence_fails` — `TODO` on reviewed → reject

15 validator tests now pass (was 14/15 with 1 broken).

### 2.2 Pilot20 gold freeze assessment — BLOCKED

Per spec: *如果缺人工标注数据，不自动生成，明确阻塞。*

`gold_freeze_check.py` is the explicit block. It refuses to fabricate
labels and reports machine-checked blockers only.

Against `agentic_v2.pilot20.jsonl`:

```
BLOCKED  ✗  eval/agentic/datasets/agentic_v2.pilot20.jsonl
  cases:              20
  reviewed:           0
  candidate:          20
  FILL_* markers:     36
  reviewedAt missing: 12
  blockers:
    - BLOCKED: 20/20 cases still reviewStatus=candidate — human dual-signoff
    - BLOCKED: 36 FILL_* markers remain — domain expert must compute
               evidenceId/contentHash from real chunk text
    - BLOCKED: 12 goldEvidence entries missing reviewedAt
    - BLOCKED: validate_dataset --require-reviewed failed with 44 errors
    - BLOCKED: validate_gold_completeness --strict failed with 44 errors
```

Exit code: **1** (BLOCKED). Production CI gate slot.

**What a human must do to lift the block (per case):**
1. Read the real chunk at `gold.goldEvidence[i].{documentId,chunkId}`
2. Compute `evidenceId = sha256(tenantId|docId|chunkId|contentHash)` and put it in the field
3. Compute `contentHash = sha256(chunk_text)` and put it in the field
4. Replace any remaining `FILL_VERSION` with the real doc version
5. Write `rationale` (why the chunk covers the Requirement)
6. Set `review.reviewStatus=reviewed`, `annotator`/`reviewer` (two different people), `reviewedAt=ISO8601`

After all 20 cases pass strict validators, `gold_freeze_check` exits 0 (FROZEN).

---

## 3. PR-7f.2b.3b — Runner Adapter

### 3.1 Operating modes

Both `agentic_runner.py` and `hybrid_runner.py` support `--mode {stub,live}`.
Default is `stub` (offline-safe) so existing behaviour is unchanged.

### 3.2 Live mode wiring

| Runtime surface | Used by adapter |
|---|---|
| Entry | `POST {base}/api/v1/chat`, body `{"query":..., "mode":"AUTO"\|"RAG", ...}` |
| Sync response | `ChatResponse` JSON (answer, citations, usage, etc.) |
| SSE endpoint | `POST /api/v1/chat/sse` — not used by adapter yet (sync is enough) |
| App port | default 8080 (override `--base-url`) |
| PLANNED_AGENT gate | `rag.router.enabled=true` + `rag.agent.planner.enabled=true` + Router decides MULTI_HOP — but `plannedPipelineEnabled` is hard-coded `false` in `ExecutionStrategyResolver.java:36` |

### 3.3 The PLANNED_AGENT runtime gap

The Java survey found that `ExecutionStrategyResolver` accepts a 2-arg
constructor for tests but the production `@Component` uses the 1-arg
constructor which hard-codes `plannedPipelineEnabled=false`. There is no
`@ConfigurationProperties` binding for this field anywhere in main source.

Per the runtime-frozen constraint of this PR, we **do not edit Java**. The
adapter therefore detects the gap rather than fabricating agent metrics:

| Adapter observation | Result |
|---|---|
| HTTP unreachable / timeout | `NOT_EXECUTED: RUNTIME_UNREACHABLE` |
| HTTP non-2xx | `NOT_EXECUTED: HTTP {code} from runtime` |
| HTTP 200 but no `pipelineType` in response envelope | `NOT_EXECUTED: RUNTIME_NO_STRATEGY_TRACE` |
| HTTP 200 + strategy != `PLANNED_AGENT` | `NOT_EXECUTED: RUNTIME_NOT_PLANNED_AGENT (strategy=...)` |
| HTTP 200 + strategy = `PLANNED_AGENT` | `executed=true` (success) — once runtime wiring is opened |

Field mapping on success: `answer → answerText / finalStatus=ANSWERED`,
`citations[*].evidenceId → citedEvidenceIds`, `usage.{input,output}Tokens →
tokenUsage`, `replanCount` / `sufficiencyStatus` propagated when present.

### 3.4 NOT_EXECUTED preserved

The runner never silently flips `executed=true`. Both stub and live modes
emit `executed=false` + zero/empty fields whenever they did not actually
reach PLANNED_AGENT. The metrics layer (PR-7f.2b.2) returns `None` for any
`executed=false` record, so no fabricated KPI can flow downstream.

### 3.5 Integration tests — `test_runner_adapter.py` (13 tests)

- `_map_chat_response_to_result` for: success, no-strategy-trace, wrong
  strategy, non-2xx — 4 tests
- `_extract_strategy` nested/flat/missing — covered above
- Hybrid `_map_hybrid_response` success + non-200 — 2 tests
- `invoke_live` against an unreachable port returns NOT_EXECUTED — 2 tests
- Subprocess CLI stub-mode writes valid JSONL — 2 tests
- `gold_freeze_check.assess` on real pilot20 (BLOCKED) and on a fully
  reviewed synthetic case (FROZEN) — 2 tests

No live LLM, no real Spring Boot boot. All deterministic.

---

## 4. Verified

```
$ python3 -m pytest eval/agentic/tests/ -q
74 passed in 0.29s
```

Breakdown:

- 10 schema (test_metrics + validator schema)
- 16 validator-semantic (15 in test_validate_dataset + 1 from test_metrics)
- 12 gold completeness (test_gold_completeness, unchanged)
- 22 metric (test_metrics, unchanged aside from 2 signature updates)
- 13 runner adapter (test_runner_adapter, new)

```
$ python3 eval/agentic/scripts/gold_freeze_check.py eval/agentic/datasets/agentic_v2.pilot20.jsonl
BLOCKED  ✗ ...
$ echo $?
1
```

```
$ python3 eval/agentic/scripts/agentic_runner.py --mode live
... wrote 20 records (0 executed, 20 NOT_EXECUTED) ...
# records show `RUNTIME_UNREACHABLE` / non-PLANNED_AGENT — never fabricated
```

---

## 5. Pre-existing failures, none

After PR-7f.2b.3a-1 the previously broken
`test_validate_dataset.py::test_placeholder_evidence_fails` is replaced and
the whole eval suite is green.

---

## 6. Completion judgement

PR-7f.2b.3 is **complete** when:

- [x] Validator test semantics corrected (candidate Accepts placeholders, reviewed Rejects)
- [x] Gold freeze assessment performed and **explicitly blocked** (no fabrication)
- [x] Gold freeze gate script (`gold_freeze_check.py`) committed with exit-code contract
- [x] Runner adapter supports live mode and **detects** the Java-side
      PLANNED_AGENT gate blocker without editing Java
- [x] Integration tests cover response mapping, unreachable runtime,
      freeze gate, fallback paths
- [x] 74 pytest pass; no live LLM; no production code modified
- [x] Documentation (this file)

**Deferred** (do NOT enter in this PR per spec):

- [ ] PR-7f.2c experiments
- [ ] Lift the Java-side `plannedPipelineEnabled` blocker (separate runtime PR)
- [ ] Domain-expert completion of 36 `FILL_*` markers in pilot20
- [ ] First real executed=true A0-vs-A5 run
