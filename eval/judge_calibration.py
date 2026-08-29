#!/usr/bin/env python3
"""LLM-as-Judge 与人工标注一致性校准（纯离线）。

流程：
  1. 完整RAGAS运行后生成抽检集：
     python eval/judge_calibration.py prepare --input eval/ragas_raw.jsonl
  2. 人工填写输出文件中的 human_label / reviewer / notes。
  3. 计算一致性：
     python eval/judge_calibration.py score --input eval/judge_calibration_sample.jsonl

标签口径：SUPPORTED / PARTIAL / UNSUPPORTED。项目建议 Cohen's kappa >= 0.70。
"""

from __future__ import annotations

import argparse
import json
import random
from collections import Counter
from pathlib import Path


LABELS = ("SUPPORTED", "PARTIAL", "UNSUPPORTED")
DEFAULT_OUTPUT = Path(__file__).resolve().parent / "judge_calibration_sample.jsonl"


def score_to_label(score: float | None) -> str | None:
    if score is None:
        return None
    value = float(score)
    if value >= 0.8:
        return "SUPPORTED"
    if value >= 0.5:
        return "PARTIAL"
    return "UNSUPPORTED"


def cohen_kappa(first: list[str], second: list[str]) -> float:
    """名义分类Cohen's kappa；输入必须等长且非空。"""
    if len(first) != len(second) or not first:
        raise ValueError("labels must be non-empty and have equal length")
    n = len(first)
    observed = sum(a == b for a, b in zip(first, second)) / n
    first_counts = Counter(first)
    second_counts = Counter(second)
    categories = set(first_counts) | set(second_counts)
    expected = sum(
        (first_counts[label] / n) * (second_counts[label] / n)
        for label in categories
    )
    if expected == 1.0:
        return 1.0 if observed == 1.0 else 0.0
    return (observed - expected) / (1.0 - expected)


def calibration_report(rows: list[dict]) -> dict:
    completed = [
        row for row in rows
        if row.get("human_label") in LABELS and row.get("judge_label") in LABELS
    ]
    if not completed:
        raise ValueError("没有同时具备 human_label 和 judge_label 的样本")
    human = [row["human_label"] for row in completed]
    judge = [row["judge_label"] for row in completed]
    confusion = {
        actual: {predicted: 0 for predicted in LABELS}
        for actual in LABELS
    }
    for actual, predicted in zip(human, judge):
        confusion[actual][predicted] += 1
    agreement = sum(a == b for a, b in zip(human, judge)) / len(completed)
    kappa = cohen_kappa(human, judge)
    return {
        "completed": len(completed),
        "agreement": agreement,
        "cohen_kappa": kappa,
        "gate_threshold": 0.70,
        "status": "PASS" if kappa >= 0.70 else "FAIL",
        "confusion_matrix": confusion,
    }


def _load_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def prepare_rows(rows: list[dict], sample_size: int, seed: int) -> list[dict]:
    """按Judge分数档分层抽样；旧产物无逐题分数时退化为确定性随机抽样。"""
    rng = random.Random(seed)
    buckets = {label: [] for label in (*LABELS, "UNSCORED")}
    for index, row in enumerate(rows):
        score = (row.get("metrics") or {}).get("faithfulness")
        label = score_to_label(score) or "UNSCORED"
        buckets[label].append((index, row, score, label))
    for bucket in buckets.values():
        rng.shuffle(bucket)

    selected = []
    bucket_names = [name for name, values in buckets.items() if values]
    while len(selected) < min(sample_size, len(rows)) and bucket_names:
        for name in list(bucket_names):
            if buckets[name]:
                selected.append(buckets[name].pop())
                if len(selected) >= min(sample_size, len(rows)):
                    break
            if not buckets[name]:
                bucket_names.remove(name)

    output = []
    for source_index, row, score, label in selected:
        output.append({
            "sample_id": f"cal-{source_index + 1:04d}",
            "question": row.get("question", ""),
            "ground_truth": row.get("ground_truth", ""),
            "answer": row.get("answer", ""),
            "contexts": row.get("contexts", []),
            "state_hint": row.get("state_hint"),
            "judge_metric": "faithfulness",
            "judge_score": score,
            "judge_label": None if label == "UNSCORED" else label,
            "human_label": "",
            "reviewer": "",
            "notes": "",
        })
    return output


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--input", type=Path, required=True)
    prepare.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    prepare.add_argument("--sample-size", type=int, default=30)
    prepare.add_argument("--seed", type=int, default=20260824)

    score = subparsers.add_parser("score")
    score.add_argument("--input", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "prepare":
        rows = prepare_rows(_load_jsonl(args.input), args.sample_size, args.seed)
        _write_jsonl(args.output, rows)
        scored = sum(row["judge_label"] is not None for row in rows)
        print(f"已生成 {len(rows)} 条校准样本: {args.output} (含Judge分数 {scored} 条)")
        return 0

    report = calibration_report(_load_jsonl(args.input))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
