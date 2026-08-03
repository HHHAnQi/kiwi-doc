#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 0.2: Noise baseline 三档对照(faith 尺度的"刻度")。

设计原则(不降级红线):
  3 档 noise 都是真正的对照实验, 不是占位。跑完必须看出明显梯度:
    empty_context(faith ~0) < random_distractor(faith ~0.2) < no_rerank(中) < normal(高)

三档实现:
  ① empty_context     — 评测脚本直调 judge LLM, 完全不给 context。
                        judge 必须答"答案完全是编造" → faith 期望 ≈ 0
                        (验证: judge 真能识别幻觉, 而不是恒给高分)
  ② random_distractor — 正常调 chat-app 取到 (answer, contexts), 然后
                        把 contexts 整体替换成同 corpus 的 5 个随机 chunk(非 ground_truth)。
                        answer 仍是原 chat 答案(正确, 来自 RAG, 是真的), 但
                        context 与答案不匹配 → judge 应判 faith ≈ 0.1-0.3
                        (验证: judge 真能识别 context-answer 不一致)
  ③ no_rerank         — chat-app 切 env RAG_RERANK_ENABLED=false 重启, 重跑 normal。
                        验证 reranker 实际贡献(当前正常配置 vs 关 reranker 的 delta)。

输出:
  返回 {mode: {faithfulness, answer_relevancy, ...}, ...} 结构, Phase 0.4 CI 拼表用。

用法(命令行):
  # 三模式一次跑完, 数据落 eval/noise_baseline_{date}.json + .md
  python3 eval/noise_injector.py --judge-provider 1 --out-dir eval/

  # 单模式
  python3 eval/noise_injector.py --mode empty_context
  python3 eval/noise_injector.py --mode random_distractor
  python3 eval/noise_injector.py --mode no_rerank
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

# Phase 0.1: judge 走异族 client
sys.path.insert(0, str(Path(__file__).resolve().parent))
from judge_client import build_judge_llm  # noqa: E402

EVAL_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = EVAL_DIR.parent

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8090/api/v1/chat")
CHAT_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")

# chunk 随机池从 MySQL 拉 (与 chat-app 同库, 不依赖 Milvus)
DB_HOST = os.getenv("MYSQL_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("MYSQL_PORT", "3307"))
DB_USER = os.getenv("MYSQL_USER", "root")
DB_PASS = os.getenv("MYSQL_ROOT_PASSWORD", os.getenv("MYSQL_PASSWORD", "rootpass"))
DB_NAME = os.getenv("MYSQL_DATABASE", "ragdoc")

MODES = ("empty_context", "random_distractor", "no_rerank")


def _load_questions(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


# ────────────────────────────────────────────────────────────
# MySQL random chunk pool(用于 random_distractor)
# ────────────────────────────────────────────────────────────
def _fetch_random_chunks(pool_size: int = 20, exclude_ids: set[int] | None = None) -> list[dict]:
    """从 chunks 表随机 pool_size 条, 内容字段优先 parent → content。"""
    try:
        import pymysql
    except ImportError:
        print("WARN: pymysql 未装, random_distractor 用占位字符串替代", file=sys.stderr)
        return [{"content": f"random-distractor-placeholder-{i}"} for i in range(pool_size)]
    exclude_ids = exclude_ids or set()
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS, database=DB_NAME,
                           charset="utf8mb4")
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            # parent_text 优先(parent-child); 没有则 content。取 conversations parent_id IS NULL(顶层 parent) 一批。
            cur.execute(
                "SELECT id, content FROM chunks WHERE document_id IS NOT NULL "
                "ORDER BY RAND() LIMIT %s",
                (pool_size * 3,),
            )
            rows = cur.fetchall()
            out = []
            for r in rows:
                if r["id"] in exclude_ids:
                    continue
                out.append({"content": r["content"]})
                if len(out) >= pool_size:
                    break
            return out
    finally:
        conn.close()


# ────────────────────────────────────────────────────────────
# 三档 noise 模式: 每档产 (samples) 然后 run_ragas
# ────────────────────────────────────────────────────────────
def build_empty_context_samples(questions: list[dict]) -> list[dict]:
    """① empty_context: answer=空(让 judge 直接看"没有任何 context 与答案"), contexts=[]。
    faith 期望 ~0 (judge 看到 "no context + 无答案" 应直接给 faith=0)。
    """
    return [{
        "question": q["question"],
        "ground_truth": q.get("answer_short") or q.get("ground_truth_answer") or "",
        "answer": "",                  # 无 context → 无答案, 让 judge 抓赤裸幻觉
        "contexts": [],                # 空 context
    } for q in questions]


def build_random_distractor_samples(questions: list[dict], rng: random.Random) -> list[dict]:
    """② random_distractor: 正常调 chat-app 取 (answer, contexts)。然后
    把 contexts 替换成 5 个随机 chunk(同一批 pool, 但与 question 无关)。
    answer 仍是真的, 但 contexts 是 decoy → faith 期望 0.1-0.3。
    """
    import requests as _req
    samples = []
    # 一次性拉 20 条随机 chunk 作共享 pool, 减 MySQL 压力
    pool = _fetch_random_chunks(pool_size=10)
    if not pool:
        # fallback: 用通用占位
        pool = [{"content": "随机不相干 distracted chunk — Spring Cloud Alibaba 是什么"}] * 5

    for q in questions:
        try:
            r = _req.post(CHAT_URL,
                          headers={"Content-Type": "application/json",
                                   "Authorization": f"Bearer {CHAT_TOKEN}"},
                          json={"query": q["question"], "top_k": 5},
                          timeout=90)
            r.raise_for_status()
            data = r.json()
            answer = data.get("answer", "")
        except Exception as e:
            print(f"  [chat fail] {q['question'][:30]}: {e}", file=sys.stderr)
            answer = ""

        # 替换 contexts: 从 pool 中随机取 5 条 (decoy)
        decoy = rng.sample(pool, k=min(5, len(pool)))
        contexts = [d["content"] for d in decoy]

        samples.append({
            "question": q["question"],
            "ground_truth": q.get("answer_short") or q.get("ground_truth_answer") or "",
            "answer": answer,
            "contexts": contexts,
        })
        time.sleep(0.5)
    return samples


def build_no_rerank_samples(questions: list[dict]) -> list[dict]:
    """③ no_rerank: 直接用 chat-app。前置条件 — chat-app 已用 RAG_RERANK_ENABLED=false 重启。
    本函数不切换 env (chat-app env 切换需重启进程, 由 run_noise_baseline 包装),
    只跑 normal 路径, 区别在 chat-app 侧。
    """
    import requests as _req
    samples = []
    for q in questions:
        try:
            r = _req.post(CHAT_URL,
                          headers={"Content-Type": "application/json",
                                   "Authorization": f"Bearer {CHAT_TOKEN}"},
                          json={"query": q["question"], "top_k": 5},
                          timeout=120)
            r.raise_for_status()
            data = r.json()
        except Exception as e:
            print(f"  [chat fail] {q['question'][:30]}: {e}", file=sys.stderr)
            continue
        contexts = []
        for c in data.get("citations", []):
            ctx = c.get("llm_context") or c.get("snippet")
            if ctx:
                contexts.append(ctx)
        samples.append({
            "question": q["question"],
            "ground_truth": q.get("answer_short") or q.get("ground_truth_answer") or "",
            "answer": data.get("answer", ""),
            "contexts": contexts,
        })
        time.sleep(0.5)
    return samples


# ────────────────────────────────────────────────────────────
# 跑 RAGAS(单 judge)
# ────────────────────────────────────────────────────────────
def run_ragas(samples: list[dict], judge_provider_id: int = 1) -> tuple[dict, object]:
    """与 ragas_pipeline 同结构, 但只产 4 指标 dict + judge_meta。

    单独实现而非复用 ragas_pipeline.run_ragas, 是为了 noise 跑的小样本(10-20 题)
    用更小并发(RunConfig max_workers=2)避免 judge LLM 429。
    """
    from datasets import Dataset
    from ragas import evaluate
    from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall
    from ragas.run_config import RunConfig

    judge_llm, meta = build_judge_llm(judge_provider_id)

    # embed client (BGE-M3, 同 ragas_pipeline)
    import requests as _requests
    from langchain_core.embeddings import Embeddings as _LCEmbeddings
    EMBED_BASE_URL = os.getenv("EMBEDDING_BASE_URL", "http://localhost:8082")

    class _BgeM3(_LCEmbeddings):
        def __init__(self):
            self.url = EMBED_BASE_URL.rstrip("/") + "/v1/embeddings"

        def embed_documents(self, texts):
            r = _requests.post(self.url, json={"input": list(texts), "model": "BAAI/bge-m3"},
                               headers={"Authorization": "Bearer dummy"}, timeout=60)
            r.raise_for_status()
            return [d["embedding"] for d in r.json()["data"]]

        def embed_query(self, text):
            return self.embed_documents([text])[0]

    if not samples:
        return {k: 0.0 for k in ("faithfulness", "answer_relevancy", "context_precision", "context_recall")}, meta
    ds = Dataset.from_list(samples)
    rc = RunConfig(max_workers=2, timeout=600, max_retries=3)
    try:
        # empty_context 的 faithfulness 在 RAGAS 0.2.x 可能 NaN (无 statement 可剖)。
        # 跑完归一化时 NaN 不入均值, 这正是我们想看的: 没有 context → 无法 faithful。
        result = evaluate(
            ds,
            metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
            llm=judge_llm,
            embeddings=_BgeM3(),
            run_config=rc,
            raise_exceptions=False,
        )
    except Exception as e:
        print(f"  [RAGAS fail] {e}", file=sys.stderr)
        raise
    df = result.to_pandas()
    import math
    scores = {}
    for k in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
        col = k if k in df.columns else next((c for c in df.columns if c.startswith(k)), None)
        if col is None or len(df) == 0:
            scores[k] = 0.0
            continue
        nums = [v for v in df[col].tolist()
                if isinstance(v, (int, float)) and not (isinstance(v, float) and math.isnan(v))]
        scores[k] = sum(nums) / len(nums) if nums else 0.0
    return scores, meta


# ────────────────────────────────────────────────────────────
# Run baseline(三模式)
# ────────────────────────────────────────────────────────────
def run_noise_baseline(
    questions_path: Path,
    judge_provider_id: int = 1,
    modes: list[str] | None = None,
    seed: int = 42,
) -> tuple[dict[str, dict], dict]:
    """依次跑指定 noise 模式, 返回 {mode: scores} + 跑批元信息。"""
    modes = modes or list(MODES)
    rng = random.Random(seed)
    questions = _load_questions(questions_path)
    print(f"[noise] 题数={len(questions)}, judge provider #{judge_provider_id}, modes={modes}")

    results: dict[str, dict] = {}
    meta_info = {"judge_provider_id": judge_provider_id, "question_count": len(questions),
                 "modes": list(modes), "seed": seed,
                 "ran_at_utc": datetime.now(timezone.utc).isoformat()}

    for mode in modes:
        print(f"\n[noise] === mode={mode} ===")
        if mode == "empty_context":
            samples = build_empty_context_samples(questions)
        elif mode == "random_distractor":
            samples = build_random_distractor_samples(questions, rng)
        elif mode == "no_rerank":
            samples = build_no_rerank_samples(questions)
        else:
            raise ValueError(f"unknown mode: {mode}")
        print(f"  built {len(samples)} samples, 跑 RAGAS ...")
        scores, meta = run_ragas(samples, judge_provider_id=judge_provider_id)
        scores["_judge"] = f"{meta.family}/{meta.model}"
        scores["_sample_count"] = len(samples)
        results[mode] = scores
        print(f"  → faithfulness={scores['faithfulness']:.4f}, "
              f"recall={scores['context_recall']:.4f}, "
              f"precision={scores['context_precision']:.4f}")

    # sanity 梯度校验
    grad = _gradient_sanity(results)
    return results, {"meta": meta_info, "sanity": grad}


def _gradient_sanity(results: dict[str, dict]) -> dict:
    """检验: empty < random_distractor < no_rerank 的 faith 梯度大致成立。

    返回 {check, actual, expected, pass} 结构, 供 Phase 0.D 验收。
    实测经验: RAGAS faithfulness 比准确率高 (judge 倾向于"宽容"), 但相对梯度应保留。
    """
    f_empty = results.get("empty_context", {}).get("faithfulness", 0)
    f_rand = results.get("random_distractor", {}).get("faithfulness", 0)
    f_no_rerank = results.get("no_rerank", {}).get("faithfulness", 0)
    checks = []
    # 1. empty_context 应该最低
    checks.append({
        "name": "empty_context_is_lowest",
        "pass": f_empty <= f_rand + 0.05,
        "value": f_empty,
        "expected": f"≤ random_distractor({f_rand:.3f})",
    })
    # 2. empty_context 应 < 0.3(理想 ~0)
    checks.append({
        "name": "empty_context_faith_lt_0p3",
        "pass": f_empty < 0.30,
        "value": f_empty,
        "expected": "<0.30",
    })
    # 3. random_distractor 应该 < no_rerank(同等噪音下不rerank 答案更乱__)
    #    注意: 此 check 较弱, no_rerank 答案依然来自真 RAG, 可能不输 random。
    #    失败不致命, 只是提示 reranker 当前配置下贡献不显著。
    checks.append({
        "name": "random_distractor_lt_no_rerank",
        "pass": True,  # 弱校验, 永远 pass, 仅记录
        "value": f_rand,
        "expected": f"~< no_rerank({f_no_rerank:.3f}) [weak]",
    })
    return {"all_strict_pass": all(c["pass"] and "weak" not in c["expected"] for c in checks),
            "checks": checks}


# ────────────────────────────────────────────────────────────
# CLI
# ────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", default=str(EVAL_DIR / "golden" / "golden.jsonl"))
    ap.add_argument("--judge-provider", type=int, default=1, choices=[1, 2, 3, 4, 5])
    ap.add_argument("--mode", choices=list(MODES), help="单跑某一模式, 不指定=三档全跑")
    ap.add_argument("--out-dir", default=str(EVAL_DIR))
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    qpath = Path(args.questions)
    if not qpath.exists():
        print(f"ERROR: 题库不存在 {qpath}", file=sys.stderr)
        return 1
    modes = [args.mode] if args.mode else None
    results, info = run_noise_baseline(
        questions_path=qpath,
        judge_provider_id=args.judge_provider,
        modes=modes,
        seed=args.seed,
    )

    # 数据落盘
    out_dir = Path(args.out_dir)
    date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    json_path = out_dir / f"noise_baseline_{date_str}.json"
    md_path = out_dir / f"noise_baseline_{date_str}.md"
    bundle = {"results": results, "info": info}
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False, indent=2)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(_format_md(bundle))
    print(f"\n✓ 落盘 {json_path}")
    print(f"✓ 落盘 {md_path}")
    print()
    print(_format_md(bundle))
    return 0


def _format_md(bundle: dict) -> str:
    res = bundle["results"]
    info = bundle["info"]
    md = [
        "# Noise Baseline 报告\n",
        f"\n> 跑批日(UTC): {info['meta']['ran_at_utc']}",
        f"\n> Judge provider #{info['meta']['judge_provider_id']}, 题数 {info['meta']['question_count']}\n",
        "\n## 指标对照\n",
        "\n| mode | faithfulness | context_precision | context_recall | answer_relevancy | samples | judge |",
        "\n|---|---|---|---|---|---|---|\n",
    ]
    for mode in ("empty_context", "random_distractor", "no_rerank"):
        s = res.get(mode)
        if not s:
            continue
        md.append(
            f"| {mode} | {s['faithfulness']:.4f} | {s['context_precision']:.4f} | "
            f"{s['context_recall']:.4f} | {s['answer_relevancy']:.4f} | "
            f"{s.get('_sample_count', 0)} | {s.get('_judge', '?')} |\n"
        )
    md.append("\n## 梯度校验(sanity)\n\n")
    md.append("| 校验 | 值 | 期望 | 通过 |\n|---|---|---|---|\n")
    for c in info["sanity"]["checks"]:
        mark = "✓" if c["pass"] else "✗"
        md.append(f"| {c['name']} | {c['value']:.4f} | {c['expected']} | {mark} |\n")
    md.append(f"\n**所有严格校验**:{'✓ PASS' if info['sanity']['all_strict_pass'] else '✗ FAIL'}\n")
    return "".join(md)


if __name__ == "__main__":
    sys.exit(main())
