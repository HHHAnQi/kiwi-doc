#!/usr/bin/env python3
"""从增强后的ragas_raw.jsonl生成可操作的指标分片和坏例报告（纯离线）。"""

from __future__ import annotations

import argparse
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path


def classify_question(question: str) -> str:
    text = question or ""
    if re.search(r"`|[A-Za-z]+[._-][A-Za-z0-9._-]+|配置键|参数名|端口|默认值", text):
        return "exact_identifier"
    if re.search(r"区别|差异|对比|分别|和.*(?:如何|为什么|哪些)", text):
        return "multi_part"
    if re.search(r"如何|怎么|怎样", text):
        return "how_to"
    if re.search(r"为什么|原因", text):
        return "why"
    return "fact"


def _mean(values):
    return statistics.mean(values) if values else None


def analyze_samples(rows: list[dict]) -> dict:
    slices = defaultdict(list)
    badcases = []
    for index, row in enumerate(rows, 1):
        slice_name = classify_question(row.get("question", ""))
        slices[slice_name].append(row)
        metrics = row.get("metrics") or {}
        retrieval = row.get("retrieval_metrics") or {}
        context_count = len(row.get("contexts") or [])
        retrieval_miss = retrieval.get("hit_rate@5") == 0.0 if retrieval else None
        low_faith = metrics.get("faithfulness") is not None and metrics["faithfulness"] < 0.5
        low_relevancy = metrics.get("answer_relevancy") is not None and metrics["answer_relevancy"] < 0.5
        if context_count == 0 or retrieval_miss or low_faith or low_relevancy:
            severity = (
                4 * int(context_count == 0)
                + 3 * int(retrieval_miss is True)
                + 2 * int(low_faith)
                + int(low_relevancy)
            )
            badcases.append({
                "sample_index": index,
                "severity": severity,
                "slice": slice_name,
                "question": row.get("question"),
                "state_hint": row.get("state_hint"),
                "context_count": context_count,
                "retrieval_metrics": retrieval,
                "generation_metrics": metrics,
                "retrieved_chunk_ids": row.get("retrieved_chunk_ids") or [],
                "gold_chunk_ids": row.get("gold_chunk_ids") or [],
            })

    slice_report = {}
    for name, items in sorted(slices.items()):
        def metric_values(group, container, key):
            return [
                item.get(container, {}).get(key)
                for item in group
                if item.get(container) and item[container].get(key) is not None
            ]

        slice_report[name] = {
            "n": len(items),
            "avg_context_count": _mean([len(item.get("contexts") or []) for item in items]),
            "faithfulness": _mean(metric_values(items, "metrics", "faithfulness")),
            "answer_relevancy": _mean(metric_values(items, "metrics", "answer_relevancy")),
            "recall@5": _mean(metric_values(items, "retrieval_metrics", "recall@5")),
            "mrr@5": _mean(metric_values(items, "retrieval_metrics", "mrr@5")),
            "ndcg@5": _mean(metric_values(items, "retrieval_metrics", "ndcg@5")),
        }
    badcases.sort(key=lambda item: (-item["severity"], item["sample_index"]))
    return {"sample_count": len(rows), "slices": slice_report, "badcases": badcases}


def render_markdown(report: dict) -> str:
    lines = [
        "# RAG Badcase与指标分片报告\n\n",
        f"样本数: {report['sample_count']}；坏例数: {len(report['badcases'])}\n\n",
        "## 分片指标\n\n",
        "| 分片 | N | Context数 | Faith | Relevancy | Recall@5 | MRR@5 | NDCG@5 |\n",
        "|---|---:|---:|---:|---:|---:|---:|---:|\n",
    ]
    def fmt(value):
        return "N/A" if value is None else f"{value:.3f}"
    for name, metrics in report["slices"].items():
        lines.append(
            f"| {name} | {metrics['n']} | {fmt(metrics['avg_context_count'])} | "
            f"{fmt(metrics['faithfulness'])} | {fmt(metrics['answer_relevancy'])} | "
            f"{fmt(metrics['recall@5'])} | {fmt(metrics['mrr@5'])} | {fmt(metrics['ndcg@5'])} |\n"
        )
    lines.extend(["\n## 优先坏例\n\n", "| # | 严重度 | 分片 | Context | 问题 |\n", "|---:|---:|---|---:|---|\n"])
    for item in report["badcases"][:30]:
        question = str(item["question"] or "").replace("|", "\\|")
        lines.append(
            f"| {item['sample_index']} | {item['severity']} | {item['slice']} | "
            f"{item['context_count']} | {question} |\n"
        )
    return "".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
    report = analyze_samples(rows)
    args.json_output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    args.markdown_output.write_text(render_markdown(report), encoding="utf-8")
    print(f"已生成坏例报告: {args.markdown_output} ({len(report['badcases'])} badcases)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
