"""生成质量指标。

设计:
- `citation_accuracy` 为纯函数 (无 LLM), 直接可在单元测试覆盖。
- `answer_correctness` / `faithfulness` 需 LLM-as-judge; 为保持本模块可在 CI/纯函数
  单测里跑而不绑死 HTTP 客户端, 这两个函数接收一个 callable `judge_fn(prompt) -> str`,
  实际的 judge HTTP 调用由 runner/judge_client.py 注入 (依赖反转)。
  `judge_llm_score()` 负责把 judge 自由文本归一化到 [0,1]。

复用 .env 的 judge LLM 角色 (deepseek-chat / qwen-max 见 JUDGE_LLM_PROVIDER_*),
评测与生产判官同源约定。
"""
from __future__ import annotations

import re
from typing import Callable, Iterable


# ─── Citation Accuracy (纯函数, 无 LLM) ─────────────────────────────────
def citation_accuracy(
    cited_ids: Iterable[int], gold_ids: Iterable[int]
) -> float:
    """引用精度 = |cited ∩ gold| / max(|cited|, 1)。

    衡量 LLM 答案的引用是否落在 ground-truth chunk 上。无引用 → 0
    (即使答案对, 没标 provenance 也算引用不达标)。
    """
    cited = list(cited_ids)
    gold = set(gold_ids)
    if not cited:
        return 0.0
    hits = sum(1 for cid in cited if cid in gold)
    return hits / len(cited)


def citation_recall(cited_ids: Iterable[int], gold_ids: Iterable[int]) -> float:
    """金标证据覆盖率 = |unique(cited) ∩ gold| / max(|gold|, 1)。"""
    cited = set(cited_ids)
    gold = set(gold_ids)
    if not gold:
        return 0.0
    return len(cited & gold) / len(gold)


def citation_hit_rate(cited_ids: Iterable[int], gold_ids: Iterable[int]) -> float:
    """答案是否至少引用一个金标 chunk。"""
    return 1.0 if set(cited_ids) & set(gold_ids) else 0.0


# ─── LLM-judge 文本归一 (供 correctness / faithfulness 共用) ────────────
_SCORE_RE = re.compile(r"([0-1](?:\.\d+)?)")

# 中文 judge 常见输出 → 分数映射
_TEXT_TO_SCORE = {
    "yes": 1.0, "pass": 1.0, "完全一致": 1.0, "正确": 1.0, "符合": 1.0,
    "partial": 0.5, "部分": 0.5, "mostly": 0.75,
    "no": 0.0, "fail": 0.0, "不一致": 0.0, "错误": 0.0, "无关": 0.0,
}


def judge_llm_score(raw: str) -> float:
    """把 judge LLM 的自由文本响应归一到 [0, 1]。

    解析优先级:
    1. 首个 [0,1] 浮点数 (兼容 "Score: 0.8" / "0.75 / 1" / 单纯 "1"/"0")
    2. 关键词 (yes/no/partial/...)
    3. 兜底 0.0
    """
    if not raw:
        return 0.0
    text = raw.strip().lower()
    # 1. 数字
    m = _SCORE_RE.search(text)
    if m:
        v = float(m.group(1))
        if 0.0 <= v <= 1.0:
            return v
    # 2. 关键词
    for kw, score in _TEXT_TO_SCORE.items():
        if kw in text:
            return score
    return 0.0


# ─── Answer Correctness (LLM judge) ─────────────────────────────────────
def answer_correctness(
    pred_answer: str,
    gold_answer: str,
    judge_fn: Callable[[str], str] | None = None,
    question: str | None = None,
) -> float:
    """答案正确性, 0~1。

    judge_fn(prompt) -> 原始 LLM 文本。为 None 时退化为关键词 overlap
    (供无 LLM 的快速冒烟, 不是严谨指标)。
    """
    if judge_fn is None:
        return _overlap_f1(pred_answer, gold_answer)
    question_block = f"【问题】\n{question}\n\n" if question else ""
    prompt = (
        "你是严格的答案正确性判官。只把【标准答案】中直接回应【问题】的事实视为必答要点；"
        "标准答案中的背景、旁支和未被问题询问的限制不应导致扣分。分数等于预测答案正确覆盖的"
        "必答要点比例；额外但正确的相关信息不影响覆盖分。\n\n"
        f"{question_block}"
        f"【标准答案】\n{gold_answer}\n\n"
        f"【预测答案】\n{pred_answer}\n\n"
        "只输出一个 [0, 1] 的浮点数 (1=完全覆盖必答要点, 0.5=部分, 0=完全不相关), "
        "不要其它解释。"
    )
    return judge_llm_score(judge_fn(prompt))


# ─── Faithfulness (LLM judge) ───────────────────────────────────────────
def faithfulness(
    pred_answer: str,
    context: str,
    judge_fn: Callable[[str], str] | None = None,
) -> float:
    """忠实度: 答案是否仅由给定 context 推导 (0~1, 越高越无幻觉)。

    context 通常是 retrieve 接口拿到的 citations[*].llm_context 拼接。
    judge_fn 为 None 时退化为 "答案 token 是否 90%+ 出现在 context" 的近似。
    """
    if judge_fn is None:
        return _context_coverage(pred_answer, context)
    prompt = (
        "你是严格的事实核验员。请先在内部把【答案】拆成可核验断言，再判断每条断言是否能由"
        "【上下文】直接支持或语义蕴含；不要求逐字一致，忽略措辞、格式和 [n] 引用标记。"
        "上下文没有的具体事实（版本号/数值/类名/步骤）才视为幻觉。分数等于被支持断言占比；"
        "所有断言均受支持必须输出 1。\n\n"
        f"【上下文】\n{context}\n\n"
        f"【答案】\n{pred_answer}\n\n"
        "只输出一个 [0, 1] 的浮点数 (1=完全由上下文支持, 0.5=部分, 0=大量幻觉), "
        "不要其它解释。"
    )
    return judge_llm_score(judge_fn(prompt))


def evidence_completeness(
    gold_answer: str,
    context: str,
    judge_fn: Callable[[str], str] | None = None,
) -> float:
    """证据完整性：上下文覆盖标准答案关键事实的比例。"""
    if not gold_answer or not context:
        return 0.0
    if judge_fn is None:
        return _context_coverage(gold_answer, context)
    prompt = (
        "你是严格的 RAG 证据完整性判官。请先在内部把【标准答案】拆成最小关键事实，"
        "再判断每条关键事实是否能由【检索上下文】直接支持或语义蕴含；不要求逐字一致，"
        "不得使用外部知识。分数等于已被上下文支持的关键事实占比；全部覆盖必须输出 1，"
        "完全没有覆盖输出 0。\n\n"
        f"【标准答案】\n{gold_answer}\n\n"
        f"【检索上下文】\n{context}\n\n"
        "只输出一个 [0, 1] 的浮点数，不要其它解释。"
    )
    return judge_llm_score(judge_fn(prompt))


# ─── 无 LLM 的退化实现 (judge_fn=None 时用) ────────────────────────────
def _overlap_f1(pred: str, gold: str) -> float:
    """字符 unigram F1 (中文友好)。仅作 LLM 不可用时的快速冒烟。"""
    pt = set(_tokenize(pred))
    gt = set(_tokenize(gold))
    if not pt or not gt:
        return 0.0
    inter = pt & gt
    p = len(inter) / len(pt)
    r = len(inter) / len(gt)
    return 0.0 if (p + r) == 0 else 2 * p * r / (p + r)


def _context_coverage(pred: str, context: str) -> float:
    """答案 token 在 context 中出现的比例。仅快速冒烟用。"""
    pt = _tokenize(pred)
    if not pt:
        return 0.0
    cs = set(_tokenize(context))
    return sum(1 for t in pt if t in cs) / len(pt)


def _tokenize(s: str) -> list[str]:
    """中文按 bigram + 单字, 英文按词 — 给 _overlap_f1 / _context_coverage 用。"""
    s = (s or "").lower()
    grams: list[str] = []
    # 英文/数字
    grams.extend(w for w in re.findall(r"[a-z0-9_]+", s) if len(w) >= 2)
    # 中文 bigram
    zh = "".join(ch for ch in s if "\u4e00" <= ch <= "\u9fff")
    grams.extend(zh[i : i + 2] for i in range(max(0, len(zh) - 1)))
    return grams


def aggregate_generation(per_query: list[dict]) -> dict:
    """宏平均所有 per_query 的 generation 指标。"""
    out = {}
    if not per_query:
        return out
    keys: set[str] = set()
    for d in per_query:
        keys.update(d.keys())
    for key in keys:
        vals = [float(d[key]) for d in per_query if key in d and d[key] is not None]
        out[key] = sum(vals) / len(vals) if vals else 0.0
    return out
