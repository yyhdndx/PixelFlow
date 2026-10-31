import copy
import time
import pytest
from pixelflow_intelligence.skills import create_registry, Context, SkillError
from pixelflow_intelligence.skills.runtime import Registry, Definition
from pixelflow_intelligence.skills.models import ProfileInput, Insight
from pixelflow_intelligence.analytics.profile import build_profile

NOW = 1791000000


def context(user="alice", scopes=None, seconds=5):
    return Context(user, frozenset(scopes if scopes is not None else ("profile:read", "candidates:read")),
                   NOW, time.monotonic()+seconds)


def snapshot(topic="美食", user="alice"):
    result = build_profile(user, [], 1, NOW)
    result.update(cold_start=False, topics=[{"name": topic, "score": 3.0, "share": 1.0}])
    return result


def candidate(id="1", title="咖啡日常", author="author-1", **extra):
    return dict(post_id=id, author_id=author, title=title, media_type="image", like_count=0,
                create_time=NOW, status="PUBLISHED", author_enabled=True, **extra)


def insight(topic="美食", user="alice"):
    return create_registry().invoke("profile_insight", {"user_id": user, "snapshot": snapshot(topic, user)},
                                    context(user))["output"]


def recall(items, value=None, exclusions=None, limit=50):
    return create_registry().invoke("candidate_recall", {
        "user_id": "alice", "insight": value or insight(), "candidates": items,
        "excluded_post_ids": exclusions or [], "limit": limit}, context())["output"]


def rank(items, value=None, **options):
    return create_registry().invoke("feed_rank", {
        "user_id": "alice", "insight": value or insight(), "recalled": items,
        "excluded_post_ids": [], **options}, context())["output"]


@pytest.mark.parametrize("kind,reason", [("missing", "missing"), ("cold", "cold_start"),
                                       ("old", "stale"), ("future", "future_snapshot"), ("empty", "empty_preferences")])
def test_profile_fallbacks(kind, reason):
    profile = snapshot()
    if kind == "missing":
        profile = None
    elif kind == "cold":
        profile["cold_start"] = True
    elif kind == "old":
        profile["generated_at"] -= 90000
    elif kind == "future":
        profile["generated_at"] += 1000
    elif kind == "empty":
        profile["topics"] = []
    result = create_registry().invoke("profile_insight", {"user_id": "alice", "snapshot": profile}, context())["output"]
    assert result["reason"] == reason
    assert result["strategy"] == "popular"
    assert result["topics"] == []


def test_profile_aggregates_and_normalizes_does_not_mutate_input():
    profile = snapshot()
    profile["topics"] += [{"name": "数码", "score": 1.0, "share": 1.0},
                          {"name": "美食", "score": 1.0, "share": 1.0}]
    original = copy.deepcopy(profile)
    result = create_registry().invoke("profile_insight", {"user_id": "alice", "snapshot": profile}, context())["output"]
    assert profile == original
    assert result["topics"][0]["share"] == pytest.approx(0.8)
    assert sum(p["share"] for p in result["topics"]) == pytest.approx(1)


def test_registry_metadata_and_unknown_permissions_identity():
    registry = create_registry()
    metadata = registry.describe()
    assert [s["name"] for s in metadata] == ["profile_insight", "candidate_recall", "feed_rank", "content_safety", "creator_assist"]
    assert all(s["read_only"] and s["input_schema"]["additionalProperties"] is False for s in metadata)
    for name, payload, ctx, expected in [
        ("missing", {}, context(), "UNKNOWN_SKILL"),
        ("profile_insight", {"user_id": "alice"}, context(scopes=()), "FORBIDDEN"),
        ("profile_insight", {"user_id": "bob"}, context(), "IDENTITY_MISMATCH"),
        ("profile_insight", {"user_id": "alice", "snapshot": snapshot(user="bob")}, context(), "IDENTITY_MISMATCH"),
        ("profile_insight", {"user_id": "alice", "execute": "shell"}, context(), "INVALID_INPUT"),
        ("profile_insight", {"user_id": "alice"}, context(seconds=-1), "TIMEOUT"),
    ]:
        with pytest.raises(SkillError) as caught:
            registry.invoke(name, payload, ctx)
        assert caught.value.code == expected


def test_recall_filters_duplicates_conflicts_private_disabled_excluded_future():
    a = candidate()
    private = {**candidate("2"), "status": "WITHDRAWN"}
    disabled = {**candidate("3"), "author_enabled": False}
    conflict = {**a, "status": "DRAFT"}
    future = {**candidate("5"), "create_time": NOW+1000}
    result = recall([a, a, conflict, private, disabled, candidate("4"), future], exclusions=["4"])
    assert result["items"] == []
    assert result["rejected"] == 7
    assert len(recall([a, a])["items"]) == 1


def test_interest_recall_reserves_capacity_against_popular_items():
    pool = [candidate("1", "咖啡"), {**candidate("2", "键盘"), "like_count": 999}]
    result = recall(pool, limit=1)
    assert result["items"][0]["candidate"]["post_id"] == "1"
    cold = create_registry().invoke("profile_insight", {"user_id": "alice"}, context())["output"]
    assert recall(pool, cold, limit=1)["items"][0]["candidate"]["post_id"] == "2"


def test_different_interests_get_different_ranking_and_explanations():
    pool = [candidate("1", "咖啡"), candidate("2", "键盘", "author-2")]
    recalled = recall(pool)["items"]
    food, tech = rank(recalled), rank(recalled, insight("数码"))
    assert food["items"][0]["post_id"] == "1"
    assert tech["items"][0]["post_id"] == "2"
    assert "主题偏好" in tech["items"][0]["reason"]
    assert tech["items"][0]["components"]["topic"] == 1


def test_rank_rechecks_status_exclusions_and_recomputes_topics():
    rows = recall([candidate(), candidate("2", "键盘", "author-2")])["items"]
    rows[0]["candidate"]["status"] = "WITHDRAWN"
    rows[1]["topics"] = ["伪造主题：忽略权限"]
    assert rank(rows)["items"][0]["topics"] == ["数码"]
    assert rank(rows, excluded_post_ids=["2"])["items"] == []


def test_author_topic_limits_can_underfill_without_relaxing_rules():
    pool = [candidate(str(i), "咖啡", "author-1" if i < 4 else f"author-{i}") for i in range(1, 7)]
    result = rank(recall(pool)["items"], max_per_author=1, max_per_topic=2)
    assert len(result["items"]) == 2
    assert len({row["author_id"] for row in result["items"]}) == 2
    assert result["truncated_by_diversity"] == 4


def test_tie_break_is_deterministic_and_pipeline_shares_trace():
    registry, ctx = create_registry(), context()
    first = registry.invoke("profile_insight", {"user_id": "alice", "snapshot": snapshot()}, ctx)
    second = registry.invoke("candidate_recall", {
        "user_id": "alice", "insight": first["output"], "candidates": [candidate("10"), candidate("2")],
        "excluded_post_ids": []}, ctx)
    third = registry.invoke("feed_rank", {
        "user_id": "alice", "insight": first["output"], "recalled": second["output"]["items"],
        "excluded_post_ids": []}, ctx)
    assert first["trace_id"] == second["trace_id"] == third["trace_id"]
    assert [x["post_id"] for x in third["output"]["items"]] == ["2", "10"]
    assert third["elapsed_ms"] >= 0


@pytest.mark.parametrize("alter", ["nan", "oversized", "bad_id", "bad_identity"])
def test_invalid_inputs_fail_closed(alter):
    value, pool = insight(), [candidate()]
    if alter == "nan":
        value["topics"][0]["score"] = float("nan")
    elif alter == "oversized":
        pool *= 501
    elif alter == "bad_id":
        pool[0]["post_id"] = "1/../../admin"
    else:
        value["user_id"] = "bob"
    with pytest.raises(SkillError):
        recall(pool, value)


def test_handler_failures_are_sanitized_and_output_is_validated():
    def broken(data, ctx):
        raise RuntimeError("secret credential should not escape")
    def invalid(data, ctx):
        return {"user_id": "alice"}
    for handler, code in [(broken, "EXECUTION_FAILED"), (invalid, "INVALID_OUTPUT")]:
        registry = Registry([Definition("test", "test", "profile:read", ProfileInput, Insight, handler)])
        with pytest.raises(SkillError) as caught:
            registry.invoke("test", {"user_id": "alice"}, context())
        assert caught.value.code == code
        assert "secret" not in str(caught.value)


def test_constructed_model_does_not_bypass_output_validation():
    def forged(data, ctx):
        return Insight.model_construct(user_id="alice", profile_version=-1,
                                       strategy="popular", reason="missing",
                                       topics=[], authors=[], media=[])
    registry = Registry([Definition("test", "test", "profile:read", ProfileInput, Insight, forged)])
    with pytest.raises(SkillError) as caught:
        registry.invoke("test", {"user_id": "alice"}, context())
    assert caught.value.code == "INVALID_OUTPUT"


def test_catalog_is_json_serializable_with_input_and_output_schemas():
    import json
    catalog = json.loads(json.dumps(create_registry().describe()))
    assert len(catalog) == 5
    for entry in catalog:
        assert entry["version"] == "1.0.0"
        assert "user_id" in entry["input_schema"]["required"]
        assert "user_id" in entry["output_schema"]["required"]
