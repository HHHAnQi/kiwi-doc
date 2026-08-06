"""PR-7d aggregate ablation report.

聚合多次 run (baseline / A0..A7) 的 report JSON, 生成 ablation 对照表 + Markdown 报告.

用途:
  - 不同 Feature Flag 组合下 repeat runs 跨 dataset 跑出的 metric.json 聚合
  - 给 docs/pr-7.md 提供消融对照表

输入:
  --inputs ablation_A0.json ablation_A1.json ...   (每个是一份 run_planner_eval.py 的 metrics-only 切片)
输出:
  --markdown aggregate_report.md
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def aggregate(input_paths: list[Path]) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for path in input_paths:
        if not path.exists():
            print(f"WARN input 不存在: {path}", file=sys.stderr)
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            print(f"WARN JSON 解析失败 {path}: {e}", file=sys.stderr)
            continue
        # 取整份 (含 scenario 标签 + metrics) 或自动 wrap 为 metrics
        metrics = data.get("metrics", data)
        scenario = data.get("scenario") or path.stem
        rows.append({"scenario": scenario, "metrics": metrics})
    return {"rows": rows}


def render_markdown(report: dict[str, Any]) -> str:
    if not report["rows"]:
        return "# PR-7d Aggregate — no input reports.\n"
    lines = [
        "# PR-7d Ablation / Aggregate Report",
        "",
        "> 各行来自一次 evaluator 运行;真实环境跑出后填入. **NOT_EXECUTED 时本表为空.**",
        "",
        "| Scenario | Dataset | Completed | Final Status Acc | Replan Rate | Replan Success | False Sufficient Rate | SSE Multiple Terminal | Non-terminal Residue | Cross-tenant Leak |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in report["rows"]:
        m = row["metrics"]
        lines.append(
            "| {scenario} | {ds} | {comp} | {facc:.4f} | {rr:.4f} | {rs:.4f} | {fsr:.4f} | {sse:.4f} | {nr:.4f} | {ct:.4f} |".format(
                scenario=row["scenario"],
                ds=m.get("datasetSize", "?"),
                comp=m.get("completedActuals", "?"),
                facc=m.get("finalStatusAccuracy", 0.0) or 0.0,
                rr=m.get("replanAttemptRate", 0.0) or 0.0,
                rs=m.get("replanSuccessRate", 0.0) or 0.0,
                fsr=m.get("falseSufficientRate", 0.0) or 0.0,
                sse=m.get("sseMultipleTerminalRate", 0.0) or 0.0,
                nr=m.get("nonTerminalResidueRate", 0.0) or 0.0,
                ct=m.get("crossTenantLeakRate", 0.0) or 0.0,
            )
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="PR-7d 报告聚合")
    p.add_argument("--inputs", type=Path, nargs="+", required=True,
                   help="多个 run_planner_eval.py 的 JSON 输出 (每个含 scenario + metrics)")
    p.add_argument("--markdown", type=Path, required=True)
    args = p.parse_args(argv)
    report = aggregate(args.inputs)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(render_markdown(report), encoding="utf-8")
    print(f"OK markdown -> {args.markdown}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
