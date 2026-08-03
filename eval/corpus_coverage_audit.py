#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 0.7 — corpus 覆盖度审计。

题库 golden.jsonl 的 ground_truth_chunk_id 是早期 corpus 时代的 id,
现在的 corpus 经过多次重建, chunk_id 已变。本工具:
  1. 扫所有题, 检查 ground_truth_chunk_id 在当前 chunks 表是否存在
  2. 进一步检查 ground_truth_doc_id 在 documents 表是否存在(粒度粗一些)
  3. 输出 corpus_covered_subset.jsonl (题库 + has_chunk_id + has_doc_id 两个 boolean)
  4. 输出 corpus_coverage_audit.md 报告(总数 / 覆盖率 / question_type 分布)

用法:
  python3 eval/corpus_coverage_audit.py
"""
from __future__ import annotations

import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
try:
    from dotenv import load_dotenv
    load_dotenv(EVAL_DIR.parent / ".env", override=False)
except ImportError:
    pass

GOLDEN = EVAL_DIR / "golden" / "golden.with_labels.jsonl"
OUT_JSONL = EVAL_DIR / "golden" / "corpus_covered_subset.jsonl"
OUT_REPORT = EVAL_DIR / "corpus_coverage_audit.md"

DB_HOST = os.getenv("MYSQL_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("MYSQL_PORT", "3307"))
DB_USER = os.getenv("MYSQL_USER", "root")
DB_PASS = os.getenv("MYSQL_ROOT_PASSWORD", os.getenv("MYSQL_PASSWORD", "rootpass"))
DB_NAME = os.getenv("MYSQL_DATABASE", "ragdoc")


def _fetch_existence(chunk_ids: set[int], doc_ids: set[int]) -> tuple[set[int], set[int]]:
    """返回 (存在的 chunk_id 集, 存在的 doc_id 集)。"""
    import pymysql
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS,
                           database=DB_NAME, charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            if chunk_ids:
                placeholders = ",".join(["%s"] * len(chunk_ids))
                cur.execute(f"SELECT DISTINCT id FROM chunks WHERE id IN ({placeholders})",
                            tuple(chunk_ids))
                chunk_present = {r[0] for r in cur.fetchall()}
            else:
                chunk_present = set()
            if doc_ids:
                placeholders = ",".join(["%s"] * len(doc_ids))
                cur.execute(f"SELECT DISTINCT id FROM documents WHERE id IN ({placeholders})",
                            tuple(doc_ids))
                doc_present = {r[0] for r in cur.fetchall()}
            else:
                doc_present = set()
    finally:
        conn.close()
    return chunk_present, doc_present


def main() -> int:
    items = [json.loads(l) for l in GOLDEN.read_text(encoding="utf-8").splitlines() if l.strip()]
    print(f"[audit] 题库 {len(items)} 题")

    # 批量查存在性
    chunk_ids = {d["ground_truth_chunk_id"] for d in items if d.get("ground_truth_chunk_id") is not None}
    doc_ids = {d["ground_truth_doc_id"] for d in items if d.get("ground_truth_doc_id") is not None}
    print(f"  unique chunk_ids: {len(chunk_ids)}, doc_ids: {len(doc_ids)}")

    chunk_present, doc_present = _fetch_existence(chunk_ids, doc_ids)
    print(f"  chunk 存在: {len(chunk_present)}/{len(chunk_ids)} ({len(chunk_present)/max(1,len(chunk_ids))*100:.1f}%)")
    print(f"  doc 存在:   {len(doc_present)}/{len(doc_ids)} ({len(doc_present)/max(1,len(doc_ids))*100:.1f}%)")

    # 打标
    out_items = []
    by_type_total = Counter()
    by_type_covered_chunk = Counter()
    by_type_covered_doc = Counter()
    for d in items:
        qt = d.get("question_type", "unknown")
        has_c = d.get("ground_truth_chunk_id") in chunk_present
        has_d = d.get("ground_truth_doc_id") in doc_present
        d["has_chunk"] = has_c
        d["has_doc"] = has_d
        out_items.append(d)
        by_type_total[qt] += 1
        if has_c:
            by_type_covered_chunk[qt] += 1
        if has_d:
            by_type_covered_doc[qt] += 1

    # 落 corpus_covered_subset.jsonl
    with open(OUT_JSONL, "w", encoding="utf-8") as f:
        for d in out_items:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    print(f"\n✓ 落盘 {OUT_JSONL}")

    # 生成报告
    total = len(items)
    c_chunk = sum(1 for d in out_items if d["has_chunk"])
    c_doc = sum(1 for d in out_items if d["has_doc"])
    md = [
        "# Corpus 覆盖度审计报告\n",
        f"\n> 题库: `{GOLDEN.name}` ({total} 题)\n",
        f"\n## 总覆盖\n\n",
        f"| 维度 | 覆盖数 | 覆盖率 |\n|---|---|---|\n",
        f"| chunk_id 精确匹配 | {c_chunk}/{total} | **{c_chunk/total*100:.1f}%** |\n",
        f"| doc_id 同文档(粗粒度) | {c_doc}/{total} | {c_doc/total*100:.1f}% |\n",
        "\n## 按 question_type 分布\n\n",
        "| type | 总题数 | chunk覆盖 | doc覆盖 |\n|---|---|---|---|\n",
    ]
    for t in sorted(by_type_total.keys()):
        md.append(f"| {t} | {by_type_total[t]} | "
                  f"{by_type_covered_chunk[t]} ({by_type_covered_chunk[t]/by_type_total[t]*100:.0f}%) | "
                  f"{by_type_covered_doc[t]} ({by_type_covered_doc[t]/by_type_total[t]*100:.0f}%) |\n")
    md.append(f"\n## 后续\n\n")
    md.append(f"- `corpus_covered_subset.jsonl` 含 has_chunk / has_doc 双 boolean, 可直接拿来过滤\n")
    md.append(f"- 30 题抽样应只从 `has_chunk=true` 的 {c_chunk} 题里取, 避免超 corpus 题拉低均值\n")
    OUT_REPORT.write_text("".join(md), encoding="utf-8")
    print(f"✓ 落盘 {OUT_REPORT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
