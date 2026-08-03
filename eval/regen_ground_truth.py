#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 2.0.1 — 重新生成 golden.jsonl 的 ground_truth_chunk_id / ground_truth_answer。

背景(corp_coverage_audit 暴露):
  - 旧 GT 的 chunk_id 是早期 corpus 时代的 id, 当前 corpus rebuild 后内容已完全错位
    (chunk_id=101 标"加权轮询"题, 实际内容是"反骚扰政策")
  - 这让 context_recall / context_precision 等指标无意义

策略(检索+LLM 评判, 客观重生):
  对每题:
  1) 用 chat-app 的真实 chained retrieval (现 reranker 状态) 拉 top10 候选 chunk
  2) 让 DeepSeek judge 判断哪个 chunk 最能回答此题, 并给出答案
  3) 把最优 chunk_id + LLM 答案 → 新 GT
  4) 全部都不行(出 "10 个都不相关") → 标 ungroundable=true, 仍保留候选最佳 + 标注
输出:
  - golden_v2.jsonl: 重生版 GT, 与原 schema 兼容 + 加新字段
    new_ground_truth_chunk_id, new_ground_truth_answer, new_ground_source,
    ungroundable(boolean), regen_method, regen_at
用法:
  python3 eval/regen_ground_truth.py --questions eval/golden/golden.jsonl \
    --out eval/golden/golden_v2.jsonl
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = EVAL_DIR.parent
try:
    from dotenv import load_dotenv
    load_dotenv(PROJECT_ROOT / ".env", override=False)
except ImportError:
    pass

sys.path.insert(0, str(EVAL_DIR))
from judge_client import build_judge_llm  # noqa: E402

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8090/api/v1/chat")
CHAT_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")


def fetch_candidates(question: str, top_k: int = 10) -> list[dict]:
    """调 chat-app 取 top_k 候选 chunk(分 数低也保留)。"""
    import requests
    r = requests.post(CHAT_URL,
                      headers={"Content-Type": "application/json",
                               "Authorization": f"Bearer {CHAT_TOKEN}"},
                      json={"query": question, "top_k": top_k}, timeout=90)
    r.raise_for_status()
    data = r.json()
    cands = []
    for c in data.get("citations", [])[:top_k]:
        cands.append({
            "chunk_id": c.get("chunk_id"),
            "doc_id": c.get("doc_id"),
            "snippet": (c.get("llm_context") or c.get("snippet") or "")[:600],
            "score": c.get("score", 0),
        })
    return cands


def llm_pick_best(question: str, candidates: list[dict], provider_id: int = 1
                  ) -> tuple[int | None, str, dict]:
    """让 DeepSeek 选最优 chunk + 给答案。

    返回 (best_chunk_id or None, answer, meta).
    meta 含 raw_decision(可能是 'all_unrelated' / 'direct_match').
    """
    from langchain_openai import ChatOpenAI
    from judge_client import get_provider_meta, _read_provider_env

    meta = get_provider_meta(provider_id)
    cfg = _read_provider_env(provider_id)
    base_url, api_key, model = cfg

    raw_llm = ChatOpenAI(base_url=base_url, api_key=api_key, model=model,
                         temperature=0.0, timeout=120, max_retries=2)

    # 构造 prompt: 把候选 chunks 展示给 LLM, 让它选 + 给答
    cand_block = ""
    for i, c in enumerate(candidates, 1):
        cand_block += f"\n[{i}] chunk_id={c['chunk_id']} doc_id={c['doc_id']}\n{c['snippet']}\n"
    sys_prompt = (
        "你是 RAG 评测标注员。给定用户问题与若干候选检索片段, 你的任务:\n"
        "1. 选出 '最直接回答此问题' 的那一个片段(按相关度, 不要求完美); \n"
        "2. 用 1-2 句话回答用户问题(基于所选片段); \n"
        "3. 如果所有片段都完全无关, 选 BEST_CHUNK_INDEX=0 并回答'无相关片段'。\n\n"
        "严格按以下 JSON 输出, 不要 markdown 围栏:\n"
        '{"BEST_CHUNK_INDEX": <整数,1-based 或 0>, "ANSWER": "<1-2 句简答>"}'
    )
    user_prompt = f"用户问题: {question}\n候选片段: {cand_block}"

    resp = raw_llm.invoke([
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": user_prompt},
    ])
    text = resp.content if hasattr(resp, "content") else str(resp)
    text = text.strip()
    # 去除可能的 markdown 围栏
    if text.startswith("```"):
        text = text.strip("`").lstrip("json").strip()
        # 末尾 fenced 也处理
        if text.endswith("```"):
            text = text[:-3].strip()
    try:
        parsed = json.loads(text)
        idx = int(parsed.get("BEST_CHUNK_INDEX", 0))
        ans = (parsed.get("ANSWER") or "").strip()
    except Exception as e:
        return None, f"(LLM 输出解析失败: {e}; raw={text[:200]})", {"raw": text[:300]}

    meta_out = {"decision": "direct_match" if idx >= 1 else "all_unrelated"}
    if idx >= 1 and idx <= len(candidates):
        return candidates[idx - 1]["chunk_id"], ans, meta_out
    return None, ans, meta_out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", default=str(EVAL_DIR / "golden" / "golden.jsonl"))
    ap.add_argument("--out", default=str(EVAL_DIR / "golden" / "golden_v2.jsonl"))
    ap.add_argument("--judge-provider", type=int, default=1)
    ap.add_argument("--top-k", type=int, default=10)
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 题(测试用), 0=全部")
    ap.add_argument("--resume", action="store_true",
                    help="断点续跑: 读 out 文件已完成的题, 跳过; 对剩余题继续")
    args = ap.parse_args()

    questions = [json.loads(l) for l in open(args.questions, encoding="utf-8") if l.strip()]
    if args.limit:
        questions = questions[:args.limit]
    print(f"[regen] 题库 {len(questions)} 题, judge provider #{args.judge_provider}, top_k={args.top_k}")

    # Resume: 读已成题, 跳过
    done = {}  # question -> record
    if args.resume and os.path.exists(args.out):
        for line in open(args.out, encoding="utf-8"):
            try:
                d = json.loads(line)
                if d.get("question"):
                    done[d["question"]] = d
            except json.JSONDecodeError:
                pass
        print(f"[regen] resume: 已完成 {len(done)} 题, 跳过; 剩余 {len(questions)-len(done)} 题")

    # 输出文件用 append-like: 已成题落盘 + 新题逐条 写入, 一律 append 模式
    # open at start in write mode only if not resume; resume 时保持原文件不动逐条 append
    if args.resume and os.path.exists(args.out):
        out_f = open(args.out, "a", encoding="utf-8")
    else:
        out_f = open(args.out, "w", encoding="utf-8")
        # 把已成题如已写入也算了(允许 safety)
    ts = datetime.now(timezone.utc).isoformat()
    stats = {"direct_match": 0, "all_unrelated": 0, "llm_parse_fail": 0, "resumed": 0}

    for i, q in enumerate(questions, 1):
        question = q["question"]
        # resume 跳过
        if question in done:
            stats["resumed"] += 1
            # 统计 ungroundable
            if done[question].get("ungroundable"):
                stats["all_unrelated"] += 1
            elif done[question].get("new_ground_truth_chunk_id") is not None:
                stats["direct_match"] += 1
            print(f"[{i}/{len(questions)}] {question[:50]} ... (skip, done)")
            continue

        print(f"\n[{i}/{len(questions)}] {question[:50]}", end=" ... ", flush=True)
        try:
            candidates = fetch_candidates(question, top_k=args.top_k)
        except Exception as e:
            print(f"✗ chat fail: {e}")
            stats["chat_fail"] = stats.get("chat_fail", 0) + 1
            q["new_ground_truth_chunk_id"] = None
            q["new_ground_truth_answer"] = f"(chat fail: {e})"
            q["ungroundable"] = True
            q["regen_method"] = "chat_failed"
            q["regen_at"] = ts
            out_f.write(json.dumps(q, ensure_ascii=False) + "\n")
            out_f.flush()
            time.sleep(1)
            continue

        if not candidates:
            print("✗ no candidates")
            q["new_ground_truth_chunk_id"] = None
            q["new_ground_truth_answer"] = ""
            q["ungroundable"] = True
            q["regen_method"] = "no_candidates"
            q["regen_at"] = ts
            out_f.write(json.dumps(q, ensure_ascii=False) + "\n")
            out_f.flush()
            stats["all_unrelated"] += 1
            continue

        try:
            best_id, ans, meta = llm_pick_best(question, candidates, args.judge_provider)
        except Exception as e:
            print(f"✗ llm fail: {e}")
            stats["llm_parse_fail"] += 1
            q["new_ground_truth_chunk_id"] = candidates[0]["chunk_id"]
            q["new_ground_truth_answer"] = ""
            q["ungroundable"] = False
            q["regen_method"] = "llm_failed_fallback_top1"
            q["regen_at"] = ts
            out_f.write(json.dumps(q, ensure_ascii=False) + "\n")
            out_f.flush()
            time.sleep(2)
            continue

        q["new_ground_truth_chunk_id"] = best_id
        q["new_ground_truth_answer"] = ans
        q["ungroundable"] = (best_id is None)
        q["regen_method"] = meta.get("decision", "unknown")
        q["regen_at"] = ts
        q["candidate_chunk_ids"] = [c["chunk_id"] for c in candidates]

        if best_id is None:
            stats["all_unrelated"] += 1
            print(f"⚠️ ungroundable (no related chunk)")
        else:
            stats["direct_match"] += 1
            print(f"✓ chunk={best_id} | ans: {ans[:50]}")
        out_f.write(json.dumps(q, ensure_ascii=False) + "\n")
        out_f.flush()
        time.sleep(1.5)  # 礼让 LLM

    out_f.close()
    print(f"\n✓ 落盘 {args.out}")
    print("分布:")
    for k, v in stats.items():
        print(f"  {k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
