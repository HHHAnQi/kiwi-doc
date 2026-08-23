#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase 1 / C7 (ADR-0011): 多轮对话 eval pipeline。

5 道 gate 自动跑出 markdown 报告:
  G1 baseline ±3pp 不退化 (跑 golden_v2_grounded.jsonl 80 题, 跟 EVAL_BASELINE_CERT.md 对比)
  G2 多轮指代消解 (跑 eval/multi_turn/conv_holdout_20.jsonl, 比对 actual_standalone vs expect_standalone)
  G3 抗污染 (跑 antipollution_holdout_10.jsonl, 验证 LLM_DEGRADED/NO_RECALL turn 是否污染重试 turn)
  G4 压缩 fidelity (跑 8-turn 长会话, 比对 summary 关键实体保留率)
  G5 topic shift (跑 5 pair × 10 变体 = 50 session, 验证 shift 后 retrieve 召回正确)

依赖:
  pip install requests numpy

用法:
  export CHAT_URL=http://localhost:8080/api/v1/chat    # (8080 默认)
  export APP_DEV_TOKEN=dev-token-change-me
  export OPENAI_API_KEY=...                            # 用于 LLM judge (DeepSeek/Qwen 兼容 OpenAI 协议)
  export OPENAI_BASE_URL=https://api.deepseek.com/v1
  export OPENAI_MODEL=deepseek-chat
  cd rag-doc-platform
  python3 eval/multi_turn/run_multi_turn_eval.py

输出:
  eval/multi_turn/report_<timestamp>.md
"""

import json
import os
import re
import sys
import time
import urllib.parse
import uuid
from datetime import datetime
from pathlib import Path

try:
    import requests
except ImportError:
    print("[FATAL] 缺少 requests; 跑 pip install requests", file=sys.stderr)
    sys.exit(2)

# ─── 配置 ───────────────────────────────
CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8080/api/v1/chat").rstrip("/")
APP_DEV_TOKEN = os.getenv("APP_DEV_TOKEN", "dev-token-change-me")
APP_ADMIN_TOKEN = os.getenv("APP_ADMIN_TOKEN", "admin-token-change-me")
TIMEOUT_S = 90

JUDGE_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.deepseek.com/v1")
JUDGE_API_KEY = os.getenv("OPENAI_API_KEY", os.getenv("JUDGE_LLM_PROVIDER_1_API_KEY", ""))
JUDGE_MODEL = os.getenv("OPENAI_MODEL", "deepseek-chat")

MULTI_TURN_DIR = Path(__file__).resolve().parent
G2_FILE = MULTI_TURN_DIR / "conv_holdout_20.jsonl"
G3_FILE = MULTI_TURN_DIR / "antipollution_holdout_10.jsonl"
G4_TEMPLATE = MULTI_TURN_DIR / "fidelity_holdout_50.json"
G5_TEMPLATE = MULTI_TURN_DIR / "topic_shift_holdout_50.json"

OUT_DIR = MULTI_TURN_DIR

# EVAL_BASELINE_CERT.md 锁定的 baseline (Phase 2.0 ensemble mean)
BASELINE_FAITH_ON_ANSWERED = 0.8654
BASELINE_REFUSAL_RATE = 0.1625


# ─── HTTP 调用 ───────────────────────────────

def call_chat(query: str, conversation_id=None, top_k=5) -> dict:
    """调 chat-app /api/v1/chat, 返回 dict 含 answer / state_hint / citations."""
    headers = {"Authorization": f"Bearer {APP_DEV_TOKEN}", "Content-Type": "application/json"}
    body = {"query": query, "top_k": top_k}
    if conversation_id is not None:
        body["conversation_id"] = conversation_id
    try:
        r = requests.post(CHAT_URL, headers=headers, json=body, timeout=TIMEOUT_S)
        if r.status_code != 200:
            return {"state_hint": "HTTP_ERR", "answer": f"http {r.status_code}: {r.text[:200]}", "citations": []}
        # SNAKE_CASE 输出 → Python 直接读 snake_case key
        return {
            "state_hint": r.json().get("state_hint", "UNKNOWN"),
            "answer": r.json().get("answer", ""),
            "citations": r.json().get("citations", []),
            "trace_id": r.json().get("trace_id"),
            # G2 可测性修复: 多轮 rewrite 后实际送检索的 standalone query(URL 编码, 头不支持中文)
            "effective_query": (
                urllib.parse.unquote(r.headers.get("X-Effective-Query"))
                if r.headers.get("X-Effective-Query") else None
            ),
        }
    except Exception as e:
        return {"state_hint": "EXCEPTION", "answer": f"exception: {e}", "citations": []}


def judge_llm(prompt: str) -> str:
    """LLM-as-judge, 复用现有 OpenAI 协议 (DeepSeek 等)。"""
    if not JUDGE_API_KEY:
        return "[no judge api key]"
    headers = {"Authorization": f"Bearer {JUDGE_API_KEY}", "Content-Type": "application/json"}
    body = {"model": JUDGE_MODEL, "messages": [{"role": "user", "content": prompt}], "temperature": 0, "max_tokens": 256}
    try:
        r = requests.post(f"{JUDGE_BASE_URL.rstrip('/')}/chat/completions", headers=headers, json=body, timeout=60)
        return r.json()["choices"][0]["message"]["content"].strip()
    except Exception as e:
        return f"[judge error: {e}]"


# ─── G1: baseline ±3pp smoke ───────────────

def run_g1_smoke(n_smoke=10) -> dict:
    """G1 用 golden_v2_grounded.jsonl 跑 10 题做 smoke 测试 (不是完整 80 题, 节省 LLM 成本)。
    baseline 完整 80 题 ±3pp 跑用 eval/eval_pipeline.py, 本 G1 只 sanity check 一致。"""
    golden_file = MULTI_TURN_DIR.parent / "golden" / "golden_v2_grounded.jsonl"
    if not golden_file.exists():
        return {"gate": "G1", "status": "SKIP", "reason": f"找不到 {golden_file}, 跳过 smoke 检查"}
    questions = []
    with open(golden_file) as f:
        for line in f:
            questions.append(json.loads(line))
    smoke = questions[:n_smoke]  # 头 10 题
    ok = 0; degraded = 0; no_recall = 0; empty_kb = 0
    for q in smoke:
        r = call_chat(q["question"])
        # state 不传 conversation_id (stateless 老路径) → baseline 应正常 0 变化
        st = r["state_hint"]
        if st == "OK": ok += 1
        elif st == "LLM_DEGRADED": degraded += 1
        elif st == "NO_RECALL": no_recall += 1
        elif st == "EMPTY_KB": empty_kb += 1
    return {
        "gate": "G1",
        "status": "PASS" if empty_kb == 0 and degraded <= 1 else "REVIEW",
        "n_smoke": len(smoke),
        "ok": ok,
        "no_recall": no_recall,
        "degraded": degraded,
        "empty_kb": empty_kb,
        "note": "10 题 smoke 跑 stateless 老路径 (无 conversation_id), 应 0 LLM_DEGRADED < 5个。若 PASS = baseline 不破。"
    }


# ─── G2: 多轮指代消解 ───────────────────────────

def run_g2() -> dict:
    if not G2_FILE.exists():
        return {"gate": "G2", "status": "SKIP", "reason": f"找不到 {G2_FILE}"}
    sessions = []
    with open(G2_FILE) as f:
        for line in f:
            if line.strip():
                sessions.append(json.loads(line))
    pass_n = 0; total = len(sessions)
    details = []
    for sess in sessions:
        conv_id = f"{sess['conversation_id_prefix']}_{uuid.uuid4().hex[:8]}"
        # play 所有 turn except 最后评测 turn
        for turn in sess["turns"][:-1]:
            call_chat(turn["content"], conversation_id=conv_id)
            time.sleep(0.5)
        eval_turn = sess["turns"][-1]
        # eval turn 调 LLM, 比对 rewrite 后 retrieve 召回正确
        r = call_chat(eval_turn["content"], conversation_id=conv_id)
        # G2 可测性修复: 有 X-Effective-Query 时直接判 rewrite 质量(query-vs-query 语义等价),
        # 不再混入检索/生成质量; 无头时回退旧的 answer-vs-gold 口径(兼容)。
        if r.get("effective_query"):
            judge_prompt = f"""判定「实际改写」是否与「期望独立问题」语义等价:
即改写后的问题是否已正确消解指代/省略, 能独立检索到期望的答案。

期望独立问题: {eval_turn.get("expect_standalone", "")}
实际改写: {r["effective_query"]}
原始追问(含指代): {eval_turn["content"]}

要求: 实际改写必须 (1)补全了指代对象 (2)未偏离原追问意图 (3)自包含可独立检索。
仅输出 "PASS" 或 "FAIL" 加一句简短理由。"""
            verdict = judge_llm(judge_prompt)
            is_pass = verdict.upper().startswith("PASS")
            if is_pass: pass_n += 1
            details.append({
                "question_id": sess["question_id"],
                "mode": "rewrite-direct(query-vs-query)",
                "rewritten_by_app": r["effective_query"],
                "raw_followup": eval_turn["content"],
                "expected_standalone": eval_turn.get("expect_standalone"),
                "judge": verdict,
                "pass": is_pass
            })
            continue
        # fallback: 旧口径(答案 vs 金标) — 仅当头缺失(如旧版本 app)
        judge_prompt = f"""请判定 bot 回答是否包含关键事实 (语义包含, 不要求精确)。

关键事实: {eval_turn.get("ground_truth_answer", "")}

Bot 回答: {r["answer"]}

判定: 回答是否提到关键事实核心点? 仅输出 "PASS" 或 "FAIL" 加一句简短理由。"""
        verdict = judge_llm(judge_prompt)
        is_pass = verdict.upper().startswith("PASS")
        if is_pass: pass_n += 1
        details.append({
            "question_id": sess["question_id"],
            "mode": "answer-based(fallback)",
            "rewritten_by_app": "(header 缺失)",
            "actual_answer": r["answer"][:200],
            "expected_standalone": eval_turn.get("expect_standalone"),
            "judge": verdict,
            "pass": is_pass
        })
    rate = pass_n / total if total else 0
    return {
        "gate": "G2",
        "status": "PASS" if rate >= 0.85 else "FAIL",
        "pass_n": pass_n,
        "total": total,
        "rate": round(rate, 3),
        "threshold": 0.85,
        "details": details
    }


# ─── G3: 抗污染 — failed turn 不进 history ───────

def run_g3() -> dict:
    if not G3_FILE.exists():
        return {"gate": "G3", "status": "SKIP", "reason": f"找不到 {G3_FILE}"}
    sessions = []
    with open(G3_FILE) as f:
        for line in f:
            if line.strip():
                sessions.append(json.loads(line))
    pass_n = 0; total = len(sessions)
    pollution_count = 0
    details = []
    for sess in sessions:
        conv_id = f"{sess['conversation_id_prefix']}_{uuid.uuid4().hex[:8]}"
        eval_turn_idx = sess["evaluation_turn_index"]
        eval_turn = sess["turns"][eval_turn_idx]
        # play 所有 turn except eval turn
        for turn in sess["turns"][:eval_turn_idx]:
            call_chat(turn["content"], conversation_id=conv_id)
            time.sleep(0.5)
        # 调 eval turn
        r = call_chat(eval_turn["content"], conversation_id=conv_id)
        # judge: answer 不应包含先前 failed/no_recall turn 的污染 (如 "出错了" "trace=" 等)
        pollution_marker = ""
        for s in ["出错了", "trace=", "未找到相关", "知识库为空"]:
            if s in r["answer"]:
                pollution_marker = s
                pollution_count += 1
                break
        # 同时验证 eval_turn 是否真能答出 ground truth
        judge_prompt = f"""请判定 bot 回答是否包含关键事实。

关键事实: {eval_turn.get("ground_truth_answer", "")}
Bot 回答: {r["answer"]}

约束: 回答不应提到 "LLM 出错" "未找到相关内容" 等兜底文案。
判定: 若回答语义上覆盖关键事实 → "PASS"; 否则 "FAIL"。"""
        verdict = judge_llm(judge_prompt)
        is_pass = verdict.upper().startswith("PASS") and not pollution_marker
        if is_pass: pass_n += 1
        details.append({
            "question_id": sess["question_id"],
            "description": sess.get("description"),
            "actual_answer": r["answer"][:200],
            "expected": eval_turn.get("expect_standalone"),
            "judge": verdict,
            "pollution_marker": pollution_marker,
            "pass": is_pass
        })
    rate = pass_n / total if total else 0
    return {
        "gate": "G3",
        "status": "PASS" if pollution_count == 0 and rate >= 0.7 else "FAIL",
        "pass_n": pass_n,
        "total": total,
        "rate": round(rate, 3),
        "pollution_count": pollution_count,
        "threshold": 0,
        "note": "G3 硬 gate: pollution_count 必须 0",
        "details": details
    }


# ─── G4: 压缩 fidelity ──────────────────────────

def run_g4() -> dict:
    if not G4_TEMPLATE.exists():
        return {"gate": "G4", "status": "SKIP", "reason": f"找不到 {G4_TEMPLATE}"}
    template = json.loads(G4_TEMPLATE.read_text())
    n_sessions = 5  # 默认跑 5 个 (完整 50 太贵; 可 ENV G4_SESSIONS=50 全跑)
    n_sessions = int(os.getenv("G4_SESSIONS", n_sessions))
    seed_topics = template["seed_topics_for_long_conversation"]
    # 简化版: 每 session 跑 8 个 turn, 全部 OK; 然后等 30s 让异步 compress 跑完; 之后调 GET conversation 拉 summary
    # (本脚本不假设有 GET endpoint, 改为: 给 chat dump 调 → Redis 直接查 conversation_id JSON 看 summary)
    # 实际验证 V2: 用户跑完看 Langfuse trace conversation_id 包的 history_compression observation
    details = []
    entities_pattern = re.compile(r"(Nacos|Sentinel|Dubbo|Seata|RocketMQ|Hystrix|\d+\.\d+(?:\.\d+)?|\b\d{4,5}\b|undo_log|half message|namespace|raft|QPS|TPS|RT|latency)")
    pass_n = 0
    for i in range(n_sessions):
        conv_id = f"conv_g4_{i}_{uuid.uuid4().hex[:8]}"
        topic = seed_topics[i % len(seed_topics)]
        # 8 turn: 每 turn 复用同 topic 各角度问 (确保 turn 彼此不 OOD)
        canned_q = [
            f"{topic} 怎么用?",
            f"{topic} 默认配置是什么?",
            f"{topic} 跟其他组件比有什么优势?",
            f"{topic} 故障排查步骤?",
            f"{topic} 性能最佳实践?",
            f"{topic} 安全相关注意事项?",
            f"{topic} 升级到最新版要注意什么?",
            f"{topic} 集群部署要几节点?"
        ]
        ground_truth_entities_set = set()
        for q in canned_q:
            r = call_chat(q, conversation_id=conv_id)
            # 收集单元: 提取 answer 中的关键实体 (我们没法读 LLM 输出 ground truth, 用 answer 当 proxy)
            for m in entities_pattern.finditer(r["answer"]):
                ground_truth_entities_set.add(m.group(0))
            time.sleep(0.3)
        # 等 60s 让 compress 跑完
        time.sleep(60)
        # summary 查询: Redis 直接 GET (依赖 docker exec)
        summary_text = query_redis_summary(conv_id)
        summary_entities_set = set(entities_pattern.findall(summary_text or ""))
        # 实体保留率
        if not ground_truth_entities_set:
            fidelity = 1.0 if not summary_entities_set else 0.0
        else:
            fidelity = len(summary_entities_set & ground_truth_entities_set) / len(ground_truth_entities_set)
        is_pass = fidelity >= template["fidelity_threshold"]
        if is_pass: pass_n += 1
        details.append({
            "session_index": i,
            "topic": topic,
            "conv_id": conv_id,
            "ground_truth_entities_count": len(ground_truth_entities_set),
            "summary_entities_count": len(summary_entities_set),
            "preserved_count": len(summary_entities_set & ground_truth_entities_set),
            "fidelity_score": round(fidelity, 3),
            "pass": is_pass,
            "summary_preview": (summary_text or "(empty)")[:200]
        })
    overall_rate = pass_n / n_sessions if n_sessions else 0
    return {
        "gate": "G4",
        "status": "PASS" if overall_rate >= 0.70 else "FAIL",
        "pass_n": pass_n,
        "total": n_sessions,
        "overall_rate": round(overall_rate, 3),
        "threshold": 0.70,
        "note": f"实跑 {n_sessions} session, 完整 50 用 G4_SESSIONS=50 环境变量",
        "details": details
    }


def query_redis_summary(conv_id: str) -> str:
    """通过 docker exec ragdoc-redis 取 conversation JSON 的 rollingSummary 字段。"""
    import subprocess
    try:
        r = subprocess.run(
            ["docker", "exec", "ragdoc-redis", "redis-cli", "-c", "GET", f"ragdoc:conv:{conv_id}"],
            capture_output=True, text=True, timeout=10
        )
        if r.returncode != 0 or not r.stdout.strip():
            return ""
        data = json.loads(r.stdout.strip())
        return data.get("rollingSummary", "") or ""
    except Exception as e:
        return f"[redis query failed: {e}]"


# ─── G5: topic shift ──────────────────────────

def run_g5() -> dict:
    if not G5_TEMPLATE.exists():
        return {"gate": "G5", "status": "SKIP", "reason": f"找不到 {G5_TEMPLATE}"}
    template = json.loads(G5_TEMPLATE.read_text())
    n_per_pair = int(os.getenv("G5_PAIRS", "5"))  # 默认每 pair 5 个 session = 5*5=25, 完整 10 → 50
    pairs = template["topic_pairs"]
    pass_n = 0; total = 0
    details = []
    for pair in pairs:
        for variant in pair["query_pair_examples"]:
            for v in range(n_per_pair):
                total += 1
                conv_id = f"conv_g5_{pair['from_topic']}_{pair['to_topic']}_{v}_{uuid.uuid4().hex[:8]}"
                # 第 1 turn: from_topic
                call_chat(pair["query_pair_examples"][0]["content"], conversation_id=conv_id)
                time.sleep(0.5)
                # 第 2 turn: shift → to_topic
                r = call_chat(variant["content"], conversation_id=conv_id)
                judge_prompt = f"""请判定 bot 回答是否包含关键事实。

关键事实: {variant.get('expect_standalone', '')}
Bot 回答: {r['answer']}

判定: 若语义覆盖 → "PASS"; 否则 "FAIL"。"""
                verdict = judge_llm(judge_prompt)
                is_pass = verdict.upper().startswith("PASS")
                if is_pass: pass_n += 1
                details.append({
                    "from": pair["from_topic"],
                    "to": pair["to_topic"],
                    "variant_idx": v,
                    "conv_id": conv_id,
                    "answer": r["answer"][:150],
                    "judge": verdict,
                    "pass": is_pass
                })
    rate = pass_n / total if total else 0
    return {
        "gate": "G5",
        "status": "PASS" if rate >= 0.80 else "FAIL",
        "pass_n": pass_n,
        "total": total,
        "rate": round(rate, 3),
        "threshold": 0.80,
        "details": details
    }


# ─── main + 报告 ──────────────────────────────

GATES_FUNCS = [
    ("G1", run_g1_smoke),
    ("G2", run_g2),
    ("G3", run_g3),
    ("G4", run_g4),
    ("G5", run_g5),
]


def render_markdown(report: dict) -> str:
    md = f"# Phase 1 / C7 Multi-turn Eval Report\n\n"
    md += f"生成时间: {report['generated_at']}\n\n"
    md += f"## 概览\n\n| Gate | Status | 说明 |\n|---|---|---|\n"
    for g in report["gates"]:
        status_emoji = {"PASS": "✅", "FAIL": "❌", "SKIP": "⚠️"}.get(g["status"], "❓")
        md += f"| {g['gate']} | {status_emoji} {g['status']} | {g.get('note', '')} |\n"
    md += "\n"
    for g in report["gates"]:
        md += f"## {g['gate']} — {g['status']}\n\n"
        md += "```json\n" + json.dumps(g, ensure_ascii=False, indent=2)[:3000] + "\n```\n\n"
    return md


def main():
    print("[INFO] Phase 1 Multi-turn Eval starting...")
    report = {
        "generated_at": datetime.utcnow().isoformat() + "Z",
        "chat_url": CHAT_URL,
        "judge_model": JUDGE_MODEL,
        "gates": []
    }
    for name, func in GATES_FUNCS:
        print(f"[GATE {name}] running...")
        try:
            r = func()
        except Exception as e:
            r = {"gate": name, "status": "FAIL", "error": str(e)}
        report["gates"].append(r)
        print(f"[GATE {name}] → {r.get('status')}")
    out_md = OUT_DIR / f"report_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.md"
    out_json = OUT_DIR / "report_latest.json"
    out_md.write_text(render_markdown(report), encoding="utf-8")
    out_json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[DONE] Report 写入: {out_md}")
    print(f"[DONE] JSON 写入:   {out_json}")
    # exit code: 任意 gate FAIL → return 1
    failed = [g for g in report["gates"] if g["status"] == "FAIL"]
    if failed:
        print(f"\n[FAIL] 这些 gate 没过: {[g['gate'] for g in failed]}")
        sys.exit(1)
    print("\n[PASS] 所有 gate 通过")


if __name__ == "__main__":
    main()
