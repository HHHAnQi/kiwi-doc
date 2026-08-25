#!/usr/bin/env python3
"""把历史问答金标重新定位到当前 corpus，避免重入库后的 chunk_id 漂移。

候选生成只使用本地全文字符 BM25，精排查询使用“问题 + 已有参考答案”；它不使用
待评测的 question-only 检索结果，因此不会把被测系统的 Top-1 循环标成金标。
输出同时保存 chunk/document content hash，后续可在评测前检测 corpus 漂移。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import pymysql
import requests
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env", override=False)


def tokens(text: str) -> list[str]:
    text = re.sub(r"\s+", "", (text or "").lower())
    chinese = "".join(re.findall(r"[\u4e00-\u9fff]", text))
    grams = [chinese[i : i + 2] for i in range(max(0, len(chinese) - 1))]
    terms = re.findall(r"[a-z0-9_.:/=-]{2,}", text)
    return grams + terms


def load_chunks() -> list[dict]:
    conn = pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3307")),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
        database=os.getenv("MYSQL_DATABASE", "ragdoc"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT c.id, c.document_id, c.content, c.content_hash, "
                "d.content_hash AS document_content_hash, d.logical_document_key, "
                "d.original_filename, d.source, d.version "
                "FROM chunks c JOIN documents d ON d.id=c.document_id "
                "WHERE d.status='INDEXED' AND d.deleted_at IS NULL"
            )
            return list(cur.fetchall())
    finally:
        conn.close()


class CharBm25:
    def __init__(self, chunks: list[dict]):
        self.term_freqs = [Counter(tokens(c["content"])) for c in chunks]
        self.lengths = [sum(tf.values()) for tf in self.term_freqs]
        self.avg_len = sum(self.lengths) / max(1, len(self.lengths))
        df = Counter()
        for tf in self.term_freqs:
            df.update(tf.keys())
        n = len(chunks)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def top(self, query: str, limit: int) -> list[tuple[int, float]]:
        q_terms = Counter(tokens(query))
        scored = []
        k1, b = 1.5, 0.75
        for idx, tf in enumerate(self.term_freqs):
            dl = self.lengths[idx]
            score = 0.0
            for term, q_weight in q_terms.items():
                freq = tf.get(term, 0)
                if not freq:
                    continue
                denom = freq + k1 * (1 - b + b * dl / max(1.0, self.avg_len))
                score += min(q_weight, 3) * self.idf.get(term, 0.0) * freq * (k1 + 1) / denom
            if score:
                scored.append((idx, score))
        return sorted(scored, key=lambda pair: pair[1], reverse=True)[:limit]


def rerank(base_url: str, query: str, candidates: list[dict], top_n: int = 5) -> list[dict]:
    response = requests.post(
        base_url.rstrip("/") + "/rerank",
        json={
            "query": query[:2000],
            "documents": [(c["content"] or "")[:4000] for c in candidates],
            "top_n": min(top_n, len(candidates)),
        },
        timeout=120,
    )
    response.raise_for_status()
    results = []
    for item in response.json().get("results", []):
        idx = int(item["index"])
        candidate = candidates[idx]
        results.append(
            {
                "chunk_id": candidate["id"],
                "document_id": candidate["document_id"],
                "score": float(item.get("relevance_score", 0.0)),
                "content_hash": candidate["content_hash"],
                "document_content_hash": candidate["document_content_hash"],
                "logical_document_key": candidate["logical_document_key"],
                "original_filename": candidate["original_filename"],
                "source": candidate["source"],
                "version": candidate["version"],
                "snippet": (candidate["content"] or "")[:500],
            }
        )
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="eval/golden/golden_v2_grounded.jsonl")
    parser.add_argument("--output", default="eval/golden/golden_v3_current_corpus.jsonl")
    parser.add_argument("--review-output", default="eval/golden/golden_v3_needs_review.jsonl")
    parser.add_argument("--rerank-url", default="http://localhost:18080")
    parser.add_argument("--candidate-pool", type=int, default=200)
    parser.add_argument("--min-score", type=float, default=0.70)
    parser.add_argument("--min-margin", type=float, default=0.15)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    rows = [json.loads(line) for line in Path(args.input).read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit:
        rows = rows[: args.limit]
    chunks = load_chunks()
    search = CharBm25(chunks)
    accepted, review = [], []
    started = time.time()
    timestamp = datetime.now(timezone.utc).isoformat()

    for number, row in enumerate(rows, 1):
        answer = row.get("new_ground_truth_answer") or row.get("ground_truth_answer") or row.get("answer_short") or ""
        label_query = f"问题：{row['question']}\n参考答案：{answer}"
        local_hits = search.top(label_query, args.candidate_pool)
        candidates = [chunks[idx] for idx, _ in local_hits]
        # 参考答案只用于扩大本地候选覆盖；cross-encoder 仍按真实问题判相关。
        # 否则“参考答案：...”会让 v2-m3 分数饱和，无法区分只碰巧包含答案词的片段。
        ranked = rerank(args.rerank_url, row["question"], candidates)
        top_score = ranked[0]["score"] if ranked else 0.0
        second_score = ranked[1]["score"] if len(ranked) > 1 else 0.0
        margin = top_score - second_score
        status = "accepted" if top_score >= args.min_score and margin >= args.min_margin else "needs_review"
        out = dict(row)
        out.update(
            {
                "new_ground_truth_chunk_id": ranked[0]["chunk_id"] if ranked else None,
                "new_ground_truth_doc_id": ranked[0]["document_id"] if ranked else None,
                "ground_truth_chunk_content_hash": ranked[0]["content_hash"] if ranked else None,
                "ground_truth_document_content_hash": ranked[0]["document_content_hash"] if ranked else None,
                "logical_document_key": ranked[0]["logical_document_key"] if ranked else None,
                "original_filename": ranked[0]["original_filename"] if ranked else None,
                "source": ranked[0]["source"] if ranked else None,
                "version": ranked[0]["version"] if ranked else None,
                "ungroundable": not bool(ranked),
                "remap_status": status,
                "remap_top1_score": round(top_score, 6),
                "remap_margin": round(margin, 6),
                "remap_candidates": ranked,
                "remap_method": "local_char_bm25_answer_aware_candidates_then_question_only_bge_reranker_v2_m3",
                "remap_at": timestamp,
            }
        )
        (accepted if status == "accepted" else review).append(out)
        print(f"[{number}/{len(rows)}] {status} chunk={out['new_ground_truth_chunk_id']} score={top_score:.4f} margin={margin:.4f}", flush=True)

    for path, data in ((Path(args.output), accepted), (Path(args.review_output), review)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in data), encoding="utf-8")
    print(json.dumps({"total": len(rows), "accepted": len(accepted), "needs_review": len(review), "elapsed_sec": round(time.time()-started, 2)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
