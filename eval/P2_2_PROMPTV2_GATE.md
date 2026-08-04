# Phase 2.B / P2-2 — PromptV2 Holdout Gate

## Goal

Validate `PromptV2` flag (`rag.prompt-v2=true`) regression-free against the certified baseline
before flipping it on in production.

## Baseline (certified, must not regress)

`eval/PHASE2_FINAL_BASELINE.md` / `eval/EVAL_BASELINE_CERT.md`:

| Metric | Baseline | V2 Gate (must beat) |
|---|---|---|
| faithfulness | 0.86 | ≥ **0.91** (+5pp) |
| context_precision | 0.67 | ≥ **0.70** (+3pp) |
| context_recall  | 0.83 | ≥ 0.83 (no regression) |
| answer_relevance | 0.69 | ≥ 0.69 (no regression) |

## Holdout set

Reuse `eval/_samples_80.jsonl` (the certified 80-question set, locked). Do **not** re-tune against this set — if so, must split into 60/20 train/holdout.

## Gate procedure

1. Confirm baseline is reproducible: run RAGAS on baseline prompt → metrics match `PHASE2_FINAL_BASELINE.md` ±0.02.
2. Flip `RAG_PROMPT_V2=true` (env var → `rag.prompt-v2`).
3. Rerun RAGAS: `python eval/ragas_eval.py --input eval/_samples_80.jsonl --output eval/p2_2_v2_results.jsonl`.
4. Diff vs baseline:
   - All 4 metrics ≥ gate threshold → merge & enable in production.
   - Any metric below → keep flag OFF, file ADR documenting regression.
5. If V2 fails G3 (LLM refusal pollution, ADR-0011 §8.2) more than baseline → immediately discard.

## Rollback

`PromptV2` reads `rag.prompt-v2` env. Ops flip-flops `RAG_PROMPT_V2=false` → revert to baseline within next pod restart (no migration).

## V2 prompt diff vs baseline (key changes)

- **Grounding rule**: forbidden categories explicit (`版本号 / 数值 / API 名 / 配置项 / 类名`) — baseline only mentioned 3.
- **Citation strict** (`promptV2Citation` ON): every non-stopword fact must carry `[n]`. Drives faithfulness up via answer length compression + explicit provenance.
- **Fallback trigger** tightened: 3 explicit predicates (different domain / pure noise / no keywords) — baseline's "完全无关" gives LLM too much room to refuse code-only / weak-match contexts.

## What V2 does **not** change

- Refusal detection (`isLlmRefusal` in `ChatService`) unchanged — still 30-char rule.
- Fall-through to `LLM_DEGRADED` for empty/refusal answers unchanged.
- `promptRelaxRefusal` (A1) independence — when both flags ON, V2 wins (stricter overrides lax).
