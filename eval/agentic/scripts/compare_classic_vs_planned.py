#!/usr/bin/env python3
"""
ADR-0012 Phase 1: Classic vs PlannedAgent 对照评测(pilot20 多跳题集)。

协议(按 docs/research/2026-08-23-agentic-rag-survey.md §3 口径):
  - 每题每模式 RUNS 次(默认 3) → accuracy mean ± std + pass^k(k 次全对率)
  - 单题延迟 p50/p95 + 平均引用数 + 拒答率(三维成本口径的字段齐全, 成本乘数
    由 token 计量接入后补, 见 ADR-0012 P2)
  - judge = DeepSeek(异族, 与业务 LLM GLM 物理隔离, temp=0.1)
  - 另跑一轮 mode=AUTO 量 TaskRouter 的升级命中率(pipeline_type 字段判定)

用法:
  .venv/bin/python3 eval/agentic/scripts/compare_classic_vs_planned.py
环境: .env(JUDGE_LLM_PROVIDER_1_*, TEST_AUTH_TOKEN), chat-app 8080 已起。
"""
import json
import os
import re
import statistics
import sys
import time
from pathlib import Path

import requests
from dotenv import load_dotenv

PROJECT = Path(__file__).resolve().parents[3]
load_dotenv(PROJECT / ".env", override=False)

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8080/api/v1/chat")
TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")
RUNS = int(os.getenv("CMP_RUNS", "3"))
DATASET = PROJECT / "eval/agentic/datasets/agentic_v2.pilot20.jsonl"
OUT = PROJECT / (
    "eval/agentic/reports/classic_vs_planned_report"
    + (("_" + os.getenv("CMP_MODES").replace(",", "-")) if os.getenv("CMP_MODES") else "")
    + ".json"
)
JUDGE_URL = os.getenv("JUDGE_LLM_PROVIDER_1_BASE_URL", "https://api.deepseek.com/v1") + "/chat/completions"
JUDGE_KEY = os.getenv("JUDGE_LLM_PROVIDER_1_API_KEY", "")
JUDGE_MODEL = os.getenv("JUDGE_LLM_PROVIDER_1_MODEL", "deepseek-chat")

JUDGE_PROMPT = """你是严格的阅卷员。对比【参考答案】与【学生答案】, 学生答案需在事实层面与参考答案一致
(多跳题: 参考答案涉及的每个组件/机制都要覆盖, 细节可措辞不同但不得与参考矛盾)。
学生答案明确表示"证据不足/无法回答"= FAIL。
只输出一个词: PASS 或 FAIL。

【问题】{q}
【参考答案】{gold}
【学生答案】{ans}"""


def load_cases():
    cases = []
    for line in open(DATASET, encoding="utf-8"):
        d = json.loads(line)
        gold = d.get("gold") or {}
        if isinstance(gold, str):  # 文件里是 repr 字符串
            try:
                gold = ast_literal(gold)
            except Exception:
                gold = {}
        gold_answer = gold.get("goldAnswer", "") if isinstance(gold, dict) else ""
        if not gold_answer:
            continue
        cases.append({"caseId": d["caseId"], "q": d["question"], "gold": gold_answer})
    return cases


def ast_literal(s):
    import ast

    return ast.literal_eval(s)


def chat(query, mode, timeout=150):
    t0 = time.time()
    r = requests.post(
        CHAT_URL,
        headers={"Authorization": f"Bearer {TOKEN}"},
        json={"query": query, "mode": mode, "top_k": 5},
        timeout=timeout,
    )
    ms = (time.time() - t0) * 1000
    d = r.json()
    return {
        "state": d.get("state_hint"),
        "pipeline": d.get("pipeline_type"),
        "answer": d.get("answer") or "",
        "citations": len(d.get("citations") or []),
        "latency_ms": round(ms),
    }


def judge(q, gold, ans):
    if not ans.strip() or "证据不足" in ans or "无法回答" in ans:
        return "FAIL"
    body = {
        "model": JUDGE_MODEL,
        "temperature": 0.1,
        "messages": [
            {"role": "user", "content": JUDGE_PROMPT.format(q=q, gold=gold[:800], ans=ans[:1200])}
        ],
    }
    for _ in range(3):
        try:
            r = requests.post(
                JUDGE_URL,
                headers={"Authorization": f"Bearer {JUDGE_KEY}"},
                json=body,
                timeout=60,
            )
            txt = r.json()["choices"][0]["message"]["content"].strip().upper()
            return "PASS" if txt.startswith("PASS") else "FAIL"
        except Exception:
            time.sleep(2)
    return "ERROR"


def main():
    cases = load_cases()
    print(f"[load] {len(cases)} 题带 goldAnswer")
    report = {"runs": RUNS, "cases": len(cases), "modes": {}}

    for mode in os.getenv("CMP_MODES", "RAG,AGENTIC").split(","):
        per_case = {c["caseId"]: [] for c in cases}
        for run in range(1, RUNS + 1):
            for i, c in enumerate(cases, 1):
                res = chat(c["q"], mode)
                verdict = judge(c["q"], c["gold"], res["answer"])
                per_case[c["caseId"]].append({**res, "verdict": verdict})
                print(
                    f"  [{mode} r{run} {i}/{len(cases)}] {verdict} "
                    f"{res['state']} {res['latency_ms']}ms cite={res['citations']}"
                )
        accs, lats, cites, refuses = [], [], [], 0
        pass_all = 0
        total = 0
        for cid, rs in per_case.items():
            oks = [r["verdict"] == "PASS" for r in rs]
            accs.append(sum(oks) / len(rs))
            if all(oks):
                pass_all += 1
            total += 1
            lats += [r["latency_ms"] for r in rs]
            cites += [r["citations"] for r in rs]
            refuses += sum(1 for r in rs if r["state"] != "OK")
        lats.sort()
        report["modes"][mode] = {
            "accuracy_mean": round(statistics.mean(accs), 4),
            "accuracy_stdev": round(statistics.stdev(accs), 4) if len(accs) > 1 else 0,
            f"pass_all_{RUNS}": round(pass_all / total, 4),
            "latency_p50_ms": lats[len(lats) // 2],
            "latency_p95_ms": lats[int(len(lats) * 0.95)],
            "avg_citations": round(statistics.mean(cites), 2),
            "non_ok_rate": round(refuses / (total * RUNS), 4),
            "per_case": {
                cid: [r["verdict"] for r in rs] for cid, rs in per_case.items()
            },
        }
        print(f"[{mode}] acc={report['modes'][mode]['accuracy_mean']} "
              f"pass^k={report['modes'][mode][f'pass_all_{RUNS}']}")

    # 路由命中率(AUTO 单轮)
    upgraded = 0
    for c in cases:
        res = chat(c["q"], "AUTO")
        if res["pipeline"] == "PLANNED_AGENT":
            upgraded += 1
    report["router_auto_upgrade_rate"] = round(upgraded / len(cases), 4)
    print(f"[router] AUTO 升级率 {report['router_auto_upgrade_rate']}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    json.dump(report, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"[done] → {OUT}")


if __name__ == "__main__":
    main()
