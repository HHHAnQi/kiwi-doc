#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Task 5 / V11 Hybrid Retrieval — dense vs hybrid AB 评测。

对同一 batch 的 query 各跑两次:
  1) mode=dense — 单路 BGE-M3 dense ANN
  2) mode=hybrid — dense + BM25 sparse + RRF 融合
对比 Recall@K / HitRate / MRR / NDCG / Precision@K 五项检索指标, 输出报告 JSON + stdout 表。

用法 (举例):
    # 1. 起 backend (默认 mode=dense 走基线), Confirm dataset 在 eval/datasets/
    # 2. 跑:
    python3 eval/runner/ab_dense_vs_hybrid.py \
        --dataset eval/datasets/retrieval_eval.jsonl \
        --k 5 --output eval/dense_vs_hybrid_report.json

注: AB 接口要求 admin token, 默认读 APP_ADMIN_TOKEN env; 普通路径 /api/v1/retrieve 也接受
mode 字段 (dev-token 可调), 故 admin-token 非强制 (除非改调 /experiment 路径)。
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

VARIANTS = ("dense", "hybrid")


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


def run_one_variant(dataset: list[dict], mode: str, k: int, retrieve_url: str) -> tuple[list[dict], list[dict]]:
    """对整 dataset 跑一次某 mode 的检索, 返回 (per_query_metrics_list, raw_responses)。"""
    per_q: list[dict] = []
    raws: list[dict] = []
    for d in dataset:
        resp = retrieve_client.retrieve(
            d["question"],
            top_k=k,
            source=d.get("source"),
            version=d.get("version"),
            language=d.get("language"),
            mode=mode,
            base_url=retrieve_url,
        )
        ids, _ = retrieve_client.extracted(resp)
        gold = d["gold_chunk_ids"]
        per_q.append(rm.per_query_metrics(ids, gold, k))
        raws.append(resp)
    return per_q, raws


def fmt_pct(x: float) -> str:
    return f"{x * 100:.2f}%"


def print_table(dense_agg: dict, hybrid_agg: dict, k: int) -> None:
    print()
    print(f"=== dense vs hybrid AB 评测 (K={k}) ===")
    header = f"{'metric':<22}{'dense':>12}{'hybrid':>12}{'delta':>12}{'winner':>10}"
    print(header)
    print("-" * len(header))
    # key 命名是 f"recall@{k}" / "precision@{k}" / "hit_rate@{k}" / "mrr@{k}" / "ndcg@{k}"
    wanted = [f"recall@{k}", f"precision@{k}", f"hit_rate@{k}", f"mrr@{k}", f"ndcg@{k}"]
    for key in wanted:
        d = dense_agg.get(key, 0.0)
        h = hybrid_agg.get(key, 0.0)
        delta = h - d
        winner = "hybrid" if delta > 0.001 else ("dense" if delta < -0.001 else "tie")
        print(f"{key:<22}{fmt_pct(d):>12}{fmt_pct(h):>12}{fmt_pct(delta):>12}{winner:>10}")
    print()


def main() -> int:
    p = argparse.ArgumentParser(description="dense vs hybrid retrieval AB eval")
    p.add_argument("--dataset", default="eval/datasets/retrieval_eval.jsonl")
    p.add_argument("--k", type=int, default=5, help="Recall@K 的 K 值, 默认 5")
    p.add_argument("--output", default="eval/dense_vs_hybrid_report.json")
    p.add_argument(
        "--retrieve-url",
        default=os.getenv("RETRIEVE_URL", "http://localhost:8080/api/v1/retrieve"),
    )
    args = p.parse_args()

    dataset = _load_dataset(Path(args.dataset))
    print(f"载入 {len(dataset)} 条 query; K={args.k}; 开始 AB 评测...", flush=True)

    raws_by_mode: dict[str, list[dict]] = {}
    agg_by_mode: dict[str, dict] = {}
    t0 = time.time()
    for mode in VARIANTS:
        print(f"\n--- 跑 mode={mode} ---", flush=True)
        per_q, raws = run_one_variant(dataset, mode, args.k, args.retrieve_url)
        agg_by_mode[mode] = rm.aggregate(per_q)
        raws_by_mode[mode] = raws

    print_table(agg_by_mode["dense"], agg_by_mode["hybrid"], args.k)

    # winner 判定: 谁的 recall@{k} 高谁胜
    recall_key = f"recall@{args.k}"
    d_recall = agg_by_mode["dense"].get(recall_key, 0.0)
    h_recall = agg_by_mode["hybrid"].get(recall_key, 0.0)
    delta_recall = h_recall - d_recall
    winner = "hybrid" if delta_recall > 0.001 else ("dense" if delta_recall < -0.001 else "tie")

    report = {
        "dataset_size": len(dataset),
        "k": args.k,
        "started_at": datetime.fromtimestamp(t0, tz=timezone.utc).isoformat(),
        "elapsed_sec": round(time.time() - t0, 2),
        "dense": agg_by_mode["dense"],
        "hybrid": agg_by_mode["hybrid"],
        f"delta_{recall_key}": round(delta_recall, 6),
        "winner": winner,
        "retrieve_url": args.retrieve_url,
    }
    # 模型版本快照 (从第一次响应取, 让报告自说明跑在什么模型栈上)
    for mode in VARIANTS:
        if raws_by_mode[mode]:
            r0 = raws_by_mode[mode][0]
            report.setdefault("model_snapshot", {})[mode] = {
                "embedding_version": r0.get("embedding_version"),
                "rerank_model": r0.get("rerank_model"),
                "rerank_enabled": r0.get("rerank_enabled"),
            }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"报告已写入: {out}")
    print(f"winner by recall@{args.k}: {winner}  (dense={fmt_pct(d_recall)} / hybrid={fmt_pct(h_recall)}, delta={fmt_pct(delta_recall)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
