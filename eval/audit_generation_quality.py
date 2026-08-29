#!/usr/bin/env python3
"""对已有生成报告重判正确性、忠实度和证据完整性，不重复调用业务 LLM/检索。"""
from __future__ import annotations

import argparse
import json
import random
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from eval.metrics import generation_metrics as gm
from eval.runner import judge_client


def bootstrap_ci(values: list[float], seed: int, iterations: int = 10_000) -> dict:
    if not values:
        return {"low": 0.0, "high": 0.0, "method": "query_bootstrap_percentile"}
    rng = random.Random(seed)
    n = len(values)
    means = sorted(sum(values[rng.randrange(n)] for _ in range(n)) / n for _ in range(iterations))
    return {
        "low": means[int(iterations * 0.025)],
        "high": means[min(iterations - 1, int(iterations * 0.975))],
        "method": "query_bootstrap_percentile",
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--judge-provider", type=int, default=2)
    p.add_argument("--start", type=int, default=1)
    p.add_argument("--limit", type=int, default=0)
    args = p.parse_args()

    report = json.loads(args.input.read_text(encoding="utf-8"))
    audits = report["per_query"]["generation_audit"]
    selected = audits[args.start - 1 :]
    if args.limit:
        selected = selected[: args.limit]
    judge = judge_client.make_judge_fn(provider_index=args.judge_provider, max_tokens=64)
    rows = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for pos, row in enumerate(selected, args.start):
        if row.get("state_hint") != "OK":
            rows.append({"question_id": row.get("__question_id", pos), "error": "non-OK generation"})
            continue
        context = "\n\n".join(
            (c.get("llm_context") or c.get("snippet") or "") for c in row.get("citations", [])
        ).strip()
        result = {
            "question_id": row.get("__question_id", pos),
            "question": row.get("question", ""),
            "answer_correctness": gm.answer_correctness(
                row.get("answer", ""),
                row.get("gold_answer", ""),
                judge,
                question=row.get("question", ""),
            ),
            "faithfulness": gm.faithfulness(row.get("answer", ""), context, judge),
            "evidence_completeness": gm.evidence_completeness(
                row.get("gold_answer", ""), context, judge
            ),
        }
        rows.append(result)
        # 每题落盘，网络中断时保留进度。
        args.output.write_text(
            json.dumps({"per_query": rows}, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(
            f"[{len(rows)}/{len(selected)}] q={result['question_id']} "
            f"c={result['answer_correctness']:.3f} f={result['faithfulness']:.3f} "
            f"e={result['evidence_completeness']:.3f}",
            flush=True,
        )

    clean = [r for r in rows if "error" not in r]
    metrics = {}
    cis = {}
    for index, key in enumerate(
        ("answer_correctness", "faithfulness", "evidence_completeness")
    ):
        vals = [r[key] for r in clean]
        metrics[key] = sum(vals) / len(vals) if vals else 0.0
        cis[key] = bootstrap_ci(vals, 20260825 + index)
    out = {
        "dataset_size": len(selected),
        "metrics": metrics,
        "confidence_intervals_95": cis,
        "judge_provider": args.judge_provider,
        "judge_model": judge_client.judge_model(args.judge_provider),
        "source_report": str(args.input),
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "per_query": rows,
    }
    args.output.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"metrics": metrics, "confidence_intervals_95": cis}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
