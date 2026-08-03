#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V2-C Step 2 (P1 工程版): RAGAS 答案质量评测 + CI 门禁。

设计文档 README.md L16 要求:
  "RAGAS 答案质量评测 + CI 门禁(指标下降 3% 阻断上线)"

指标 (RAGAS 标准库):
  - faithfulness:        答案是否完全从 context 推导(0-1, 越高越无幻觉)
  - answer_relevancy:    答案是否真的回答了问题(0-1)
  - context_precision:   top-k 中相关 chunk 的位次质量(0-1, LLM judge 每条是否相关)
  - context_recall:      ground_truth 答案被 context 覆盖的比例(LLM judge)

vs 老自实现 eval_pipeline.py:
  - 自实现的 context_precision=1/hit_rank 是 MRR 近似, RAGAS 用 LLM judge 更准
  - 自实现无 faithfulness, RAGAS 补齐
  - 中文场景用 glm-4-flash 当 judge LLM, 通过 langchain_openai 接 OpenAI 兼容协议

CI 门禁:
  - 读 eval/baseline.json 基线, 任一指标降超 3% 退出非零(阻断 PR 合并)
  - 评测结果落到 eval/eval_ragas_report.md 便于人查

用法:
  python3 eval/ragas_pipeline.py            # 跑 RAGAS 评测
  python3 eval/ragas_pipeline.py --gate     # 跑完对比基线, -3% 阻断
  python3 eval/ragas_pipeline.py --set-baseline  # 把本次结果存为新基线

依赖:
  pip install ragas langchain-openai langchain-core
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests
from dotenv import load_dotenv

# Phase 0.1: judge client 必须走异族 provider, 不再读 LLM_* (那是业务 LLM 同源污染源)
sys.path.insert(0, str(Path(__file__).resolve().parent))
from judge_client import build_judge_llm, get_provider_meta  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = Path(__file__).resolve().parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

# Phase 0.5: 默认题库切到 asset 化的 golden(100 题, source 标注清楚)
QUESTIONS_FILE = Path(os.getenv("EVAL_QUESTIONS_FILE", str(EVAL_DIR / "golden" / "golden.jsonl")))
RAW_OUT_FILE = EVAL_DIR / "ragas_raw.jsonl"
REPORT_FILE = EVAL_DIR / "eval_ragas_report.md"
BASELINE_FILE = EVAL_DIR / "ragas_baseline.json"

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8090/api/v1/chat")
CHAT_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")

# embed 走 OpenAI 兼容 (BGE-M3 服务) — 与 judge 完全不同管线
EMBED_BASE_URL = os.getenv("EMBEDDING_BASE_URL", "http://localhost:8082")

# judge 配置从 JUDGE_LLM_PROVIDER_N_* 读, 见 eval/judge_client.py
# 全局 JUDGE_PROVIDER_ID 由 --judge-provider flag 设置, 默认 1
DEFAULT_JUDGE_PROVIDER_ID = 1

METRICS_TO_TRACK = ["faithfulness", "answer_relevancy", "context_precision", "context_recall"]
GATE_THRESHOLD = 0.03  # 文档约定 -3% 阻断


def load_questions():
    items = []
    with open(QUESTIONS_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def call_chat(query, top_k=5):
    """调 chat 接口: 返回 (answer, [citations])

    字段名要 snake_case: 项目全局 Jackson PropertyNamingStrategies.SNAKE_CASE,
    传 camelCase 会被静默丢(以前 topK 被吞 fallback 到默认值, 凑巧不影响; docId/source 则会真丢)。
    contexts 用 llm_context(parent-child 模式下=parent 全文, 真正喂 LLM 的内容), 不是 snippet(child 摘要);
    若用 snippet 评测 context_recall 几乎捕捉不到 parent-child 的提升。
    """
    r = requests.post(
        CHAT_URL,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {CHAT_TOKEN}"},
        json={"query": query, "top_k": top_k},
        timeout=120,
    )
    r.raise_for_status()
    data = r.json()
    # 优先 llm_context(parent 全文), 没有/为空才 fallback snippet(老接口/flat 模式)
    contexts = []
    for c in data.get("citations", []):
        ctx = c.get("llm_context") or c.get("snippet")
        if ctx:
            contexts.append(ctx)
    return data.get("answer", ""), contexts


def build_ragas_dataset(questions):
    """逐题调 chat, 收集 answer + contexts, 拼成 RAGAS 输入格式。"""
    samples = []
    for i, q in enumerate(questions, 1):
        try:
            answer, contexts = call_chat(q["question"])
            # 兼容 gen_questions.py 多种 key: 旧版 'answer', V3-W3 新版 'ground_truth_answer'
            gt = q.get("ground_truth_answer") or q.get("answer") or ""
            samples.append({
                "question": q["question"],
                "ground_truth": gt,
                "answer": answer,
                "contexts": contexts,
            })
            print(f"  [{i}/{len(questions)}] got answer_len={len(answer)} ctx={len(contexts)} gt_len={len(gt)}")
        except Exception as e:
            print(f"  [{i}/{len(questions)}] FAIL: {e}")
        time.sleep(0.5)  # 礼让 LLM
    return samples


def run_ragas(samples, judge_provider_id: int = DEFAULT_JUDGE_PROVIDER_ID):
    """跑 RAGAS 评测。延迟 import 避免装包前后副作用。

    judge 必须走异族 provider(judge_client.build_judge_llm), 不再 fallback 到业务 LLM。
    """
    from datasets import Dataset  # ragas 依赖 datasets
    from ragas import evaluate
    from ragas.metrics import (
        faithfulness,
        answer_relevancy,
        context_precision,
        context_recall,
    )
    from ragas.run_config import RunConfig

    judge_llm, judge_meta = build_judge_llm(judge_provider_id)
    print(f"[RAGAS] judge provider #{judge_meta.provider_id} family={judge_meta.family} "
          f"model={judge_meta.model} thinking={judge_meta.is_thinking}")

    # embedding: BGE-M3 走 OpenAI 兼容协议(与 judge 完全不同管线)。
    # 关键: 不能用 langchain_openai.OpenAIEmbeddings, 它底层走 httpx,
    # 与 BGE-M3 容器(Docker proxy + text-embeddings-inference) 不兼容, 返回 502
    # (这正是历史上 answer_relevancy=0 的真正根因)。
    # 自己实现 requests 底层的 Embeddings 类规避, 验证 OK。
    import requests as _requests
    from langchain_core.embeddings import Embeddings as _LCEmbeddings

    class _BgeM3Embeddings(_LCEmbeddings):
        def __init__(self, base_url, model="BAAI/bge-m3", timeout=60):
            self.url = base_url.rstrip("/") + "/v1/embeddings"
            self.model = model
            self.timeout = timeout

        def embed_documents(self, texts):
            r = _requests.post(
                self.url,
                json={"input": list(texts), "model": self.model},
                headers={"Authorization": "Bearer dummy"},
                timeout=self.timeout,
            )
            r.raise_for_status()
            return [d["embedding"] for d in r.json()["data"]]

        def embed_query(self, text):
            return self.embed_documents([text])[0]

    judge_embed = _BgeM3Embeddings(base_url=EMBED_BASE_URL)

    ds = Dataset.from_list(samples)
    print(f"\n[RAGAS] 评测 {len(samples)} 条, judge={judge_meta.model} ...")
    # GLM-4.7 思考模式: 单题 ~20-60s reasoning; 智谱免费档 RPM 限流。
    # 默认 RAGAS max_workers=16 会触发 429。降并发到 4, timeout=600s 应对长 reasoning。
    rc = RunConfig(max_workers=4, timeout=600, max_retries=3)
    try:
        result = evaluate(
            ds,
            metrics=[faithfulness, answer_relevancy, context_precision, context_recall],
            llm=judge_llm,
            embeddings=judge_embed,
            run_config=rc,
            raise_exceptions=False,
        )
    except Exception as e:
        print(f"RAGAS 评测失败: {e}")
        raise

    return result_to_scores(result), judge_meta


def result_to_scores(result):
    """归一化 RAGAS 结果到 dict[str, float].

    RAGAS 0.2.x Result 对象支持 to_pandas() 转成 DataFrame, 列名 = metric 名。
    每列是逐行得分, 取 ignore_nan 的均值。
    NaN 通常源于 judge LLM 调用失败/拒判, 不能进均值。
    """
    import math

    df = result.to_pandas()
    scores = {}
    for k in METRICS_TO_TRACK:
        col = k if k in df.columns else next((c for c in df.columns if c.startswith(k)), None)
        if col is None or len(df) == 0:
            scores[k] = 0.0
            continue
        nums = [
            v for v in df[col].tolist()
            if isinstance(v, (int, float)) and not (isinstance(v, float) and math.isnan(v))
        ]
        scores[k] = sum(nums) / len(nums) if nums else 0.0
    return scores


def write_report(scores, samples, judge_meta=None):
    md = ["# RAGAS 评测报告 (P1)\n",
          "\n设计文档 README.md L16: RAGAS 答案质量评测 + CI 门禁 (-3% 阻断)\n"]
    # Phase 0.1: 报告需标注 judge 是异族 (Phase 0 前"同源污染"问题已修复)
    if judge_meta is not None:
        md.append(
            f"\n> Judge provider #{judge_meta.provider_id} `{judge_meta.family}/{judge_meta.model}` "
            f"(temperature={judge_meta.temperature}, thinking={judge_meta.is_thinking}). "
            f"Judge 与业务 LLM 配置物理隔离 (JUDGE_LLM_PROVIDER_* env namespace), Phase 0 同源污染已脱。\n"
        )
    md.append("\n## 核心指标\n")
    md.append("\n| 指标 | 数值 | 说明 |\n|---|---|---|\n")
    desc = {
        "faithfulness": "答案是否完全从 context 推导, 高=低幻觉",
        "answer_relevancy": "答案相关性, 高=答非所问少",
        "context_precision": "LLM judge 检索条目相关性位次质量",
        "context_recall": "ground_truth 被 context 覆盖比例",
    }
    for k in METRICS_TO_TRACK:
        md.append(f"| {k} | {scores[k]:.4f} | {desc[k]} |\n")
    md.append(f"\n## 样本数: {len(samples)}\n")
    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        f.write("".join(md))
    with open(RAW_OUT_FILE, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")


def gate_check(scores):
    """对比 baseline, 任一指标降超 GATE_THRESHOLD 退出非零。"""
    if not BASELINE_FILE.exists():
        print(f"[gate] 无 baseline, 跳过门禁(本跑可作 baseline: --set-baseline)")
        return 0
    baseline = json.load(open(BASELINE_FILE, encoding="utf-8"))
    print(f"\n[gate] 对比 baseline {BASELINE_FILE.name}:")
    fail = False
    for k in METRICS_TO_TRACK:
        base_v = baseline.get(k)
        cur_v = scores.get(k)
        if base_v is None:
            continue
        delta = cur_v - base_v
        flag = "✓" if delta >= -GATE_THRESHOLD else "✗ BLOCK"
        print(f"  {flag} {k}: {base_v:.4f} → {cur_v:.4f} (Δ={delta:+.4f})")
        if delta < -GATE_THRESHOLD:
            fail = True
    if fail:
        print(f"\n[gate] ❌ 指标下降超 {-GATE_THRESHOLD:.0%}, 阻断合并")
        return 1
    print(f"\n[gate] ✓ 全部指标在阈值内, 放行")
    return 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gate", action="store_true", help="对比 baseline, -3% 阻断")
    p.add_argument("--set-baseline", action="store_true", help="把本次结果存为新 baseline")
    # Phase 0.1: 默认单 judge = provider 1 (GLM)。STOP 校验 / Phase 0.3 ensemble 用 --judge-provider 控制
    p.add_argument(
        "--judge-provider",
        type=int,
        default=DEFAULT_JUDGE_PROVIDER_ID,
        choices=[1, 2, 3, 4, 5],
        help="judge provider id, 对应 .env 的 JUDGE_LLM_PROVIDER_N_* (默认 1)",
    )
    p.add_argument(
        "--questions",
        type=str,
        default=str(QUESTIONS_FILE),
        help=f"题库 jsonl 路径 (默认 {QUESTIONS_FILE.name})",
    )
    args = p.parse_args()

    # 切换题库 — Phase 0.5 起默认 golden.jsonl
    global QUESTIONS_FILE
    QUESTIONS_FILE = Path(args.questions)
    if not QUESTIONS_FILE.exists():
        print(f"ERROR: 题库不存在 {QUESTIONS_FILE}", file=sys.stderr)
        return 1

    questions = load_questions()
    print(f"[1/3] 装入 {len(questions)} 题 (from {QUESTIONS_FILE.name})")

    print(f"[2/3] 调 chat 接口收集 (answer + contexts) ...")
    samples = build_ragas_dataset(questions)

    print(f"[3/3] 跑 RAGAS 评测 (judge provider #{args.judge_provider}) ...")
    scores, judge_meta = run_ragas(samples, judge_provider_id=args.judge_provider)
    write_report(scores, samples, judge_meta=judge_meta)
    print(f"\n✓ 报告: {REPORT_FILE}")
    for k in METRICS_TO_TRACK:
        print(f"  {k:20} = {scores[k]:.4f}")
    print(f"  judge               = provider#{judge_meta.provider_id} ({judge_meta.family}/{judge_meta.model})")

    if args.set_baseline:
        with open(BASELINE_FILE, "w", encoding="utf-8") as f:
            json.dump(scores, f, ensure_ascii=False, indent=2)
        print(f"\n✓ 已存为 baseline: {BASELINE_FILE}")

    if args.gate:
        sys.exit(gate_check(scores))

    return 0


if __name__ == "__main__":
    main()
