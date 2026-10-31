import time
import pytest
from pixelflow_intelligence.skills import create_registry, Context, SkillError

NOW = 1791000000


def context(user="alice", scopes=None, seconds=5):
    return Context(user, frozenset(scopes if scopes is not None else ("content:check", "content:assist")),
                   NOW, time.monotonic() + seconds)


def safety(title="", content="", tags=None, user="alice"):
    return create_registry().invoke("content_safety", {
        "user_id": user, "title": title, "content": content, "tags": tags or []}, context(user))["output"]


def assist(draft, goal="all", user="alice", prompt=""):
    return create_registry().invoke("creator_assist", {
        "user_id": user, "draft": draft, "goal": goal, "prompt": prompt}, context(user))["output"]


# ---- content_safety ----

def test_safety_banned_word_is_high_risk_and_needs_review():
    out = safety(content="这里在宣传赌博平台")
    assert out["risk_level"] == "high"
    assert out["rule_hits"] == ["banned_word"]
    assert out["requires_human_review"] is True
    assert out["suggestions"]


def test_safety_banned_word_in_tags_is_also_flagged():
    out = safety(tags=["色情"])
    assert out["risk_level"] == "high"
    assert "banned_word" in out["rule_hits"]


def test_safety_contact_info_is_medium_risk():
    out = safety(content="加我微信 abc12345 详聊")
    assert out["risk_level"] == "medium"
    assert out["rule_hits"] == ["contact_info"]
    assert out["requires_human_review"] is False


def test_safety_clean_content_is_low_risk():
    out = safety(title="咖啡日常", content="今天的咖啡很好喝", tags=["#咖啡"])
    assert out["risk_level"] == "low"
    assert out["rule_hits"] == []
    assert out["suggestions"] == []
    assert out["requires_human_review"] is False


def test_safety_policy_version():
    assert safety(content="正常内容")["policy_version"] == "content-safety-v1"


# ---- creator_assist ----

def test_assist_fills_empty_title_from_first_sentence():
    out = assist({"title": "", "content": "今天的咖啡很好喝。值得再来。", "tags": []}, "all")
    assert out["title_candidates"] == ["今天的咖啡很好喝"]


def test_assist_expands_short_title():
    out = assist({"title": "咖啡", "content": "今天的咖啡很好喝。", "tags": []}, "improve_title")
    assert out["title_candidates"] == ["咖啡｜今天的咖啡很好喝"]


def test_assist_extracts_hashtags_as_tag_suggestions():
    out = assist({"title": "标题", "content": "今天 #咖啡 很好喝，也喜欢 #烘焙", "tags": []}, "suggest_tags")
    assert out["tag_suggestions"] == ["#咖啡", "#烘焙"]


def test_assist_does_not_repeat_tags_already_present():
    out = assist({"title": "标题", "content": "#咖啡 很好喝", "tags": ["#咖啡"]}, "suggest_tags")
    assert out["tag_suggestions"] == []


def test_assist_empty_content_gets_polish_suggestion():
    out = assist({"title": "标题", "content": "", "tags": []}, "polish_content")
    assert any("正文为空" in s for s in out["edit_suggestions"])


def test_assist_long_title_gets_trim_suggestion():
    out = assist({"title": "这是一个非常非常非常非常非常非常非常非常长的标题超过三十个字了吧", "content": "正文", "tags": []}, "improve_title")
    assert any("标题偏长" in s for s in out["edit_suggestions"])


def test_assist_no_tags_gets_suggestion():
    out = assist({"title": "标题", "content": "普通正文内容", "tags": []}, "all")
    assert any("话题标签" in s for s in out["edit_suggestions"])


def test_assist_goal_scopes_output():
    out = assist({"title": "", "content": "正文内容", "tags": []}, "suggest_tags")
    assert out["title_candidates"] == []
    assert out["tag_suggestions"] == []


def test_assist_policy_version():
    out = assist({"title": "标题", "content": "正文", "tags": []}, "all")
    assert out["policy_version"] == "creator-assist-v1"


def test_assist_writes_content_from_prompt():
    out = assist({"title": "", "content": "", "tags": []}, "write_content", prompt="咖啡")
    assert len(out["content_candidates"]) == 3
    assert out["content_candidates"][0] == "今天想和大家分享一下「咖啡」的一些感受。"


def test_assist_writes_content_falls_back_to_title():
    out = assist({"title": "咖啡日常", "content": "", "tags": []}, "write_content")
    assert out["content_candidates"][0] == "今天想和大家分享一下「咖啡日常」的一些感受。"


def test_assist_prompt_beats_title_for_write_content():
    out = assist({"title": "标题", "content": "", "tags": []}, "write_content", prompt="咖啡")
    assert out["content_candidates"][0] == "今天想和大家分享一下「咖啡」的一些感受。"


def test_assist_write_content_does_not_leak_into_all():
    out = assist({"title": "标题", "content": "正文", "tags": []}, "all")
    assert out["content_candidates"] == []


# ---- registry error semantics ----

def test_creation_skills_are_registered():
    assert [s["name"] for s in create_registry().describe()] == \
           ["profile_insight", "candidate_recall", "feed_rank", "content_safety", "creator_assist"]


@pytest.mark.parametrize("name,payload,ctx,expected", [
    ("content_safety", {"user_id": "alice", "title": "", "content": "", "tags": []}, context(scopes=()), "FORBIDDEN"),
    ("creator_assist", {"user_id": "bob", "draft": {"title": "", "content": "", "tags": []}, "goal": "all"}, context(), "IDENTITY_MISMATCH"),
    ("content_safety", {"user_id": "alice", "title": "", "content": "", "tags": [], "extra": "x"}, context(), "INVALID_INPUT"),
    ("creator_assist", {"user_id": "alice", "draft": {"title": "", "content": "", "tags": []}, "goal": "all"}, context(seconds=-1), "TIMEOUT"),
])
def test_creation_skills_error_semantics(name, payload, ctx, expected):
    with pytest.raises(SkillError) as caught:
        create_registry().invoke(name, payload, ctx)
    assert caught.value.code == expected


def test_creation_skills_outputs_are_json_serializable():
    import json
    safe = json.loads(json.dumps(safety(content="正常内容")))
    assert safe["risk_level"] == "low"
    created = json.loads(json.dumps(assist({"title": "", "content": "正文内容", "tags": []}, "all")))
    assert "title_candidates" in created
