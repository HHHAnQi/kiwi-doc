#!/usr/bin/env python3
"""为 run_eval 生成报告补充逐题 bootstrap 95% CI 与门禁结论。"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


def bootstrap(values: list[float], seed: int, iterations: int = 10_000) -> dict:
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
    args = p.parse_args()
    report = json.loads(args.input.read_text(encoding="utf-8"))
    rows = [r for r in report["per_query"]["generation"] if "error" not in r]
    keys = [
        "answer_correctness",
        "faithfulness",
        "evidence_completeness",
        "citation_hit_rate",
        "citation_recall",
        "citation_accuracy",
    ]
    cis = {
        key: bootstrap([float(row[key]) for row in rows], 20260825 + i)
        for i, key in enumerate(keys)
    }
    thresholds = {
        "answer_correctness": 0.80,
        "faithfulness": 0.95,
        "evidence_completeness": 0.90,
        "citation_hit_rate": 0.99,
        "citation_recall": 0.95,
    }
    metrics = report["metrics"]["generation"]
    checks = {key: metrics[key] >= value for key, value in thresholds.items()}
    checks["generation_failures_zero"] = report.get("generation_failures", 0) == 0
    out = {
        "dataset_size": report["dataset_size"],
        "answered": len(rows),
        "generation_failures": report.get("generation_failures", 0),
        "metrics": metrics,
        "confidence_intervals_95": cis,
        "gate_thresholds": thresholds,
        "gate_checks": checks,
        "gate_pass": all(checks.values()),
        "source_report": str(args.input),
    }
    args.output.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
