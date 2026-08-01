#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V2-C Step 2: 评测管道 — hit /api/v1/chat → 收集响应 → 算 RAGAS 4 + EM/F1 → 出报告。

指标:
- context_precision:   检索 top-k 中, ground_truth_chunk 是否命中(passes@k)
- context_recall:      同上(单一 ground truth, recall == hit@k)
- answer_f1:           answer vs ground_truth_answer 的 token F1
- answer_em:           answer vs ground_truth_answer 的 exact match(中文场景只参考)
- answer faithfulness: (V2-C step2 简化为布尔, LLM judge 留 V2-C step3)

依赖:
  pip install requests jieba

用法:
  python3 eval_pipeline.py
"""

import json
import os
import sys
import time
from collections import Counter
from pathlib import Path

import requests

PROJECT_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = Path(__file__).resolve().parent
QUESTIONS_FILE = EVAL_DIR / "questions.jsonl"
OUT_FILE = EVAL_DIR / "eval_results.jsonl"
REPORT_FILE = EVAL_DIR / "eval_report.md"

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8080/api/v1/chat")
CHAT_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")


def load_questions():
    """加载题目"""
    if not QUESTIONS_FILE.exists():
        print(f"ERROR: 题库不存在 {QUESTIONS_FILE}, 先跑 gen_questions.py")
        sys.exit(1)
    items = []
    with open(QUESTIONS_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def call_chat(question, top_k=5):
    """调 chat 接口, 返回 {answer, citations, stateHint, traceId} 或 None"""
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {CHAT_TOKEN}",
    }
    body = {"query": question, "top_k": top_k}
    try:
        r = requests.post(CHAT_URL, headers=headers, json=body, timeout=90)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        print(f"  [error] chat failed: {e}")
        return None


def tokenize_zh(text):
    """中文分词(jieba), fallback 到字符级。"""
    try:
        import jieba
        return [t for t in jieba.cut(text) if t.strip()]
    except ImportError:
        # fallback 到字符级 (jieba 没装时)
        return list(text)


def f1_score(pred, gold):
    """token-level F1"""
    pred_tokens = tokenize_zh(pred)
    gold_tokens = tokenize_zh(gold)
    if not pred_tokens or not gold_tokens:
        return 0.0
    common = Counter(pred_tokens) & Counter(gold_tokens)
    num_same = sum(common.values())
    if num_same == 0:
        return 0.0
    precision = num_same / len(pred_tokens)
    recall = num_same / len(gold_tokens)
    return 2 * precision * recall / (precision + recall)


def em_score(pred, gold):
    """exact match (normalize 后)"""
    def norm(s):
        return "".join(s.split()).lower()
    return 1.0 if norm(pred) == norm(gold) else 0.0


def evaluate_one(question_item):
    """跑一题, 返回评测结果"""
    q = question_item["question"]
    gt_answer = question_item["ground_truth_answer"]
    gt_chunk_id = question_item.get("ground_truth_chunk_id")

    resp = call_chat(q, top_k=5)
    if resp is None:
        return {"question": q, "error": "chat_failed"}

    answer = resp.get("answer", "")
    citations = resp.get("citations", [])
    retrieved_chunk_ids = [c.get("chunk_id") for c in citations]

    # 指标计算
    f1 = f1_score(answer, gt_answer)
    em = em_score(answer, gt_answer)

    # context 指标: ground_truth_chunk_id 是否在检索结果中
    ctx_hit = (gt_chunk_id in retrieved_chunk_ids) if gt_chunk_id else False

    # Context Recall: ground_truth_chunk 是否在 top-k (passes@k)
    ctx_recall = 1.0 if ctx_hit else 0.0

    # Context Precision (RAGAS 简化版, 无 LLM judge):
    # 假设只有 ground_truth_chunk 相关, 其余为冗余。
    # relevant 位次的 precision@i 均值 → 等价于 1 / hit_rank (MRR-style)。
    # 命中位次越靠前 → 冗余越少 → precision 越高。
    if ctx_hit and len(retrieved_chunk_ids) > 0:
        hit_rank = retrieved_chunk_ids.index(gt_chunk_id) + 1
        ctx_precision = 1.0 / hit_rank
    else:
        ctx_precision = 0.0

    return {
        "question": q,
        "ground_truth_answer": gt_answer,
        "ground_truth_chunk_id": gt_chunk_id,
        "answer": answer,
        # Spring Boot Jackson SNAKE_CASE: state_hint, trace_id
        "state_hint": resp.get("state_hint"),
        "retrieved_chunk_ids": retrieved_chunk_ids,
        "metrics": {
            "answer_f1": round(f1, 4),
            "answer_em": em,
            "context_precision": round(ctx_precision, 4),
            "context_recall": ctx_recall,
        },
    }


def gen_report(results):
    """生成 Markdown 报告"""
    valid = [r for r in results if "error" not in r]
    total = len(results)
    n_ok = len(valid)
    n_err = total - n_ok

    if n_ok == 0:
        return "# V2-C 评测报告\n\n所有题目失败, 无法计算指标\n"

    avg_f1 = sum(r["metrics"]["answer_f1"] for r in valid) / n_ok
    avg_em = sum(r["metrics"]["answer_em"] for r in valid) / n_ok
    avg_cp = sum(r["metrics"]["context_precision"] for r in valid) / n_ok
    avg_cr = sum(r["metrics"]["context_recall"] for r in valid) / n_ok

    # state_hint 分布
    state_dist = Counter(r.get("state_hint") for r in valid)

    # 每个 chunk 的召回率
    chunk_recall = {}
    for r in valid:
        gt = r.get("ground_truth_chunk_id")
        if gt is None:
            continue
        chunk_recall.setdefault(gt, {"total": 0, "hit": 0})
        chunk_recall[gt]["total"] += 1
        if r["metrics"]["context_recall"] == 1.0:
            chunk_recall[gt]["hit"] += 1

    md = f"""# V2-C 评测报告

## 总览

| 指标 | 数值 |
|---|---|
| 评测题数 | {total} |
| 成功调用 | {n_ok} |
| 失败 | {n_err} |

## 核心指标(平均)

| 指标 | 数值 | 说明 |
|---|---|---|
| Context Recall | {avg_cr:.4f} | ground truth chunk 命中比例 |
| Context Precision | {avg_cp:.4f} | 召回质量(位次越靠前越高) |
| Answer F1 | {avg_f1:.4f} | answer vs ground_truth token F1 |
| Answer EM | {avg_em:.4f} | exact match(中文场景参考值) |

## state_hint 分布

| state | 数量 | 占比 |
|---|---|---|
"""
    for k, v in state_dist.most_common():
        md += f"| {k} | {v} | {v/n_ok*100:.1f}% |\n"

    md += f"""
## 按 chunk 召回率分解

| chunk_id | 题数 | 命中数 | 命中率 |
|---|---|---|---|
"""
    for cid, s in sorted(chunk_recall.items()):
        rate = s["hit"] / s["total"] if s["total"] else 0
        md += f"| {cid} | {s['total']} | {s['hit']} | {rate*100:.1f}% |\n"

    return md


def main():
    questions = load_questions()
    print(f"[1/3] 加载题库: {len(questions)} 题")
    print(f"[2/3] 调用 chat 接口 ({CHAT_URL})...")

    results = []
    for i, q in enumerate(questions, 1):
        print(f"  [{i}/{len(questions)}] {q['question'][:50]}", end=" ... ", flush=True)
        result = evaluate_one(q)
        if "error" in result:
            print("✗")
        else:
            m = result["metrics"]
            print(
                f"✓ f1={m['answer_f1']} recall={m['context_recall']} state={result['state_hint']}"
            )
        results.append(result)
        # 礼让 LLM 不打爆 rate limit (DashScope qwen-max 限制 60 QPM)
        time.sleep(2)

    print(f"[3/3] 写入详细结果 + 报告")
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    report = gen_report(results)
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        f.write(report)

    print(f"\n✓ 完成。详细结果: {OUT_FILE}")
    print(f"✓ 报告: {REPORT_FILE}")
    print()
    print(report)


if __name__ == "__main__":
    main()
