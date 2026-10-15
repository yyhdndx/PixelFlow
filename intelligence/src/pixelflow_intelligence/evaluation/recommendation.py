"""Deterministic synthetic regression benchmark, not a real-world accuracy estimate."""
from collections import Counter
import json
import time
from ..analytics.profile import build_profile
from ..skills import Context, SkillError, create_registry

NOW = 1791000000
DATASET_VERSION = "synthetic-recommendation-v1"


def post(identifier, title="咖啡", author=None, **changes):
    return {"post_id": str(identifier), "author_id": author or f"author-{identifier}",
            "title": title, "media_type": "image", "like_count": 0, "create_time": NOW,
            "status": "PUBLISHED", "author_enabled": True, **changes}


def fixtures():
    pool = [post(1, "手冲咖啡", like_count=10), post(2, "机械键盘", like_count=50),
            post(3, "旅行日落", like_count=100)]
    return [
        dict(name="food_interest", pool=pool, topic="美食", expected=["1", "3", "2"], relevant=["1"]),
        dict(name="technology_interest", pool=pool, topic="数码", expected=["2", "3", "1"], relevant=["2"]),
        dict(name="cold_start", pool=pool, expected=["3", "2", "1"]),
        dict(name="stale_profile", pool=pool, topic="美食", age=90000, expected=["3", "2", "1"]),
        dict(name="explicit_exclusion", pool=pool, topic="美食", exclusions=["1"], expected=["3", "2"]),
        dict(name="visibility_and_conflicts", pool=[post(1), post(1, status="WITHDRAWN"),
             post(2, status="DRAFT"), post(3, author_enabled=False), post(4, create_time=NOW+1000),
             post(5), post(5)], expected=["5"]),
        dict(name="author_diversity", pool=[post(i, author="same") for i in range(1, 5)], expected=["1", "2"]),
        dict(name="topic_diversity", pool=[post(i) for i in range(1, 6)], max_per_topic=2, expected=["1", "2"]),
        dict(name="empty_pool", pool=[], expected=[]),
    ]


def execute(case, baseline=False):
    registry = create_registry()
    context = Context("evaluation-user", frozenset({"profile:read", "candidates:read"}),
                      NOW, time.monotonic() + 5)
    snapshot = None
    if case.get("topic") and not baseline:
        snapshot = build_profile(context.user_id, [], 1, NOW - case.get("age", 0))
        snapshot.update(cold_start=False, topics=[{"name": case["topic"], "score": 3.0, "share": 1.0}])
    insight = registry.invoke("profile_insight", {"user_id": context.user_id, "snapshot": snapshot}, context)["output"]
    recalled = registry.invoke("candidate_recall", {
        "user_id": context.user_id, "insight": insight, "candidates": case["pool"],
        "excluded_post_ids": case.get("exclusions", []), "limit": 50}, context)["output"]
    ranked = registry.invoke("feed_rank", {
        "user_id": context.user_id, "insight": insight, "recalled": recalled["items"],
        "excluded_post_ids": case.get("exclusions", []), "limit": 3,
        "max_per_author": 2, "max_per_topic": case.get("max_per_topic", 3)}, context)["output"]
    return ranked


def violations(case, result):
    """Independent assertions; do not reuse production eligibility helpers."""
    items = result["items"]
    ids = [item["post_id"] for item in items]
    errors = []
    if len(ids) != len(set(ids)):
        errors.append("duplicate_post")
    if len(ids) > 3:
        errors.append("result_limit")
    for item in items:
        rows = [row for row in case["pool"] if row["post_id"] == item["post_id"]]
        if (not rows or any(row != rows[0] for row in rows) or item["post_id"] in case.get("exclusions", [])
                or any(row["status"] != "PUBLISHED" or not row["author_enabled"]
                       or row["create_time"] > NOW + 300 for row in rows)):
            errors.append("ineligible_post")
        elif item["author_id"] != rows[0]["author_id"]:
            errors.append("wrong_author")
    if max(Counter(item["author_id"] for item in items).values(), default=0) > 2:
        errors.append("author_limit")
    if max(Counter(topic for item in items for topic in item["topics"]).values(), default=0) > case.get("max_per_topic", 3):
        errors.append("topic_limit")
    return sorted(set(errors))


def evaluate(cases=None):
    rows = []
    hits = baseline_hits = labeled = 0
    for case in fixtures() if cases is None else cases:
        try:
            result, baseline = execute(case), execute(case, baseline=True)
            ids = [item["post_id"] for item in result["items"]]
            baseline_ids = [item["post_id"] for item in baseline["items"]]
            errors = violations(case, result) + ["baseline_" + e for e in violations(case, baseline)]
            if ids != case["expected"]:
                errors.append("unexpected_order")
            if case.get("relevant"):
                labeled += 1
                hits += int(bool(ids) and ids[0] in case["relevant"])
                baseline_hits += int(bool(baseline_ids) and baseline_ids[0] in case["relevant"])
            rows.append({"case": case["name"], "passed": not errors, "errors": errors,
                         "post_ids": ids, "baseline_post_ids": baseline_ids,
                         "max_per_topic": case.get("max_per_topic", 3),
                         "policy_version": result["policy_version"]})
        except SkillError as error:
            rows.append({"case": case["name"], "passed": False, "errors": [error.code]})
    return {"dataset_version": DATASET_VERSION, "evaluation_time": NOW,
            "scope": "synthetic_skill_regression_only", "parameters": {
                "recall_limit": 50, "rank_limit": 3, "max_per_author": 2, "max_per_topic": 3},
            "cases": rows, "total": len(rows), "passed": sum(row["passed"] for row in rows),
            "labeled_cases": labeled, "hit_at_1": hits/labeled if labeled else None,
            "popular_baseline_hit_at_1": baseline_hits/labeled if labeled else None}


def main():
    report = evaluate()
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report["total"] > 0 and report["passed"] == report["total"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
