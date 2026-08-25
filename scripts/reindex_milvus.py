#!/usr/bin/env python3
"""安全地将当前 MySQL corpus 重建到新的 Milvus collection（蓝绿模式）。

默认仅 dry-run。本脚本从不删除、清空或覆盖 collection；目标已存在时直接拒绝。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import pymysql
import requests
from dotenv import load_dotenv
from pymilvus import DataType, Function, FunctionType, MilvusClient

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
EMBEDDING_BASE_URL = os.getenv("EMBEDDING_BASE_URL", "http://localhost:8082").rstrip("/")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")
VECTOR_DIMENSION = 1024
TEXT_MAX_BYTES = 4000
CONTEXT_PREFIX_MAX_CHARS = 120


def truncate_utf8_bytes(text: str, limit: int = TEXT_MAX_BYTES) -> str:
    raw = (text or "").encode("utf-8")
    return text or "" if len(raw) <= limit else raw[:limit].decode("utf-8", errors="ignore")


def contextual_input(row: dict) -> str:
    title = row["original_filename"] or ""
    dot = title.rfind(".")
    if 0 < dot < len(title) - 1:
        title = title[:dot]
    parts = []
    if row["source"] and row["source"] != "unknown":
        parts.append(f"来源: {row['source'].strip()}")
    if title.strip():
        parts.append(f"文档: {title.strip()}")
    section_path = row.get("section_path")
    if section_path:
        if isinstance(section_path, str):
            try:
                section_path = json.loads(section_path)
            except json.JSONDecodeError:
                section_path = []
        if section_path:
            parts.append("章节: " + " › ".join(str(item) for item in section_path))
    prefix = ("[" + " | ".join(parts) + "]\n") if parts else ""
    return prefix[:CONTEXT_PREFIX_MAX_CHARS] + (row["content"] or "")


def fetch_chunks() -> list[dict]:
    sql = """
        SELECT c.id AS chunk_id, c.document_id, c.generation, c.page, c.content,
               c.chunk_type, c.section_path, d.original_filename, d.source,
               COALESCE(d.version, '') AS version, d.logical_document_key,
               d.language, d.doc_type, d.tenant_id
        FROM chunks c JOIN documents d ON d.id = c.document_id
        WHERE d.deleted_at IS NULL AND d.status = 'INDEXED'
          AND c.generation = d.active_generation
        ORDER BY c.document_id, c.seq, c.id
    """
    conn = pymysql.connect(**MYSQL_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            cursor.execute(sql)
            return list(cursor.fetchall())
    finally:
        conn.close()


def corpus_sha256(rows: list[dict]) -> str:
    digest = hashlib.sha256()
    for row in rows:
        digest.update(f"{row['chunk_id']}\0{row['document_id']}\0".encode())
        digest.update((row["content"] or "").encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def create_collection(client: MilvusClient, name: str) -> None:
    schema = client.create_schema(
        auto_id=True, enable_dynamic_field=False,
        description="RAG doc chunks V3 blue-green rebuilt with verified BGE-M3 vectors",
    )
    schema.add_field("id", DataType.INT64, is_primary=True, auto_id=True)
    schema.add_field("dense_vector", DataType.FLOAT_VECTOR, dim=VECTOR_DIMENSION)
    schema.add_field("text", DataType.VARCHAR, max_length=TEXT_MAX_BYTES,
                     enable_analyzer=True, analyzer_params={"type": "chinese"})
    schema.add_field("sparse_bm25", DataType.SPARSE_FLOAT_VECTOR)
    schema.add_field("document_id", DataType.INT64)
    schema.add_field("ingestion_generation", DataType.INT32)
    schema.add_field("chunk_id", DataType.INT64)
    schema.add_field("page", DataType.INT32)
    schema.add_field("tenant_id", DataType.VARCHAR, max_length=32)
    schema.add_field("source", DataType.VARCHAR, max_length=32)
    schema.add_field("version", DataType.VARCHAR, max_length=16)
    schema.add_field("logical_document_key", DataType.VARCHAR, max_length=128)
    schema.add_field("language", DataType.VARCHAR, max_length=8)
    schema.add_field("doc_type", DataType.VARCHAR, max_length=16)
    schema.add_field("chunk_type", DataType.VARCHAR, max_length=16)
    schema.add_function(Function(
        name="text_to_bm25", function_type=FunctionType.BM25,
        input_field_names=["text"], output_field_names=["sparse_bm25"],
    ))
    indexes = client.prepare_index_params()
    indexes.add_index("dense_vector", index_name="dense_vector", index_type="HNSW",
                      metric_type="IP", params={"M": 16, "efConstruction": 200})
    indexes.add_index("sparse_bm25", index_name="sparse_bm25",
                      index_type="SPARSE_INVERTED_INDEX", metric_type="BM25")
    indexes.add_index("document_id", index_name="document_id", index_type="STL_SORT")
    client.create_collection(name, schema=schema, index_params=indexes)


def embed(texts: list[str], retries: int = 3) -> list[list[float]]:
    last_error = None
    for attempt in range(1, retries + 1):
        try:
            response = requests.post(
                f"{EMBEDDING_BASE_URL}/v1/embeddings",
                json={"model": EMBEDDING_MODEL, "input": texts}, timeout=180,
            )
            response.raise_for_status()
            vectors = [item["embedding"] for item in response.json()["data"]]
            if len(vectors) != len(texts) or any(len(v) != VECTOR_DIMENSION for v in vectors):
                raise ValueError("embedding 响应数量或维度不正确")
            return vectors
        except Exception as error:
            last_error = error
            if attempt < retries:
                time.sleep(attempt * 2)
    raise RuntimeError(f"embedding 连续失败 {retries} 次: {last_error}")


def cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right)) / math.sqrt(
        sum(a * a for a in left) * sum(b * b for b in right))


def verify(client: MilvusClient, name: str, rows: list[dict]) -> dict:
    count = int(client.get_collection_stats(name)["row_count"])
    sample = rows[::max(1, len(rows) // 10)][:10]
    sample_ids = [int(row["chunk_id"]) for row in sample]
    stored = {
        int(item["chunk_id"]): item["dense_vector"]
        for item in client.query(
            name, filter=f"chunk_id in [{','.join(map(str, sample_ids))}]",
            output_fields=["chunk_id", "dense_vector"], limit=len(sample_ids))
    }
    fresh = embed([contextual_input(row) for row in sample])
    similarities = [cosine(stored[row["chunk_id"]], vector) for row, vector in zip(sample, fresh)]
    return {
        "expected_rows": len(rows), "actual_rows": count, "sample_size": len(sample),
        "fresh_vs_stored_cosine_min": min(similarities),
        "fresh_vs_stored_cosine_mean": sum(similarities) / len(similarities),
        "passed": count == len(rows) and min(similarities) >= 0.999,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-collection", required=True)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--confirm", action="store_true")
    parser.add_argument("--resume", action="store_true", help="目标存在时跳过已写 chunk_id 并断点续建")
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 32:
        parser.error("--batch-size 必须在 1..32")
    if args.target_collection == os.getenv("MILVUS_COLLECTION", "documents_current"):
        parser.error("目标不得等于当前 collection；必须使用新的蓝绿 collection")

    rows = fetch_chunks()
    info = requests.get(f"{EMBEDDING_BASE_URL}/info", timeout=10).json()
    summary = {
        "target_collection": args.target_collection,
        "chunk_count": len(rows),
        "document_count": len({row["document_id"] for row in rows}),
        "corpus_sha256": corpus_sha256(rows),
        "embedding_model_config": EMBEDDING_MODEL,
        "embedding_service": info,
        "contextual_prefix_enabled": True,
        "contextual_prefix_max_chars": CONTEXT_PREFIX_MAX_CHARS,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if not args.confirm:
        print("DRY-RUN 完成；未创建 collection。加 --confirm 才执行蓝绿重建。")
        return 0

    client = MilvusClient(uri=MILVUS_URI)
    target_exists = client.has_collection(args.target_collection)
    if target_exists and not args.resume:
        raise SystemExit(f"目标 collection 已存在，拒绝覆盖；确认是本次任务后可加 --resume: {args.target_collection}")
    if not target_exists:
        create_collection(client, args.target_collection)
    existing_ids: set[int] = set()
    if target_exists:
        existing_ids = {
            int(item["chunk_id"])
            for item in client.query(
                args.target_collection, filter="chunk_id >= 0",
                output_fields=["chunk_id"], limit=16_384)
        }
    pending_rows = [row for row in rows if int(row["chunk_id"]) not in existing_ids]
    print(f"resume_existing={len(existing_ids)} pending={len(pending_rows)}", flush=True)
    started = time.time()
    for offset in range(0, len(pending_rows), args.batch_size):
        batch = pending_rows[offset:offset + args.batch_size]
        vectors = embed([contextual_input(row) for row in batch])
        payload = [{
            "dense_vector": vector,
            "text": truncate_utf8_bytes(row["content"] or ""),
            "document_id": row["document_id"], "ingestion_generation": row["generation"],
            "chunk_id": row["chunk_id"], "page": row["page"] or 0,
            "tenant_id": row["tenant_id"] or "default", "source": row["source"] or "unknown",
            "version": row["version"] or "", "logical_document_key": row["logical_document_key"] or "unknown",
            "language": row["language"] or "zh", "doc_type": row["doc_type"] or "doc",
            "chunk_type": row["chunk_type"] or "TEXT",
        } for row, vector in zip(batch, vectors)]
        client.insert(args.target_collection, payload)
        done = len(existing_ids) + min(offset + len(batch), len(pending_rows))
        if done == len(rows) or done % 80 < args.batch_size:
            print(f"progress={done}/{len(rows)} elapsed_sec={time.time() - started:.1f}", flush=True)

    client.flush(args.target_collection)
    client.load_collection(args.target_collection)
    result = {
        **summary, "created_at": datetime.now(timezone.utc).isoformat(),
        "elapsed_sec": round(time.time() - started, 2),
        "verification": verify(client, args.target_collection, rows),
    }
    manifest = args.manifest or PROJECT_ROOT / "eval" / "runs" / f"{args.target_collection}_manifest.json"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result["verification"], ensure_ascii=False, indent=2))
    print(f"manifest={manifest}")
    return 0 if result["verification"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
