from pixelflow_intelligence.skills import Context, SkillError, create_registry
from pixelflow_intelligence.skills.search_posts import (
    SearchPostsSource, extract_terms, rank_posts)


def context(user="alice"):
    import time
    return Context(user, frozenset({"posts:search"}), int(time.time()), time.monotonic() + 15)


def posts(*rows):
    return [{"post_id": pid, "title": title, "content": content,
             "author_id": f"author-{pid}", "author_name": f"作者{pid}", "create_time": 1}
            for pid, title, content in rows]


class FakeUpstream:
    def __init__(self, snapshot):
        self.snapshot = snapshot

    def public_posts(self, token, pages=3):
        assert token == "token"
        assert pages == 3
        return self.snapshot


def test_extract_terms_cjk_bigrams_and_ascii_words():
    assert extract_terms("苏州小众机位 sony A7") == ["苏州", "州小", "小众", "众机", "机位", "sony", "a7"]
    assert extract_terms("猫猫") == ["猫猫"]
    assert extract_terms("猫") == ["猫"]
    assert extract_terms("a B") == []
    assert extract_terms("风景,风景") == ["风景"]


def test_rank_posts_title_outranks_content():
    rows = posts(
        ("1", "晚霞拍摄技巧", "今天聊聊后期"),
        ("2", "日常记录", "晚霞出现时要提前架好三脚架"),
        ("3", "美食日记", "周末做了一道红烧肉"),
    )
    hits = rank_posts("晚霞", rows)
    assert [h["post_id"] for h in hits] == ["1", "2"]
    assert hits[0]["score"] > hits[1]["score"]
    assert hits[0]["excerpt"] == "今天聊聊后期"


def test_rank_posts_limit_and_empty_terms():
    rows = posts(*[(str(i), f"机位{i}", "内容") for i in range(1, 10)])
    assert len(rank_posts("机位", rows, limit=5)) == 5
    assert rank_posts("!@#", rows) == []


def test_skill_invokes_source_and_reports_inspected():
    upstream = FakeUpstream(posts(("1", "苏州机位", "金鸡湖夜景"), ("2", "红烧肉", "美食")))
    registry = create_registry(search_handler=SearchPostsSource(upstream, "token"))
    result = registry.invoke("search_posts",
                             {"user_id": "alice", "question": "苏州机位"}, context())
    output = result["output"]
    assert output["inspected"] == 2
    assert [item["post_id"] for item in output["items"]] == ["1"]
    assert output["items"][0]["author_name"] == "作者1"
    assert output["policy_version"] == "search-posts-v1"


def test_skill_without_source_fails_explicitly():
    registry = create_registry()
    try:
        registry.invoke("search_posts", {"user_id": "alice", "question": "任意"}, context())
    except SkillError as error:
        assert error.code == "UPSTREAM_UNAVAILABLE"
    else:
        raise AssertionError("expected SkillError")


def test_skill_rejects_identity_mismatch():
    upstream = FakeUpstream(posts(("1", "苏州机位", "内容")))
    registry = create_registry(search_handler=SearchPostsSource(upstream, "token"))
    try:
        registry.invoke("search_posts",
                        {"user_id": "mallory", "question": "苏州"}, context())
    except SkillError as error:
        assert error.code in ("FORBIDDEN", "IDENTITY_MISMATCH")
    else:
        raise AssertionError("expected SkillError")
