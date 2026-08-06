"""PR-7d Planner Benchmark dataset validator.

验证 planner_benchmark_v1.*.jsonl 是否合规、可参与评测。
依据 PR-7d 任务书 §9 (Dataset Validator) 与 schema (schemas/planner_case.schema.json).

退出码:
  0  全部通过
  1  存在错误
  2  命令行参数错误
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

DEFAULT_SCHEMA = Path(__file__).resolve().parent / "schemas" / "planner_case.schema.json"
STATUSES_REQUIRING_ANSWERABLE_FALSE = {
    "REFUSED_NO_EVIDENCE",
    "REFUSED_CONFLICT",
    "REFUSED_PERMISSION",
    "TOOL_FAILED",
    "BUDGET_EXCEEDED",
    "TIMED_OUT",
    "CANCELLED",
    "SYSTEM_FAILED",
}


class ValidationError(Exception):
    pass


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise ValidationError(
                    f"{path}:{ln} JSON decode error: {e.msg}"
                ) from e
    return out


def _validate_case(case: dict[str, Any], case_no: int, require_reviewed: bool) -> list[str]:
    errors: list[str] = []
    cid = case.get("caseId", f"<line {case_no}>")

    required_fields = [
        "schemaVersion", "caseId", "question", "intent", "requirements",
        "answerable", "replanExpected", "expectedFinalStatus",
        "maxSteps", "maxToolCalls", "reviewStatus", "reviewer", "reviewedAt",
    ]
    for k in required_fields:
        if k not in case:
            errors.append(f"{cid}: missing field '{k}'")

    # caseId pattern
    cid_val = case.get("caseId", "")
    if not (isinstance(cid_val, str) and len(cid_val) >= 5 and cid_val.startswith("mh-")):
        errors.append(f"{cid}: caseId 必须形如 mh-001 (实际='{cid_val}')")

    # schemaVersion
    if case.get("schemaVersion") != "v1":
        errors.append(f"{cid}: schemaVersion 当前只支持 'v1'")

    # requirements ID 唯一
    reqs = case.get("requirements", [])
    ids = [r.get("requirementId") for r in reqs if isinstance(r, dict)]
    dup = {x for x in ids if ids.count(x) > 1}
    if dup:
        errors.append(f"{cid}: requirementId 重复 {sorted(dup)}")
    for r in reqs:
        if not isinstance(r, dict):
            errors.append(f"{cid}: requirement 不是 object: {r!r}")
            continue
        if not r.get("requirementId"):
            errors.append(f"{cid}: requirement 缺 requirementId")
        if not r.get("description"):
            errors.append(f"{cid}: requirement 缺 description")
        if r.get("type") not in {
            "FACT", "ENTITY_ATTRIBUTE", "RELATION", "TEMPORAL",
            "COMPARISON_SIDE", "FOLLOW_UP_ENTITY",
        }:
            errors.append(f"{cid}: requirement type 非法 ({r.get('type')})")

    # answerable / expectedFinalStatus 对齐
    final = case.get("expectedFinalStatus")
    answerable = case.get("answerable")
    if answerable is True and final == "REFUSED_NO_EVIDENCE":
        errors.append(
            f"{cid}: answerable=true 但 expectedFinalStatus=REFUSED_NO_EVIDENCE (冲突)"
        )
    if answerable is False and final == "ANSWERED":
        errors.append(
            f"{cid}: answerable=false 但 expectedFinalStatus=ANSWERED (冲突)"
        )

    # replanExpected / acceptableReplanPlans 一致
    replan_expected = case.get("replanExpected", False)
    acceptable_replan = case.get("acceptableReplanPlans", []) or []
    if replan_expected and not acceptable_replan:
        errors.append(
            f"{cid}: replanExpected=true 但 acceptableReplanPlans 为空"
        )
    if acceptable_replan and not replan_expected:
        errors.append(
            f"{cid}: acceptableReplanPlans 非空 但 replanExpected=false"
        )

    # budget
    ms = case.get("maxSteps")
    mc = case.get("maxToolCalls")
    if not isinstance(ms, int) or not (1 <= ms <= 5):
        errors.append(f"{cid}: maxSteps 应 1..5 (实际 {ms!r})")
    if not isinstance(mc, int) or not (1 <= mc <= 10):
        errors.append(f"{cid}: maxToolCalls 应 1..10 (实际 {mc!r})")

    # gold evidence ID format  — 必须 nonempty 当 answerable=true
    if answerable is True:
        ge = case.get("goldEvidenceIds", []) or []
        gd = case.get("goldDocumentIds", []) or []
        if not ge and not gd:
            errors.append(
                f"{cid}: answerable=true 但 goldEvidenceIds/goldDocumentIds 同时为空"
            )

    # forbidden signature 非空 仅当 case 应触发 loop detection
    # PR-7d: 这是 hint, 不强制

    # reviewStatus
    rs = case.get("reviewStatus")
    if rs not in {"candidate", "reviewed", "rejected"}:
        errors.append(f"{cid}: reviewStatus 非法 ({rs!r})")
    if require_reviewed and rs != "reviewed":
        errors.append(
            f"{cid}: require_reviewed=true 但 reviewStatus={rs!r} (评测拒绝未审核数据)"
        )

    # 敏感数据扫描 (基本 hint; 不替代 review)
    sensitive_hint_keys = {"token", "apiKey", "password", "cookie", "secret"}
    text_blob = json.dumps(case, ensure_ascii=False).lower()
    for hint in sensitive_hint_keys:
        if hint in text_blob:
            errors.append(f"{cid}: 检测到可疑敏感字段关键字 '{hint}', 请人工 review")

    return errors


def validate_dataset(
    path: Path,
    schema_path: Path | None = None,
    require_reviewed: bool = False,
    print_summary: bool = True,
) -> tuple[int, list[str]]:
    schema_path = schema_path or DEFAULT_SCHEMA
    if not path.exists():
        return 1, [f"dataset 文件不存在: {path}"]
    if not schema_path.exists():
        return 1, [f"schema 文件不存在: {schema_path}"]
    try:
        cases = _load_jsonl(path)
    except ValidationError as e:
        return 1, [str(e)]

    if not cases:
        return 1, [f"{path}: 空数据集 (无法评测)"]

    # caseId 全局唯一
    id_count: dict[str, int] = {}
    for c in cases:
        cid = c.get("caseId", "")
        id_count[cid] = id_count.get(cid, 0) + 1
    dup_ids = sorted([k for k, v in id_count.items() if v > 1])
    if dup_ids:
        return 1, [f"{path}: caseId 重复 {dup_ids}"]

    all_errors: list[str] = []
    for i, case in enumerate(cases, 1):
        all_errors.extend(_validate_case(case, i, require_reviewed))

    if print_summary:
        if all_errors:
            print(f"FAIL  {path}: {len(all_errors)} 错误, {len(cases)} cases 入校验",
                  file=sys.stderr)
            for e in all_errors[:50]:
                print(f"  - {e}", file=sys.stderr)
            if len(all_errors) > 50:
                print(f"  ... 另 {len(all_errors) - 50} 错误省略", file=sys.stderr)
        else:
            print(f"OK    {path}: {len(cases)} cases 通过"
                  f"{' (require_reviewed)' if require_reviewed else ''}")
    return (0 if not all_errors else 1), all_errors


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="PR-7d Planner Benchmark dataset validator")
    p.add_argument("dataset", type=Path, help="jsonl dataset 路径")
    p.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA, help="schema 文件路径")
    p.add_argument("--require-reviewed", action="store_true",
                   help="仅允许 reviewStatus=reviewed 的 case (用于正式评测)")
    args = p.parse_args(argv)
    rc, _ = validate_dataset(args.dataset, args.schema, args.require_reviewed)
    return rc if rc in (0, 1) else 2


if __name__ == "__main__":
    sys.exit(main())
