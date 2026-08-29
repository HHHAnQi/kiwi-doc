#!/usr/bin/env python3
"""基于双阶段证据审计生成 current-corpus generation-valid v4 冻结集。"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


# 原问题 -> (证据可回答的新问题, 证据可完整支持的新答案)
REPLACEMENTS = {
    "Spring Cloud Alibaba 中如何使用 JVM 启动参数实现点对点直连服务提供者？": (
        "Dubbo 点对点直连以什么粒度配置，对其他服务接口有什么影响？",
        "点对点直连以服务接口为单位配置，并忽略注册中心的提供者列表；A 接口配置点对点不会影响 B 接口继续从注册中心获取提供者列表。",
    ),
    "在容量评估时，单节点 Nacos 支持多少级服务实例与配置？": (
        "Nacos2 三节点集群服务发现性能测试的容量和核心接口 TPS 达到了什么水平？",
        "压测时服务及实例容量达到百万级并持续稳定；注册/注销实例 TPS 超过 26000，查询实例 TPS 超过 30000。该测试只覆盖临时实例和单核心接口。",
    ),
    "Spring Cloud Alibaba 的哪个版本开始支持服务降级功能？": (
        "spring-cloud-alibaba-seata 以 2.2.0.RELEASE 为分界点，内部依赖发生了什么变化？",
        "2.2.0.RELEASE 之前内部依赖 seata-all；从 2.2.0.RELEASE 起内部改为依赖 seata-spring-boot-starter，并由后者提供 Seata 的自动配置。",
    ),
    "在 Spring Cloud Alibaba 中，如何实现 Dubbo 服务消费者的 Callback 接口？": (
        "使用 XML 配置 Dubbo 服务提供者并以 Nacos 为注册中心时，需要声明哪些核心配置？",
        "需要声明应用名、Nacos 注册中心地址、Dubbo 协议、要暴露的服务接口及其实现 Bean；示例中协议端口为 -1，服务版本为 2.0.0。",
    ),
    "在 Spring Cloud Alibaba 中，泛接口实现方式主要用于什么场景？": (
        "在 Spring Cloud Alibaba 中，泛接口实现方式主要用于什么场景？",
        "泛接口实现主要用于服务端没有 API 接口及模型类元的场景，POJO 参数和返回值都用 Map 表示，常用于通用远程服务 Mock 等框架集成。",
    ),
    "在上述 Map 中，如何指定 Person 对象的实现类？": (
        "泛化调用的 Map 参数中，如何显式指定接口类型对象的实现类？",
        "在 Map 中加入 class 属性并设置实现类全限定名，例如 map.put(\"class\", \"com.xxx.PersonImpl\")。",
    ),
    "如何在 Spring Cloud Alibaba 中开启 Dubbo 的访问日志功能？": (
        "Dubbo 访问日志会记录什么信息，启用时需要注意什么？",
        "访问日志会记录每一次请求，形式类似 Apache 访问日志；由于日志量较大，启用时需要关注磁盘容量。",
    ),
    "如何将访问日志输出到指定的文件？": (
        "将 Dubbo 访问日志输出到指定文件的 protocol 配置示例是什么？",
        "将 dubbo:protocol 的 accesslog 属性设置为目标文件路径，例如 <dubbo:protocol accesslog=\"http://10.20.160.198/wiki/display/dubbo/foo/bar.log\" />。",
    ),
}


PARTIAL_OVERRIDES = {
    "在Spring Cloud Alibaba中，如何判断平滑加权轮询算法是否适合用户有加权轮询需求的情况？":
        "用户有加权轮询需求时，可以考虑使用平滑加权轮询算法。",
}


# 虽然答案审计可通过，但真实 Top-5 无法取得所标证据；改写后仍使用同一原金标。
FORCED_REPLACEMENTS = {
    "如何在 Spring Cloud Alibaba 中配置扩展配置 extension-configs？": (
        "Nacos Spring Cloud 的 dataId 前缀和文件扩展名如何配置，如何实现配置自动更新？",
        "dataId 前缀默认取 spring.application.name，也可通过 spring.cloud.nacos.config.prefix 配置；文件扩展名通过 spring.cloud.nacos.config.file-extension 配置，支持 properties 和 yaml；使用 @RefreshScope 实现配置自动更新。",
    ),
    "在 Spring Cloud Alibaba 中，如何实现一个远程服务的代理？": (
        "如何通过 XML 的 dubbo:reference interface 与 url 配置点对点直连服务提供者？",
        "使用 dubbo:reference 声明服务接口和提供者 URL，例如 interface 设置为服务接口全限定名，url 设置为 dubbo://localhost:20890。",
    ),
    "如何在 Spring Cloud Alibaba 中开启访问日志？": (
        "Dubbo protocol 的 accesslog 属性能否直接指定日志文件路径，示例是什么？",
        "可以。将 accesslog 属性设置为目标文件路径，例如 <dubbo:protocol accesslog=\"http://10.20.160.198/wiki/display/dubbo/foo/bar.log\" />。",
    ),
}


# 说明段与紧邻代码块共同构成完整证据；检索命中任一相关证据都应视为有效，
# 避免只标代码块导致 Hit@K 与真实可回答性背离。
EVIDENCE_EXPANSIONS = {
    "如何在 Spring Cloud Alibaba 中禁用特定的提供者实例？": [466, 467],
    "如何在 Spring Cloud Alibaba 中通过 XML 配置实现点对点直连服务提供者？": [959, 960, 961],
    "在 Spring Cloud Alibaba 中，如何配置远程服务的 Stub？": [1845, 1846, 1850, 1854],
    "如何通过 XML 的 dubbo:reference interface 与 url 配置点对点直连服务提供者？": [959, 960, 961],
    "Dubbo protocol 的 accesslog 属性能否直接指定日志文件路径，示例是什么？": [770, 771, 772],
    "将 Dubbo 访问日志输出到指定文件的 protocol 配置示例是什么？": [770, 771, 772],
}


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="eval/golden/golden_v3_frozen80.jsonl")
    parser.add_argument("--auto-audit", default="eval/runs/frozen80_generation_grounding_audit_auto21_judge2.json")
    parser.add_argument("--non-auto-audit", default="eval/runs/frozen80_generation_grounding_audit_non_auto59_judge2.json")
    parser.add_argument("--output", default="eval/golden/golden_v4_generation_valid_frozen80.jsonl")
    args = parser.parse_args()

    source = load_jsonl(Path(args.input))
    audits = []
    for audit_path in (args.auto_audit, args.non_auto_audit):
        audits.extend(json.loads(Path(audit_path).read_text(encoding="utf-8"))["results"])
    by_question = {row["question"]: row for row in audits}
    if len(source) != 80 or len(by_question) != 80:
        raise RuntimeError(f"expected 80 source/audit rows, got {len(source)}/{len(by_question)}")

    timestamp = datetime.now(timezone.utc).isoformat()
    global_evidence_by_id = {
        int(item["chunk_id"]): item
        for source_row in source
        for item in [
            *(source_row.get("gold_evidence") or []),
            *(source_row.get("remap_candidates") or []),
        ]
        if item.get("chunk_id") is not None
    }
    output = []
    counts = {"unchanged_full": 0, "answer_corrected": 0, "question_replaced": 0}
    for original in source:
        row = dict(original)
        question = original["question"]
        audit = by_question[question]
        support = audit["support"]
        if question in FORCED_REPLACEMENTS:
            replacement_question, replacement_answer = FORCED_REPLACEMENTS[question]
            row["original_question"] = question
            row["original_ground_truth_answer"] = (
                original.get("new_ground_truth_answer") or original.get("ground_truth_answer")
            )
            row["question"] = replacement_question
            row["ground_truth_answer"] = replacement_answer
            row["new_ground_truth_answer"] = replacement_answer
            counts["question_replaced"] += 1
        elif support == "full":
            counts["unchanged_full"] += 1
        elif support == "partial":
            corrected = PARTIAL_OVERRIDES.get(question) or audit.get("supported_answer")
            if not corrected:
                raise RuntimeError(f"partial row missing supported answer: {question}")
            row["original_ground_truth_answer"] = (
                original.get("new_ground_truth_answer") or original.get("ground_truth_answer")
            )
            row["ground_truth_answer"] = corrected
            row["new_ground_truth_answer"] = corrected
            counts["answer_corrected"] += 1
        else:
            if question not in REPLACEMENTS:
                raise RuntimeError(f"non-full row missing replacement: {support}: {question}")
            replacement_question, replacement_answer = REPLACEMENTS[question]
            row["original_question"] = question
            row["original_ground_truth_answer"] = (
                original.get("new_ground_truth_answer") or original.get("ground_truth_answer")
            )
            row["question"] = replacement_question
            row["ground_truth_answer"] = replacement_answer
            row["new_ground_truth_answer"] = replacement_answer
            counts["question_replaced"] += 1

        row["review_status"] = "generation_evidence_validated"
        row["generation_grounding_review"] = {
            "source_support": support,
            "judge_model": "qwen-max",
            "reviewed_at": timestamp,
        }
        expanded_ids = EVIDENCE_EXPANSIONS.get(row["question"])
        if expanded_ids:
            missing = [chunk_id for chunk_id in expanded_ids if chunk_id not in global_evidence_by_id]
            if missing:
                raise RuntimeError(f"expanded evidence metadata missing {missing}: {row['question']}")
            row["gold_chunk_ids"] = expanded_ids
            row["gold_evidence"] = [
                {
                    "chunk_id": chunk_id,
                    "document_id": global_evidence_by_id[chunk_id]["document_id"],
                    "content_hash": global_evidence_by_id[chunk_id]["content_hash"],
                }
                for chunk_id in expanded_ids
            ]
        output.append(row)

    questions = [row["question"] for row in output]
    if len(set(questions)) != 80:
        raise RuntimeError("v4 questions are not unique")
    payload = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in output)
    path = Path(args.output)
    path.write_text(payload, encoding="utf-8")
    print(json.dumps({
        **counts,
        "total": len(output),
        "sha256": hashlib.sha256(payload.encode()).hexdigest(),
        "output": str(path),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
