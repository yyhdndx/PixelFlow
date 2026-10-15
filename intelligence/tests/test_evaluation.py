import copy
from pixelflow_intelligence.evaluation.recommendation import evaluate, fixtures, violations, post


def test_fixed_regression_suite_is_repeatable_and_does_not_mutate_fixtures():
    cases = fixtures()
    original = copy.deepcopy(cases)
    result = evaluate(cases)
    assert result == evaluate(cases)
    assert cases == original
    assert result["passed"] == result["total"] == 9
    assert result["labeled_cases"] == 2
    assert result["hit_at_1"] == 1 and result["popular_baseline_hit_at_1"] == 0


def test_changed_expectation_fails_regression():
    cases = fixtures()[:1]
    cases[0]["expected"] = ["999"]
    result = evaluate(cases)
    assert result["passed"] == 0
    assert "unexpected_order" in result["cases"][0]["errors"]


def test_independent_safety_checker_detects_invalid_result():
    case = {"pool": [post(1, status="WITHDRAWN")], "exclusions": ["1"]}
    item = {"post_id": "1", "author_id": "author-1", "topics": ["美食"]}
    errors = violations(case, {"items": [item] * 4})
    assert set(errors) == {"duplicate_post", "ineligible_post", "author_limit", "topic_limit", "result_limit"}


def test_no_labels_does_not_report_fake_zero_accuracy():
    report = evaluate(fixtures()[-1:])
    assert report["hit_at_1"] is None and report["popular_baseline_hit_at_1"] is None


def test_invalid_skill_input_is_reported_as_failure():
    cases = fixtures()[:1]
    cases[0]["pool"] = [post(0)]
    report = evaluate(cases)
    assert report["passed"] == 0 and report["cases"][0]["errors"] == ["INVALID_INPUT"]
