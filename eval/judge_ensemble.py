#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 0.3: Judge Ensemble + Badcase 落盘。

策略:
  对同一批 (samples, ground_truth), 每个 judge 各跑一次 RAGAS,
  产出 per-sample faithfulness。聚合:
    - per question: faith 多 judge 取均值(ensemble score);
    - 任一 judge faith 与 ensemble 偏移 > 0.2 → 进 badcase 队列,
      含 question, 各 judge faith, 答案, contexts, 入库 eval/badcases/。

为什么不用 majority vote:
  RAGAS faithfulness ∈ [0, 1] 连续值, 不存在"通过/不通过"的天然阈值。
  "Majority vote" 在连续值上等价于取均值, 这里直接 ensemble_mean + disagreement 落盘。

为什么依然有用:
  - 单 judge 偏差(如 glm 一律给 0.9) 会被第二个 judge 平衡;
  - badcase 队列是 Phase 2 算法升级 + Phase 0.5 题库人工校正的输入源。

用法:
  python3 eval/judge_ensemble.py --questions eval/golden/golden.jsonl --providers 1,2

  # 单独跑两个 provider 即可 ensemble。CI 不默认开(--providers 1 默认 = 单 judge)。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from judge_client import build_judge_llm, list_configured_providers  # noqa: E402

EVAL_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = EVAL_DIR.parent

# Phase 0.1: 加载 .env (JUDGE_LLM_PROVIDER_*)
try:
    from dotenv import load_dotenv
    load_dotenv(PROJECT_ROOT / ".env", override=False)
except ImportError:
    pass

BADCASE_DIR = EVAL_DIR / "badcases"

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8090/api/v1/chat")
CHAT_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")

# 当某 judge 与 ensemble 均值的偏差超过这个阈值时, 进 badcase(默认 0.2 = 20pp)
DISAGREEMENT_THRESHOLD = 0.2

METRICS = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")


def _load_questions(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def _call_chat(query: str, top_k: int = 5) -> tuple[str, list[str]]:
    import requests
    r = requests.post(
        CHAT_URL,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {CHAT_TOKEN}"},
        json={"query": query, "top_k": top_k},
        timeout=120,
    )
    r.raise_for_status()
    data = r.json()
    contexts = []
    for c in data.get("citations", []):
        ctx = c.get("llm_context") or c.get("snippet")
        if ctx:
            contexts.append(ctx)
    return data.get("answer", ""), contexts


def _build_samples(questions: list[dict]) -> list[dict]:
    samples = []
    for q in questions:
        try:
            answer, contexts = _call_chat(q["question"])
        except Exception as e:
            print(f"  [chat fail] {q['question'][:30]}: {e}", file=sys.stderr)
            continue
        samples.append({
            "question": q["question"],
            "ground_truth": q.get("answer_short") or q.get("ground_truth_answer") or "",
            "answer": answer,
            "contexts": contexts,
        })
        time.sleep(0.5)
    return samples


def _run_ragas_per_sample(samples: list[dict], judge_provider_id: int) -> list[dict]:
    """单 judge 跑整批, 返回 per-sample score list[duplicate of sample + metric]。"""
    from datasets import Dataset
    from ragas import evaluate
    from ragas.metrics import faithfulness, answer_relevancy, context_precision, context_recall
    from ragas.run_config import RunConfig
    import math

    judge_llm, meta = build_judge_llm(judge_provider_id)
    print(f"  [judge #{meta.provider_id}] family={meta.family} model={meta.model}, RAGAS 跑 {len(samples)} 题中...")

    # embed 同 noise_injector
    import requests as _requests
    from langchain_core.embeddings import Embeddings as _LCEmbeddings
    EMBED_BASE_URL = __import__("os").getenv("EMBEDDING_BASE_URL", "http://localhost:8082")

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
        return []
    ds = Dataset.from_list(samples)
    rc = RunConfig(max_workers=4, timeout=600, max_retries=3)
    result = evaluate(
        ds,
        metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
        llm=judge_llm,
        embeddings=_BgeM3(),
        run_config=rc,
        raise_exceptions=False,
    )
    df = result.to_pandas()
    # 把列对齐回 samples
    per_sample = []
    for i, s in enumerate(samples):
        row = {}
        for m in METRICS:
            col = m if m in df.columns else next((c for c in df.columns if c.startswith(m)), None)
            if col is not None and i < len(df):
                v = df[col].iloc[i]
                if isinstance(v, float) and math.isnan(v):
                    row[m] = None
                else:
                    row[m] = float(v)
            else:
                row[m] = None
        per_sample.append({"sample": s, "metrics": row})
    return per_sample, meta


def run_ensemble(
    questions_path: Path,
    provider_ids: list[int],
    disagreement: float = DISAGREEMENT_THRESHOLD,
) -> dict:
    """多 judge 跑同一批 samples, 产 ensemble 报告 + badcase。"""
    questions = _load_questions(questions_path)
    print(f"[ensemble] 题数={len(questions)}, providers={provider_ids}")

    print(f"\n[1/3] 调 chat 端点取 (answer, contexts) 一次, 共享给所有 judge ...")
    samples = _build_samples(questions)
    print(f"  got {len(samples)} samples")

    print(f"\n[2/3] 每个 judge 各跑一次 RAGAS ...")
    per_judge: dict[int, list[dict]] = {}
    metas: dict[int, dict] = {}
    for pid in provider_ids:
        per_sample, meta = _run_ragas_per_sample(samples, judge_provider_id=pid)
        per_judge[pid] = per_sample
        metas[pid] = {
            "provider_id": pid,
            "family": meta.family,
            "model": meta.model,
            "is_thinking": meta.is_thinking,
        }
        time.sleep(2)

    print(f"\n[3/3] 聚合: ensemble 均值 + disagreement 检测 ...")
    # 对每个 sample 在各 judge 间求均值, 任一 judge 与均值偏差>disagreement 进 badcase
    badcases: list[dict] = []
    ensemble_scores: dict[str, list[float]] = {m: [] for m in METRICS}
    n = len(samples)
    for i in range(n):
        # 各 judge 在题 i 的指标
        per_q: dict[str, list[float]] = {m: [] for m in METRICS}
        for pid in provider_ids:
            row = per_judge[pid][i]["metrics"]
            for m in METRICS:
                v = row.get(m)
                if v is not None:
                    per_q[m].append(v)
        # 题级 ensemble mean (不参与的 judge 跳过)
        sample_mean: dict[str, float] = {}
        for m in METRICS:
            if per_q[m]:
                mean = sum(per_q[m]) / len(per_q[m])
                sample_mean[m] = mean
                ensemble_scores[m].append(mean)
            else:
                sample_mean[m] = None

        # disagreement: 任一 judge faith 偏 mean > threshold → badcase
        faith_vals = per_q["faithfulness"]
        if faith_vals and sample_mean["faithfulness"] is not None:
            mean_f = sample_mean["faithfulness"]
            for j_pid, j_f in zip(provider_ids, faith_vals):
                if abs(j_f - mean_f) > disagreement:
                    badcases.append({
                        "question": samples[i]["question"],
                        "ground_truth": samples[i]["ground_truth"],
                        "answer": samples[i]["answer"][:500],
                        "contexts_count": len(samples[i]["contexts"]),
                        "ensemble_mean": {m: round(sample_mean[m], 4) if sample_mean[m] is not None else None
                                          for m in METRICS},
                        "per_judge": {str(pid): {m: round(per_judge[pid][i]["metrics"].get(m), 4)
                                                 if per_judge[pid][i]["metrics"].get(m) is not None else None
                                                 for m in METRICS} for pid in provider_ids},
                        "disagreement_judge": j_pid,
                        "disagreement_delta": round(abs(j_f - mean_f), 4),
                        "judge_metas": {str(metas[pid]["provider_id"]): f"{metas[pid]['family']}/{metas[pid]['model']}"
                                        for pid in provider_ids},
                    })
                    break  # 同一 sample 一个 judge 不一致就够

    summary = {
        "question_count": n,
        "providers": [metas[pid] for pid in provider_ids],
        "ensemble_avg": {m: (round(sum(ensemble_scores[m]) / len(ensemble_scores[m]), 4)
                             if ensemble_scores[m] else 0.0) for m in METRICS},
        "disagreement_threshold": disagreement,
        "badcase_count": len(badcases),
        "badcase_rate": round(len(badcases) / n, 4) if n else 0.0,
        "ran_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    return {"summary": summary, "badcases": badcases, "per_judge": {str(k): v for k, v in per_judge.items()}}


# ────────────────────────────────────────────────────────────
# CLI
# ────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", default=str(EVAL_DIR / "golden" / "golden.jsonl"))
    ap.add_argument(
        "--providers",
        type=str,
        default=",".join(str(p) for p in list_configured_providers()[:2]),
        help="逗号分隔 judge provider ids, 默认取 .env 已配置前两个",
    )
    ap.add_argument("--disagreement", type=float, default=DISAGREEMENT_THRESHOLD)
    args = ap.parse_args()

    qpath = Path(args.questions)
    if not qpath.exists():
        print(f"ERROR: 题库不存在 {qpath}", file=sys.stderr)
        return 1

    try:
        provider_ids = [int(s.strip()) for s in args.providers.split(",") if s.strip()]
    except ValueError:
        print(f"ERROR: --providers 解析失败: {args.providers}", file=sys.stderr)
        return 1
    if len(provider_ids) < 2:
        print(f"WARN: ensemble 至少 2 个 judge, 当前 {provider_ids}. "
              "退化为单 judge(无 disagreement 检测能力, 仅供 stop-gap)。",
              file=sys.stderr)

    bundle = run_ensemble(qpath, provider_ids, disagreement=args.disagreement)
    date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    json_path = EVAL_DIR / f"judge_ensemble_{date_str}.json"
    md_path = EVAL_DIR / f"judge_ensemble_{date_str}.md"
    BADCASE_DIR.mkdir(exist_ok=True)
    badcases_path = BADCASE_DIR / f"badcases_{date_str}.jsonl"

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False, indent=2)
    with open(badcases_path, "w", encoding="utf-8") as f:
        for bc in bundle["badcases"]:
            f.write(json.dumps(bc, ensure_ascii=False) + "\n")
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(_format_md(bundle))
    print(f"\n✓ {json_path}\n✓ {md_path}\n✓ {badcases_path}")
    print()
    print(_format_md(bundle))
    return 0


def _format_md(bundle: dict) -> str:
    s = bundle["summary"]
    md = ["# Judge Ensemble 报告\n",
          f"\n> 跑批(UTC): {s['ran_at_utc']}\n",
          f"\n> 题数 {s['question_count']}, providers {len(s['providers'])} 个, "
          f"disagreement 阈值 {s['disagreement_threshold']}\n",
          "\n## Ensembled judge 列表\n\n| # | family | model | thinking |\n|---|---|---|---|\n"]
    for p in s["providers"]:
        md.append(f"| {p['provider_id']} | {p['family']} | {p['model']} | {p['is_thinking']} |\n")
    md.append("\n## Ensemble 均值(全题)\n\n| 指标 | 均值 |\n|---|---|\n")
    for m in METRICS:
        md.append(f"| {m} | {s['ensemble_avg'].get(m, 0):.4f} |\n")
    md.append(f"\n## Badcase(判官分歧>={s['disagreement_threshold']})\n\n")
    md.append(f"**{s['badcase_count']} / {s['question_count']} = {s['badcase_rate']*100:.1f}%**\n")
    md.append(f"\n详细见 `eval/badcases/` 当日 jsonl。\n")
    return "".join(md)


if __name__ == "__main__":
    sys.exit(main())
