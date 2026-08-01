#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C-3: 重灌 Milvus collection 数据(让 sparse_bm25 字段有真值)。

背景:
  V2-A 旧 collection schema 没有 text/BM25 Function 字段, sparse 都是占位 0L→0.0f。
  C-2a 新 schema 用 BM25 Function 自动算 sparse, 需要:
    1. 删旧 collection → 重启 app 时 MilvusCollectionInitializer 自动重建
    2. 重新 upsert 每个 chunk (填 text 字段, sparse_bm25 由 Function 自动算)
  本脚本完成第 2 步: 从 MySQL 拉 chunks → BGE-M3 embed → Milvus Python upsert。

用法(必须):
  1. 确保 Java app 已重启(新 collection schema 已建好):
     - 旧 collection 已被 MilvusCollectionInitializer 删除重建
  2. 跑本脚本:
     cd /Users/huanqi/RagDoc/rag-doc-platform
     .venv/bin/python3 scripts/reindex_milvus.py

依赖: pymysql + pymilvus 2.5.x + requests(已装)
"""
import json
import os
import re
import sys
import time
from pathlib import Path

import pymysql
import requests
from dotenv import load_dotenv
from pymilvus import MilvusClient, DataType

PROJECT_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

MYSQL_CONFIG = {
    "host": os.getenv("MYSQL_HOST", "localhost"),
    "port": int(os.getenv("MYSQL_PORT", "3307")),
    "user": os.getenv("MYSQL_USER", "root"),
    "password": os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
    "database": os.getenv("MYSQL_DATABASE", "ragdoc"),
    "charset": "utf8mb4",
}

MILVUS_URI = f"http://{os.getenv('MILVUS_HOST', 'localhost')}:{os.getenv('MILVUS_PORT', '19530')}"
MILVUS_COLLECTION = os.getenv("MILVUS_COLLECTION", "documents_v1")

EMBED_URL = os.getenv("EMBEDDING_BASE_URL", "http://localhost:8082") + "/v1/embeddings"
TEXT_MAX_LENGTH = 4000
BATCH_SIZE = 1  # TEI payload 413, 单条单条 embed 稳

# ===== TextCleaner 复刻(与 Java TextCleaner 同规则, 必须保持一致) =====
_RULES = [
    # Hugo pageinfo 整段
    re.compile(r"\{\{%[^%]*?pageinfo[^%]*?%\}\}[\s\S]*?\{\{%[^%]*?/pageinfo[^%]*?%\}\}", re.IGNORECASE),
    # Hugo 任一 shortcode
    re.compile(r"\{\{[<%][^>}]*?[>%]\}\}"),
    # Markdown 图片
    re.compile(r"!\[[^\]]*\]\([^)]+\)"),
    # HTML 标签
    re.compile(r"<[^>]+>"),
    # UUID/traceId
    re.compile(r"[0-9a-fA-F]{4,}-[0-9a-fA-F]{4,}(?:-[0-9a-fA-F]+){2,}"),
    # 裸 URL
    re.compile(r"https?://[^\s)\"'<>]+|ftp://[^\s)\"'<>]+"),
]


def clean_text(text: str) -> str:
    """复刻 Java TextCleaner.clean()。改这里时务必同步改 Java。"""
    if not text:
        return ""
    t = text
    for r in _RULES:
        t = r.sub(" ", t)
    # 代码围栏开闭标记(行首 ``` )
    t = re.sub(r"(?m)^```[^\n]*$", "", t)
    # Markdown 标题井号
    t = re.sub(r"(?m)^#{1,6}\s+", "", t)
    # HTML 实体
    for ent, ch in [("&lt;", "<"), ("&gt;", ">"), ("&amp;", "&"),
                    ("&quot;", '"'), ("&nbsp;", " "), ("&#39;", "'")]:
        t = t.replace(ent, ch)
    # 多余空白
    t = re.sub(r"\n{3,}", "\n\n", t)
    t = re.sub(r"[ \t]{2,}", " ", t)
    return t.strip()


def fetch_chunks():
    """拉所有 chunks, 按 document_id 分组"""
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT id, document_id, seq, page, content FROM chunks "
                "ORDER BY document_id, seq"
            )
            return cur.fetchall()
    finally:
        conn.close()


def embed_batch(texts):
    """批量 embed, 返回 dense 向量列表(BGE-M3 1024 维)"""
    r = requests.post(
        EMBED_URL,
        json={"input": texts},
        timeout=90,
    )
    r.raise_for_status()
    data = r.json()["data"]
    return [d["embedding"] for d in data]


def truncate(text, max_len):
    return text[:max_len] if text and len(text) > max_len else (text or "")


def main():
    print(f"[1/3] 从 MySQL 拉 chunks ...")
    chunks = fetch_chunks()
    print(f"  共 {len(chunks)} 条 chunk")

    docs = {}
    for c in chunks:
        docs.setdefault(c["document_id"], []).append(c)
    print(f"  分布在 {len(docs)} 个文档")

    client = MilvusClient(uri=MILVUS_URI)
    if not client.has_collection(MILVUS_COLLECTION):
        print(f"\n✗ collection '{MILVUS_COLLECTION}' 不存在!")
        sys.exit(1)

    # 清洗统计
    total_before_chars = sum(len(c["content"] or "") for c in chunks)
    cleaned_counter = 0

    print(f"\n[2/3] 清洗 + 重新 embed + upsert (按文档分批, batch={BATCH_SIZE}) ...")
    total_done = 0
    for doc_id, doc_chunks in docs.items():
        client.delete(
            collection_name=MILVUS_COLLECTION,
            filter=f"document_id == {doc_id}",
        )
        for i in range(0, len(doc_chunks), BATCH_SIZE):
            batch = doc_chunks[i:i + BATCH_SIZE]
            # 每条 chunk 跑清洗
            cleaned_texts = []
            skipped_empty = 0
            for c in batch:
                orig = c["content"] or ""
                cleaned = clean_text(orig)
                if len(cleaned) < len(orig):
                    cleaned_counter += 1
                # 关键: 清洗可能整段去成空(Hugo pageinfo 的 chunk 没有正文),
                # 空 text TEI 会 413 "input cannot be empty"。空就用占位非空字符串。
                if not cleaned.strip():
                    cleaned = "内容为文档元数据(模板/导航), 无正文"
                    skipped_empty += 1
                cleaned_texts.append(cleaned)
            if skipped_empty:
                print(f"    [warn] chunk 清洗后变空 占位替代 {skipped_empty} 条")

            texts = [truncate(t, TEXT_MAX_LENGTH) for t in cleaned_texts]
            embeddings = embed_batch(texts)
            rows = []
            for c, text, dense in zip(batch, texts, embeddings):
                rows.append({
                    "dense_vector": dense,
                    "text": text,
                    "document_id": doc_id,
                    "chunk_id": c["id"],
                    "page": c["page"] or 0,
                    "tenant_id": "default",
                })
            client.insert(collection_name=MILVUS_COLLECTION, data=rows)
            total_done += len(rows)
            print(f"  doc={doc_id}  done {total_done}/{len(chunks)}")
            time.sleep(0.3)

    total_after_chars = sum(len(clean_text(c["content"] or "")) for c in chunks)
    print(f"\n[3/3] ✓ 重灌完成: {total_done} 条 chunk 已写入 Milvus (含清洗)")
    print(f"  清洗统计: ")
    print(f"    原始总字符数: {total_before_chars}")
    print(f"    清洗后字符数: {total_after_chars} ({total_after_chars*100//total_before_chars}%)")
    print(f"    有清洗动作的 chunk 数: {cleaned_counter}/{len(chunks)}")
    print(f"\n下一步: 烟测 chat api 验证 hybrid search 工作, 然后 make eval-gate 跑 RAGAS 验收")


if __name__ == "__main__":
    main()
