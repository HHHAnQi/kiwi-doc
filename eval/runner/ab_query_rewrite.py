#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 6 / V12 Query Enhancement — baseline vs rewrite AB 评测。

对同一 batch 的 query 各跑两次:
  1) enhance=False — baseline, 走原 query 直接检索
  2) enhance=True  — 启用 query rewrite + expansion (走 fallback LLM 增强)
对比 Recall@K / HitRate / MRR / NDCG / Precision@K, 输出表格 + JSON 报告 + 判 winner。

前置:
  - backend 已起 (默认 mode=dense)
  - rag.query-enhance.enabled=true 且 fallback LLM route 可达
    (改 .env 里 RAG_QUERY_ENHANCE_ENABLED=true; 或刷 application-dev.yml)
  - dataset eval/datasets/retrieval_eval.jsonl 存在

用法:
    python3 eval/runner/ab_query_rewrite.py \\
        --dataset eval/datasets/retrieval_eval.jsonl \\
        --k 5 --output eval/query_rewrite_report.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from eval.metrics import retrieval_metrics as rm  # noqa: E402
from eval.runner import retrieve_client  # noqa: E402

# 两个 variant: baseline (不 enhance) vs rewrite (强制 enhance)
VARIANTS = (("baseline", False), ("rewrite", True))


def _load_dataset(path: Path) -> list[dict]:
    out: list[dict] = []
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if "question" not in d or "gold_chunk_ids" not in d:
                raise ValueError(f"dataset 行 {i} 缺必填字段 question/gold_chunk_ids: {line[:80]}")
            out.append(d)
    return out


def run_one_variant(
    dataset: list[dict], enhance: bool, k: int, retrieve_url: str
) -> tuple[list[dict], list[dict]]:
    per_q: list[dict] = []
    raws: list[dict] = []
    for d in dataset:
        resp = retrieve_client.retrieve(
            d["question"],
            top_k=k,
            source=d.get("source"),
            version=d.get("version"),
            language=d.get("language"),
            enhance=enhance,
            base_url=retrieve_url,
        )
        ids, _ = retrieve_client.extracted(resp)
        gold = d["gold_chunk_ids"]
        per_q.append(rm.per_query_metrics(ids, gold, k))
        raws.append(resp)
    return per_q, raws


def fmt_pct(x: float) -> str:
    return f"{x * 100:.2f}%"


def print_table(baseline_agg: dict, rewrite_agg: dict, k: int) -> None:
    print()
    print(f"=== baseline (original query) vs rewrite (enhanced) AB 评测 (K={k}) ===")
    header = f"{'metric':<22}{'baseline':>12}{'rewrite':>12}{'delta':>12}{'winner':>10}"
    print(header)
    print("-" * len(header))
    wanted = [f"recall@{k}", f"precision@{k}", f"hit_rate@{k}", f"mrr@{k}", f"ndcg@{k}"]
    for key in wanted:
        b = baseline_agg.get(key, 0.0)
        r = rewrite_agg.get(key, 0.0)
        delta = r - b
        winner = "rewrite" if delta > 0.001 else ("baseline" if delta < -0.001 else "tie")
        print(f"{key:<22}{fmt_pct(b):>12}{fmt_pct(r):>12}{fmt_pct(delta):>12}{winner:>10}")
    print()


def main() -> int:
    p = argparse.ArgumentParser(description="baseline vs rewrite query AB eval")
    p.add_argument("--dataset", default="eval/datasets/retrieval_eval.jsonl")
    p.add_argument("--k", type=int, default=5, help="Recall@K 的 K 值, 默认 5")
    p.add_argument("--output", default="eval/query_rewrite_report.json")
    p.add_argument(
        "--retrieve-url",
        default=os.getenv("RETRIEVE_URL", "http://localhost:8080/api/v1/retrieve"),
    )
    args = p.parse_args()

    dataset = _load_dataset(Path(args.dataset))
    print(f"载入 {len(dataset)} 条 query; K={args.k}; 开始 AB 评测...", flush=True)

    raws_by_variant: dict[str, list[dict]] = {}
    agg_by_variant: dict[str, dict] = {}
    t0 = time.time()
    for name, enhance_flag in VARIANTS:
        print(f"\n--- 跑 variant={name} (enhance={enhance_flag}) ---", flush=True)
        per_q, raws = run_one_variant(dataset, enhance_flag, args.k, args.retrieve_url)
        agg_by_variant[name] = rm.aggregate(per_q)
        raws_by_variant[name] = raws

    print_table(agg_by_variant["baseline"], agg_by_variant["rewrite"], args.k)

    recall_key = f"recall@{args.k}"
    b_recall = agg_by_variant["baseline"].get(recall_key, 0.0)
    r_recall = agg_by_variant["rewrite"].get(recall_key, 0.0)
    delta_recall = r_recall - b_recall
    winner = "rewrite" if delta_recall > 0.001 else ("baseline" if delta_recall < -0.001 else "tie")

    report = {
        "dataset_size": len(dataset),
        "k": args.k,
        "started_at": datetime.fromtimestamp(t0, tz=timezone.utc).isoformat(),
        "elapsed_sec": round(time.time() - t0, 2),
        "baseline": agg_by_variant["baseline"],
        "rewrite": agg_by_variant["rewrite"],
        f"delta_{recall_key}": round(delta_recall, 6),
        "winner": winner,
        "retrieve_url": args.retrieve_url,
    }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"报告已写入: {out}")
    print(
        f"winner by {recall_key}: {winner}  "
        f"(baseline={fmt_pct(b_recall)} / rewrite={fmt_pct(r_recall)}, delta={fmt_pct(delta_recall)})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
