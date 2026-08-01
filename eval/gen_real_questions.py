#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V2-C Step 1 (P0 工程版): 手写 30 道真实开发者口吻题 —— 业务标注评测集草案。

设计文档 README.md L16 要求:
  "200 条业务标注评测集 + RAGAS + CI 门禁(指标下降 3% 阻断上线)"

本脚本先落地 30 条草案(后续扩 200 条)。题目设计:
  - 来源: 当前知识库 44 文档覆盖的主题(Nacos 配置/Dubbo 异步/鉴权/附件/访问日志 等)
  - 问法: 真实开发者卡住时的自然语言, 刻意避开 chunk 原词, 制造检索难度
    例: chunk 写 "async call 用 CompletableFuture", 题目用 "异步调到底怎么写", 不带原文词
  - ground_truth_chunk_id: 用 embedding 反查当前知识库最近 chunk 标注(同 gen_questions 思路)
  - ground_truth_answer: 简短要点(给 RAGAS 算 Answer 相关性用), 不写长篇

输出: eval/questions.real.jsonl
"""
import json
import os
import sys
from pathlib import Path

import requests
from dotenv import load_dotenv

PROJECT_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

EMBED_URL = os.getenv("EMBEDDING_BASE_URL", "http://localhost:8082") + "/v1/embeddings"
CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8090/api/v1/chat")
OUT_FILE = Path(__file__).resolve().parent / "questions.real.jsonl"


# 30 道手工题。每个: 真实开发者问法(question) + 要点答案(answer)
# chunk_id 暂留空, 后面用 embedding 反查自动填
QUESTIONS = [
    # ===== Nacos 配置 =====
    {
        "question": "Nacos 配置改了不生效, 必须重启项目才能读到新值, 怎么回事",
        "answer": "需要配合 @RefreshScope 注解, 配置变更时 bean 才会重建",
        "topic": "nacos-refresh",
    },
    {
        "question": "多个服务共用一些 Nacos 配置, 怎么抽到一起统一管理",
        "answer": "用 extension-configs 扩展配置, 指定 shared-dataids 共享",
        "topic": "nacos-shared-config",
    },
    {
        "question": "@NacosValue 注入的字段值一直不变, 改库了它也不刷新",
        "answer": "@NacosValue 默认不自动刷新, 需加 autoRefreshed=true",
        "topic": "nacos-value-refresh",
    },
    {
        "question": "Nacos 后台控制台密码忘了或想关掉权限, 怎么操作",
        "answer": "改 application.properties 的 nacos.core.auth.enabled=false 临时关, 或用默认 nacos/nacos",
        "topic": "nacos-auth-disable",
    },
    {
        "question": "不同环境 dev/prod 的 Nacos 配置怎么隔离",
        "answer": "用 namespace 隔离环境, group 隔离业务组, data-id 区分配置文件",
        "topic": "nacos-namespace",
    },
    # ===== Dubbo 异步 =====
    {
        "question": "Dubbo 想让 provider 异步处理不阻塞线程, 该怎么写",
        "answer": "provider 返回 CompletableFuture, 框架自动识别为异步执行",
        "topic": "dubbo-async-provider",
    },
    {
        "question": "Dubbo 异步调用一直没拿到结果, 怎么查是消费端阻塞还是 provider 问题",
        "answer": "调用方用 CompletableFuture.get(timeout) 加超时, 日志看 RpcContext.isConsumerSide",
        "topic": "dubbo-async-debug",
    },
    {
        "question": "Dubbo 老版本 async 用 Future 拿不到上下文, 升级到新版本要注意啥",
        "answer": "新版本统一用 CompletableFuture, 不再用老的 ReturnFuture, 上下文在回调里取",
        "topic": "dubbo-async-upgrade",
    },
    {
        "question": "Dubbo provider 异步里要拿到当前请求的 attachment 参数, 用什么 API",
        "answer": "在调用瞬间通过 RpcContext.getContext().getAttachment(key) 先取出来, 再进异步块",
        "topic": "dubbo-async-attachment",
    },
    {
        "question": "Dubbo provider 端怎么做异步返回, 消费端感知它是异步",
        "answer": "provider 方法签名直接返回 CompletableFuture<T>, 消费端无需特殊配置",
        "topic": "dubbo-async-signature",
    },
    # ===== Dubbo 鉴权 =====
    {
        "question": "Dubbo 直连部署后发现没人挡, 想加一层鉴权, 从哪开始",
        "answer": "用 dubbo-auth 模块, provider 配 authenticator, 开启后调用必须带 token",
        "topic": "dubbo-auth-enable",
    },
    {
        "question": "Dubbo 鉴权默认用啥算法签 token, 改成自定义 key 行不行",
        "answer": "默认 HS256, 可通过 access-key/secret-key 配置自定义, 实现自定义 AccessKeyStorage",
        "topic": "dubbo-auth-algo",
    },
    {
        "question": "Dubbo provider 怎么指定只有特定消费者才调得通",
        "answer": "结合 token 鉴权, 在 provider 端配置 token, 消费端同 token 才认证通过",
        "topic": "dubbo-auth-token",
    },
    # ===== Dubbo Attachment(隐式参数传递) =====
    {
        "question": "Dubbo 想把灰度标识从入口透传到下游 provider, 不改接口签名怎么做",
        "answer": "用 RpcContext.getContext().setAttachment(k,v) 在调用前设, provider 端 getAttachment 取",
        "topic": "dubbo-attachment-pass",
    },
    {
        "question": "Dubbo attachment 设了但provider拿不到, 顺序出问题了吗",
        "answer": "必须在发起调用的同一行之前 setAttachment; 在调用后再 set 不会发出去",
        "topic": "dubbo-attachment-order",
    },
    {
        "question": "Dubbo 异步场景 attachment 丢失, 这是个 bug 还是设计如此",
        "answer": "RCA: attachment 是调用栈线程上下文, 跨线程要手动捕获传递, 不是 bug",
        "topic": "dubbo-attachment-async",
    },
    # ===== Dubbo 访问日志(accesslog) =====
    {
        "question": "Dubbo 想记录每个调用的访问日志, 类似 nginx accesslog, 怎么开",
        "answer": "provider 配 accesslog=\"true\" 或指定日志文件路径, dubbo 自动写访问日志",
        "topic": "dubbo-accesslog-enable",
    },
    {
        "question": "Dubbo accesslog 一直不输出, 文件也没生成, 是路径不对还是没触发",
        "answer": "RCA: accesslog 只在 provider 端生效, 且必须有真实调用才写, 路径要有写权限",
        "topic": "dubbo-accesslog-debug",
    },
    {
        "question": "Dubbo accesslog 想自定义格式, 加字段能行吗",
        "answer": "accesslog 格式固定, 要自定义只能在 Filter 里拦截调用日志",
        "topic": "dubbo-accesslog-format",
    },
    # ===== 启动/注册中心故障类(经典高频真实问题) =====
    {
        "question": "Nacos 客户端启动卡住, 报 Client not connected, current status:STARTING",
        "answer": "RCA: 通常是 grpc 端口(9849 等偏移端口)未通, 检查 firewall/网络策略",
        "topic": "nacos-client-connect",
    },
    {
        "question": "项目起不来, 日志报 com.alibaba.nacos.api.exception.NacosException: client not connected",
        "answer": "排查 nacos server 地址是否对、grpc 主端口+偏移端口都通、凭证是否正确",
        "topic": "nacos-startup-fail",
    },
    {
        "question": "Dubbo provider 重启了, consumer 还连着旧 IP 一直报错, 多久能自愈",
        "answer": "取决于注册中心推送频率和心跳间隔, nacos 默认 5-10s 推送, 旧连接靠 heartbeat 失效",
        "topic": "dubbo-provider-restart",
    },
    {
        "question": "Dubbo 启动后服务不出, 怎么验证到底注册到 nacos 没",
        "answer": "用 nacos 控制台服务列表 / OpenAPI 查 instance list, 或检查 dubbo:registry 配置",
        "topic": "dubbo-register-verify",
    },
    {
        "question": "本机起 Nacos 单机模式, 数据想存到 mysql 而不是内嵌 derby, 怎么改",
        "answer": "改 conf/application.properties: spring.datasource.platform=mysql + 配 db 连接",
        "topic": "nacos-mysql",
    },
    {
        "question": "Nacos 集群部署, 选了几台节点, 数据一致性怎么保证",
        "answer": "Nacos 用 raft(CP) + distro(AP) 双协议, 配置用 CP 强一致, 服务发现用 AP 高可用",
        "topic": "nacos-cluster-consensus",
    },
    # ===== 综合/常见工程困惑 =====
    {
        "question": "Dubbo 默认负载均衡算法是啥, 想换成一致性哈希改哪",
        "answer": "默认 random 随机; 改用 consistenthash, 配 loadbalance=\"consistenthash\"",
        "topic": "dubbo-lb-default",
    },
    {
        "question": "Dubbo 调用偶发超时, 但 provider 实际处理并不慢, 怀疑线程池不够",
        "answer": "检查 provider线程池大小(threads), 配 keeps alive 调整, 看远程日志有无 reject",
        "topic": "dubbo-threadpool",
    },
    {
        "question": "Dubbo provider 想延迟 5 秒再暴露服务, 等其他资源就绪, 怎么配",
        "answer": "配置 delay=5000(ms), 或 delay=\"-1\" 用 Spring 加载完再暴露",
        "topic": "dubbo-delay-expose",
    },
    {
        "question": "Dubbo consumer 想降级, provider 挂了别抛异常直接走 mock 实现",
        "answer": "用 mock=\"return null\" 或 mock=\"true\" 实现接口的 Mock 实现类",
        "topic": "dubbo-mock-fallback",
    },
    {
        "question": "Dubbo 多注册中心订阅, 流量怎么按权重分到不同注册中心",
        "answer": "用多注册中心选址策略, preferred/同区域优先/权重轮询/缺省, 通过配置控制",
        "topic": "dubbo-multi-registry",
    },
]


def embed_text(text):
    """用 BGE-M3 服务把文本变向量"""
    r = requests.post(
        EMBED_URL,
        json={"input": [text]},
        timeout=30,
    )
    r.raise_for_status()
    data = r.json()["data"][0]["embedding"]
    return data


def find_gt_chunk_via_chat(question):
    """通过调 chat 接口拿 top1 引用, 作为该题的 ground_truth_chunk_id。

    设计取舍:
      - 不直接查 Milvus(省耦合), 复用线上 retrieve 链路, 还顺便测一遍 chat
      - top1 是检索系统认为最相关的 chunk, 用作 gt 在 dense-only 下偏宽松
      - RAGAS Context Precision 会按更严的标准判每条 context 相关性
    """
    try:
        r = requests.post(
            CHAT_URL,
            json={"query": question, "top_k": 5},
            headers={"Content-Type": "application/json"},
            timeout=90,
        )
        r.raise_for_status()
        citations = r.json().get("citations", [])
        if not citations:
            return None, []
        return citations[0]["chunk_id"], [c["chunk_id"] for c in citations]
    except Exception as e:
        print(f"  [warn] chat failed: {e}")
        return None, []


def main():
    print(f"[1/2] 装入 {len(QUESTIONS)} 道手工题")
    print(f"[2/2] 对每题调 chat 接口反查 ground_truth_chunk_id (gone through real retrieve)")
    results = []
    for i, q in enumerate(QUESTIONS, 1):
        gt_cid, retrieved = find_gt_chunk_via_chat(q["question"])
        results.append({
            **q,
            "ground_truth_chunk_id": gt_cid,
            "retrieved_chunk_ids": retrieved,
            "source": "hand-written-realistic",
        })
        status = f"gt_chunk={gt_cid}" if gt_cid else "NO_GT"
        print(f"  [{i:2}/{len(QUESTIONS)}] {q['topic']:28} → {status}")

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    have_gt = sum(1 for r in results if r["ground_truth_chunk_id"])
    print(f"\n✓ 写入 {len(results)} 题到 {OUT_FILE}")
    print(f"  成功标注 gt_chunk_id 的: {have_gt}/{len(results)}")
    print(f"  没召回的(NO_GT): {len(results) - have_gt} 题 — 这些就是检索盲区")
    print(f"\n下一步: 用 RAGAS 跑这套题, 拿 Faithfulness/Answer-Relevancy/Context-Precision")


if __name__ == "__main__":
    main()
