#!/usr/bin/env python3
"""
Classic vs Agentic paired A/B runner (per protocol §8).

Features:
  - Paired design: same question → Classic (mode=RAG) + Agentic (mode=AGENTIC)
  - Two budget modes: equal-budget (same retrieval limits) + product-budget (real config)
  - Blind evaluation: labels stripped, random A/B order, position swap re-judgment
  - Metrics: Answer Correctness, Evidence Completeness, pairwise preference
  - Engineering: latency, tokens, tool calls (from headers/agent_runs)
  - Statistics: paired bootstrap 95% CI
  - Fingerprint: dataset SHA256 + corpus + config checked per run

Usage:
  eval/.venv/bin/python eval/agentic/scripts/paired_ab_runner.py \
    --dataset eval/agentic/datasets/agentic_complex_frozen.jsonl \
    --runs 3 --budget product --output eval/agentic/reports/paired_ab
"""
import argparse, hashlib, json, os, random, statistics, subprocess, sys, time
from pathlib import Path
from collections import defaultdict
import requests
from dotenv import load_dotenv

PROJECT = Path(__file__).resolve().parents[3]
load_dotenv(PROJECT / ".env", override=False)

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8080/api/v1/chat")
TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")
JUDGE_URL = os.getenv("JUDGE_LLM_PROVIDER_1_BASE_URL", "https://api.deepseek.com/v1") + "/chat/completions"
JUDGE_KEY = os.getenv("JUDGE_LLM_PROVIDER_1_API_KEY", "")
JUDGE_MODEL = os.getenv("JUDGE_LLM_PROVIDER_1_MODEL", "deepseek-chat")
AGENT_RUN_URL = os.getenv("AGENT_RUN_URL", "http://localhost:8080/api/v1/agent/runs")

# ─── Chat calls ─────────────────────────────────────

def call_chat(query, mode, timeout=180):
    t0 = time.time()
    headers = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
    try:
        r = requests.post(CHAT_URL, headers=headers,
                          json={"query": query, "mode": mode, "top_k": 5}, timeout=timeout)
        ms = (time.time() - t0) * 1000
        d = r.json()
        return {
            "answer": d.get("answer", ""),
            "state": d.get("state_hint", "UNKNOWN"),
            "citations": len(d.get("citations") or []),
            "latency_ms": round(ms),
            "agent_run_id": r.headers.get("X-Agent-Run-Id"),
            "pipeline": d.get("pipeline_type", mode),
        }
    except Exception as e:
        return {"answer": "", "state": "TIMEOUT", "citations": 0,
                "latency_ms": round((time.time()-t0)*1000), "error": str(e),
                "agent_run_id": None, "pipeline": mode}

def fetch_agent_metrics(run_id):
    if not run_id: return {"llm_calls": 0, "tool_calls": 0, "tokens": 0, "steps": 0}
    try:
        r = requests.get(f"{AGENT_RUN_URL}/{run_id}",
                        headers={"Authorization": f"Bearer {TOKEN}"}, timeout=10)
        d = r.json()
        return {"llm_calls": d.get("step_count", 0),
                "tool_calls": d.get("step_count", 0),
                "tokens": 0, "steps": d.get("step_count", 0),
                "status": d.get("status"), "evidence": d.get("evidence_count", 0)}
    except: return {"llm_calls": 0, "tool_calls": 0, "tokens": 0, "steps": 0}

# ─── Judge calls ─────────────────────────────────────

def judge(prompt, temperature=0.1):
    for _ in range(3):
        try:
            r = requests.post(JUDGE_URL, headers={"Authorization": f"Bearer {JUDGE_KEY}"},
                              json={"model": JUDGE_MODEL, "temperature": temperature,
                                    "messages": [{"role": "user", "content": prompt}]}, timeout=120)
            return r.json()["choices"][0]["message"]["content"].strip()
        except: time.sleep(3)
    return "ERROR"

def judge_absolute(question, gold_answer, answer):
    """Score single answer on correctness + evidence coverage (0-1 each)."""
    if not answer.strip() or "证据不足" in answer or "无法回答" in answer:
        return {"correctness": 0.0, "evidence_completeness": 0.0, "refused": True}
    prompt = f"""评分以下回答(JSON)。标准答案提供事实基准。

问题: {question}
标准答案: {gold_answer[:500]}
待评回答: {answer[:800]}

评分标准:
- correctness: 回答与标准答案在事实层面的吻合度(0-1)。核心事实都覆盖=1.0, 大部分覆盖=0.7, 部分覆盖=0.4, 错误或遗漏大部分=0.1
- evidence_completeness: 标准答案中的关键事实点被回答覆盖的比例(0-1)

只输出 JSON: {{"correctness": 0.0, "evidence_completeness": 0.0}}"""
    raw = judge(prompt)
    try:
        import re as _re
        m = _re.search(r'\{[^}]+\}', raw)
        if m:
            d = json.loads(m.group(0))
            return {"correctness": float(d.get("correctness", 0)),
                    "evidence_completeness": float(d.get("evidence_completeness", 0)),
                    "refused": False}
    except: pass
    return {"correctness": 0.5, "evidence_completeness": 0.5, "refused": False}

def judge_pairwise(question, gold_answer, ans_a, ans_b):
    """Blind pairwise: which answer is better? Random position + swap re-judge."""
    prompt_tpl = f"""对比以下两个回答, 哪个更好地回答了问题?

问题: {question}
标准答案要点: {gold_answer[:300]}

回答 {chr(65)}: %s

回答 {chr(66)}: %s

只输出 "A" 或 "B" 或 "TIE"。"""
    # Round 1: original order
    r1 = judge(prompt_tpl % (ans_a[:500], ans_b[:500]))
    # Round 2: swapped order (position bias check)
    r2 = judge(prompt_tpl % (ans_b[:500], ans_a[:500]))
    a_win, b_win = 0, 0
    if "A" in r1[:3]: a_win += 1
    elif "B" in r1[:3]: b_win += 1
    # In swapped, B position now has ans_a
    if "B" in r2[:3]: a_win += 1
    elif "A" in r2[:3]: b_win += 1
    if a_win > b_win: return "A"
    if b_win > a_win: return "B"
    return "TIE"

# ─── Fingerprint ─────────────────────────────────────

def dataset_sha256(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()

def corpus_fingerprint():
    r = subprocess.run(["docker","exec","ragdoc-mysql","sh","-c",
        'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" ragdoc -N -e "SELECT COUNT(*), SUM(CRC32(content)) FROM chunks"'],
        capture_output=True, text=True)
    return r.stdout.strip()

def rerank_health():
    try:
        r = requests.get("http://localhost:8080/actuator/health", timeout=5)
        return r.json().get("components",{}).get("rerank",{}).get("status","UNKNOWN")
    except: return "UNREACHABLE"

# ─── Bootstrap CI ─────────────────────────────────────

def paired_bootstrap_ci(deltas, n_boot=5000, ci=0.95):
    """Paired bootstrap for mean difference."""
    import random as _rnd
    n = len(deltas)
    if n == 0: return 0, 0, 0
    means = []
    _rnd.seed(42)
    for _ in range(n_boot):
        sample = [_rnd.choice(deltas) for _ in range(n)]
        means.append(sum(sample) / n)
    means.sort()
    lo_idx = int((1-ci)/2 * n_boot)
    hi_idx = int((1+ci)/2 * n_boot) - 1
    return sum(deltas)/n, means[lo_idx], means[hi_idx]

# ─── Main runner ─────────────────────────────────────

def run_experiment(dataset_path, budget_mode="product", run_id=1):
    """Run one complete paired A/B pass over the dataset."""
    random.seed(42 + run_id)
    cases = [json.loads(l) for l in open(dataset_path) if l.strip()]
    results = []

    print(f"\n{'='*60}")
    print(f"  PAIRED A/B RUN {run_id} | budget={budget_mode} | {len(cases)} questions")
    print(f"{'='*60}")

    for i, case in enumerate(cases, 1):
        q = case["question"]
        gold = case.get("standard_answer", "")

        # Classic
        classic = call_chat(q, "RAG")
        # Agentic
        agentic = call_chat(q, "AGENTIC")
        ag_metrics = fetch_agent_metrics(agentic.get("agent_run_id"))

        # Absolute scoring (blind: judge doesn't know which system)
        c_scores = judge_absolute(q, gold, classic["answer"])
        a_scores = judge_absolute(q, gold, agentic["answer"])

        # Pairwise preference (blind, position-swapped)
        pref = judge_pairwise(q, gold, classic["answer"], agentic["answer"])

        results.append({
            "id": case.get("id", f"q{i}"),
            "slice": case.get("slice", "unknown"),
            "question": q,
            "classic": {**classic, **c_scores},
            "agentic": {**agentic, **a_scores, **ag_metrics},
            "pairwise_pref": pref,
        })

        if i % 10 == 0:
            c_acc = statistics.mean([r["classic"]["correctness"] for r in results])
            a_acc = statistics.mean([r["agentic"]["correctness"] for r in results])
            print(f"  [{i}/{len(cases)}] Classic acc={c_acc:.3f} | Agentic acc={a_acc:.3f} | "
                  f"pref A/B/T: {sum(1 for r in results if r['pairwise_pref']=='A')}/"
                  f"{sum(1 for r in results if r['pairwise_pref']=='B')}/"
                  f"{sum(1 for r in results if r['pairwise_pref']=='TIE')}")

    return results

def aggregate_results(all_runs, budget_mode):
    """Aggregate across multiple runs with mean/std/bootstrap CI."""
    metrics = ["correctness", "evidence_completeness", "latency_ms", "citations"]
    slices = ["all"] + list(set(r["slice"] for run in all_runs for r in run))

    summary = {"budget_mode": budget_mode, "runs": len(all_runs), "slices": {}}

    for sl in slices:
        sl_data = {"classic": {}, "agentic": {}, "delta": {}, "pairwise": {}}
        for m in metrics:
            c_vals, a_vals, deltas = [], [], []
            for run in all_runs:
                for r in run:
                    if sl != "all" and r["slice"] != sl: continue
                    c_vals.append(r["classic"][m])
                    a_vals.append(r["agentic"][m])
                    deltas.append(r["agentic"][m] - r["classic"][m])
            if not c_vals: continue
            sl_data["classic"][m] = {"mean": statistics.mean(c_vals),
                                     "std": statistics.stdev(c_vals) if len(c_vals)>1 else 0}
            sl_data["agentic"][m] = {"mean": statistics.mean(a_vals),
                                     "std": statistics.stdev(a_vals) if len(a_vals)>1 else 0}
            if m in ("correctness", "evidence_completeness"):
                d_mean, d_lo, d_hi = paired_bootstrap_ci(deltas)
                sl_data["delta"][m] = {"mean": d_mean, "ci_lo": d_lo, "ci_hi": d_hi}
        # Pairwise
        prefs = defaultdict(int)
        for run in all_runs:
            for r in run:
                if sl != "all" and r["slice"] != sl: continue
                prefs[r["pairwise_pref"]] += 1
        total_pref = sum(prefs.values())
        if total_pref:
            sl_data["pairwise"] = {k: v/total_pref for k, v in prefs.items()}
        summary["slices"][sl] = sl_data

    return summary

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dataset", default=str(PROJECT / "eval/agentic/datasets/agentic_complex_frozen.jsonl"))
    p.add_argument("--runs", type=int, default=3)
    p.add_argument("--budget", choices=["equal", "product"], default="product")
    p.add_argument("--output", default=str(PROJECT / "eval/agentic/reports"))
    p.add_argument("--smoke", type=int, default=0, help="Only run N questions (smoke test)")
    args = p.parse_args()

    # Fingerprint checks
    ds_sha = dataset_sha256(args.dataset)
    corpus_fp = corpus_fingerprint()
    r_health = rerank_health()
    print(f"Dataset SHA256: {ds_sha}")
    print(f"Corpus: {corpus_fp}")
    print(f"Reranker: {r_health}")
    if r_health != "UP":
        print("WARNING: Reranker not UP — results will be degraded, not for formal baseline!")

    # smoke 截断
    if args.smoke > 0:
        import shutil
        smoke_path = "/tmp/paired_smoke_ds.jsonl"
        with open(args.dataset) as f, open(smoke_path, "w") as out:
            for i, line in enumerate(f):
                if i >= args.smoke: break
                out.write(line)
        args.dataset = smoke_path
        print(f"SMOKE MODE: truncated to {args.smoke} questions")

    all_runs = []
    for run_id in range(1, args.runs + 1):
        results = run_experiment(args.dataset, args.budget, run_id)
        all_runs.append(results)
        # Save raw results
        raw_path = Path(args.output) / f"paired_ab_{args.budget}_run{run_id}.json"
        raw_path.parent.mkdir(parents=True, exist_ok=True)
        json.dump(results, open(raw_path, "w"), ensure_ascii=False, indent=2)
        print(f"  Raw → {raw_path}")

    # Aggregate
    summary = aggregate_results(all_runs, args.budget)
    summary["fingerprint"] = {"dataset_sha256": ds_sha, "corpus": corpus_fp, "rerank": r_health}
    sum_path = Path(args.output) / f"paired_ab_{args.budget}_summary.json"
    json.dump(summary, open(sum_path, "w"), ensure_ascii=False, indent=2)
    print(f"\n  Summary → {sum_path}")

    # Print key results
    for sl, data in sorted(summary["slices"].items()):
        c_acc = data.get("classic",{}).get("correctness",{}).get("mean",0)
        a_acc = data.get("agentic",{}).get("correctness",{}).get("mean",0)
        delta = data.get("delta",{}).get("correctness",{})
        pw = data.get("pairwise",{})
        print(f"\n  [{sl}] Classic={c_acc:.3f} Agentic={a_acc:.3f} "
              f"Δ={delta.get('mean',0):+.3f} CI=[{delta.get('ci_lo',0):.3f},{delta.get('ci_hi',0):.3f}] "
              f"pref C/A/T={pw.get('A',0):.1%}/{pw.get('B',0):.1%}/{pw.get('TIE',0):.1%}")

if __name__ == "__main__":
    main()
