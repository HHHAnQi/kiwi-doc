#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BadcaseRegressionRunner — 离线回归每条 badcase 在当前系统下是否仍坏。

工作流:
  1. 读 badcase/dataset/badcases.jsonl
  2. 对每条:
     - SECURITY   → 直接 pass (不转发敏感题给线上)
     - 其它       → POST /api/v1/retrieve + /api/v1/chat, 走 verdict 严格比对
  3. 写 badcase/badcase_report.json, 失败非零退出 (CI 模式用 --ci)

零业务侵入: 完全不改 Java/其它业务; 仅读 + 写 badcase/ 内文件。

用法:
  # 真跑 (backend 需要)
  eval/.venv/bin/python badcase/regression/runner.py

  # 仅做分类/格式 smoke, 不调线上 (backend 不在跑时用)
  eval/.venv/bin/python badcase/regression/runner.py --skip-remote

  # CI: 任一 regression → 非 0 退出
  eval/.venv/bin/python badcase/regression/runner.py --ci
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

# 内联解析 .env, 让本模块独立运行 (与 Makefile 给 backend source .env 的约定一致)。
# 不引入 python-dotenv 依赖: 仅解析 KEY=VALUE, 跳过注释/空行; 不覆盖已 export 的环境变量。
_env_file = REPO_ROOT / ".env"
if _env_file.exists():
    with open(_env_file, encoding="utf-8") as _f:
        for _line in _f:
            _line = _line.strip()
            if not _line or _line.startswith("#") or "=" not in _line:
                continue
            _k, _, _v = _line.partition("=")
            _k = _k.strip()
            _v = _v.strip().strip('"').strip("'")
            if _k and _k not in os.environ:
                os.environ[_k] = _v

from badcase.classifier import error_type as et  # noqa: E402
from badcase.regression import clients, verdict  # noqa: E402


def _load(path: Path) -> list[dict]:
    out: list[dict] = []
    for i, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        if "id" not in d or "question" not in d:
            raise ValueError(f"badcase 行 {i} 缺 id/question: {line[:80]}")
        out.append(d)
    return out


def run_case(c: dict, k: int, skip_remote: bool, judge_fn) -> dict:
    """跑一条 case, 返回 per_case 字典。"""
    cid = c["id"]
    runtime_et = c.get("error_type") or et.WRONG_RECALL
    runtime_reason = "skip-remote: 未发起请求"
    retrieve_resp = None
    chat_resp = None

    if c.get("error_type") == et.SECURITY:
        # 不转发敏感题给线上; 直接 mark pass
        return {
            "id": cid,
            "error_type": et.SECURITY,
            "pass": True,
            "reasons": ["SECURITY case: 跳过线上调用, 避免把敏感题发回系统"],
            "skipped_remote": True,
        }

    if skip_remote:
        # 仅走分类器 (用库里当时留存的字段), 不算严格回归 verdict
        try:
            runtime_et, runtime_reason = et.classify(c, chat_resp=None, retrieve_resp=None, judge_fn=None)
        except Exception as e:
            runtime_et, runtime_reason = et.GENERATION_ERROR, f"classify failed: {e}"
        return {
            "id": cid,
            "error_type": runtime_et,
            "pass": True,
            "reasons": [f"skip-remote smoke 仅做分类: {runtime_reason}"],
            "skipped_remote": True,
        }

    # 真跑: retrieve + chat
    try:
        retrieve_resp = clients.call_retrieve(c["question"], top_k=k)
    except Exception as e:
        retrieve_resp = {"error": str(e), "items": []}
    try:
        chat_resp = clients.call_chat(c["question"], top_k=k)
    except Exception as e:
        chat_resp = {"error": str(e), "answer": "", "state_hint": "EXCEPTION", "citations": []}

    try:
        runtime_et, runtime_reason = et.classify(
            c, chat_resp=chat_resp, retrieve_resp=retrieve_resp, judge_fn=judge_fn
        )
    except Exception as e:
        runtime_et, runtime_reason = "CLASSIFY_ERROR", str(e)

    v = verdict.verdict(c, chat_resp, retrieve_resp, judge_fn=judge_fn, k=k)
    return {
        "id": cid,
        "error_type": runtime_et,
        "label_error_type": c.get("error_type"),  # 库里标的原标签
        "pass": v["pass"],
        "reasons": v["reasons"] or [f"classify → {runtime_reason}"],
        "metrics": v["metrics"],
        "trace_id": (chat_resp or {}).get("trace_id"),
    }


def main():
    p = argparse.ArgumentParser(description="BadcaseRegressionRunner")
    p.add_argument("--dataset", default="badcase/dataset/badcases.jsonl")
    p.add_argument("--output", default="badcase/badcase_report.json")
    p.add_argument("--k", type=int, default=5)
    p.add_argument("--skip-remote", action="store_true", help="不调线上, 仅做分类+格式 smoke")
    p.add_argument("--no-judge", action="store_true", help="不用 LLM judge (退化字符串 overlap)")
    p.add_argument("--ci", action="store_true", help="有 regression 非 0 退出 (CI 用)")
    args = p.parse_args()

    dataset_path = (REPO_ROOT / args.dataset).resolve()
    out_path = (REPO_ROOT / args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cases = _load(dataset_path)
    print(f"[INFO] dataset={dataset_path} size={len(cases)} skip_remote={args.skip_remote} no_judge={args.no_judge}")

    judge_fn = None if (args.skip_remote or args.no_judge) else clients.make_judge()

    per_case: list[dict] = []
    model_meta: dict = {}
    for i, c in enumerate(cases, 1):
        r = run_case(c, args.k, args.skip_remote, judge_fn)
        per_case.append(r)
        status = "PASS" if r["pass"] else "FAIL"
        print(f"[{i}/{len(cases)}] {r['id']:10s} {r['error_type']:18s} {status} {r['reasons'][-1] if r['reasons'] else ''}")
        if i == 1 and not args.skip_remote:
            # 抓模型栈 (从第一条 retrieve 响应)
            try:
                resp = clients.call_retrieve(c["question"], top_k=args.k)
                model_meta = {
                    "model_version": resp.get("model_version"),
                    "embedding_version": resp.get("embedding_version"),
                    "rerank_model": resp.get("rerank_model"),
                    "rerank_enabled": resp.get("rerank_enabled"),
                }
            except Exception as e:
                model_meta = {"model_version_error": str(e)}
        time.sleep(0.1)

    total = len(per_case)
    passed = sum(1 for r in per_case if r["pass"])
    failed = total - passed
    skipped = sum(1 for r in per_case if r.get("skipped_remote"))

    # by_error_type 分桶
    by_type: dict[str, dict] = {}
    for r in per_case:
        t = r.get("error_type", "UNKNOWN")
        b = by_type.setdefault(t, {"pass": 0, "fail": 0})
        b["pass" if r["pass"] else "fail"] += 1

    regressions = [
        {"id": r["id"], "error_type": r.get("error_type"), "reasons": r.get("reasons")}
        for r in per_case
        if not r["pass"]
    ]

    report = {
        "total": total,
        "passed": passed,
        "failed": failed,
        "skipped": skipped,
        "by_error_type": by_type,
        "regressions": regressions,
        "per_case": per_case,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "skip_remote": args.skip_remote,
        "judge_model": clients.primary_judge_model() if judge_fn else None,
        **model_meta,
    }
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[DONE] report → {out_path}")
    print(f"        passed={passed} failed={failed} skipped={skipped} by_type={by_type}")

    if args.ci and failed > 0:
        print(f"\n[CI FAIL] {failed} regression(s)")
        sys.exit(1)


if __name__ == "__main__":
    main()
