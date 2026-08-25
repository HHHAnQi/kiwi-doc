#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V3-W3 重写: deterministic-leaning QA 生成器(P0 badcase 调研后重写).

V2 版本的痛点(badcase 分析 docs/v3/badcase-analysis.md §3):
  1. ground_truth 被 LLM 改写成 "Spring Cloud Alibaba 中可以通过 X 实现" 这种通用 wrapper,
     与真实 chunk 文本偏离 → RAGAS context_recall 查不到原 chunk 文本, 分数极低
  2. 遍历所有 chunks (100+ doc × ~20 chunk = 2000+), 每个 LLM 调用 → 跑了几小时没出 30 题

新版本设计:
  1. extractive ground_truth - LLM 抽 chunk 中**直接原文当 answer**, 不改写不总结
  2. 采样而非全遍 - 随机 N=(target*2) 个 chunks 做 seed(过滤掉<200字过短 + code-only 的)
  3. 单题输出 - 每个 chunk 只生 1 题(够 curated 数量级, 控成本)
  4. 必须给 evidence_span - LLM 返回 chunk 内的原文 span, answer 严格=evidence_span 不改

用法:
  LLM_API_KEY=xxx LLM_BASE_URL=xxx LLM_MODEL=glm-4-plus \
      python3 gen_questions.py 30 [随机 seed]

输出: eval/questions.jsonl 每行:
  {"question":"...", "ground_truth":"<chunk 内原文>", "ground_truth_chunk_id":..., "ground_truth_doc_id":...}
"""
import json
import argparse
import os
import random
import re
import sys
from pathlib import Path

import pymysql
from dotenv import load_dotenv
from openai import OpenAI

PROJECT_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "glm-4-plus")

DB_CONFIG = {
    "host": os.getenv("MYSQL_HOST", "localhost"),
    "port": int(os.getenv("MYSQL_PORT", "3307")),
    "user": os.getenv("MYSQL_USER", "root"),
    "password": os.getenv("MYSQL_ROOT_PASSWORD", "rootpass"),
    "database": os.getenv("MYSQL_DATABASE", "ragdoc"),
    "charset": "utf8mb4",
}

OUT_FILE = Path(__file__).resolve().parent / "questions.jsonl"


def fetch_chunks(sample_size, seed):
    """随机采样 chunk: 过滤过短 + 全 code 内容, 保证 chunk 真有可问事实."""
    try:
        conn = pymysql.connect(**DB_CONFIG)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            # 先捞所有候选 chunk(len>=200), 再 Python 侧 sample,
            # 避免 ORDER BY RAND() 在百万行表上的代价(本项目 2k 行其实用 RAND 也行, 但保持通用).
            # 同时拉 chunk_type 过滤掉 PARENT(整段太长 LLM gen 质量差) 仅保留 CHILD/TEXT.
            cur.execute(
                "SELECT c.id, c.document_id, c.seq, c.chunk_type, c.content, "
                "c.content_hash AS chunk_content_hash, d.content_hash AS document_content_hash, "
                "d.logical_document_key, d.original_filename, d.source, d.version "
                "FROM chunks c JOIN documents d ON d.id = c.document_id "
                "WHERE LENGTH(c.content) >= 200 AND c.chunk_type IN ('CHILD', 'TEXT') "
                "AND d.status = 'INDEXED' AND d.deleted_at IS NULL "
                "ORDER BY c.document_id, c.seq"
            )
            rows = cur.fetchall()
    finally:
        conn.close()

    rng = random.Random(seed)
    # 每个 source 内固定随机，再轮询取样，避免大语料源淹没小语料源。
    by_source = {}
    for row in rows:
        by_source.setdefault(row.get("source") or "unknown", []).append(row)
    for group in by_source.values():
        rng.shuffle(group)
    rows = []
    while any(by_source.values()):
        for source in sorted(by_source):
            if by_source[source]:
                rows.append(by_source[source].pop())
    out = []
    for r in rows[:sample_size]:
        c = r["content"] or ""
        # 跳过 code block 占主导的 chunk(> 50% 行是 code 围栏内)
        if looks_like_code(c):
            continue
        out.append(r)
        if len(out) >= sample_size:
            break
    return out


def looks_like_code(text):
    """简单启发式: 大于 50% 行以非语言字符开头(# - * 全算 markdown 不算 code)"""
    lines = [l for l in text.split("\n") if l.strip()]
    if not lines:
        return True
    code_lines = sum(
        1
        for l in lines
        if l.strip().startswith(("<", "import ", "package ", "public ", "private "))
        or re.match(r"^\s*[a-zA-Z_]+\s*=", l)
    )
    return code_lines * 2 > len(lines)


def gen_qa_extractive(client, chunk, attempt=0):
    """让 LLM 基于 chunk 抽出 1 个问答对.

    关键: answer 必须**原样摘录** chunk 内 1-3 句, 严禁改写/总结.
    这是 V3-W3 badcase 修复重点 — 让 ground_truth 跟 chunk 完全对齐,
    RAGAS context_recall judge 能直接命中 chunk.
    """
    text = (chunk["content"] or "").strip()
    if len(text) > 1500:
        text = text[:1500]

    prompt = f"""下面是 Spring Cloud Alibaba 技术文档的一个片段。请基于该片段生成 1 个验证用问答对。

【硬性约束(违反即视为无效输出)】:
1. question 必须具体(配置项名/步骤号/版本号都行), 是开发者真会问的问题;
2. answer 必须**原样摘录**片段中的 1-3 句话(连续段), 不允许任何改写、总结或翻译;
3. 只回答片段明确给出的信息, 不要编造;
4. 输出严格 JSON: {{"question": "...", "answer": "<片段原文逐字摘录 1-3 句>"}}
5. 不输出任何其他文字(无前言、无解释、无 markdown fence)。

片段内容:
{text}
"""
    try:
        resp = client.chat.completions.create(
            model=LLM_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0,
            max_tokens=400,
        )
        content = resp.choices[0].message.content.strip()
        # 去 markdown fence(若 LLM 违规加)
        if content.startswith("```"):
            content = content.split("```")[1]
            if content.lower().startswith("json"):
                content = content[4:]
        content = content.strip().rstrip("`").strip()
        qa = json.loads(content)
        # 关键: 验证 answer 真是 chunk 原文摘录
        answer = qa.get("answer", "")
        if not answer or not is_substring_of(answer, chunk["content"] or ""):
            print(
                f"  [warn] answer 不是 chunk 原文摘录, 丢弃 chunk_id={chunk['id']} attempt={attempt}"
            )
            return None
        return qa
    except Exception as e:
        print(f"  [warn] gen_qa failed chunk_id={chunk['id']}: {e}")
        return None


def is_substring_of(answer, source):
    """answer 是否是 source 的子串(允许少量空白差异).

    规范化: 去所有空白后子串匹配, 解决不同换行/空白让 strict substring 假阴性.
    """
    norm_answer = re.sub(r"\s+", "", answer)
    norm_source = re.sub(r"\s+", "", source or "")
    return norm_answer and norm_answer in norm_source


def main():
    parser = argparse.ArgumentParser(description="从当前索引语料生成可追溯的 extractive QA 金标")
    parser.add_argument("target", nargs="?", type=int, default=30)
    parser.add_argument("seed", nargs="?", type=int, default=42)
    parser.add_argument("--output", default=str(OUT_FILE))
    args = parser.parse_args()
    target = args.target
    seed = args.seed
    out_file = Path(args.output)

    if not LLM_API_KEY:
        print("ERROR: LLM_API_KEY 未配置")
        sys.exit(1)

    # 采样目标数量 × 2(给 LLM 失败 / 短 answer 失败留余量)
    candidate_size = min(target * 2, 200)
    print(f"[1/3] 采样 {candidate_size} chunks (seed={seed}, target={target})")
    chunks = fetch_chunks(candidate_size, seed)
    print(f"      实际过滤后 {len(chunks)} 个候选 chunk")

    client = OpenAI(api_key=LLM_API_KEY, base_url=LLM_BASE_URL)
    print(f"[2/3] 调 LLM({LLM_MODEL}) 生成 extractive QA(每 chunk 1 题)...")

    all_qa = []
    for i, chunk in enumerate(chunks):
        if len(all_qa) >= target:
            break
        qa = gen_qa_extractive(client, chunk)
        if not qa:
            continue
        all_qa.append(
            {
                "question": qa["question"],
                "ground_truth_answer": qa["answer"][:500],  # 防超长
                "ground_truth_chunk_id": chunk["id"],
                "ground_truth_doc_id": chunk["document_id"],
                "topic": f"gen-{chunk['document_id']}-{chunk['seq']}",
                "source": chunk.get("source"),
                "version": chunk.get("version"),
                "original_filename": chunk.get("original_filename"),
                "logical_document_key": chunk.get("logical_document_key"),
                "ground_truth_chunk_content_hash": chunk.get("chunk_content_hash"),
                "ground_truth_document_content_hash": chunk.get("document_content_hash"),
                "generation_seed": seed,
                "generation_model": LLM_MODEL,
            }
        )
        print(f"  [{len(all_qa)}/{target}] chunk_id={chunk['id']} ok")

    print(f"[3/3] 写入 {len(all_qa)} 题 到 {out_file}")
    out_file.parent.mkdir(parents=True, exist_ok=True)
    with open(out_file, "w", encoding="utf-8") as f:
        for qa in all_qa:
            f.write(json.dumps(qa, ensure_ascii=False) + "\n")

    print(f"\n✓ 完成, {len(all_qa)}/{target} 题(extractive ground truth)")


if __name__ == "__main__":
    sys.exit(main() or 0)
