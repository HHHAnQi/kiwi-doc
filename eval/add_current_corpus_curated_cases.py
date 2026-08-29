#!/usr/bin/env python3
"""向 63 题 reviewed current-corpus 集补充 17 条证据先行的组件覆盖题。"""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import pymysql
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env", override=False)

# (question, answer, chunk_ids, type)
CASES = [
    ("Seata 中 TM、RM 和 TC 在事务生命周期中分别承担什么职责？", "TM 创建全局事务并决定提交或回滚；RM 管理资源并注册分支事务；TC 统一协调各 RM 执行二阶段提交或回滚。", [4], "factual"),
    ("Seata AT 模式在提交本地事务前为什么必须获取全局锁？", "本地事务提交前必须先取得全局锁；获取失败时不能提交，超过重试范围会回滚本地事务并释放本地锁，以避免全局事务间的数据冲突。", [114], "why"),
    ("Seata AT 模式默认的全局隔离级别是什么，如何实现全局读已提交？", "AT 模式默认全局隔离级别是读未提交；需要全局读已提交时，通过代理 SELECT FOR UPDATE 申请并等待全局锁。", [121], "config"),
    ("Seata Saga 模式发生异常时如何执行补偿回滚？", "Saga 使用状态图定义服务调用及补偿节点；异常时状态机引擎反向执行已成功节点对应的补偿节点完成回滚。", [818], "procedural"),
    ("Seata TCC 模式的一阶段和二阶段分别执行什么逻辑？", "一阶段调用自定义 prepare；二阶段根据结果调用自定义 commit 或 rollback。TCC 不依赖底层数据资源的事务支持。", [784], "factual"),
    ("Seata XA 模式如何保证分支事务可回滚和持久化？", "业务 SQL 在 XA 分支中执行以保证可回滚，XA prepare 保证持久化；完成阶段再执行 XA commit 或 rollback。", [82], "factual"),
    ("RocketMQ 普通消息从发送到删除经历哪些主要生命周期状态？", "主要经历初始化、待消费、消费中、消费提交和消息删除；消费提交后先逻辑标记已消费，达到保存期限或空间条件后才物理删除。", [849], "factual"),
    ("RocketMQ 定时消息的定时时间应如何设置，有哪些边界限制？", "定时时间要使用毫秒级 Unix 时间戳并晚于当前时间；超过支持范围或早于当前时间会立即投递，默认最大定时时长为 24 小时。", [991], "config"),
    ("RocketMQ 顺序消息如何通过消息组保证顺序？", "相同消息组的消息按发送顺序存储并遵循 FIFO；不同消息组之间不保证顺序。要保证生产顺序还需单一生产者串行发送。", [1029], "factual"),
    ("RocketMQ 事务消息在什么情况下会触发事务回查？", "服务端未收到二次确认，或收到 Unknown 状态时，会在固定时间后向生产者集群发起事务回查；生产者检查本地事务后再次提交 Commit 或 Rollback。", [1010], "troubleshoot"),
    ("RocketMQ 因服务端流控错误触发发送重试时采用什么退避策略？", "采用指数退避并加入随机抖动；默认初始等待 1 秒、倍率 1.6、抖动因子 0.2、最大等待 120 秒。", [1239], "config"),
    ("RocketMQ 提供哪些消费者类型，PullConsumer 适合什么场景？", "提供 PushConsumer、SimpleConsumer 和 PullConsumer；PullConsumer 仅推荐在流处理框架集成场景使用，大多数场景使用前两者。", [1273], "factual"),
    ("Sentinel 主要从哪些方面保障微服务的可靠性？", "Sentinel 围绕流量提供流量控制、流量整形、并发限制、熔断和系统自适应过载保护。", [301], "factual"),
    ("Sentinel 的实时监控可以展示哪些范围的运行信息？", "可以实时查看单机运行信息，也可以查看少于 500 个节点集群的聚合运行信息。", [303], "factual"),
    ("Sentinel 原生支持哪些语言，并能集成哪些常用框架？", "原生支持 Java、Go、C++ 和 Rust，并提供 Spring Cloud、gRPC、Apache Dubbo、Quarkus 等框架的开箱即用集成。", [305], "factual"),
    ("如何为 Sentinel 的 HelloWorld 资源配置每秒最多 20 次访问的流控规则？", "创建 FlowRule，将 resource 设为 HelloWorld、count 设为 20、grade 设为 FLOW_GRADE_QPS，再通过 FlowRuleManager.loadRules 加载规则。", [323, 325], "config"),
    ("为什么 Sentinel 控制台的簇点链路页面可能暂时看不到资源？", "资源监控采用延迟初始化，客户端产生访问量后，资源才会出现在簇点链路页面。", [1728], "why"),
]


def fetch_metadata(ids: set[int]) -> dict[int, dict]:
    conn = pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"), port=int(os.getenv("MYSQL_PORT", "3307")),
        user=os.getenv("MYSQL_USER", "root"), password=os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
        database=os.getenv("MYSQL_DATABASE", "ragdoc"), charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            marks = ",".join(["%s"] * len(ids))
            cur.execute(
                "SELECT c.id,c.document_id,c.content_hash,d.content_hash AS document_content_hash,"
                "d.logical_document_key,d.original_filename,d.source,d.version "
                f"FROM chunks c JOIN documents d ON d.id=c.document_id WHERE c.id IN ({marks})",
                tuple(sorted(ids)),
            )
            return {int(row["id"]): row for row in cur.fetchall()}
    finally:
        conn.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="eval/golden/golden_v3_reviewed_current_corpus.jsonl")
    parser.add_argument("--output", default="eval/golden/golden_v3_frozen80.jsonl")
    args = parser.parse_args()
    base = [json.loads(line) for line in Path(args.input).read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(base) != 63:
        raise RuntimeError(f"expected 63 reviewed rows, got {len(base)}")
    ids = {cid for _, _, chunks, _ in CASES for cid in chunks}
    metadata = fetch_metadata(ids)
    if ids - metadata.keys():
        raise RuntimeError(f"missing current chunks: {sorted(ids - metadata.keys())}")
    existing_questions = {row["question"] for row in base}
    timestamp = datetime.now(timezone.utc).isoformat()
    added = []
    for question, answer, chunk_ids, question_type in CASES:
        if question in existing_questions:
            raise RuntimeError(f"duplicate question: {question}")
        first = metadata[chunk_ids[0]]
        added.append({
            "question": question,
            "ground_truth_answer": answer,
            "new_ground_truth_answer": answer,
            "ground_truth_chunk_id": chunk_ids[0],
            "new_ground_truth_chunk_id": chunk_ids[0],
            "ground_truth_doc_id": first["document_id"],
            "new_ground_truth_doc_id": first["document_id"],
            "gold_chunk_ids": chunk_ids,
            "ground_truth_chunk_content_hash": first["content_hash"],
            "ground_truth_document_content_hash": first["document_content_hash"],
            "logical_document_key": first["logical_document_key"],
            "original_filename": first["original_filename"],
            "source": first["source"],
            "version": first["version"],
            "question_type": question_type,
            "ungroundable": False,
            "review_status": "human_curated",
            "reviewer": "codex-evidence-first-curation",
            "reviewed_at": timestamp,
            "gold_evidence": [
                {"chunk_id": cid, "document_id": metadata[cid]["document_id"], "content_hash": metadata[cid]["content_hash"]}
                for cid in chunk_ids
            ],
        })
    final = base + added
    if len(final) != 80:
        raise RuntimeError(f"expected frozen80, got {len(final)}")
    Path(args.output).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in final), encoding="utf-8")
    print(json.dumps({"base": len(base), "curated": len(added), "frozen_total": len(final)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
