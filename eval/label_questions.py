#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 0.5: 题库 question_type 自动标签(启发式)。

服务对象:
  Phase 2 算法升级(Lost-in-middle / HyDE / Late-chunking) 时, 按 question_type 分组
  评测"哪类 query 受益最大", 避免"全题平均后看不出谁涨谁跌"。

标签定义(5 类, 互斥, 优先级 troubleshoot > config > multi_hop > procedural > factual):
  - factual       事实陈述/概念定义, 关键词 "是什么/定义/意思/含义/包含"
  - config        配置项/文件/yaml, 关键词 "配置/yaml/yml/properties/字段/怎么配置"
  - multi_hop     多跳/对比, 关键词 "区别/对比/相比/不同/和.*哪个"
  - troubleshoot  故障排查, 关键词 "报错/不生效/失败/异常/为什么/怎么办/起不来"
  - procedural    步骤/操作, 关键词 "如何/怎么/步骤/流程/方式"

用法:
  python3 eval/label_questions.py
  python3 eval/label_questions.py --in eval/golden/golden.jsonl --out eval/golden/golden.with_labels.jsonl
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent

# 标签规则: 按 (priority, label, [patterns]) 排序, 命中即打。
# troubleshoot 优先级最高(故障题往往同时含"怎么"), 比 procedural 更特化。
RULES: list[tuple[int, str, list[re.Pattern]]] = [
    (4, "troubleshoot", [
        re.compile(r"报错|异常|失败|不能|无法|起不来|挂|崩"),
        re.compile(r"不生效|不生效|无效|没用|有问题|出问题"),
        re.compile(r"为什么|怎么回事|怎么办|为啥"),
    ]),
    (3, "config", [
        re.compile(r"配置|properties|\.ya?ml|\.xml|字段|参数|开关|环境变量|profile"),
    ]),
    (3, "multi_hop", [
        re.compile(r"区别|对比|相比|差异|不同|之间"),
        re.compile(r".+和.+哪个|.+与.+哪"),
    ]),
    (2, "procedural", [
        re.compile(r"如何|怎么|怎样|步骤|流程|方式|方法|怎样实现"),
    ]),
    (1, "factual", [
        re.compile(r"是什么|什么是|定义|意思|含义|包含|包括|哪些|列举"),
        re.compile(r"多少|几|哪种|哪个是"),
    ]),
]


def classify(question: str) -> str:
    """给单条 question 打标签, 不命中规则返回 'other' 。"""
    q = question.strip()
    # 按 priority 高 → 低, 首个命中即返回
    for _prio, label, pats in sorted(RULES, key=lambda x: -x[0]):
        if any(p.search(q) for p in pats):
            return label
    return "other"


def label_file(in_path: Path, out_path: Path) -> dict[str, int]:
    """给整个 jsonl 打标, 返回 label 分布。"""
    items = [json.loads(l) for l in in_path.read_text(encoding="utf-8").splitlines() if l.strip()]
    dist: dict[str, int] = {}
    with open(out_path, "w", encoding="utf-8") as f:
        for d in items:
            label = classify(d["question"])
            d["question_type"] = label
            dist[label] = dist.get(label, 0) + 1
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    return dist


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", default=str(EVAL_DIR / "golden" / "golden.jsonl"))
    ap.add_argument("--out", default=str(EVAL_DIR / "golden" / "golden.with_labels.jsonl"))
    args = ap.parse_args()
    in_path, out_path = Path(args.inp), Path(args.out)
    if not in_path.exists():
        print(f"ERROR: input not found: {in_path}", file=sys.stderr)
        return 1
    dist = label_file(in_path, out_path)
    total = sum(dist.values())
    print(f"✓ labeled {total} questions → {out_path}")
    print("分布:")
    for label, n in sorted(dist.items(), key=lambda x: -x[1]):
        print(f"  {label:15s} {n:4d}  ({n/total*100:5.1f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
