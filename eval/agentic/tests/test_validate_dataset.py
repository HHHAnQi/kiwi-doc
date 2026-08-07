"""PR-7f.2a: Dataset validator tests."""
from __future__ import annotations

import json
import sys
from pathlib import Path

# resolve package path for "scripts" module import
EVAL_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(EVAL_ROOT / "scripts"))

import validate_dataset  # noqa: E402


def _valid_base(case_id: str = "amh-100") -> dict:
    return {
        "schemaVersion": "v2",
        "caseId": case_id,
        "question": "What implements auth in v2 and how did it change?",
        "questionType": "MULTI_HOP",
        "intent": "MULTI_HOP",
        "entities": ["auth", "v2"],
        "filters": {},
        "requirements": [
            {"requirementId": "REQ-1", "type": "FACT", "required": True,
             "description": "auth component", "targetEntities": ["auth"],
             "expectedFilters": {}},
            {"requirementId": "REQ-2", "type": "TEMPORAL", "required": True,
             "description": "v2 changes", "targetEntities": ["v2"],
             "expectedFilters": {}}
        ],
        "gold": {
            "goldAnswer": "Auth is implemented by component X; in v2 it added OAuth.",
            "goldEvidence": [
                {
                    "evidenceId": "aabbccddeeff",
                    "documentId": 1, "chunkId": 10,
                    "documentVersion": "v1",
                    "contentHash": "112233445566",
                    "contentSnippet": "auth component X",
                    "bindsToRequirementIds": ["REQ-1"],
                    "rationale": "chunk 10 names the component",
                    "reviewer": "reviewer1",
                    "reviewedAt": "2026-08-05T00:00:00Z"
                }
            ],
            "goldDocumentIds": [1],
            "goldCoverageByRequirement": {"REQ-1": ["aabbccddeeff"]},
            "answerable": True,
        },
        "planConstraints": {
            "acceptableInitialPlans": [
                {"toolSequence": ["semantic_search", "metadata_search"], "coveredReqIds": ["REQ-1", "REQ-2"]}
            ],
            "acceptableReplanPlans": [],
            "forbiddenToolSignatures": [],
        },
        "expected": {
            "expectedFinalStatus": "ANSWERED",
            "replanExpected": False,
            "maxSteps": 3, "maxToolCalls": 3,
        },
        "slice": "initial_sufficient",
        "review": {
            "reviewStatus": "candidate",
            "annotator": "TODO", "reviewer": "TODO", "reviewedAt": ""
        },
    }


def _write_jsonl(tmp_path: Path, cases: list[dict]) -> Path:
    p = tmp_path / "ds.jsonl"
    with p.open("w", encoding="utf-8") as f:
        for c in cases:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    return p


def test_valid_case_passes(tmp_path):
    p = _write_jsonl(tmp_path, [_valid_base()])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 0
    assert errs == []


def test_dup_case_id_fails(tmp_path):
    p = _write_jsonl(tmp_path, [_valid_base("amh-001"), _valid_base("amh-001")])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("duplicate caseIds" in e for e in errs)


def test_dup_requirement_id_fails(tmp_path):
    case = _valid_base()
    case["requirements"].append(dict(case["requirements"][0]))  # dup REQ-1
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("duplicate requirementId" in e for e in errs)


def test_gold_evidence_binds_unknown_req_fails(tmp_path):
    case = _valid_base()
    case["gold"]["goldEvidence"][0]["bindsToRequirementIds"] = ["REQ-99"]
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("unknown requirementId" in e for e in errs)


def test_answerable_status_conflict_fails(tmp_path):
    case = _valid_base()
    case["gold"]["answerable"] = False  # but expectedFinalStatus=ANSWERED
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("answerable=false" in e for e in errs)


def test_slice_status_conflict_fails(tmp_path):
    case = _valid_base()
    case["slice"] = "no_answer_refuse"  # expects REFUSED_NO_EVIDENCE
    case["expected"]["expectedFinalStatus"] = "ANSWERED"  # conflict
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("slice=" in e for e in errs)


def test_dual_signoff_annotator_eq_reviewer_fails(tmp_path):
    case = _valid_base()
    case["review"] = {
        "reviewStatus": "reviewed",
        "annotator": "alice",
        "reviewer": "alice",  # same person
        "reviewedAt": "2026-08-05T00:00:00Z"
    }
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("dual-signoff" in e for e in errs)


def test_reviewed_without_reviewedAt_fails(tmp_path):
    case = _valid_base()
    case["review"] = {
        "reviewStatus": "reviewed",
        "annotator": "alice", "reviewer": "bob",
        "reviewedAt": ""  # missing
    }
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("reviewedAt" in e for e in errs)


def test_candidate_placeholder_evidence_ok(tmp_path):
    """Candidate (template) cases MAY carry placeholder values like 'TODO':
    the template is intentionally unfilled and reviewers will fill it later.
    The validator must NOT reject placeholders on candidate cases.
    """
    case = _valid_base()  # review.reviewStatus == "candidate"
    case["gold"]["goldEvidence"][0]["evidenceId"] = "TODO"
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 0
    assert not any("placeholder" in e for e in errs)


def test_candidate_fill_marker_still_rejected(tmp_path):
    """Candidate cases are allowed generic placeholders ('TODO', '', 'TBD') but
    FILL_* markers are an explicit 'must fill before review' flag and MUST be
    rejected even on candidate cases, so reviewers cannot forget them.
    """
    case = _valid_base()
    case["gold"]["goldEvidence"][0]["evidenceId"] = "FILL_FROM_SHA256"
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("must be filled" in e for e in errs)


def test_reviewed_placeholder_evidence_fails(tmp_path):
    """Reviewed cases have completed human sign-off: placeholder values must
    be rejected to prevent shipping unaudited gold evidence.
    """
    case = _valid_base()
    case["review"] = {
        "reviewStatus": "reviewed",
        "annotator": "alice", "reviewer": "bob",
        "reviewedAt": "2026-08-05T00:00:00Z",
    }
    case["gold"]["goldEvidence"][0]["evidenceId"] = "TODO"
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("is placeholder" in e for e in errs)


def test_require_reviewed_mode_rejects_candidate(tmp_path):
    case = _valid_base()  # reviewStatus=candidate
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, require_reviewed=True, print_summary=False)
    assert rc == 1
    assert any("require-reviewed" in e for e in errs)


def test_reviewed_replan_expected_empty_plans_fails(tmp_path):
    """When reviewed + replanExpected=true, acceptableReplanPlans must NOT be empty."""
    case = _valid_base()
    case["slice"] = "replan_success"
    case["expected"]["replanExpected"] = True
    case["expected"]["expectedFinalStatus"] = "ANSWERED"
    case["planConstraints"]["acceptableReplanPlans"] = []
    case["review"] = {
        "reviewStatus": "reviewed",
        "annotator": "alice", "reviewer": "bob",
        "reviewedAt": "2026-08-05T00:00:00Z"
    }
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 1
    assert any("replanExpected=true" in e for e in errs)


def test_candidate_replan_expected_empty_plans_ok(tmp_path):
    """Template phase: candidate + replanExpected=true + empty replan plans is allowed."""
    case = _valid_base()
    case["slice"] = "replan_success"
    case["expected"]["replanExpected"] = True
    case["expected"]["expectedFinalStatus"] = "ANSWERED"
    case["planConstraints"]["acceptableReplanPlans"] = []
    # review = candidate (unchanged)
    p = _write_jsonl(tmp_path, [case])
    rc, errs = validate_dataset.validate_dataset(p, print_summary=False)
    assert rc == 0


def test_template_60_passes(tmp_path):
    """Template placeholder: 60 cases all candidate → should pass."""
    from pathlib import Path as P
    template_path = P(__file__).resolve().parent.parent / "datasets" / "agentic_v2.template.jsonl"
    if template_path.exists():
        rc, _ = validate_dataset.validate_dataset(template_path, print_summary=False)
        assert rc == 0
