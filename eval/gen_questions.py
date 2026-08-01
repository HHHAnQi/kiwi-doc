#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V2-C Step 1: 从 chunks 表合成 30 题 QA 评测集。

流程:
1. 从 MySQL 拉 chunks (按 document_id 分组, 每个文档取全部 chunks)
2. 调 DashScope qwen-max 让 LLM 基于 chunk 内容生成 N 个 QA 对
   每个 QA: {question, ground_truth_answer, ground_truth_chunk_id}
3. 输出 JSONL 到 eval/questions.30.jsonl

依赖:
  pip install openai pymysql python-dotenv

用法:
  export LLM_API_KEY=sk-xxx       # 或在 ~/RagDoc/rag-doc-platform/.env
  python3 gen_questions.py 30     # 30 题, 数字可改
"""

import json
import os
import sys
import random
from pathlib import Path

import pymysql
from openai import OpenAI
from dotenv import load_dotenv

# 加载 .env
# override=False: shell 已 export 的优先(例如 LLM_API_KEY), .env 只填未设置的
PROJECT_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

DASHSCOPE_BASE_URL = os.getenv(
    "LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
)
DASHSCOPE_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "qwen-max")

DB_CONFIG = {
    "host": os.getenv("MYSQL_HOST", "localhost"),
    "port": int(os.getenv("MYSQL_PORT", "3307")),
    "user": os.getenv("MYSQL_USER", "root"),
    "password": os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
    "database": os.getenv("MYSQL_DATABASE", "ragdoc"),
    "charset": "utf8mb4",
}

OUT_FILE = Path(__file__).resolve().parent / "questions.jsonl"


def fetch_chunks():
    """拉所有 chunks, 按 document_id 分组返回 [{doc_id, chunks: [...]}]"""
    conn = pymysql.connect(**DB_CONFIG)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT id, document_id, seq, content FROM chunks "
                "ORDER BY document_id, seq"
            )
            rows = cur.fetchall()
    finally:
        conn.close()

    # 按 document_id 分组
    docs = {}
    for r in rows:
        docs.setdefault(r["document_id"], []).append(r)
    return [
        {"doc_id": k, "chunks": v} for k, v in docs.items()
    ]


def gen_qa_for_chunk(client, chunk, n_questions):
    """让 LLM 基于 chunk 内容生成 n 个 QA 对"""
    # 清洗 chunk 文本(去多余空行, 限 1500 字)
    text = (chunk["content"] or "").strip()
    text = "\n".join(line for line in text.split("\n") if line.strip())
    if len(text) > 1500:
        text = text[:1500]

    if len(text) < 80:
        return []  # 内容太少, 跳过

    prompt = f"""下面是 Spring Cloud Alibaba 技术文档中的一个片段。请基于该片段生成 {n_questions} 个高质量的问答对, 用于评测 RAG 系统。

要求:
1. 问题要具体、可操作, 体现真实开发者会问的工程问题(如配置方式、步骤、原理、对比)
2. 每个问题必须有可从片段内容直接推出的明确答案
3. 答案限 2-5 句, 不要编造片段中没有的内容
4. 用 JSON 数组返回, 每个元素格式 {{"question": "...", "answer": "..."}}
5. 不要输出任何其他文本(无前言无解释), 只输出 JSON 数组

片段内容:
{text}
"""
    try:
        resp = client.chat.completions.create(
            model=LLM_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.7,
        )
        content = resp.choices[0].message.content.strip()
        # LLM 可能带 ```json fence, 去掉
        if content.startswith("```"):
            content = content.split("```")[1]
            if content.startswith("json"):
                content = content[4:]
        content = content.strip().rstrip("`").strip()
        qa_list = json.loads(content)
        return qa_list
    except Exception as e:
        print(f"  [warn] gen_qa failed chunk_id={chunk['id']}: {e}")
        return []


def main():
    target = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    if not DASHSCOPE_API_KEY:
        print("ERROR: LLM_API_KEY 未配置")
        sys.exit(1)

    client = OpenAI(api_key=DASHSCOPE_API_KEY, base_url=DASHSCOPE_BASE_URL)
    docs = fetch_chunks()
    total_chunks = sum(len(d["chunks"]) for d in docs)
    print(f"[1/3] 拉取 chunks: {len(docs)} 个文档, 共 {total_chunks} 个 chunk")

    # 每个 chunk 平均生成 target/total_chunks 个问题, 向上取整
    per_chunk = max(1, (target + total_chunks - 1) // total_chunks)
    print(f"[2/3] 每个 chunk 生成 {per_chunk} 题(目标共 {per_chunk * total_chunks} 题)")

    all_qa = []
    for doc in docs:
        for chunk in doc["chunks"]:
            qas = gen_qa_for_chunk(client, chunk, per_chunk)
            for q in qas:
                if "question" not in q or "answer" not in q:
                    continue
                all_qa.append({
                    "question": q["question"],
                    "ground_truth_answer": q["answer"],
                    "ground_truth_chunk_id": chunk["id"],
                    "ground_truth_doc_id": doc["doc_id"],
                })
            print(f"  chunk_id={chunk['id']} doc_id={doc['doc_id']} seq={chunk['seq']} → {len(qas)} 题")

    # 打乱并截取 target 数
    random.seed(42)  # 复现性
    random.shuffle(all_qa)
    all_qa = all_qa[:target]

    print(f"[3/3] 写入 {len(all_qa)} 题到 {OUT_FILE}")
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        for item in all_qa:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")

    print(f"\n✓ 完成。可执行评测: python3 eval_pipeline.py")
    print(f"  预览前 3 题:")
    for i, item in enumerate(all_qa[:3], 1):
        print(f"  [{i}] Q: {item['question'][:60]}")
        print(f"      A: {item['ground_truth_answer'][:60]}")


if __name__ == "__main__":
    main()
