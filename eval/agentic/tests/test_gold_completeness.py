"""PR-7f.2b.1: Gold completeness validator tests."""
from __future__ import annotations

import sys
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(EVAL_ROOT / "scripts"))

import validate_gold_completeness as vgc


def _base(answerable=True, reviewed=False):
    evidence = [{
        "evidenceId": "aabbccddeeff",
        "documentId": 1, "chunkId": 10,
        "documentVersion": "v1",
        "contentHash": "112233445566",
        "bindsToRequirementIds": ["REQ-1"],
        "reviewer": "bob", "reviewedAt": "2026-08-05T00:00:00Z",
    }]
    return {
        "caseId": "amh-001",
        "requirements": [{"requirementId": "REQ-1", "required": True, "type": "FACT", "description": "x"}],
        "gold": {
            "goldAnswer": "answer",
            "goldEvidence": evidence if answerable else [],
            "goldCoverageByRequirement": {"REQ-1": ["aabbccddeeff"]} if answerable else {},
            "answerable": answerable,
        },
        "planConstraints": {
            "acceptableInitialPlans": [{"toolSequence": ["semantic_search"], "coveredReqIds": ["REQ-1"]}],
            "forbiddenToolSignatures": [],
        },
        "expected": {"expectedFinalStatus": "ANSWERED" if answerable else "REFUSED_NO_EVIDENCE"},
        "slice": "initial_sufficient" if answerable else "no_answer_refuse",
        "review": {
            "reviewStatus": "reviewed" if reviewed else "candidate",
            "annotator": "alice", "reviewer": "bob",
            "reviewedAt": "2026-08-05T00:00:00Z" if reviewed else "",
        },
    }


def test_complete_case_no_errors_no_warnings():
    case = _base()
    errs, warns = vgc.check_completeness(case)
    assert errs == []
    assert warns == []


def test_fill_marker_is_warning_not_error():
    case = _base()
    case["gold"]["goldEvidence"][0]["evidenceId"] = "FILL_FROM_SHA256"
    errs, warns = vgc.check_completeness(case, strict=False)
    assert errs == []
    assert any("evidenceId" in w for w in warns)


def test_fill_marker_is_error_in_strict():
    case = _base()
    case["gold"]["goldEvidence"][0]["evidenceId"] = "FILL_FROM_SHA256"
    errs, warns = vgc.check_completeness(case, strict=True)
    assert any("evidenceId" in e for e in errs)


def test_fill_marker_is_error_when_reviewed():
    """When reviewStatus=reviewed, FILL_ must be error."""
    case = _base(reviewed=True)
    case["gold"]["goldEvidence"][0]["evidenceId"] = "FILL_FROM_SHA256"
    errs, warns = vgc.check_completeness(case)
    assert any("evidenceId" in e for e in errs)


def test_non_hex_evidence_id_fails():
    case = _base()
    case["gold"]["goldEvidence"][0]["evidenceId"] = "not-hex!"
    errs, _ = vgc.check_completeness(case)
    assert any("not ≥12-char hex" in e for e in errs)


def test_required_req_without_evidence_warning():
    case = _base()
    case["requirements"].append(
        {"requirementId": "REQ-2", "required": True, "type": "RELATION", "description": "x"})
    errs, warns = vgc.check_completeness(case)
    assert any("REQ-2" in w for w in warns)


def test_answerable_false_with_evidence_warns():
    case = _base(answerable=False)
    case["gold"]["goldEvidence"] = [{
        "evidenceId": "aabbccddeeff", "documentId": 1, "chunkId": 10,
        "documentVersion": "v1", "contentHash": "112233445566",
        "bindsToRequirementIds": ["REQ-1"],
        "reviewer": "bob", "reviewedAt": "2026-08-05T00:00:00Z",
    }]
    errs, warns = vgc.check_completeness(case)
    assert any("answerable=false" in w for w in warns)


def test_duplicate_evidence_id_fails():
    case = _base()
    case["gold"]["goldEvidence"].append(dict(case["gold"]["goldEvidence"][0]))
    errs, _ = vgc.check_completeness(case)
    assert any("duplicate evidenceId" in e for e in errs)


def test_forbidden_sig_overlap_fails():
    case = _base()
    case["planConstraints"]["forbiddenToolSignatures"] = ["semantic_search"]
    errs, _ = vgc.check_completeness(case)
    assert any("overlap" in e for e in errs)


def test_dual_signoff_reviewed_same_person_fails():
    case = _base(reviewed=True)
    case["review"]["annotator"] = "bob"  # same as reviewer
    errs, _ = vgc.check_completeness(case)
    assert any("dual-signoff" in e for e in errs)


def test_slice_status_mismatch_fails():
    case = _base()
    case["slice"] = "no_answer_refuse"
    case["expected"]["expectedFinalStatus"] = "ANSWERED"  # mismatch
    errs, _ = vgc.check_completeness(case)
    assert any("slice=" in e for e in errs)


def test_pilot20_file_exists_and_validates():
    pilot = EVAL_ROOT / "datasets" / "agentic_v2.pilot20.jsonl"
    if not pilot.exists():
        return  # skip if not generated yet
    import json
    cases = [json.loads(l) for l in pilot.open() if l.strip()]
    assert len(cases) == 20
    # Should have 0 errors (warnings OK — template phase)
    for case in cases:
        errs, _ = vgc.check_completeness(case)
        assert errs == [], f"{case['caseId']} has structural errors: {errs}"
