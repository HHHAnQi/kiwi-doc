#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Retrieval Evaluation Framework — 主入口。

对每题做两段评测:
  检索段:  POST /api/v1/retrieve → 5 指标 (Recall@K / Precision@K / HitRate / MRR / NDCG)
  生成段:  POST /api/v1/chat      → 3 指标 (Answer Correctness / Faithfulness / Citation Accuracy)
           (调真实 LLM, 默认 --no-skip-generation)

产物: eval/eval_report.json (含 dataset_size / metrics / timestamp / 模型版本信息)。

用法 (举例):
    # 1. 起 backend: make run (会在 source .env 后启动, 含 RAG_RERANK_ENABLED / RAG_PROMPT_V2)
    # 2. (如开 reranker) 起 Autodl ssh 隧道 18080→6006
    # 3. 跑:
    python3 eval/runner/run_eval.py \
        --dataset eval/datasets/retrieval_eval.jsonl \
        --k 5 \
        --output eval/eval_report.json

    # 只跑检索段 (生成段跳过, 节省 LLM 钱):
    python3 eval/runner/run_eval.py --skip-generation

    # CI 门禁: 任一检索指标比 baseline 退超 3pp → 退出非零:
    python3 eval/runner/run_eval.py --baseline eval/baseline.json --gate 0.03
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))
# 客户端模块在 import 时读取 URL/token，必须先加载 .env，避免误连残留服务。
load_dotenv(REPO_ROOT / ".env", override=False)

# 让 runner 模块能直接相对 import (python3 eval/runner/run_eval.py 时)
from eval.metrics import retrieval_metrics as rm  # noqa: E402
from eval.metrics import generation_metrics as gm  # noqa: E402
from eval.runner import retrieve_client, chat_client, judge_client  # noqa: E402


def _load_dataset(path: Path) -> list[dict]:
    out: list[dict] = []
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if "question" not in d or "gold_chunk_ids" not in d:
                raise ValueError(f"dataset 行 {i} 缺必填字段 question/gold_chunk_ids: {line[:80]}")
            out.append(d)
    return out


def run_retrieval(query: str, gold: list[int], k: int, api_base_url=None, **kw) -> tuple[dict, dict]:
    """调 /retrieve 算 5 指标, 返回 (metrics_dict, retrieve 原始响应——供生成段复用 context)。"""
    call_kw = _filter_kw(kw)
    if api_base_url:
        call_kw["base_url"] = f"{api_base_url.rstrip('/')}/api/v1/retrieve"
    resp = retrieve_client.retrieve(query, top_k=k, **call_kw)
    ids, items = retrieve_client.extracted(resp)
    return rm.per_query_metrics(ids, gold, k), resp


def run_generation(
    query, gold, gold_answer, pred_context, k, judge_fn, api_base_url=None, **kw
) -> tuple[dict | None, dict]:
    """调 /chat 算生成指标，并返回逐题审计信息。

    Faithfulness 优先使用本次 /chat 实际 citations 携带的上下文；只有服务未返回
    citation context 时，才回退到预检索上下文，避免评测证据与真实生成证据错位。
    """
    call_kw = _filter_kw(kw)
    if api_base_url:
        call_kw["base_url"] = f"{api_base_url.rstrip('/')}/api/v1/chat"
    chat_resp = chat_client.chat(query, top_k=k, **call_kw)
    answer, cited, state_hint = chat_client.parse(chat_resp)
    citations = chat_resp.get("citations") or []
    actual_ctx = "\n\n".join((c.get("llm_context") or "") for c in citations).strip()
    ctx = actual_ctx or pred_context
    audit = {
        "answer": answer,
        "state_hint": state_hint,
        "cited_chunk_ids": cited,
        "gold_chunk_ids": list(gold),
        "context_source": "chat_citations" if actual_ctx else "retrieve_fallback",
        "citations": citations,
    }
    if state_hint != "OK":
        # 降级话术不是模型答案，不能送 Judge 后以 0 分混入质量均值。
        return None, audit
    metrics = {
        "answer_correctness": gm.answer_correctness(
            answer, gold_answer or "", judge_fn, question=query
        ),
        "faithfulness": gm.faithfulness(answer, ctx, judge_fn),
        "evidence_completeness": gm.evidence_completeness(gold_answer or "", ctx, judge_fn),
        "citation_accuracy": gm.citation_accuracy(cited, gold),
        "citation_recall": gm.citation_recall(cited, gold),
        "citation_hit_rate": gm.citation_hit_rate(cited, gold),
    }
    return metrics, audit


def _filter_kw(kw: dict) -> dict:
    """只透传 retrieve/chat 支持的可选过滤参数。"""
    out = {}
    for key in ("doc_id", "source", "version", "language"):
        if key in kw and kw[key] is not None:
            out[key] = kw[key]
    return out


def _ground_truth_answer(case: dict) -> str:
    """当前 corpus 的人工修订答案必须优先于历史答案。"""
    return (
        case.get("gold_answer")
        or case.get("new_ground_truth_answer")
        or case.get("ground_truth_answer")
        or case.get("answer")
        or ""
    )


def main():
    p = argparse.ArgumentParser(description="Retrieval Evaluation Framework runner")
    p.add_argument("--dataset", default="eval/datasets/retrieval_eval.jsonl")
    p.add_argument("--k", type=int, default=5, help="Recall/Precision/NDCG cutoff")
    p.add_argument("--output", default="eval/eval_report.json")
    p.add_argument(
        "--skip-generation",
        action="store_true",
        help="跳过 /chat 调用与生成指标 (省 LLM)。检索段仍跑。",
    )
    p.add_argument("--baseline", default=None, help="可选 baseline JSON, 与检索指标对比")
    p.add_argument("--gate", type=float, default=0.0, help="指标退化阈值 (baseline - gate) 即 FAIL, 退出非零")
    p.add_argument("--limit", type=int, default=0, help="只跑前 N 题 (0=全部, 用于冒烟)")
    p.add_argument("--start", type=int, default=1, help="从第 N 题开始运行（1-based，默认 1）")
    p.add_argument(
        "--api-base-url",
        default=None,
        help="显式固定被测后端根地址，如 http://localhost:8096；会覆盖客户端默认 URL",
    )
    p.add_argument(
        "--judge-provider",
        type=int,
        choices=(1, 2),
        default=None,
        help="固定使用指定 Judge provider；默认按配置顺序失败回退",
    )
    args = p.parse_args()

    dataset_path = (REPO_ROOT / args.dataset).resolve()
    out_path = (REPO_ROOT / args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)

    cases = _load_dataset(dataset_path)
    if args.start < 1 or args.start > len(cases):
        p.error(f"--start 必须在 1..{len(cases)} 之间")
    cases = cases[args.start - 1 :]
    if args.limit > 0:
        cases = cases[:args.limit]
    print(f"[INFO] dataset={dataset_path} size={len(cases)} k={args.k} skip_gen={args.skip_generation}")

    judge_fn = (
        None
        if args.skip_generation
        else judge_client.make_judge_fn(provider_index=args.judge_provider)
    )

    per_q_retrieval: list[dict] = []
    per_q_generation: list[dict] = []
    per_q_generation_audit: list[dict] = []
    sample_meta: dict = {}  # 第一条 retrieve 响应抽模型版本信息

    for i, c in enumerate(cases, 1):
        gold = c.get("gold_chunk_ids") or []
        q = c["question"]
        kw = {k: c.get(k) for k in ("doc_id", "source", "version", "language")}
        gold_ans = _ground_truth_answer(c)

        retrieval_err = None
        # ---- retrieval ----
        try:
            m, resp = run_retrieval(q, gold, args.k, api_base_url=args.api_base_url, **kw)
            m["__question_id"] = c.get("id", args.start + i - 1)
            per_q_retrieval.append(m)
            if i == 1:
                sample_meta = {
                    "model_version": resp.get("model_version"),
                    "embedding_version": resp.get("embedding_version"),
                    "rerank_model": resp.get("rerank_model"),
                    "rerank_enabled": resp.get("rerank_enabled"),
                    "rerank_state_first": resp.get("rerank_state"),
                }
        except Exception as e:
            retrieval_err = str(e)
            print(f"[{i}] retrieve FAIL: {e}")
            per_q_retrieval.append({
                "__question_id": c.get("id", args.start + i - 1),
                "error": retrieval_err,
            })
            m = {}

        if args.skip_generation:
            print(f"[{i}/{len(cases)}] retrieval: {m}")
            continue

        # ---- generation ----
        # 用第一题已拿到的 retrieve 响应里的 llm_context 作 faithfulness 的 context
        try:
            ctx = ""
            # 再调一次 retrieve 拿 llm_context (与 chat 的 citations 一致性 best-effort)
            retrieve_kw = _filter_kw(kw)
            if args.api_base_url:
                retrieve_kw["base_url"] = f"{args.api_base_url.rstrip('/')}/api/v1/retrieve"
            items_resp = retrieve_client.retrieve(q, top_k=args.k, **retrieve_kw)
            _, items = retrieve_client.extracted(items_resp)
            ctx = "\n\n".join((it.get("llm_context") or "") for it in items).strip()
        except Exception:
            ctx = ""
        try:
            gm_metrics, audit = run_generation(
                q,
                gold,
                gold_ans,
                ctx,
                args.k,
                judge_fn,
                api_base_url=args.api_base_url,
                **kw,
            )
            audit_row = {
                "__question_id": c.get("id", args.start + i - 1),
                "question": q,
                "gold_answer": gold_ans,
                **audit,
            }
            per_q_generation_audit.append(audit_row)
            if gm_metrics is None:
                state_hint = audit.get("state_hint", "UNKNOWN")
                per_q_generation.append({
                    "__question_id": c.get("id", args.start + i - 1),
                    "error": f"non-OK generation state: {state_hint}",
                })
                print(f"[{i}/{len(cases)}] generation DEGRADED: {state_hint}")
                time.sleep(0.1)
                continue
            gm_metrics["__question_id"] = c.get("id", args.start + i - 1)
            per_q_generation.append(gm_metrics)
            print(f"[{i}/{len(cases)}] retrieval@{args.k}={ {k2:round(v,3) for k2,v in m.items() if not k2.startswith('__')} } gen={ {k2:round(v,3) for k2,v in gm_metrics.items() if not k2.startswith('__')} }")
        except Exception as e:
            print(f"[{i}] generation FAIL: {e}")
            per_q_generation.append({
                "__question_id": c.get("id", args.start + i - 1),
                "error": str(e),
            })
            per_q_generation_audit.append({
                "__question_id": c.get("id", args.start + i - 1),
                "question": q,
                "gold_answer": gold_ans,
                "error": str(e),
            })

        time.sleep(0.1)  # 轻微节流, 防 LLM rate limit

    # ---- aggregate ----
    clean_retrieval = [d for d in per_q_retrieval if "error" not in d]
    clean_gen = [d for d in per_q_generation if "error" not in d]

    metrics: dict[str, dict] = {
        "retrieval": {k: v for k, v in rm.aggregate(clean_retrieval).items() if not k.startswith("__")},
    }
    if not args.skip_generation:
        metrics["generation"] = {
            k: v for k, v in gm.aggregate_generation(clean_gen).items() if not k.startswith("__")
        }

    report = {
        "dataset_size": len(cases),
        "metrics": metrics,
        "per_query": {
            "retrieval": per_q_retrieval,
            "generation": per_q_generation if not args.skip_generation else [],
            "generation_audit": per_q_generation_audit if not args.skip_generation else [],
        },
        "retrieval_failures": sum(1 for d in per_q_retrieval if "error" in d),
        "generation_failures": sum(1 for d in per_q_generation if "error" in d),
        "k": args.k,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "model_version": sample_meta.get("model_version"),
        "embedding_version": sample_meta.get("embedding_version"),
        "rerank_model": sample_meta.get("rerank_model"),
        "rerank_enabled": sample_meta.get("rerank_enabled"),
        "judge_provider": args.judge_provider if judge_fn else None,
        "api_base_url": args.api_base_url,
        "judge_model": (
            judge_client.judge_model(args.judge_provider)
            if judge_fn and args.judge_provider is not None
            else judge_client.primary_judge_model() if judge_fn else None
        ),
        "skip_generation": args.skip_generation,
        "metric_definitions": {
            "evidence_completeness": "fraction of gold-answer key facts supported by actual chat context",
            "citation_accuracy": "exact-gold citation precision: |cited ∩ gold| / |cited|",
            "citation_recall": "exact-gold evidence coverage: |cited ∩ gold| / |gold|",
            "citation_hit_rate": "whether at least one exact gold chunk was cited",
        },
    }

    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[DONE] report → {out_path}")
    print(json.dumps(report["metrics"], ensure_ascii=False, indent=2))

    # ---- baseline gate (可选) ----
    if args.baseline:
        bpath = (REPO_ROOT / args.baseline).resolve()
        if bpath.exists():
            baseline = json.loads(bpath.read_text(encoding="utf-8"))
            failures = []
            for key, bval in (baseline.get("metrics", {}).get("retrieval", {}) or {}).items():
                cur = metrics["retrieval"].get(key)
                if cur is None:
                    continue
                delta = cur - bval
                if delta < -args.gate:
                    failures.append(f"{key}退步 {delta*100:.1f}pp (baseline={bval:.3f} cur={cur:.3f} 阈-{args.gate*100:.0f}pp)")
            if failures:
                print("\n[GATE] FAIL:")
                for f in failures:
                    print("  -", f)
                sys.exit(1)
            print(f"\n[GATE] PASS (检索指标退步均 < {args.gate*100:.0f}pp)")
        else:
            print(f"[GATE] 跳过, baseline 不存在: {bpath}")


if __name__ == "__main__":
    main()
