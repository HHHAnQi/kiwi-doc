#!/usr/bin/env python3
"""把 2026-08-25 人工复核结论合并为 current-corpus 冻结集。

复核规则：问题、答案必须由指定当前 chunk 直接支持；需要改正旧答案时显式写在
REVIEWED 中。未通过项保留在 rejected 文件，不参与指标。
"""
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

# question -> (gold chunk ids, corrected answer or None)
REVIEWED: dict[str, tuple[list[int], str | None]] = {
    "在配置 Dubbo 应用时，如何指定配置作用的应用粒度？": ([502], None),
    "Nacos 控制台如何帮助用户管理服务流量权重？": ([1421], None),
    "在Nacos中，如何定义实例的权重以及其作用？": ([1421], None),
    "在Spring Cloud Alibaba中，如何修改Dubbo服务的超时时间？": ([500], None),
    "如何在 Spring Cloud Alibaba 中禁用特定的提供者实例？": ([467], "通过覆盖规则为目标提供者设置 disabled=true，可临时禁用该提供者实例。"),
    "如何在Nacos中修改服务实例的流量权重？": ([1421], None),
    "在 Spring Cloud Alibaba 中，如何使用 XML 配置来创建服务消费者的示例？": ([1118], "加载 /META-INF/spring/dubbo-consumer-context.xml，刷新 Spring 上下文并取得 DemoService Bean，即可启动 XML 配置的服务消费者。"),
    "在Spring Cloud Alibaba中，如何判断平滑加权轮询算法是否适合用户有加权轮询需求的情况？": ([1684], None),
    "Dubbo 如何实现从服务器端调用客户端逻辑？": ([382], None),
    "在 Spring Cloud Alibaba 中，如何使业务类在 Nacos 配置变更时自动重建以加载最新配置？": ([1426], "在业务类上添加 @RefreshScope，配置变化后 Bean 会刷新并加载新配置。"),
    "如何在 Spring Cloud Alibaba 中通过 XML 配置实现点对点直连服务提供者？": ([961], None),
    "Spring Cloud Alibaba 2.7.5 版本引入的线程池模型相比老版本有哪些改进？": ([605], None),
    "在 Spring Cloud Alibaba 中，如何配置远程服务的 Stub？": ([1850], "在 Spring 配置中为服务设置 stub 实现类，例如将 stub 配置为 com.foo.BarServiceStub。"),
    "在 Spring Cloud Alibaba 中，如何实现一个远程服务的代理？": ([961], "使用 dubbo:reference 声明服务接口和提供者 URL，Dubbo 会据此创建远程服务代理。"),
    "在 Spring Cloud Alibaba 中，如何使用 RpcContext 进行隐式参数传递？": ([8], None),
    "配置 Dubbo 端口时，可以在哪些配置文件中设置？": ([1085], None),
    "为什么在 Spring Cloud Alibaba 中获取客户端隐式传入的参数不建议常规业务使用？": ([12], None),
    "如何配置 Dubbo 协议以实现延迟连接？": ([1566], "延迟连接用于减少长连接数，只有在调用发起时才创建长连接。"),
    "覆盖规则是从哪个版本开始支持从服务和应用两个粒度来调整动态配置的？": ([497], None),
    "BarServiceStub 的构造函数接收什么参数？": ([1852], "BarServiceStub 的构造函数接收真正的远程 BarService 代理对象。"),
    "如何在 Spring Cloud Alibaba 中使用 Nacos 集群模式？": ([2703], "Nacos 集群模式可通过 Raft 组织节点；生产部署建议使用 MySQL 数据库，并按集群部署文档启动节点。"),
    "Dubbo 微服务支持哪些部署架构？": ([115], None),
    "如何配置广播调用失败后停止调用其他节点的比例？": ([976], None),
    "在Spring Cloud Alibaba中，如何配置一个服务提供端使其调用需要鉴权认证通过？": ([63], None),
    "在 Spring Cloud Alibaba 中，如何使用 `force:` 和 `fail:` 来控制 Mock 行为？": ([1771], None),
    "在Nacos中，如何使用配置订阅者来监听配置变更并检查变更是否已推送到客户端？": ([1450], "使用配置订阅者查询监听者，并比较客户端当前配置的 MD5 校验值，可检查配置变更是否已推送到客户端。"),
    "如何在 Spring Cloud Alibaba 中限制客户端服务使用的连接数？": ([418], None),
    "在生产环境中，使用 Spring Cloud Alibaba Nacos 时，建议采用哪种数据源配置？": ([3037], "生产环境推荐 Nacos 集群模式并默认使用外置数据库，以获得高可用、高扩展和高并发能力。"),
    "Dubbo3 相比 Dubbo2 在哪些方面进行了升级？": ([75], None),
    "如何指定 Dubbo 的线程堆栈导出路径？": ([775, 776], None),
    "如何在 Spring Cloud Alibaba 中开启访问日志？": ([772], "设置 dubbo:protocol 的 accesslog 属性；属性可指定日志文件路径，同时应关注日志量和磁盘容量。"),
    "Nacos支持哪些类型的构建物？": ([194], None),
    "在 Spring Cloud Alibaba 中，如何确保配置集的 Data ID 具有全局唯一性？": ([2577], None),
    "在 Nacos 中，配置分组的作用是什么？": ([564], None),
    "Dubbo 的扩展能力体现在哪些方面？": ([95], None),
    "在Spring Cloud Alibaba中，如何设置服务的客户端的`loadbalance`属性为`leastactive`？": ([425], None),
    "在 Dubbo 中如何实现分布式事务的支持？": ([599], None),
    "在Spring Cloud Alibaba中，如何使用异步方式进行Dubbo服务调用？": ([60], None),
    "Dubbo3 如何支持云原生微服务架构？": ([115], None),
    "Dubbo 如何支持 Kubernetes 平台调度？": ([123], None),
    "在 Nacos 中，配置变更历史和服务标签是如何关联的？": ([191], None),
    "如何将访问日志输出到指定的文件？": ([772], None),
}


def chunk_metadata(ids: set[int]) -> dict[int, dict]:
    conn = pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"), port=int(os.getenv("MYSQL_PORT", "3307")),
        user=os.getenv("MYSQL_USER", "root"), password=os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
        database=os.getenv("MYSQL_DATABASE", "ragdoc"), charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            placeholders = ",".join(["%s"] * len(ids))
            cur.execute(
                "SELECT c.id,c.document_id,c.content_hash,d.content_hash AS document_content_hash,"
                "d.logical_document_key,d.original_filename,d.source,d.version "
                f"FROM chunks c JOIN documents d ON d.id=c.document_id WHERE c.id IN ({placeholders})",
                tuple(sorted(ids)),
            )
            return {int(row["id"]): row for row in cur.fetchall()}
    finally:
        conn.close()


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--accepted", default="eval/golden/golden_v3_current_corpus.jsonl")
    parser.add_argument("--review", default="eval/golden/golden_v3_needs_review.jsonl")
    parser.add_argument("--output", default="eval/golden/golden_v3_reviewed_current_corpus.jsonl")
    parser.add_argument("--rejected-output", default="eval/golden/golden_v3_rejected.jsonl")
    args = parser.parse_args()

    accepted = [json.loads(line) for line in Path(args.accepted).read_text(encoding="utf-8").splitlines() if line.strip()]
    pending = [json.loads(line) for line in Path(args.review).read_text(encoding="utf-8").splitlines() if line.strip()]
    all_ids = {cid for ids, _ in REVIEWED.values() for cid in ids}
    metadata = chunk_metadata(all_ids)
    missing = sorted(all_ids - metadata.keys())
    if missing:
        raise RuntimeError(f"reviewed chunk ids missing from current corpus: {missing}")

    timestamp = datetime.now(timezone.utc).isoformat()
    reviewed_rows, rejected = [], []
    for row in pending:
        decision = REVIEWED.get(row["question"])
        if not decision:
            row["review_status"] = "rejected"
            row["review_reason"] = "question/answer is not directly supported by current corpus evidence"
            rejected.append(row)
            continue
        ids, corrected_answer = decision
        first = metadata[ids[0]]
        row.update({
            "gold_chunk_ids": ids,
            "new_ground_truth_chunk_id": ids[0],
            "new_ground_truth_doc_id": first["document_id"],
            "ground_truth_chunk_content_hash": first["content_hash"],
            "ground_truth_document_content_hash": first["document_content_hash"],
            "logical_document_key": first["logical_document_key"],
            "original_filename": first["original_filename"],
            "source": first["source"],
            "version": first["version"],
            "review_status": "reviewed",
            "reviewer": "codex-manual-evidence-review",
            "reviewed_at": timestamp,
            "remap_status": "human_reviewed",
        })
        if corrected_answer:
            row["new_ground_truth_answer"] = corrected_answer
            row["answer_correction_reason"] = "old answer was not fully supported by current corpus"
        row["gold_evidence"] = [
            {"chunk_id": cid, "document_id": metadata[cid]["document_id"], "content_hash": metadata[cid]["content_hash"]}
            for cid in ids
        ]
        reviewed_rows.append(row)

    for row in accepted:
        row.setdefault("gold_chunk_ids", [row["new_ground_truth_chunk_id"]])
        row.setdefault("review_status", "high_confidence_auto_accepted")
    final_rows = accepted + reviewed_rows
    write_jsonl(Path(args.output), final_rows)
    write_jsonl(Path(args.rejected_output), rejected)
    print(json.dumps({"auto_accepted": len(accepted), "human_reviewed": len(reviewed_rows), "frozen_total": len(final_rows), "rejected": len(rejected)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
