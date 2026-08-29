#!/usr/bin/env python3
"""把定向重试题按 question 合并回完整生成报告，并重新计算宏平均。"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from eval.metrics import generation_metrics as gm


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--retry", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--base-index",
        type=int,
        default=None,
        help="按完整报告的 1-based 位置替换单题；用于问题文本被纠正后无法按 question 匹配",
    )
    args = parser.parse_args()

    base = json.loads(args.base.read_text(encoding="utf-8"))
    retry = json.loads(args.retry.read_text(encoding="utf-8"))
    retry_metrics = retry["per_query"]["generation"]
    retry_audits = retry["per_query"]["generation_audit"]
    if len(retry_metrics) != len(retry_audits):
        raise RuntimeError("retry metrics/audit 数量不一致")
    replacement = {
        audit["question"]: (metric, audit)
        for metric, audit in zip(retry_metrics, retry_audits)
    }

    base_metrics = base["per_query"]["generation"]
    base_audits = base["per_query"]["generation_audit"]
    replaced = []
    targets = []
    if args.base_index is not None:
        if len(retry_metrics) != 1 or not (1 <= args.base_index <= len(base_audits)):
            raise RuntimeError("--base-index 要求 retry 恰好一题且位置在 base 范围内")
        targets = [(args.base_index - 1, retry_metrics[0], retry_audits[0])]
    else:
        targets = [
            (index, *replacement[audit["question"]])
            for index, audit in enumerate(base_audits)
            if audit["question"] in replacement
        ]
    for index, metric, new_audit in targets:
        audit = base_audits[index]
        question = audit["question"]
        # 定向报告可能从 1 重新编号；合并后必须保留完整冻结集 ID。
        metric["__question_id"] = base_metrics[index].get("__question_id", index + 1)
        new_audit["__question_id"] = audit.get("__question_id", index + 1)
        base_metrics[index] = metric
        base_audits[index] = new_audit
        replaced.append(new_audit["question"])
    if args.base_index is None and set(replaced) != set(replacement):
        raise RuntimeError("部分 retry question 未在 base 中找到")

    clean = [row for row in base_metrics if "error" not in row]
    base["metrics"]["generation"] = {
        key: value
        for key, value in gm.aggregate_generation(clean).items()
        if not key.startswith("__")
    }
    base["generation_failures"] = sum("error" in row for row in base_metrics)
    base["post_fix_retry"] = {
        "base_report": str(args.base),
        "retry_report": str(args.retry),
        "replaced_questions": replaced,
        "merged_at": datetime.now(timezone.utc).isoformat(),
    }
    args.output.write_text(json.dumps(base, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "replaced": len(replaced),
        "generation_failures": base["generation_failures"],
        "generation_metrics": base["metrics"]["generation"],
        "output": str(args.output),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
