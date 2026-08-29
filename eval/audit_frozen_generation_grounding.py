#!/usr/bin/env python3
"""审计冻结集的标准答案是否被当前 corpus 证据完整支持。"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from eval.runner import judge_client, retrieve_client  # noqa: E402


def parse_json(raw: str) -> dict:
    text = raw.strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.I | re.S)
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise ValueError(f"Judge 未返回 JSON: {raw[:200]}")
    return json.loads(text[start : end + 1])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="eval/golden/golden_v3_frozen80.jsonl")
    parser.add_argument("--output", default="eval/runs/frozen80_generation_grounding_audit.json")
    parser.add_argument("--all", action="store_true", help="审计全部题；默认只审计自动接受的 21 题")
    parser.add_argument("--non-auto", action="store_true", help="只审计已人工复核/证据先行的 59 题")
    parser.add_argument("--judge-provider", type=int, choices=(1, 2), default=1)
    parser.add_argument("--limit", type=int, default=0, help="仅审计前 N 题；0 表示全部")
    args = parser.parse_args()

    rows = [
        json.loads(line)
        for line in (REPO_ROOT / args.dataset).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if args.all and args.non_auto:
        parser.error("--all 与 --non-auto 不能同时使用")
    if args.non_auto:
        rows = [r for r in rows if r.get("review_status") != "high_confidence_auto_accepted"]
    elif not args.all:
        rows = [r for r in rows if r.get("review_status") == "high_confidence_auto_accepted"]
    if args.limit > 0:
        rows = rows[: args.limit]

    judge = judge_client.make_judge_fn(provider_index=args.judge_provider, max_tokens=1024)
    results = []
    for index, row in enumerate(rows, 1):
        question = row["question"]
        answer = (
            row.get("new_ground_truth_answer")
            or row.get("ground_truth_answer")
            or row.get("answer")
            or ""
        )
        doc_id = row.get("new_ground_truth_doc_id") or row.get("ground_truth_doc_id")
        response = retrieve_client.retrieve(question, top_k=20, doc_id=doc_id)
        _, items = retrieve_client.extracted(response)
        evidence = "\n\n".join(
            f"[chunk_id={item.get('chunk_id')}] {item.get('llm_context') or item.get('snippet') or ''}"
            for item in items
        )
        prompt = f"""你是 RAG 数据集证据审计员。只根据给定证据判断标准答案是否被完整支持。

问题：{question}

标准答案：{answer}

当前语料证据：
{evidence}

规则：
1. full：标准答案每个关键事实（数值、版本、参数、步骤、名称）都有证据。
2. partial：只支持部分关键事实，或答案比证据更具体。
3. none：证据不支持核心答案。
4. 不得使用外部知识补全。

只输出 JSON：
{{"support":"full|partial|none","supported_answer":"仅保留证据可支持事实的修正版答案","reason":"一句话原因"}}"""
        raw_verdict = ""
        try:
            raw_verdict = judge(prompt)
            verdict = parse_json(raw_verdict)
            support = str(verdict.get("support", "parse_error")).lower()
            if support not in {"full", "partial", "none"}:
                support = "parse_error"
        except Exception as exc:
            verdict = {"reason": str(exc), "raw_judge_response": raw_verdict}
            support = "error"
        result = {
            "question": question,
            "review_status": row.get("review_status"),
            "gold_chunk_ids": row.get("gold_chunk_ids") or [],
            "retrieved_chunk_ids": [item.get("chunk_id") for item in items],
            "support": support,
            "supported_answer": verdict.get("supported_answer"),
            "reason": verdict.get("reason"),
        }
        if support == "error":
            result["raw_judge_response"] = verdict.get("raw_judge_response")
        results.append(result)
        print(f"[{index}/{len(rows)}] {support}: {question[:50]}")
        time.sleep(0.1)

    counts = Counter(r["support"] for r in results)
    report = {
        "dataset": args.dataset,
        "scope": (
            "all" if args.all else
            "non_auto_reviewed_or_curated" if args.non_auto else
            "high_confidence_auto_accepted"
        ),
        "sample_count": len(rows),
        "judge_provider": args.judge_provider,
        "judge_model": judge_client.judge_model(args.judge_provider),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "counts": dict(counts),
        "full_support_rate": counts.get("full", 0) / len(rows) if rows else 0.0,
        "results": results,
    }
    output = REPO_ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
