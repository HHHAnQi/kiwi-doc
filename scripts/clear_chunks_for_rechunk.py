#!/usr/bin/env python3
"""
清库脚本 - P3-A parent-child 全量重灌前用。

协同清 3 个存储:
1. Milvus collectioin documents_v1: 按 document_id filter 删向量
2. MySQL chunks 表: 按 document_id 硬删(PARENT + CHILD + TEXT 全清)
3. MySQL documents 表: 软删(deleted_at = NOW())保留记录避免上传脚本生成新 id, 同时让
   findByContentHash 必须避开已软删记录(否则幂等命中老 doc 不重切 parent-child,
   这是 sentinel 530 烟测踩的坑)

用法:
  # Dry-run(默认): 只打印将删什么, 不真删
  python3 scripts/clear_chunks_for_rechunk.py --doc-ids 556,646,719,530

  # 真删:
  python3 scripts/clear_chunks_for_rechunk.py --doc-ids 556,646,719,530 --confirm

  # 全量清(危险, 默认 dry-run):
  python3 scripts/clear_chunks_for_rechunk.py --all --confirm
"""
import argparse
import os
import sys

import pymysql
from pymilvus import MilvusClient

MYSQL_CONFIG = {
    "host": os.getenv("MYSQL_HOST", "localhost"),
    "port": int(os.getenv("MYSQL_PORT", "3307")),
    "user": os.getenv("MYSQL_USER", "root"),
    "password": os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
    "database": os.getenv("MYSQL_DATABASE", "ragdoc"),
}
MILVUS_URI = f"http://{os.getenv('MILVUS_HOST', 'localhost')}:{os.getenv('MILVUS_PORT', '19530')}"
MILVUS_COLLECTION = os.getenv("MILVUS_COLLECTION", "documents_v1")


def list_docs(conn, doc_ids):
    """返回每个 doc 当前的 chunks 分布 + documents 元信息, 供 dry-run 决策。"""
    with conn.cursor(pymysql.cursors.DictCursor) as cur:
        if doc_ids:
            ph = ",".join(["%s"] * len(doc_ids))
            cur.execute(
                f"SELECT document_id, chunk_type, COUNT(*) AS cnt, "
                f"ROUND(AVG(CHAR_LENGTH(content))) AS avg_chars "
                f"FROM chunks WHERE document_id IN ({ph}) "
                f"GROUP BY document_id, chunk_type ORDER BY document_id, chunk_type",
                doc_ids,
            )
        else:
            cur.execute(
                "SELECT document_id, chunk_type, COUNT(*) AS cnt, "
                "ROUND(AVG(CHAR_LENGTH(content))) AS avg_chars "
                "FROM chunks GROUP BY document_id, chunk_type "
                "ORDER BY document_id, chunk_type"
            )
        chunk_stats = cur.fetchall()

        if doc_ids:
            cur.execute(
                f"SELECT id, original_filename, source, status, deleted_at "
                f"FROM documents WHERE id IN ({ph})",
                doc_ids,
            )
        else:
            cur.execute(
                "SELECT id, original_filename, source, status, deleted_at FROM documents"
            )
        doc_meta = cur.fetchall()
    return chunk_stats, doc_meta


def count_milvus_chunks(client, doc_ids):
    """Milvus 按 document_id filter 计数(对照 MySQL chunks 是否一致)。"""
    stats = {}
    if doc_ids:
        for did in doc_ids:
            res = client.query(
                collection_name=MILVUS_COLLECTION,
                filter=f"document_id == {did}",
                output_fields=["chunk_id"],
                limit=16384,
            )
            stats[did] = len(res)
    else:
        res = client.query(
            collection_name=MILVUS_COLLECTION,
            filter="document_id >= 0",
            output_fields=["chunk_id", "document_id"],
            limit=16384,
        )
        # 按 doc 聚合
        from collections import Counter

        c = Counter(r["document_id"] for r in res)
        stats = dict(c)
    return stats


def clear_doc(conn, client, doc_ids, confirm):
    """真正的清库操作: MySQL chunks 删 + documents 软删 + Milvus 删。"""
    actions = []
    try:
        with conn.cursor() as cur:
            if doc_ids:
                ph = ",".join(["%s"] * len(doc_ids))
                cur.execute(f"DELETE FROM chunks WHERE document_id IN ({ph})", doc_ids)
                actions.append(("MySQL.chunks DELETE", cur.rowcount))
                # 注意: DocumentStatus 枚举只有 UPLOADED/PARSING/READY/FAILED, 没有 DELETED。
                # 软删只动 deleted_at 列, status 保持原值(让 DocumentUploadService.reactivate
                # 能正确把 status 从 READY/FAILED 通过 reactivate 路径转回 UPLOADED→PARSING)。
                cur.execute(
                    f"UPDATE documents SET deleted_at = NOW() "
                    f"WHERE id IN ({ph}) AND deleted_at IS NULL",
                    doc_ids,
                )
                actions.append(("MySQL.documents soft-delete", cur.rowcount))
            else:
                cur.execute("DELETE FROM chunks")
                actions.append(("MySQL.chunks DELETE ALL", cur.rowcount))
                cur.execute(
                    "UPDATE documents SET deleted_at = NOW(), status = 'DELETED' "
                    "WHERE deleted_at IS NULL"
                )
                actions.append(("MySQL.documents soft-delete", cur.rowcount))
            if confirm:
                conn.commit()
            else:
                conn.rollback()

        # Milvus(单独每 doc delete, 失败可单独重试)
        if doc_ids:
            for did in doc_ids:
                client.delete(
                    collection_name=MILVUS_COLLECTION, filter=f"document_id == {did}"
                )
                actions.append((f"Milvus delete doc={did}", "ok"))
        else:
            res = client.query(
                collection_name=MILVUS_COLLECTION,
                filter="document_id >= 0",
                output_fields=["chunk_id"],
                limit=16384,
            )
            chunk_ids = [r["chunk_id"] for r in res]
            if chunk_ids:
                # Milvus delete by filter expression(全部 doc)
                client.delete(
                    collection_name=MILVUS_COLLECTION, filter="document_id >= 0"
                )
            actions.append((f"Milvus delete all({len(chunk_ids)} chunks)", "ok"))
    except Exception as e:
        if confirm:
            conn.rollback()
        raise
    return actions


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--doc-ids", help="逗号分隔 document_id 列表; 不传 = 所有 doc")
    p.add_argument("--all", action="store_true", help="清全库(默认会 dry-run, --confirm 才真删)")
    p.add_argument("--confirm", action="store_true", help="真删(不加只 dry-run 打印)")
    args = p.parse_args()

    if not args.all and not args.doc_ids:
        print("✗ 必须指定 --doc-ids 或 --all")
        sys.exit(2)
    if args.doc_ids:
        try:
            doc_ids = [int(x.strip()) for x in args.doc_ids.split(",") if x.strip()]
        except ValueError:
            print("✗ --doc-ids 必须是逗号分隔整数")
            sys.exit(2)
        if not doc_ids:
            print("✗ --doc-ids 为空")
            sys.exit(2)
    else:
        doc_ids = None  # 全库

    print(f"\n=== {'CONFIRM 真删' if args.confirm else 'DRY-RUN (不真删)'} mode ===")
    print(f"目标 docs: {doc_ids if doc_ids else '全部'}\n")

    conn = pymysql.connect(**MYSQL_CONFIG)
    client = MilvusClient(uri=MILVUS_URI)
    if not client.has_collection(MILVUS_COLLECTION):
        print(f"✗ Milvus collection '{MILVUS_COLLECTION}' 不存在")
        sys.exit(1)

    chunk_stats, doc_meta = list_docs(conn, doc_ids)
    print("--- MySQL chunks 现状 ---")
    for r in chunk_stats:
        print(f"  doc={r['document_id']:5} type={r['chunk_type']:8} cnt={r['cnt']:4} avg_chars={r['avg_chars']}")
    if not chunk_stats:
        print("  (chunks 表无匹配行, 可能本来就清了)")

    print("\n--- documents 元信息 ---")
    for r in doc_meta:
        print(f"  doc={r['id']:5} src={r['source']:8} status={r['status']:8} "
              f"fn={(r['original_filename'] or '')[:40]:40} deleted_at={r['deleted_at']}")

    milvus_stats = count_milvus_chunks(client, doc_ids)
    print("\n--- Milvus 索引现状(per doc chunk 计数) ---")
    for did, cnt in (sorted(milvus_stats.items()) if isinstance(milvus_stats, dict) else []):
        print(f"  doc={did:5} milvus_chunks={cnt}")
    if not milvus_stats:
        print("  (Milvus 无匹配行)")

    if not args.confirm:
        print("\n=== DRY-RUN 完成, 不做任何修改. 加 --confirm 真删 ===")
        return

    print("\n=== 开始清库 ===")
    actions = clear_doc(conn, client, doc_ids, args.confirm)
    for label, n in actions:
        print(f"  ✓ {label}: {n}")
    print("\n=== 清库完成 ===")


if __name__ == "__main__":
    main()
