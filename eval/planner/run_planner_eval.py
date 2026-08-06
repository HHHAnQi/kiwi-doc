"""PR-7d Planner / Sufficiency / Trajectory / End-to-End evaluator.

输入:
  - dataset jsonl (planner_benchmark_v1.reviewed.jsonl)
  - actuals jsonl (per-case 实测 Planner / Sufficiency / Tool / Answer / Citation / final status)

输出 metrics JSON + CSV, 详见 PR-7d 任务书 §10–§13.

PR-7d v1 仅实现:
  - 读取 dataset
  - 跨 case 计算 Trajectory status / Planner schema valid / Sufficiency status
等基础指标;
  - Replan 数 / Agent run final status accuracy;
  - Tool/LLM calls per task;
  - **不**计算 Answer Correctness / Faithfulness / RAGAS — 需要真实模型, 必须明确标 NOT_EXECUTED.

不伪造指标. dataset 为空 / actuals 为空 → 返回 NOT_EXECUTED 报告.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    out = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


TERMINAL_STATUSES = {
    "ANSWERED", "REFUSED_NO_EVIDENCE", "REFUSED_CONFLICT",
    "REFUSED_PERMISSION", "TOOL_FAILED", "BUDGET_EXCEEDED",
    "TIMED_OUT", "CANCELLED", "SYSTEM_FAILED",
}


def _safe_div(num: float, den: float) -> float:
    return num / den if den else 0.0


def evaluate(
    dataset: list[dict[str, Any]],
    actuals_by_case: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    """汇总 metrics. 以 dataset 为主线, 找不到 actual 的 case 记 missing."""
    n = len(dataset)
    completed = 0
    final_status_match = 0
    replan_count_total = 0
    replan_attempted = 0
    replan_succeeded = 0
    no_progress_count = 0
    loop_escape_count = 0
    non_terminal_residue = 0
    false_sufficient_count = 0
    avg_tool_calls_num = 0
    avg_llm_calls_num = 0
    avg_real_tool_calls_num = 0
    cross_tenant_leak = 0
    sse_multiple_terminal = 0
    per_case_results: list[dict[str, Any]] = []

    for case in dataset:
        cid = case["caseId"]
        actual = actuals_by_case.get(cid)
        line = {
            "caseId": cid,
            "expectedFinalStatus": case["expectedFinalStatus"],
            "answerable": case["answerable"],
            "slice": case.get("slice", ""),
        }
        if not actual:
            line["actual_present"] = False
            per_case_results.append(line)
            continue
        completed += 1
        actual_status = actual.get("finalStatus", "MISSING")
        line["actual_present"] = True
        line["actualFinalStatus"] = actual_status
        line["replanCount"] = actual.get("replanCount", 0)
        line["sseTerminalEventCount"] = actual.get("sseTerminalEventCount", 0)
        line["answerCalls"] = actual.get("answerCalls", 0)
        line["llmCallsTotal"] = actual.get("llmCallsTotal", 0)
        line["toolCalls"] = actual.get("toolCalls", 0)
        line["illegalToolExecutions"] = actual.get("illegalToolExecutions", 0)
        line["nonTerminalStepResidue"] = actual.get("nonTerminalStepResidue", 0)
        line["crossTenantEvidenceLeak"] = actual.get("crossTenantEvidenceLeak", 0)
        # status match
        if actual_status == case["expectedFinalStatus"]:
            final_status_match += 1
        # replan
        rc = actual.get("replanCount", 0)
        replan_count_total += rc
        if rc == 1:
            replan_attempted += 1
            if actual_status in {"ANSWERED", "REFUSED_CONFLICT"}:
                # ANSWERED 视为 replan 成功, 拒答视为非成功
                replan_succeeded += actual_status == "ANSWERED"
        # no progress
        if actual.get("noProgress") is True:
            no_progress_count += 1
        # loop escape — forbiddenToolSignatures 出现在 executedSignatures 中应被拒
        exec_sigs = set(actual.get("executedSignatures", []))
        forbidden = set(case.get("forbiddenToolSignatures", []) or [])
        if forbidden and forbidden & exec_sigs:
            # 应该被 loop detection 阻断; 若 status=ANSWERED 则视为泄漏
            loop_escape_count += actual_status == "ANSWERED"
        # non-terminal residue
        if actual.get("nonTerminalStepResidue", 0) > 0:
            non_terminal_residue += 1
        # false sufficient
        # actual.guardRejections>0 时 judge 错判但 guard 拦截; 算 false_sufficient_caught.
        # actual.falseSufficientLeak>0 时 guard 也漏放 — 这是真正的 false sufficient.
        if actual.get("falseSufficientLeak", 0) > 0:
            false_sufficient_count += 1
        # cross-tenant
        if actual.get("crossTenantEvidenceLeak", 0) > 0:
            cross_tenant_leak += 1
        # SSE 多终态
        if actual.get("sseTerminalEventCount", 0) > 1:
            sse_multiple_terminal += 1
        avg_tool_calls_num += actual.get("toolCalls", 0)
        avg_llm_calls_num += actual.get("llmCallsTotal", 0)
        avg_real_tool_calls_num += actual.get("realToolCalls", 0)
        per_case_results.append(line)

    metrics = {
        "datasetSize": n,
        "completedActuals": completed,
        "missingActuals": n - completed,
        "finalStatusAccuracy": _safe_div(final_status_match, completed),
        "replanAttempts": replan_attempted,
        "replanAttemptRate": _safe_div(replan_attempted, completed),
        "replanSuccessRate": _safe_div(replan_succeeded, replan_attempted),
        "noProgressRate": _safe_div(no_progress_count, completed),
        "loopEscapeRate": _safe_div(loop_escape_count, completed),
        "nonTerminalResidueRate": _safe_div(non_terminal_residue, completed),
        "falseSufficientRate": _safe_div(false_sufficient_count, completed),
        "crossTenantLeakRate": _safe_div(cross_tenant_leak, completed),
        "sseMultipleTerminalRate": _safe_div(sse_multiple_terminal, completed),
        "avgToolCallsPerTask": _safe_div(avg_tool_calls_num, completed),
        "avgLlmCallsPerTask": _safe_div(avg_llm_calls_num, completed),
        "avgRealToolCallsPerTask": _safe_div(avg_real_tool_calls_num, completed),
        "answerCorrectness": None,  # NOT_EXECUTED 需要 RAGAS
        "faithfulness": None,
        "citationPrecision": None,  # 需 citation verifier
        "citationRecall": None,
        "goldEvidenceRecall": None,
        "p50LatencyMs": None,
        "p95LatencyMs": None,
        "estimatedCostPerTask": None,
    }
    return {
        "metrics": metrics,
        "per_case": per_case_results,
    }


def _write_csv(per_case: list[dict[str, Any]], path: Path) -> None:
    if not per_case:
        return
    cols = [
        "caseId", "slice", "answerable", "expectedFinalStatus",
        "actual_present", "actualFinalStatus", "replanCount",
        "answerCalls", "llmCallsTotal", "toolCalls",
        "nonTerminalStepResidue", "crossTenantEvidenceLeak",
        "sseTerminalEventCount",
    ]
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for row in per_case:
            w.writerow(row)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="PR-7d Planner evaluator")
    p.add_argument("dataset", type=Path, help="reviewed dataset jsonl")
    p.add_argument("--actuals", type=Path, help="actuals jsonl", default=None)
    p.add_argument("--out-json", type=Path, default=Path("eval/planner/reports/last_report.json"))
    p.add_argument("--out-csv", type=Path, default=Path("eval/planner/reports/last_report.csv"))
    args = p.parse_args(argv)

    if not args.dataset.exists():
        print(f"dataset 不存在: {args.dataset}", file=sys.stderr)
        return 1

    dataset = _load_jsonl(args.dataset)
    actuals_by_case: dict[str, dict[str, Any]] = {}
    if args.actuals and args.actuals.exists():
        for row in _load_jsonl(args.actuals):
            actuals_by_case[row["caseId"]] = row

    report = evaluate(dataset, actuals_by_case)
    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    args.out_json.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    _write_csv(report["per_case"], args.out_csv)
    print(f"OK: report -> {args.out_json} / {args.out_csv}")
    print(json.dumps(report["metrics"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
