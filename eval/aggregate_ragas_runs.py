#!/usr/bin/env python3
"""聚合至少3次同配置RAGAS运行并生成可用于G1的正式v2基线。"""

from __future__ import annotations

import argparse
import json
import random
import statistics
from datetime import datetime, timezone
from pathlib import Path


METRICS = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
DEFAULT_OUTPUT = Path(__file__).resolve().parent / "ragas_baseline.json"


def _bootstrap(values: list[float], iterations: int = 5000, seed: int = 20260824) -> dict:
    rng = random.Random(seed)
    n = len(values)
    means = sorted(
        sum(values[rng.randrange(n)] for _ in range(n)) / n
        for _ in range(iterations)
    )
    return {
        "low": means[int(0.025 * iterations)],
        "high": means[min(iterations - 1, int(0.975 * iterations))],
        "n_runs": n,
        "iterations": iterations,
    }


def aggregate_metadata(runs: list[dict]) -> dict:
    if len(runs) < 3:
        raise ValueError("正式基线至少需要3轮运行")
    for run in runs:
        if run.get("schema_version") != 2:
            raise ValueError("所有运行都必须是v2 metadata")
        sample_count = int(run.get("sample_count", 0))
        intervals = run.get("confidence_intervals_95") or {}
        for metric in METRICS:
            valid_n = (intervals.get(metric) or {}).get("n")
            if valid_n != sample_count:
                raise ValueError(
                    f"运行 {run.get('experiment_id')} 的 {metric} 有效判分数不完整: "
                    f"{valid_n} != {sample_count}"
                )

    reference = runs[0]
    for field in ("questions_sha256", "sample_count", "judge", "public_config"):
        if any(run.get(field) != reference.get(field) for run in runs[1:]):
            raise ValueError(f"运行之间的 {field} 不一致，禁止聚合")

    metric_values = {
        metric: [float(run["scores"][metric]) for run in runs]
        for metric in METRICS
    }
    baseline = {
        "schema_version": 2,
        "baseline_type": "multi_run",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "run_count": len(runs),
        "source_experiment_ids": [run.get("experiment_id") for run in runs],
        "questions_file": reference.get("questions_file"),
        "questions_sha256": reference["questions_sha256"],
        "sample_count": reference["sample_count"],
        "judge": reference["judge"],
        "public_config": reference["public_config"],
        "scores": {
            metric: statistics.mean(values)
            for metric, values in metric_values.items()
        },
        "run_stdev": {
            metric: statistics.stdev(values)
            for metric, values in metric_values.items()
        },
        "run_confidence_intervals_95": {
            metric: _bootstrap(values, seed=20260824 + index)
            for index, (metric, values) in enumerate(metric_values.items())
        },
    }
    if all(run.get("retrieval_scores") for run in runs):
        retrieval_metrics = sorted(set.intersection(*(
            set(run["retrieval_scores"]) for run in runs
        )))
        retrieval_values = {
            metric: [float(run["retrieval_scores"][metric]) for run in runs]
            for metric in retrieval_metrics
        }
        baseline["retrieval_scores"] = {
            metric: statistics.mean(values)
            for metric, values in retrieval_values.items()
        }
        baseline["retrieval_run_stdev"] = {
            metric: statistics.stdev(values)
            for metric, values in retrieval_values.items()
        }
    return baseline


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("metadata", nargs="+", type=Path, help="至少3个 runs/*/metadata.json")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    runs = [json.loads(path.read_text(encoding="utf-8")) for path in args.metadata]
    baseline = aggregate_metadata(runs)
    args.output.write_text(json.dumps(baseline, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已聚合 {baseline['run_count']} 轮并冻结基线: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
