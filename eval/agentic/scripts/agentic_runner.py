#!/usr/bin/env python3
"""PR-7f.2b.3b: PlannedAgentPipeline REST Adapter.

Lives next to the stub runners and upgrades them with a real *live* mode that
talks to the running Spring Boot app over HTTP — WITHOUT touching any Java code
(per the runtime-frozen constraint of PR-7f.2b.3).

Two operating modes:

  --mode stub   (default): emit NOT_EXECUTED records. Identical to PR-7f.2b.2
                            behaviour; never touches the network.
  --mode live : attempt to invoke the live pipeline via REST; map the response
                into the EvaluationResult schema. If the runtime cannot be
                reached OR the router did not actually enter PLANNED_AGENT
                (e.g. plannedPipelineEnabled hard-coded false — see note below),
                the record falls back to NOT_EXECUTED with a precise blocker
                message. No fabrication.

Endpoints (auto-discovered by Java agent — see chat-controller survey):
  POST {base}/api/v1/chat     (sync JSON)        ChatResponse
  POST {base}/api/v1/chat/sse (text/event-stream)  SseEmitter (events: citations, delta, done, error)

Note on the runtime blocker (DO NOT silently work around):
  ExecutionStrategyResolver hard-codes `plannedPipelineEnabled=false` at
  platform-bootstrap/.../planned/ExecutionStrategyResolver.java:36 with no
  @ConfigurationProperties binding. Per the runtime-frozen constraint we do
  NOT edit Java in this PR. The live adapter therefore:

    1. Calls the endpoint with mode=AUTO.
    2. Inspects the response for a `pipelineType` / `executionStrategy` trace
       field (the controller emits it in the debug envelope when set).
    3. If the trace is missing OR != "PLANNED_AGENT", marks the record
       NOT_EXECUTED with blocker="RUNTIME_NOT_PLANNED_AGENT".

  This documents the gap rather than producing fake agent metrics. Once the
  resolver is wired to a config property in a later PR, the adapter starts
  emitting executed=true records with no changes here.

Usage:
  # Default: stub (offline)
  python3 eval/agentic/scripts/agentic_runner.py \\
      --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \\
      --out     eval/agentic/results/agentic_stub.jsonl

  # Live: must point at a running platform-bootstrap (default :8080)
  python3 eval/agentic/scripts/agentic_runner.py \\
      --mode live --base-url http://localhost:8080 \\
      --dataset eval/agentic/datasets/agentic_v2.pilot20.jsonl \\
      --out     eval/agentic/results/agentic_live.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import load_jsonl  # noqa: E402

PIPELINE = "AGENTIC_FULL"
SYNC_ENDPOINT = "/api/v1/chat"
SSE_ENDPOINT = "/api/v1/chat/sse"
DEFAULT_TIMEOUT = 120  # seconds per case
DEFAULT_BASE_URL = "http://localhost:8080"

# Trace field names the controller emits (PR-7f.2c-pre exposes pipeline_type
# via Jackson SNAKE_CASE; legacy/alt keys kept for forward-compat).
STRATEGY_TRACE_KEYS = (
    "pipeline_type", "pipelineType",
    "execution_strategy", "executionStrategy",
    "pipeline", "strategy", "chat_pipeline_type",
)


# ═══════════════════════════ STUB MODE ═══════════════════════════

def build_not_executed_record(case: dict[str, Any], reason: str) -> dict[str, Any]:
    """NOT_EXECUTED record: executed=false, zero/empty fields.

    `reason` distinguishes offline-stub vs runtime-blocker vs network-error.
    """
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
        "replanCount": 0,
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


# ═══════════════════════════ LIVE MODE ═══════════════════════════

def _post_json(url: str, body: dict, timeout: int) -> tuple[int, dict | str]:
    """Return (http_status, parsed_json_or_raw_text)."""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, str(e)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        return -1, str(e)


def _extract_strategy(response: Any) -> str | None:
    """Look for any of STRATEGY_TRACE_KEYS in the response envelope."""
    if not isinstance(response, dict):
        return None
    # check top-level + nested envelope shapes
    candidates = [response]
    for k in ("data", "result", "envelope", "meta", "trace"):
        v = response.get(k)
        if isinstance(v, dict):
            candidates.append(v)
    for c in candidates:
        for key in STRATEGY_TRACE_KEYS:
            if key in c and isinstance(c[key], str):
                return c[key]
    return None


def _map_chat_response_to_result(
    case: dict[str, Any],
    response: dict,
    strategy: str | None,
    latency_ms: int,
    http_status: int,
) -> dict[str, Any]:
    """Map ChatResponse JSON → EvaluationResult.

    Field mapping (best-effort against the current ChatResponse shape —
    extended as the runtime contract stabilises):
      answer          → answerText, finalStatus=ANSWERED if non-empty
      citations[*]    → citedEvidenceIds / evidenceIds
      usage.{input,output}Tokens → tokenUsage
    """
    required_reqs = [r.get("requirementId", "")
                     for r in case.get("requirements", [])
                     if r.get("required")]

    answer = (response.get("answer") or response.get("content") or "").strip()
    is_planned_agent = (strategy == "PLANNED_AGENT")

    citations = (response.get("citations") or response.get("evidence") or [])
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

    final_status = "ANSWERED" if answer else "REFUSED_NO_EVIDENCE"
    # If the runtime trace is not PLANNED_AGENT, we deliberately refuse to
    # claim executed=true — even though we got an HTTP 200 back.
    executed = is_planned_agent and http_status == 200

    coverage = [
        {"requirementId": rid,
         "status": ("COVERED" if executed and answer else "NOT_COVERED"),
         "evidenceIds": cited_ids if executed else []}
        for rid in required_reqs
    ]

    err = ""
    if not executed:
        if http_status != 200:
            err = f"NOT_EXECUTED: HTTP {http_status} from runtime"
        elif strategy is None:
            err = ("NOT_EXECUTED: RUNTIME_NO_STRATEGY_TRACE — controller did not "
                   "expose pipelineType; cannot confirm PLANNED_AGENT was reached")
        else:
            err = (f"NOT_EXECUTED: RUNTIME_NOT_PLANNED_AGENT — runtime returned "
                   f"strategy={strategy!r}; PLANNED_AGENT gate closed "
                   f"(plannedPipelineEnabled hard-coded false; see PR-7f.2b.3 doc)")

    return {
        "caseId": case.get("caseId", ""),
        "pipeline": PIPELINE,
        "finalStatus": final_status if executed else "SYSTEM_FAILED",
        "evidenceIds": cited_ids if executed else [],
        "requirementCoverage": coverage,
        "toolCalls": int(response.get("toolCalls", 0)) if executed else 0,
        "realToolCalls": int(response.get("realToolCalls", 0)) if executed else 0,
        "llmCalls": int(response.get("llmCalls", 0)) if executed else 0,
        "replanCount": int(response.get("replanCount", 0)) if executed else 0,
        "latencyMs": latency_ms if executed else 0,
        "tokenUsage": {"inputTokens": in_tok, "outputTokens": out_tok} if executed
                      else {"inputTokens": 0, "outputTokens": 0},
        "answerText": answer if executed else "",
        "citedEvidenceIds": cited_ids if executed else [],
        "guardRejections": int(response.get("guardRejections", 0)) if executed else 0,
        "falseSufficientLeak": bool(response.get("falseSufficientLeak", False)) if executed else False,
        "sufficiencyStatus": response.get("sufficiencyStatus", "NOT_RUN") if executed else "NOT_RUN",
        "sseTerminalEvents": 0,  # sync endpoint, no SSE
        "nonTerminalStepResidue": 0,
        "crossTenantEvidenceLeak": 0,
        "executedToolSignatures": response.get("executedToolSignatures", []) if executed else [],
        "errorMessage": err,
        "executed": executed,
        "strategyTrace": strategy,
        "httpStatus": http_status,
    }


def invoke_live(case: dict, base_url: str, timeout: int) -> dict:
    """Send one case to POST /api/v1/chat in mode=AUTO and map the response."""
    url = base_url.rstrip("/") + SYNC_ENDPOINT
    body = {
        "query": case.get("question", ""),
        "mode": "AUTO",
        "language": case.get("language", "zh"),
    }
    t0 = time.monotonic()
    status, resp = _post_json(url, body, timeout)
    latency_ms = int((time.monotonic() - t0) * 1000)
    strategy = _extract_strategy(resp)
    return _map_chat_response_to_result(case, resp if isinstance(resp, dict) else {},
                                        strategy, latency_ms, status)


# ═══════════════════════════ RUN ═══════════════════════════

def run(dataset_path: Path, out_path: Path, *,
        mode: str = "stub", base_url: str = DEFAULT_BASE_URL,
        timeout: int = DEFAULT_TIMEOUT) -> tuple[int, int]:
    """Returns (total, executed_count)."""
    if not dataset_path.exists():
        raise FileNotFoundError(f"dataset not found: {dataset_path}")
    cases = load_jsonl(dataset_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    total = 0
    executed = 0
    with out_path.open("w", encoding="utf-8") as fh:
        for case in cases:
            if mode == "live":
                rec = invoke_live(case, base_url, timeout)
                if rec.get("executed"):
                    executed += 1
            else:
                rec = build_not_executed_record(
                    case, "stub mode (default); pass --mode live to invoke runtime"
                )
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            total += 1
    print(f"[agentic_runner mode={mode}] wrote {total} records "
          f"({executed} executed, {total - executed} NOT_EXECUTED) -> {out_path}")
    return total, executed


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Agentic RAG evaluation runner")
    p.add_argument("--dataset", default="eval/agentic/datasets/agentic_v2.pilot20.jsonl",
                   type=Path)
    p.add_argument("--out", default="eval/agentic/results/agentic_stub.jsonl", type=Path)
    p.add_argument("--mode", choices=("stub", "live"), default="stub",
                   help="stub=offline (NOT_EXECUTED); live=invoke REST runtime")
    p.add_argument("--base-url", default=DEFAULT_BASE_URL,
                   help=f"Base URL of running platform-bootstrap (default {DEFAULT_BASE_URL})")
    p.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                   help="HTTP timeout per case in seconds")
    args = p.parse_args(argv)

    run(args.dataset.resolve(), args.out.resolve(),
        mode=args.mode, base_url=args.base_url, timeout=args.timeout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
