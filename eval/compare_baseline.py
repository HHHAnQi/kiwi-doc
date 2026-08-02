#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对比当前 RAGAS 报告与 baseline, 任一指标降 > threshold pp 写 REGRESSION_DETECTED.

baseline 期望格式:
    ## 汇总指标
    - faithfulness: 0.6711
    - answer_relevancy: 0.6215
    - context_precision: 0.7193
    - context_recall: 0.5711

current 同 format(eval_ragas_report.md 标准输出)。

输出: regression_check.md
  含 4 指标对比表 + 是否 regression 的明确 flag
  GHA step 读 REGRESSION_DETECTED 子串决定是否 fail build
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

METRICS = [
    "faithfulness",
    "answer_relevancy",
    "context_precision",
    "context_recall",
]


def parse_report(p: Path) -> dict[str, float]:
    if not p.exists():
        return {}
    text = p.read_text(encoding="utf-8")
    out = {}
    for m in METRICS:
        # 匹配 "- faithfulness: 0.6711" 或 "faithfulness  0.6711" 或 "faithfulness: 0.6711"
        match = re.search(rf"{m}\s*:?\s*([0-9]+\.[0-9]+)", text)
        if match:
            out[m] = float(match.group(1))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", type=Path, required=True)
    ap.add_argument("--current", type=Path, required=True)
    ap.add_argument("--threshold", type=float, default=3.0, help="允许的最大下降 pp")
    ap.add_argument("--output", type=Path, default=Path("eval/regression_check.md"))
    args = ap.parse_args()

    base = parse_report(args.baseline)
    curr = parse_report(args.current)

    print(f"baseline: {base}")
    print(f"current:  {curr}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# RAGAS Regression Check", ""]
    lines.append(f"| metric | baseline | current | Δ pp | status |")
    lines.append(f"|---|---|---|---|---|")

    regressions = []
    for m in METRICS:
        b = base.get(m)
        c = curr.get(m)
        if b is None or c is None:
            lines.append(f"| {m} | {b or 'N/A'} | {c or 'N/A'} | - | SKIP(missing data) |")
            continue
        delta_pp = (c - b) * 100  # 0.65→0.60 = -5pp
        # 下降超过 threshold 才算 regression(上升不算)
        status = "OK" if delta_pp >= -args.threshold else "REGRESSION"
        if status == "REGRESSION":
            regressions.append(m)
        lines.append(
            f"| {m} | {b:.4f} | {c:.4f} | {delta_pp:+.2f} | {status} |"
        )

    lines.append("")
    if regressions:
        lines.append(f"## REGRESSION_DETECTED")
        lines.append(
            f"以下 {len(regressions)} 个指标降幅超 {args.threshold}pp: {', '.join(regressions)}"
        )
    else:
        lines.append(f"## ALL_OK — 无指标降幅超 {args.threshold}pp")

    args.output.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n报告: {args.output}")
    print("\n".join(lines[-5:]))

    return 0 if not regressions else 2  # exit 2 让 CI 识别为 fail


if __name__ == "__main__":
    sys.exit(main())
