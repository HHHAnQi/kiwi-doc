#!/usr/bin/env python3
"""严格聚合分批执行的 G1-G5 报告，生成可复核的全门禁证据。"""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


EXPECTED_TOTALS = {"G1": 80, "G2": 20, "G3": 10, "G4": 50, "G5": 50}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()

    loaded = [(path, json.loads(path.read_text(encoding="utf-8"))) for path in args.reports]
    if not loaded:
        raise SystemExit("至少需要一份报告")

    reference = loaded[0][1]["fingerprints"]
    comparable_keys = ("datasets", "g1_result", "g1_baseline")
    for path, report in loaded[1:]:
        for key in comparable_keys:
            if report["fingerprints"].get(key) != reference.get(key):
                raise SystemExit(f"报告指纹不一致: {path} -> {key}")
        for key in ("chat_url", "judge_model", "judge_provider_id"):
            if report["fingerprints"]["config"].get(key) != reference["config"].get(key):
                raise SystemExit(f"报告配置不一致: {path} -> {key}")

    selected: dict[str, dict] = {}
    sources = []
    for path, report in loaded:
        sources.append({"path": str(path), "sha256": sha256(path), "generated_at": report["generated_at"]})
        for gate in report["gates"]:
            if gate.get("status") == "SKIP":
                continue
            name = gate["gate"]
            if name in selected:
                raise SystemExit(f"重复的非 SKIP gate: {name}")
            selected[name] = gate

    errors = []
    summaries = []
    for name, expected_total in EXPECTED_TOTALS.items():
        gate = selected.get(name)
        if not gate:
            errors.append(f"缺少 {name}")
            continue
        if gate.get("status") != "PASS":
            errors.append(f"{name} 状态为 {gate.get('status')}")
        actual_total = gate.get("sample_count") if name == "G1" else gate.get("total")
        if actual_total != expected_total:
            errors.append(f"{name} 样本数 {actual_total} != {expected_total}")
        if name == "G3" and gate.get("pollution_count") != 0:
            errors.append(f"G3 pollution_count={gate.get('pollution_count')}")
        if name == "G4":
            compressed = sum(1 for item in gate.get("details", []) if item.get("compression_observed"))
            if compressed < 35:
                errors.append(f"G4 真实压缩会话仅 {compressed}/50")
        else:
            compressed = None
        summaries.append({
            "gate": name,
            "status": gate.get("status"),
            "passed": gate.get("pass_n"),
            "total": actual_total,
            "rate": gate.get("rate", gate.get("overall_rate")),
            "pollution_count": gate.get("pollution_count"),
            "compression_observed": compressed,
            "deltas": gate.get("deltas"),
        })

    now = datetime.now(timezone.utc)
    stamp = now.strftime("%Y%m%d_%H%M%S")
    aggregate = {
        "generated_at": now.isoformat(),
        "overall_status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "sources": sources,
        "fingerprints": reference,
        "gates": summaries,
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.out_dir / f"all_gates_{stamp}.json"
    md_path = args.out_dir / f"all_gates_{stamp}.md"
    json_path.write_text(json.dumps(aggregate, ensure_ascii=False, indent=2), encoding="utf-8")
    rows = "\n".join(
        f"| {g['gate']} | {g['status']} | {g['passed'] if g['passed'] is not None else '-'} | "
        f"{g['total']} | {g['rate'] if g['rate'] is not None else '-'} |"
        for g in summaries
    )
    md_path.write_text(
        "# G1-G5 全门禁聚合报告\n\n"
        f"生成时间: {aggregate['generated_at']}\n\n"
        f"整体状态: **{aggregate['overall_status']}**\n\n"
        "| Gate | Status | Passed | Total | Rate |\n|---|---:|---:|---:|---:|\n"
        f"{rows}\n\n"
        f"错误: {errors or '无'}\n",
        encoding="utf-8",
    )
    print(json_path)
    print(md_path)
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
