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

PROJECT_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = Path(__file__).resolve().parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

QUESTIONS_FILE = EVAL_DIR / "questions.real.jsonl"
RAW_OUT_FILE = EVAL_DIR / "ragas_raw.jsonl"
REPORT_FILE = EVAL_DIR / "eval_ragas_report.md"
BASELINE_FILE = EVAL_DIR / "ragas_baseline.json"

CHAT_URL = os.getenv("CHAT_URL", "http://localhost:8090/api/v1/chat")
CHAT_TOKEN = os.getenv("TEST_AUTH_TOKEN", "dev-token-change-me")

# judge LLM 走 OpenAI 兼容协议 (智谱 glm-4-flash)
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "glm-4-flash")
# embed 也走 OpenAI 兼容 (BGE-M3 服务)
EMBED_BASE_URL = os.getenv("EMBEDDING_BASE_URL", "http://localhost:8082")
# RAGAS metrics 需要 embedding 维度匹配, 用 .all-MiniLM 系列会自动适配

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
            samples.append({
                "question": q["question"],
                "ground_truth": q.get("answer", ""),
                "answer": answer,
                "contexts": contexts,
            })
            print(f"  [{i}/{len(questions)}] got answer_len={len(answer)} ctx={len(contexts)}")
        except Exception as e:
            print(f"  [{i}/{len(questions)}] FAIL: {e}")
        time.sleep(0.5)  # 礼让 LLM
    return samples


def run_ragas(samples):
    """跑 RAGAS 评测。延迟 import 避免装包前后副作用。"""
    from datasets import Dataset  # ragas 依赖 datasets
    from langchain_openai import ChatOpenAI
    from ragas import evaluate
    from ragas.llms import LangchainLLMWrapper
    from ragas.metrics import (
        faithfulness,
        answer_relevancy,
        context_precision,
        context_recall,
    )
    from ragas.run_config import RunConfig

    if not LLM_BASE_URL or not LLM_API_KEY:
        print("ERROR: LLM_BASE_URL/LLM_API_KEY 未配置(.env)")
        sys.exit(1)

    # GLM-4.7 思考模式支持: 通过 extra_body 传给智谱 OpenAI 兼容接口。
    # 思考模式必须 temperature=1.0; 关思考用 0.1 兼容老 glm-4-flash。
    is_glm47 = "glm-4.7" in LLM_MODEL.lower() or "glm-4.5" in LLM_MODEL.lower()
    extra_body = {"thinking": {"type": "enabled"}} if is_glm47 else None
    temp_value = 1.0 if is_glm47 else 0.1
    raw_llm = ChatOpenAI(
        base_url=LLM_BASE_URL,  # .env 已含 /api/paas/v4
        api_key=LLM_API_KEY,
        model=LLM_MODEL,
        temperature=temp_value,
        extra_body=extra_body,  # None 时 langchain 自动忽略
        # GLM-4.7 思考模式会跑长 reasoning chain, 默认 ~10s 不够 → 大量 TimeoutError
        # (Run #1 在文末 374/400 起持续超时, recall 0.22 是假数字)。timeout 拉到 600s。
        timeout=600,
        max_retries=3,
    )
    judge_llm = LangchainLLMWrapper(raw_llm)

    # BUG fix: RAGAS 0.2.15 LangchainLLMWrapper.get_temperature(n=1) 返回 1e-8,
    # 智谱 glm-4-flash 要求 temperature>0 且最多 2 位小数 → 1e-8 被拒 400。
    # patch 成固定值(GLM-4.7 思考模式 1.0, 其它 0.1), 兼容所有国产 OpenAI 兼容服务商。
    judge_llm.get_temperature = lambda n: temp_value

    # embedding: BGE-M3 走 OpenAI 兼容协议。
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
    print(f"\n[RAGAS] 评测 {len(samples)} 条, judge={LLM_MODEL} ...")
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

    return result_to_scores(result)


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


def write_report(scores, samples):
    md = ["# RAGAS 评测报告 (P1)\n",
          "\n设计文档 README.md L16: RAGAS 答案质量评测 + CI 门禁 (-3% 阻断)\n",
          "\n## 核心指标\n",
          "\n| 指标 | 数值 | 说明 |\n|---|---|---|\n"]
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
    args = p.parse_args()

    questions = load_questions()
    print(f"[1/3] 装入 {len(questions)} 题")

    print(f"[2/3] 调 chat 接口收集 (answer + contexts) ...")
    samples = build_ragas_dataset(questions)

    print(f"[3/3] 跑 RAGAS 评测 ...")
    scores = run_ragas(samples)
    write_report(scores, samples)
    print(f"\n✓ 报告: {REPORT_FILE}")
    for k in METRICS_TO_TRACK:
        print(f"  {k:20} = {scores[k]:.4f}")

    if args.set_baseline:
        with open(BASELINE_FILE, "w", encoding="utf-8") as f:
            json.dump(scores, f, ensure_ascii=False, indent=2)
        print(f"\n✓ 已存为 baseline: {BASELINE_FILE}")

    if args.gate:
        sys.exit(gate_check(scores))


if __name__ == "__main__":
    main()
