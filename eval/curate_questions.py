#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Question 集 curator(ADR-0008 D1)。

V2/V3 实测发现 100 题里约 16-30 题在当前 50 docs corpus 内不命中(NO_RECALL)。
这些题单条分数=0, 拉低均值约 17%, 让 RAGAS 数字不可信。

本脚本: 检查每题的 ground_truth_answer 关键词是否能在当前 corpus 的 chunks 内容里
找到足够 coverage, 过滤掉低 coverage 题。输出 curated 子集。

判定方法(简洁但有效):
1. 提取 ground_truth_answer 的关键词(jieba 分词, 去停用词)
2. 与当前 corpus 的所有 chunks 内容做 keyword match
3. coverage = (命中的 ground_truth 关键词数) / (ground_truth 关键词总数)
4. coverage >= MIN_COVERAGE 保留, 否则过滤

输入:
  - eval/questions.real.jsonl       原始 100 题
  - chunks 表内容(走 MySQL)         chunk 全文

输出:
  - eval/questions.curated.jsonl    过滤后的高质量题集

用法:
  python3 eval/curate_questions.py
  python3 eval/curate_questions.py --min-coverage 0.4 --max-questions 50
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

# ============ config ============
DEFAULT_MIN_COVERAGE = 0.3  # 默认 30% 关键词命中才算合理题
DEFAULT_MAX_QUESTIONS = 80
EVAL_DIR = Path(__file__).parent
QUESTIONS_FILE = EVAL_DIR / "questions.real.jsonl"
OUTPUT_FILE = EVAL_DIR / "questions.curated.jsonl"

# 停用词(简洁版, 避免过度工程)
STOPWORDS = set(
    """的 是 有 在 和 与 或 也 这 那 你 我 他 它 们 个 些 等 及 以 了 着 过
       要 不 没 都 就 还 只 又 很 太 最 一个 一种 一些 如何 怎么 怎样
       什么 为何 为什么 哪 哪些 是否 能 不能 可以 应该 需要 通过 使用
       进行 实现 配置 设置 安装 部署 中文 简单 一下 比如 例如 即 也就是
       a an the of in on at for to with and or is are was were be been
       we you they he she it this that these those""".split()
)


def get_chunks_text() -> str:
    """从 MySQL 拉 chunks 全文。
    走 docker exec 子进程而非 pymysql(pymysql + python3.13 + MySQL 8.4 caching_sha2
    auth 有兼容性 bug, 见 V3 Day2 实测), 直接 mysql -e 走标准客户端 pipeline。
    """
    import subprocess

    result = subprocess.run(
        [
            "docker",
            "exec",
            "ragdoc-mysql",
            "mysql",
            "-uroot",
            "-prootpass",
            "-N",
            "-B",
            "--default-character-set=utf8mb4",
            "ragdoc",
            "-e",
            "SELECT content FROM chunks WHERE chunk_type IN ('TEXT','CHILD','PARENT')",
        ],
        capture_output=True,
        text=False,  # bytes, 自己 decode 容错
        check=True,
    )
    # utf-8 容错 decode: latin-1 兜底防止 chunk 二进制噪音中断
    try:
        return result.stdout.decode("utf-8", errors="replace")
    except Exception:
        return result.stdout.decode("latin-1", errors="replace")


def tokenize(text: str) -> list[str]:
    """中文优先 jieba, fallback 多字 token regex。"""
    try:
        import jieba

        tokens = [t.strip() for t in jieba.lcut(text) if len(t.strip()) >= 2]
    except ImportError:
        # fallback: 多字汉字连续段 + 英文词
        tokens = re.findall(r"[\u4e00-\u9fff]{2,}|[a-zA-Z]{3,}", text)
    return [t for t in tokens if t.lower() not in STOPWORDS and not t.isdigit()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--min-coverage", type=float, default=DEFAULT_MIN_COVERAGE)
    parser.add_argument("--max-questions", type=int, default=DEFAULT_MAX_QUESTIONS)
    parser.add_argument("--input", type=Path, default=QUESTIONS_FILE)
    parser.add_argument("--output", type=Path, default=OUTPUT_FILE)
    args = parser.parse_args()

    print(f"[1/4] 读取 questions: {args.input}")
    questions = []
    with open(args.input) as f:
        for line in f:
            line = line.strip()
            if line:
                questions.append(json.loads(line))
    print(f"      共 {len(questions)} 题")

    print(f"[2/4] 读取 corpus chunks 全文(走 MySQL)")
    corpus_text = get_chunks_text()
    print(f"      chunks 全文长度: {len(corpus_text)} 字符")

    print(f"[3/4] 逐题测 coverage(阈值 {args.min_coverage})")
    curated = []
    for q in questions:
        gt = q.get("ground_truth_answer") or q.get("answer") or q["question"]
        keywords = tokenize(gt)
        if not keywords:
            continue
        hits = sum(1 for kw in keywords if kw in corpus_text)
        cov = hits / len(keywords)
        q["_coverage"] = round(cov, 3)
        q["_keywords_count"] = len(keywords)
        if cov >= args.min_coverage:
            curated.append(q)

    curated.sort(key=lambda x: x["_coverage"], reverse=True)
    if len(curated) > args.max_questions:
        curated = curated[: args.max_questions]

    print(f"      过滤后 {len(curated)} 题(从 {len(questions)} 题)")

    print(f"[4/4] 写入 {args.output}")
    with open(args.output, "w") as f:
        for q in curated:
            # 移除内部辅助字段, 输出干净 question
            out = {k: v for k, v in q.items() if not k.startswith("_")}
            f.write(json.dumps(out, ensure_ascii=False) + "\n")

    # 报告 5 个被过滤的(便于人工 review)
    filtered_out = [q for q in questions if q not in curated]
    if filtered_out:
        print(f"\n过滤掉的样例(top 5, 便于 review):")
        for q in filtered_out[:5]:
            q_str = q["question"][:60]
            print(f"  cov={q.get('_coverage', '?')} | {q_str}")

    print(f"\n✓ 生成 {args.output} ({len(curated)} 题)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
