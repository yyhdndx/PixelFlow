"""Tests for the content_safety and creator_assist skills (deterministic, offline)."""
import time

import pytest

from pixelflow_intelligence.skills import Context, SkillError, create_registry


def context(scopes=("content:check", "content:assist"), *, deadline_in=5.0, user="alice"):
    return Context(user_id=user, scopes=frozenset(scopes), now=int(time.time()),
                   deadline=time.monotonic() + deadline_in)


def test_registry_now_exposes_five_skills():
    described = create_registry().describe()
    names = [item["name"] for item in described]
    assert names == ["profile_insight", "candidate_recall", "feed_rank",
                     "content_safety", "creator_assist"]
    assert all(item["version"] == "1.0.0" and item["read_only"] for item in described)
    safety = next(item for item in described if item["name"] == "content_safety")
    assert safety["required_scopes"] == ["content:check"]
    assert "input_schema" in safety and "output_schema" in safety


def test_safety_flags_contact_information_as_high_risk():
    result = create_registry().invoke("content_safety", {
        "user_id": "alice",
        "title": "出闲置相机",
        "content": "九成新，有意加微信 13800138000 详聊",
        "tags": ["数码"],
    }, context())
    output = result["output"]
    assert output["risk"] == "high"
    assert output["decision"] == "human_review_required"
    rules = {hit["rule"] for hit in output["hits"]}
    assert "CONTACT_INFO" in rules
    assert any("联系方式" in hint for hint in output["suggestions"])


def test_safety_marks_clean_post_as_allowed():
    result = create_registry().invoke("content_safety", {
        "user_id": "alice",
        "title": "冬天第一杯手冲咖啡",
        "content": "记录今天的手冲参数与风味，欢迎交流做法。",
        "tags": ["美食"],
    }, context())
    output = result["output"]
    assert output["risk"] == "low" and output["decision"] == "allowed"
    assert output["hits"] == []


@pytest.mark.parametrize("content,rule,level", [
    ("详见 https://example.com/abc", "EXTERNAL_LINK", "medium"),
    ("支持代购和返利，私聊转账", "PRIVATE_TRADE", "high"),
    ("好好好好好好好好好好好好", "SPAM_REPEAT", "medium"),
    ("短", "LOW_INFORMATION", "low"),
])
def test_safety_rules_are_deterministic(content, rule, level):
    payload = {"user_id": "alice", "title": "测试标题", "content": content, "tags": []}
    first = create_registry().invoke("content_safety", payload, context())
    second = create_registry().invoke("content_safety", payload, context())
    assert first["output"] == second["output"]
    matches = [hit for hit in first["output"]["hits"] if hit["rule"] == rule]
    assert matches and matches[0]["level"] == level


def test_assist_suggests_topics_tags_and_titles():
    result = create_registry().invoke("creator_assist", {
        "user_id": "alice",
        "title": "",
        "content": "周末煮了一杯手冲咖啡，配上面包和草莓，记录一下。",
        "media_count": 0,
    }, context())
    output = result["output"]
    assert "美食" in output["topics"]
    assert output["tags"] and all(len(tag) <= 24 for tag in output["tags"])
    assert 1 <= len(output["title_suggestions"]) <= 3
    assert all(len(title) <= 120 for title in output["title_suggestions"])
    assert any("图片" in note for note in output["notes"])
    assert output["policy_version"] == "creator-assist-v1"


def test_assist_is_deterministic_and_keeps_title_when_fitting():
    payload = {"user_id": "alice", "title": "手冲咖啡记录", "content": "今天试了新的水温。",
               "media_count": 1}
    first = create_registry().invoke("creator_assist", payload, context())
    second = create_registry().invoke("creator_assist", payload, context())
    assert first["output"] == second["output"]
    assert any("手冲咖啡记录" in title for title in first["output"]["title_suggestions"])


def test_new_skills_enforce_scope_identity_and_input_validation():
    registry = create_registry()
    payload = {"user_id": "alice", "title": "标题", "content": "正文内容足够长一些"}
    with pytest.raises(SkillError) as forbidden:
        registry.invoke("content_safety", payload, context(scopes=()))
    assert forbidden.value.code == "FORBIDDEN"

    with pytest.raises(SkillError) as mismatch:
        registry.invoke("content_safety", {**payload, "user_id": "bob"}, context())
    assert mismatch.value.code == "IDENTITY_MISMATCH"

    with pytest.raises(SkillError) as invalid:
        registry.invoke("content_safety", {"user_id": "alice", "title": "x" * 200}, context())
    assert invalid.value.code == "INVALID_INPUT"

    with pytest.raises(SkillError) as unknown_field:
        registry.invoke("creator_assist", {"user_id": "alice", "content": "正文", "extra": 1},
                        context())
    assert unknown_field.value.code == "INVALID_INPUT"
