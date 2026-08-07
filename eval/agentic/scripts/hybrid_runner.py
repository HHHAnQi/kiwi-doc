#!/usr/bin/env python3
"""PR-7f.2b.3b: Hybrid RAG Runner (REST-aware).

Same lifecycle as agentic_runner.py:

  --mode stub   (default): NOT_EXECUTED records (offline-safe).
  --mode live : calls POST /api/v1/chat with mode=RAG (forces CLASSIC_RAG —
                the Hybrid-style legacy path) and maps the response.

Hybrid never replans; replanCount is always 0. Sufficient/Replan coverage
fields remain "NOT_RUN"/empty by design.

Usage:
  python3 eval/agentic/scripts/hybrid_runner.py --mode live \\
      --base-url http://localhost:8080 \\
      --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \\
      --out     eval/agentic/results/hybrid_live.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import load_jsonl  # noqa: E402

PIPELINE = "HYBRID_RAG"
SYNC_ENDPOINT = "/api/v1/chat"
DEFAULT_TIMEOUT = 120
DEFAULT_BASE_URL = "http://localhost:8080"


def build_not_executed_record(case: dict[str, Any], reason: str) -> dict[str, Any]:
    required_reqs = [r.get("requirementId", "")
                     for r in case.get("requirements", [])
                     if r.get("required")]
    return {
        "caseId": case.get("caseId", ""),
        "pipeline": PIPELINE,
        "finalStatus": "SYSTEM_FAILED",
        "evidenceIds": [],
        "requirementCoverage": [
            {"requirementId": rid, "status": "NOT_COVERED", "evidenceIds": []}
            for rid in required_reqs
        ],
        "toolCalls": 0,
        "realToolCalls": 0,
        "llmCalls": 0,
        "replanCount": 0,  # Hybrid never replans
        "latencyMs": 0,
        "tokenUsage": {"inputTokens": 0, "outputTokens": 0},
        "answerText": "",
        "citedEvidenceIds": [],
        "guardRejections": 0,
        "falseSufficientLeak": False,
        "sufficiencyStatus": "NOT_RUN",
        "sseTerminalEvents": 0,
        "nonTerminalStepResidue": 0,
        "crossTenantEvidenceLeak": 0,
        "executedToolSignatures": [],
        "errorMessage": f"NOT_EXECUTED: {reason}",
        "executed": False,
        "stubbedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }


def _map_hybrid_response(
    case: dict[str, Any], response: dict, latency_ms: int, http_status: int,
) -> dict[str, Any]:
    """Hybrid path: executor forced through CLASSIC_RAG via mode=RAG.

    Hybrid cannot reach PLANNED_AGENT so we trust HTTP 200 + non-empty answer
    as the success signal — but coverage fields stay best-effort (Hybrid gives
    no per-Requirement insight). replanCount always 0.
    """
    required_reqs = [r.get("requirementId", "")
                     for r in case.get("requirements", [])
                     if r.get("required")]
    answer = (response.get("answer") or response.get("content") or "").strip()
    citations = response.get("citations") or response.get("evidence") or []
    cited_ids = []
    for c in citations:
        if isinstance(c, dict):
            cid = c.get("evidenceId") or c.get("id") or c.get("chunkId")
            if cid:
                cited_ids.append(str(cid))
        elif isinstance(c, str):
            cited_ids.append(c)
    usage = response.get("usage") or response.get("tokenUsage") or {}
    in_tok = int(usage.get("inputTokens") or usage.get("promptTokens") or 0)
    out_tok = int(usage.get("outputTokens") or usage.get("completionTokens") or 0)

    executed = http_status == 200
    err = "" if executed else f"NOT_EXECUTED: HTTP {http_status} from runtime"

    return {
        "caseId": case.get("caseId", ""),
        "pipeline": PIPELINE,
        "finalStatus": "ANSWERED" if (executed and answer) else (
            "REFUSED_NO_EVIDENCE" if executed else "SYSTEM_FAILED"),
        "evidenceIds": cited_ids,
        "requirementCoverage": [
            {"requirementId": rid,
             "status": "COVERED" if (executed and answer) else "NOT_COVERED",
             "evidenceIds": cited_ids} for rid in required_reqs
        ],
        "toolCalls": 1 if executed else 0,  # Hybrid = 1 retrieval call
        "realToolCalls": 1 if executed else 0,
        "llmCalls": 1 if executed else 0,
        "replanCount": 0,
        "latencyMs": latency_ms if executed else 0,
        "tokenUsage": ({"inputTokens": in_tok, "outputTokens": out_tok}
                       if executed else {"inputTokens": 0, "outputTokens": 0}),
        "answerText": answer if executed else "",
        "citedEvidenceIds": cited_ids if executed else [],
        "guardRejections": 0,
        "falseSufficientLeak": False,
        "sufficiencyStatus": "NOT_RUN",
        "sseTerminalEvents": 0,
        "nonTerminalStepResidue": 0,
        "crossTenantEvidenceLeak": 0,
        "executedToolSignatures": ["semantic_search"] if executed else [],
        "errorMessage": err,
        "executed": executed,
        "httpStatus": http_status,
    }


def invoke_live(case: dict, base_url: str, timeout: int) -> dict:
    import urllib.error, urllib.request
    url = base_url.rstrip("/") + SYNC_ENDPOINT
    body = {"query": case.get("question", ""), "mode": "RAG",
            "language": case.get("language", "zh")}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            status = r.status
            try:
                resp_dict = json.loads(raw)
            except json.JSONDecodeError:
                resp_dict = {}
    except urllib.error.HTTPError as e:
        status = e.code
        try:
            resp_dict = json.loads(e.read().decode("utf-8"))
        except Exception:
            resp_dict = {}
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return build_not_executed_record(case, "RUNTIME_UNREACHABLE in live mode")
    latency_ms = int((time.monotonic() - t0) * 1000)
    return _map_hybrid_response(case, resp_dict, latency_ms, status)


def run(dataset_path: Path, out_path: Path, *,
        mode: str = "stub", base_url: str = DEFAULT_BASE_URL,
        timeout: int = DEFAULT_TIMEOUT) -> tuple[int, int]:
    if not dataset_path.exists():
        raise FileNotFoundError(f"dataset not found: {dataset_path}")
    cases = load_jsonl(dataset_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    total = executed = 0
    with out_path.open("w", encoding="utf-8") as fh:
        for case in cases:
            if mode == "live":
                rec = invoke_live(case, base_url, timeout)
                if rec.get("executed"):
                    executed += 1
            else:
                rec = build_not_executed_record(
                    case, "stub mode (default); pass --mode live to invoke runtime")
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            total += 1
    print(f"[hybrid_runner mode={mode}] wrote {total} records "
          f"({executed} executed, {total - executed} NOT_EXECUTED) -> {out_path}")
    return total, executed


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Hybrid RAG evaluation runner")
    p.add_argument("--dataset", default="eval/agentic/datasets/agentic_v2.pilot20.jsonl",
                   type=Path)
    p.add_argument("--out", default="eval/agentic/results/hybrid_stub.jsonl", type=Path)
    p.add_argument("--mode", choices=("stub", "live"), default="stub")
    p.add_argument("--base-url", default=DEFAULT_BASE_URL)
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    args = p.parse_args(argv)
    run(args.dataset.resolve(), args.out.resolve(),
        mode=args.mode, base_url=args.base_url, timeout=args.timeout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
