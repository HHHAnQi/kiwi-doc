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
  # 正式基线需至少3轮，使用 eval/aggregate_ragas_runs.py 聚合

依赖:
  pip install ragas langchain-openai langchain-core
"""
import argparse
import hashlib
import json
import os
import random
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from dotenv import load_dotenv

# Phase 0.1: judge client 必须走异族 provider, 不再读 LLM_* (那是业务 LLM 同源污染源)
sys.path.insert(0, str(Path(__file__).resolve().parent))
from judge_client import build_judge_llm, get_provider_meta  # noqa: E402

# 使用完整包名，避免把 eval.metrics 注册成顶层 ``metrics``，与 agentic/scripts/metrics.py 冲突。
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from eval.metrics import retrieval_metrics as retrieval_metrics_lib  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent
EVAL_DIR = Path(__file__).resolve().parent
load_dotenv(PROJECT_ROOT / ".env", override=False)

# Phase 0.5: 默认题库切到 asset 化的 golden(100 题, source 标注清楚)
QUESTIONS_FILE = Path(os.getenv("EVAL_QUESTIONS_FILE", str(EVAL_DIR / "golden" / "golden.jsonl")))
RAW_OUT_FILE = EVAL_DIR / "ragas_raw.jsonl"
RUN_METADATA_FILE = EVAL_DIR / "ragas_run_metadata.json"
RUN_ARCHIVE_DIR = EVAL_DIR / "runs"
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
BOOTSTRAP_ITERATIONS = int(os.getenv("EVAL_BOOTSTRAP_ITERATIONS", "2000"))
BOOTSTRAP_SEED = int(os.getenv("EVAL_BOOTSTRAP_SEED", "20260824"))


def load_questions(path: "Path | None" = None):
    """加载题目。path 不传则用模块级 QUESTIONS_FILE。"""
    qpath = path or QUESTIONS_FILE
    items = []
    with open(qpath, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


def call_chat(query, top_k=5):
    """调 chat 接口，返回答案、上下文和可审计的运行状态。

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
    citations = []
    for rank, c in enumerate(data.get("citations", []), 1):
        ctx = c.get("llm_context") or c.get("snippet")
        if ctx:
            contexts.append(ctx)
        citations.append({
            "rank": rank,
            "chunk_id": c.get("chunk_id"),
            "doc_id": c.get("doc_id"),
            "page": c.get("page"),
            "section_path": c.get("section_path") or [],
        })
    return {
        "answer": data.get("answer", ""),
        "contexts": contexts,
        "citations": citations,
        "state_hint": data.get("state_hint", "UNKNOWN"),
        "trace_id": data.get("trace_id"),
        "pipeline_type": data.get("pipeline_type"),
    }


def build_ragas_dataset(questions):
    """逐题调 chat, 收集 answer + contexts, 拼成 RAGAS 输入格式。"""
    samples = []
    for i, q in enumerate(questions, 1):
        try:
            chat = call_chat(q["question"])
            answer = chat["answer"]
            contexts = chat["contexts"]
            # 兼容 gen_questions.py 多种 key: 旧版 'answer', V3-W3 新版 'ground_truth_answer'
            gt = q.get("ground_truth_answer") or q.get("answer") or ""
            gold_chunk_id = q.get("new_ground_truth_chunk_id") or q.get("ground_truth_chunk_id")
            # 冻结集经过 current-corpus remap 后，旧 document id 已不再指向
            # 当前索引。chunk id 与 document id 必须使用同一版 remap 结果，
            # 否则 RAGAS 主指标正常，但附带的检索命中率会被旧 ID 污染。
            gold_doc_id = q.get("new_ground_truth_doc_id") or q.get("ground_truth_doc_id")
            samples.append({
                "question": q["question"],
                "ground_truth": gt,
                "answer": answer,
                "contexts": contexts,
                "citations": chat["citations"],
                "retrieved_chunk_ids": [
                    citation["chunk_id"]
                    for citation in chat["citations"]
                    if citation["chunk_id"] is not None
                ],
                "retrieved_doc_ids": [
                    citation["doc_id"]
                    for citation in chat["citations"]
                    if citation["doc_id"] is not None
                ],
                "gold_chunk_ids": [] if gold_chunk_id is None or q.get("ungroundable") else [gold_chunk_id],
                "gold_doc_ids": [] if gold_doc_id is None or q.get("ungroundable") else [gold_doc_id],
                "question_type": q.get("question_type") or q.get("topic"),
                "state_hint": chat["state_hint"],
                "trace_id": chat["trace_id"],
                "pipeline_type": chat["pipeline_type"],
            })
            print(f"  [{i}/{len(questions)}] got answer_len={len(answer)} ctx={len(contexts)} gt_len={len(gt)}")
        except Exception as e:
            print(f"  [{i}/{len(questions)}] FAIL: {e}")
        time.sleep(0.5)  # 礼让 LLM
    return samples


def attach_retrieval_metrics(samples, k=5):
    """基于chat真实引用顺序计算IR指标；没有Gold ID的题不进入聚合。"""
    enriched = []
    per_query = []
    for sample in samples:
        item = dict(sample)
        gold_ids = item.get("gold_chunk_ids") or []
        if gold_ids:
            metrics = retrieval_metrics_lib.per_query_metrics(
                item.get("retrieved_chunk_ids") or [], gold_ids, k
            )
            item["retrieval_metrics"] = metrics
            per_query.append(metrics)
        else:
            item["retrieval_metrics"] = None
        enriched.append(item)
    return enriched, retrieval_metrics_lib.aggregate(per_query), len(per_query)


def retrieval_confidence_intervals(samples):
    values = {}
    for sample in samples:
        for metric, value in (sample.get("retrieval_metrics") or {}).items():
            values.setdefault(metric, []).append(value)
    return {
        metric: bootstrap_mean_ci(metric_values, seed=BOOTSTRAP_SEED + 100 + index)
        for index, (metric, metric_values) in enumerate(sorted(values.items()))
    }


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
    # Phase 2.0.2: 附加 per-sample 数据, 由 run_ragas 之后算 refusal_rate / faith_on_answered
    scores["_per_sample_df"] = df
    return scores


def bootstrap_mean_ci(values, iterations=BOOTSTRAP_ITERATIONS, seed=BOOTSTRAP_SEED):
    """确定性 percentile bootstrap 95% CI；返回可直接写JSON的结果。"""
    clean = [float(value) for value in values if isinstance(value, (int, float))]
    if not clean:
        return {"low": None, "high": None, "n": 0, "iterations": iterations}
    if len(clean) == 1:
        return {"low": clean[0], "high": clean[0], "n": 1, "iterations": iterations}

    rng = random.Random(seed)
    n = len(clean)
    means = sorted(
        sum(clean[rng.randrange(n)] for _ in range(n)) / n
        for _ in range(iterations)
    )
    low_index = max(0, int(0.025 * iterations))
    high_index = min(iterations - 1, int(0.975 * iterations))
    return {
        "low": means[low_index],
        "high": means[high_index],
        "n": n,
        "iterations": iterations,
    }


def compute_confidence_intervals(samples, per_sample_df):
    """计算RAGAS四指标及拒答分离指标的逐题bootstrap置信区间。"""
    import math

    result = {}
    for metric in METRICS_TO_TRACK:
        column = metric if metric in per_sample_df.columns else next(
            (c for c in per_sample_df.columns if c.startswith(metric)), None
        )
        values = [] if column is None else [
            value
            for value in per_sample_df[column].tolist()
            if isinstance(value, (int, float))
            and not (isinstance(value, float) and math.isnan(value))
        ]
        result[metric] = bootstrap_mean_ci(values, seed=BOOTSTRAP_SEED)

    refusal_flags = [
        1.0 if is_refusal(sample.get("answer", ""), sample.get("state_hint")) else 0.0
        for sample in samples
    ]
    result["refusal_rate"] = bootstrap_mean_ci(refusal_flags, seed=BOOTSTRAP_SEED + 1)

    faith_column = "faithfulness" if "faithfulness" in per_sample_df.columns else next(
        (c for c in per_sample_df.columns if c.startswith("faithfulness")), None
    )
    answered_faith, refused_faith = [], []
    if faith_column is not None:
        for index, sample in enumerate(samples):
            if index >= len(per_sample_df):
                break
            value = per_sample_df[faith_column].iloc[index]
            if not isinstance(value, (int, float)) or (
                isinstance(value, float) and math.isnan(value)
            ):
                continue
            target = refused_faith if refusal_flags[index] else answered_faith
            target.append(value)
    result["faith_on_answered"] = bootstrap_mean_ci(
        answered_faith, seed=BOOTSTRAP_SEED + 2
    )
    result["faith_on_refused"] = bootstrap_mean_ci(
        refused_faith, seed=BOOTSTRAP_SEED + 3
    )
    return result


# Phase 2.0.2: 拒答模式匹配
import re as _re
_REFUSAL_PATTERNS = [
    _re.compile(r"知识库中没有相关内容"),
    _re.compile(r"未在知识库中找到"),
    _re.compile(r"知识库中还没有"),
    _re.compile(r"片段与问题完全无关"),
]


_REFUSAL_STATES = {
    "EMPTY_KB",
    "NO_RECALL",
    "LLM_DEGRADED",
    "VERIFY_FAILED",
    "REFUSED",
}


def is_refusal(answer: str, state_hint: str | None = None) -> bool:
    """用结构化状态优先识别拒答；文本仅作为旧接口兼容兜底。

    正确答案可能非常短（例如“默认 160 个虚拟节点”），因此绝不能再用长度判拒答。
    """
    if state_hint and str(state_hint).upper() in _REFUSAL_STATES:
        return True
    if not answer or not isinstance(answer, str):
        return False
    a = answer.strip()
    return any(p.search(a) for p in _REFUSAL_PATTERNS)


def compute_refusal_metrics(samples, per_sample_df) -> dict:
    """Phase 2.0.2 三档独立指标:
    - refusal_rate:           答案被判为拒答的比例
    - faith_on_answered:      非拒答题的 faith 均值（真实 RAG 能力）
    - faith_on_refused:       拒答题的 faith 均值（应几乎 0，验尺子）

    用 per_sample_df 同序的 faithfulness 列。
    """
    import math
    n = len(samples)
    if n == 0:
        return {"refusal_rate": 0.0, "faith_on_answered": 0.0, "faith_on_refused": 0.0}
    faith_col = "faithfulness" if "faithfulness" in per_sample_df.columns else \
        next((c for c in per_sample_df.columns if c.startswith("faithfulness")), None)
    refused, answered = [], []
    for i, s in enumerate(samples):
        ans = s.get("answer", "")
        if faith_col is not None and i < len(per_sample_df):
            f = per_sample_df[faith_col].iloc[i]
            if isinstance(f, float) and math.isnan(f):
                continue
            f = float(f)
        else:
            continue
        if is_refusal(ans, s.get("state_hint")):
            refused.append(f)
        else:
            answered.append(f)
    refusal_rate = len(refused) / n
    faith_on_answered = sum(answered) / len(answered) if answered else 0.0
    faith_on_refused = sum(refused) / len(refused) if refused else 0.0
    return {
        "refusal_rate": refusal_rate,
        "faith_on_answered": faith_on_answered,
        "faith_on_refused": faith_on_refused,
        "_n_refused": len(refused),
        "_n_answered": len(answered),
    }


def attach_per_sample_scores(samples, per_sample_df):
    """把 RAGAS 每题得分写回原始样本，保证 badcase 可追溯。"""
    import math

    enriched = []
    for i, sample in enumerate(samples):
        item = dict(sample)
        metrics = {}
        if i < len(per_sample_df):
            row = per_sample_df.iloc[i]
            for metric in METRICS_TO_TRACK:
                column = metric if metric in per_sample_df.columns else next(
                    (c for c in per_sample_df.columns if c.startswith(metric)), None
                )
                if column is None:
                    continue
                value = row[column]
                if isinstance(value, (int, float)) and not (
                    isinstance(value, float) and math.isnan(value)
                ):
                    metrics[metric] = float(value)
        item["metrics"] = metrics
        enriched.append(item)
    return enriched


def _git_commit() -> str | None:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=PROJECT_ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except Exception:
        return None


def write_run_metadata(
    question_path,
    scores,
    refusal,
    confidence_intervals,
    retrieval_scores,
    retrieval_confidence_intervals_95,
    retrieval_evaluable_n,
    judge_meta,
):
    """保存不含密钥的配置快照、题集指纹和汇总结果。"""
    qpath = Path(question_path)
    generated_at = datetime.now(timezone.utc).isoformat()
    questions_sha256 = hashlib.sha256(qpath.read_bytes()).hexdigest()
    git_commit = _git_commit()
    experiment_id = hashlib.sha256(
        f"{generated_at}|{git_commit}|{questions_sha256}".encode("utf-8")
    ).hexdigest()[:16]
    metadata = {
        "schema_version": 2,
        "experiment_id": experiment_id,
        "generated_at": generated_at,
        "git_commit": git_commit,
        "questions_file": str(qpath),
        "questions_sha256": questions_sha256,
        "sample_count": sum(1 for line in qpath.read_text(encoding="utf-8").splitlines() if line.strip()),
        "chat_url": CHAT_URL,
        "judge": {
            "provider_id": getattr(judge_meta, "provider_id", None),
            "family": getattr(judge_meta, "family", None),
            "model": getattr(judge_meta, "model", None),
            "temperature": getattr(judge_meta, "temperature", None),
        },
        "public_config": {
            name: os.getenv(name)
            for name in (
                "RAG_RETRIEVE_MODE",
                "RAG_RETRIEVE_CANDIDATE_POOL",
                "RAG_RETRIEVE_RRF_K",
                "RAG_RERANK_ENABLED",
                "RAG_RERANK_MODEL",
                "RAG_QUERY_ENHANCE_ENABLED",
                "RAG_QUERY_ENHANCE_MODE",
                "RAG_CITATION_VERIFIER_ENABLED",
                "RAG_CITATION_VERIFIER_ON_FAIL",
            )
        },
        "scores": scores,
        "confidence_intervals_95": confidence_intervals,
        "retrieval_scores": retrieval_scores,
        "retrieval_confidence_intervals_95": retrieval_confidence_intervals_95,
        "retrieval_evaluable_n": retrieval_evaluable_n,
        "refusal_metrics": refusal,
    }
    RUN_METADATA_FILE.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return metadata


def archive_run(metadata, samples):
    """按experiment_id归档，避免后续三轮评测相互覆盖。"""
    run_dir = RUN_ARCHIVE_DIR / metadata["experiment_id"]
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (run_dir / "samples.jsonl").write_text(
        "".join(json.dumps(sample, ensure_ascii=False) + "\n" for sample in samples),
        encoding="utf-8",
    )
    return run_dir


def write_report(
    scores,
    samples,
    judge_meta=None,
    refusal=None,
    confidence_intervals=None,
    retrieval_scores=None,
    retrieval_confidence_intervals_95=None,
    retrieval_evaluable_n=0,
):
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
    md.append("\n| 指标 | 数值 | 95% CI | 说明 |\n|---|---|---|---|\n")
    desc = {
        "faithfulness": "答案是否完全从 context 推导, 高=低幻觉",
        "answer_relevancy": "答案相关性, 高=答非所问少",
        "context_precision": "LLM judge 检索条目相关性位次质量",
        "context_recall": "ground_truth 被 context 覆盖比例",
    }
    for k in METRICS_TO_TRACK:
        ci = (confidence_intervals or {}).get(k, {})
        ci_text = (
            f"[{ci['low']:.4f}, {ci['high']:.4f}]"
            if ci.get("low") is not None else "N/A"
        )
        md.append(f"| {k} | {scores[k]:.4f} | {ci_text} | {desc[k]} |\n")
    md.append(f"\n## 样本数: {len(samples)}\n")

    if retrieval_scores:
        md.append(f"\n## 检索侧指标（有Gold Chunk ID的样本: {retrieval_evaluable_n}）\n")
        md.append("\n| 指标 | 数值 | 95% CI |\n|---|---|---|\n")
        for metric in sorted(retrieval_scores):
            ci = (retrieval_confidence_intervals_95 or {}).get(metric, {})
            ci_text = (
                f"[{ci['low']:.4f}, {ci['high']:.4f}]"
                if ci.get("low") is not None else "N/A"
            )
            md.append(f"| {metric} | {retrieval_scores[metric]:.4f} | {ci_text} |\n")

    # Phase 2.0.2: 拒答分离指标(把诚实拒答与幻觉分开, RAGAS 默认混在一起)
    if refusal:
        md.append("\n## Phase 2.0.2 拒答分离指标\n")
        md.append("\n> RAGAS faithfulness 把 [诚实拒答 (知识库中没有相关内容)] 与 [幻觉] 都判 0,\n")
        md.append("\n> 拒答分离指标把两类分开看, 才能真实衡量 RAG 能力。\n\n")
        md.append("| 指标 | 数值 | 说明 |\n|---|---|---|\n")
        md.append(f"| **refusal_rate** | {refusal['refusal_rate']:.4f} "
                  f"({refusal['_n_refused']}/{refusal['_n_refused']+refusal['_n_answered']}) | "
                  f"拒答率(短答 or 含'无相关') |\n")
        md.append(f"| **faith_on_answered** | {refusal['faith_on_answered']:.4f} | "
                  f"非拒答题 faith 均值 ← 真实 RAG 能力 |\n")
        md.append(f"| faith_on_refused | {refusal['faith_on_refused']:.4f} | "
                  f"拒答题 faith, 应≈0(尺刻度验证) |\n")

    with open(REPORT_FILE, "w", encoding="utf-8") as f:
        f.write("".join(md))
    with open(RAW_OUT_FILE, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")


def gate_check(scores, current_metadata=None):
    """对比 baseline, 任一指标降超 GATE_THRESHOLD 退出非零。"""
    if not BASELINE_FILE.exists():
        print(f"[gate] 无 baseline, 门禁状态 INCOMPLETE (可用 --set-baseline 冻结)")
        return 2
    baseline = json.load(open(BASELINE_FILE, encoding="utf-8"))
    baseline_scores = baseline.get("scores", baseline)
    if baseline.get("schema_version") != 2:
        print("[gate] 旧基线缺少题集/Judge指纹，门禁状态 INCOMPLETE；请重新冻结v2基线")
        return 2
    if current_metadata is None:
        print("[gate] 当前运行缺少metadata，门禁状态 INCOMPLETE")
        return 2
    for field in ("questions_sha256", "sample_count"):
        if baseline.get(field) != current_metadata.get(field):
            print(f"[gate] {field} 与基线不一致，不能比较")
            return 2
    if baseline.get("judge") != current_metadata.get("judge"):
        print("[gate] Judge配置与基线不一致，不能比较")
        return 2
    print(f"\n[gate] 对比 baseline {BASELINE_FILE.name}:")
    fail = False
    for k in METRICS_TO_TRACK:
        base_v = baseline_scores.get(k)
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
    p.add_argument(
        "--set-baseline",
        action="store_true",
        help="已弃用：单轮不得冻结正式基线；请使用 aggregate_ragas_runs.py",
    )
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
    qpath = Path(args.questions)
    if not qpath.exists():
        print(f"ERROR: 题库不存在 {qpath}", file=sys.stderr)
        return 1

    questions = load_questions(qpath)
    print(f"[1/3] 装入 {len(questions)} 题 (from {qpath.name})")

    print(f"[2/3] 调 chat 接口收集 (answer + contexts) ...")
    samples = build_ragas_dataset(questions)
    samples, retrieval_scores, retrieval_evaluable_n = attach_retrieval_metrics(samples, k=5)
    retrieval_ci = retrieval_confidence_intervals(samples)

    print(f"[3/3] 跑 RAGAS 评测 (judge provider #{args.judge_provider}) ...")
    scores, judge_meta = run_ragas(samples, judge_provider_id=args.judge_provider)

    # Phase 2.0.2: per-sample faith 已经在 scores["_per_sample_df"] 里, 算拒答分离指标
    per_df = scores.pop("_per_sample_df", None)
    refusal = compute_refusal_metrics(samples, per_df) if per_df is not None else None
    confidence_intervals = (
        compute_confidence_intervals(samples, per_df) if per_df is not None else {}
    )
    if per_df is not None:
        samples = attach_per_sample_scores(samples, per_df)

    write_report(
        scores,
        samples,
        judge_meta=judge_meta,
        refusal=refusal,
        confidence_intervals=confidence_intervals,
        retrieval_scores=retrieval_scores,
        retrieval_confidence_intervals_95=retrieval_ci,
        retrieval_evaluable_n=retrieval_evaluable_n,
    )
    run_metadata = write_run_metadata(
        qpath,
        scores,
        refusal,
        confidence_intervals,
        retrieval_scores,
        retrieval_ci,
        retrieval_evaluable_n,
        judge_meta,
    )
    run_dir = archive_run(run_metadata, samples)
    print(f"  experiment_id       = {run_metadata['experiment_id']}")
    print(f"  archived_run        = {run_dir}")
    print(f"\n✓ 报告: {REPORT_FILE}")
    for k in METRICS_TO_TRACK:
        print(f"  {k:20} = {scores[k]:.4f}")
    print(f"  judge               = provider#{judge_meta.provider_id} ({judge_meta.family}/{judge_meta.model})")
    if refusal:
        print(f"\n--- Phase 2.0.2 拒答分离指标 ---")
        print(f"  refusal_rate        = {refusal['refusal_rate']:.4f} "
              f"({refusal['_n_refused']}/{refusal['_n_refused']+refusal['_n_answered']})")
        print(f"  faith_on_answered   = {refusal['faith_on_answered']:.4f}  ← 真实 RAG 能力")
        print(f"  faith_on_refused    = {refusal['faith_on_refused']:.4f}  ← 应≈0, 尺刻度验证")

    if args.set_baseline:
        print(
            "\n[baseline] 已禁止用单轮结果冻结正式基线。请完成至少3轮后使用 "
            "eval/aggregate_ragas_runs.py 聚合。",
            file=sys.stderr,
        )
        return 2

    if args.gate:
        sys.exit(gate_check(scores, run_metadata))

    return 0


if __name__ == "__main__":
    main()
