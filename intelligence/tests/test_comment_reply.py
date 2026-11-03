import time

from pixelflow_intelligence.agents.comment_reply import decide_reply
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills import Context

RULES = [{"question": "有人问价格", "answer": "99 元，链接在主页"},
         {"question": "有人问发货时间", "answer": "48 小时内发出"}]


def context(seconds=8):
    return Context("comment-reply-service", frozenset({"comment:reply"}),
                   int(time.time()), time.monotonic() + seconds)


def decide(chooser=None, rules=RULES, key=""):
    return decide_reply(post_id="7", post_title="手冲咖啡豆", post_content="中度烘焙",
                        comment="这个多少钱", rules=rules,
                        settings=Settings(database=":memory:", deepseek_api_key=key),
                        store=None, context=context(), chooser=chooser)


def test_matched_rule_uses_author_answer_verbatim():
    calls = []

    def chooser(*, post_title, post_content, comment, rules, deadline):
        calls.append(comment)
        return {"match_index": 0, "reason": "评论在询问价格"}

    result = decide(chooser)
    assert result["match_index"] == 0
    assert result["answer"] == RULES[0]["answer"]  # 逐字取作者配置，模型不生成答案
    assert result["degraded"] == 0
    assert result["provider"] == "deepseek"
    assert calls == ["这个多少钱"]


def test_out_of_range_index_or_null_means_no_reply():
    assert decide(lambda **_: {"match_index": 9, "reason": "x"})["answer"] is None
    assert decide(lambda **_: {"match_index": None, "reason": "无关"})["answer"] is None
    assert decide(lambda **_: {"match_index": True, "reason": "x"})["answer"] is None


def test_model_failure_degrades_to_no_reply():
    def boom(**_):
        raise TimeoutError()

    result = decide(boom)
    assert result["answer"] is None
    assert result["degraded"] == 1
    assert "未自动回复" in result["reason"]


def test_missing_key_degrades_without_calling_model():
    result = decide(chooser=None, key="")
    assert result["answer"] is None
    assert result["degraded"] == 1


def test_no_rules_skips_model_entirely():
    called = []
    result = decide(lambda **_: called.append(1) or {"match_index": 0, "reason": "x"}, rules=[])
    assert result["answer"] is None
    assert called == []
    assert result["reason"] == "作者未配置场景"
