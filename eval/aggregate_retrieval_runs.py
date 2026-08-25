#!/usr/bin/env python3
"""聚合同配置检索运行，区分运行稳定性与评测集抽样不确定性。"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
from datetime import datetime, timezone
from pathlib import Path


def _percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _bootstrap_ci(values: list[float], *, seed: int, iterations: int = 10_000) -> dict:
    rng = random.Random(seed)
    n = len(values)
    means = sorted(
        statistics.fmean(values[rng.randrange(n)] for _ in range(n))
        for _ in range(iterations)
    )
    return {
        "method": "query_bootstrap_percentile",
        "low": _percentile(means, 0.025),
        "high": _percentile(means, 0.975),
        "sample_count": n,
        "iterations": iterations,
    }


def _wilson_ci(successes: int, total: int, z: float = 1.959963984540054) -> dict:
    proportion = successes / total
    denominator = 1 + z * z / total
    centre = (proportion + z * z / (2 * total)) / denominator
    half_width = z * math.sqrt(
        proportion * (1 - proportion) / total + z * z / (4 * total * total)
    ) / denominator
    return {
        "method": "wilson_score",
        "low": max(0.0, centre - half_width),
        "high": min(1.0, centre + half_width),
        "successes": successes,
        "sample_count": total,
    }


def aggregate_runs(runs: list[dict], source_files: list[str]) -> dict:
    if len(runs) < 3:
        raise ValueError("正式检索基线至少需要 3 轮运行")
    reference = runs[0]
    required_same = ("dataset_size", "dataset_sha256", "k", "modes", "model_snapshot")
    for field in required_same:
        if any(run.get(field) != reference.get(field) for run in runs[1:]):
            raise ValueError(f"运行之间的 {field} 不一致，禁止聚合")
    if len(reference.get("modes") or []) != 1:
        raise ValueError("正式基线聚合要求每份报告只包含一种 mode")
    mode = reference["modes"][0]
    sample_count = int(reference["dataset_size"])
    metric_names = sorted(reference[mode])

    for index, run in enumerate(runs, 1):
        rows = run.get("per_query", {}).get(mode, [])
        if len(rows) != sample_count:
            raise ValueError(f"第 {index} 轮逐题记录数量与 dataset_size 不一致")
        states = run.get("diagnostics", {}).get(mode, {}).get("rerank_states", {})
        if run.get("model_snapshot", {}).get(mode, {}).get("rerank_enabled"):
            if states != {"applied": sample_count}:
                raise ValueError(f"第 {index} 轮 rerank 未 100% applied: {states}")

    run_values = {
        metric: [float(run[mode][metric]) for run in runs]
        for metric in metric_names
    }
    # 三轮同题运行不是三个独立样本。抽样区间只使用首轮逐题值，避免伪重复。
    reference_rows = reference["per_query"][mode]
    query_values = {
        metric: [float(row["metrics"][metric]) for row in reference_rows]
        for metric in metric_names
    }
    sampling_ci = {}
    for index, metric in enumerate(metric_names):
        values = query_values[metric]
        binary = all(value in (0.0, 1.0) for value in values)
        sampling_ci[metric] = (
            _wilson_ci(sum(value == 1.0 for value in values), len(values))
            if binary
            else _bootstrap_ci(values, seed=20260825 + index)
        )

    latency_means = [float(run["diagnostics"][mode]["latency_ms"]["mean"]) for run in runs]
    latency_p95s = [float(run["diagnostics"][mode]["latency_ms"]["p95"]) for run in runs]
    return {
        "schema_version": 1,
        "baseline_type": "retrieval_multi_run",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "run_count": len(runs),
        "source_files": source_files,
        "dataset_size": sample_count,
        "dataset_sha256": reference["dataset_sha256"],
        "k": reference["k"],
        "mode": mode,
        "model_snapshot": reference["model_snapshot"][mode],
        "scores": {metric: statistics.fmean(values) for metric, values in run_values.items()},
        "run_stdev": {metric: statistics.stdev(values) for metric, values in run_values.items()},
        "sampling_confidence_intervals_95": sampling_ci,
        "latency_ms": {
            "mean_of_run_means": statistics.fmean(latency_means),
            "run_mean_stdev": statistics.stdev(latency_means),
            "mean_of_run_p95": statistics.fmean(latency_p95s),
            "run_p95_stdev": statistics.stdev(latency_p95s),
        },
        "rerank_applied": {
            "count": sample_count * len(runs),
            "expected": sample_count * len(runs),
            "rate": 1.0,
        },
        "interpretation": {
            "run_stdev": "同一冻结集重复运行的系统稳定性；不可替代样本置信区间",
            "sampling_confidence_intervals_95": "仅按一轮的逐题结果估计，避免将重复运行伪装成独立样本",
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path, help="至少 3 份同配置检索报告")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    runs = [json.loads(path.read_text(encoding="utf-8")) for path in args.reports]
    baseline = aggregate_runs(runs, [str(path) for path in args.reports])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(baseline, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已聚合 {baseline['run_count']} 轮检索基线: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
