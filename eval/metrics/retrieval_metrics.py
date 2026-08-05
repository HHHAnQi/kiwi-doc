"""检索质量指标 — 纯函数, 不依赖网络/检索系统。

每个 `_query` 函数输入 (retrieved_ids 顺序列表, gold_ids 集合, k), 返回单个 float。
`aggregate` 把一组 per-query dict 算宏平均 (每指标独立, 跳过缺失 key)。

约定:
- retrieved_ids: 模型返回的 chunk_id 顺序列表 (rank 0..n-1, score 已降序排过)
- gold_ids: ground-truth chunk_id 集合 (list/set 均可)
- k: cutoff, 若 retrieved 短于 k 则按实际长度算 (与 RAGAS/IR 评测惯例一致)

不依赖任何外部库, 方便在 CI/纯单元测试里跑。
"""
from __future__ import annotations

import math
from typing import Iterable, Mapping


def recall_at_k(retrieved_ids: Iterable[int], gold_ids: Iterable[int], k: int) -> float:
    """Recall@K = |gold ∩ retrieved[:k]| / |gold|。|gold|=0 → 0。"""
    gold = set(gold_ids)
    if not gold or k <= 0:
        return 0.0
    top = list(retrieved_ids)[:k]
    hits = sum(1 for cid in top if cid in gold)
    return hits / len(gold)


def precision_at_k(retrieved_ids: Iterable[int], gold_ids: Iterable[int], k: int) -> float:
    """Precision@K = |gold ∩ retrieved[:k]| / k。k<=0 → 0。"""
    if k <= 0:
        return 0.0
    gold = set(gold_ids)
    top = list(retrieved_ids)[:k]
    hits = sum(1 for cid in top if cid in gold)
    return hits / k


def hit_rate(retrieved_ids: Iterable[int], gold_ids: Iterable[int], k: int) -> float:
    """HitRate@K: top-k 内是否命中任一 gold (1.0 / 0.0)。"""
    if k <= 0:
        return 0.0
    gold = set(gold_ids)
    if not gold:
        return 0.0
    top = list(retrieved_ids)[:k]
    return 1.0 if any(cid in gold for cid in top) else 0.0


def mrr(retrieved_ids: Iterable[int], gold_ids: Iterable[int], k: int | None = None) -> float:
    """MRR = 1/rank_of_first_relevant (1-based)。k=None 时不截断。无命中 → 0。"""
    gold = set(gold_ids)
    if not gold:
        return 0.0
    ids = list(retrieved_ids)
    limit = len(ids) if k is None else min(k, len(ids))
    for i in range(limit):
        if ids[i] in gold:
            return 1.0 / (i + 1)
    return 0.0


def ndcg_at_k(retrieved_ids: Iterable[int], gold_ids: Iterable[int], k: int) -> float:
    """NDCG@K (二元相关性: gold=1 / 非 gold=0)。

    DCG@k  = Σ rel_i / log2(i+2), i 从 0 起
    IDCG@k = 理想排序的 DCG (相关全排前)
    无相关命中 → 0。
    """
    if k <= 0:
        return 0.0
    gold = set(gold_ids)
    if not gold:
        return 0.0
    top = list(retrieved_ids)[:k]
    dcg = sum(1.0 / math.log2(i + 2) for i, cid in enumerate(top) if cid in gold)
    ideal_n = min(len(gold), k)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(ideal_n))
    return dcg / idcg if idcg > 0 else 0.0


def per_query_metrics(retrieved_ids: Iterable[int], gold_ids: Iterable[int], k: int) -> dict:
    """一次算齐 5 个指标, 返回 dict — 减少 runner 调用样板。"""
    rids = list(retrieved_ids)
    gids = list(gold_ids)
    return {
        f"recall@{k}": recall_at_k(rids, gids, k),
        f"precision@{k}": precision_at_k(rids, gids, k),
        f"hit_rate@{k}": hit_rate(rids, gids, k),
        f"mrr@{k}": mrr(rids, gids, k),
        f"ndcg@{k}": ndcg_at_k(rids, gids, k),
    }


def aggregate(per_query: list[dict]) -> dict:
    """宏平均所有 per_query dict 的所有同名 key。

    - 空列表 → {}
    - 单条 per_query 缺某 key 不影响其它 key (取并集)
    """
    if not per_query:
        return {}
    keys: set[str] = set()
    for d in per_query:
        keys.update(d.keys())
    out = {}
    for key in keys:
        vals = [float(d[key]) for d in per_query if key in d and d[key] is not None]
        out[key] = sum(vals) / len(vals) if vals else 0.0
    return out
