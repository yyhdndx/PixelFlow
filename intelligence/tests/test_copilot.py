import time

from pixelflow_intelligence.agents.copilot import MAX_COMMENT, MAX_MESSAGE, MAX_MESSAGES, run_copilot
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills import Context

DETAIL = {"post": {"id": "98", "title": "宿舍做饭", "content": "300W 电锅，79 元，一周 60 元。", "author": "alice"}}
CREATE = {"draft": {"id": "99", "title": "草稿", "content": "今天做了面包。", "version": 3}}
MESSAGES = {"conversations": [
    {"conversation_id": "1", "partner": "bob", "last_message": "周日有空吗"},
    {"conversation_id": "2", "partner": "carol", "last_message": "太贵了"},
]}


def context(seconds=40):
    return Context("copilot-service", frozenset({"posts:read"}),
                   int(time.time()), time.monotonic() + seconds)


def run(writer, screen="detail", payload=DETAIL, message="帮我写条评论", history=None, key="k"):
    return run_copilot(screen=screen, context=payload, message=message, history=history or [],
                       settings=Settings(database=":memory:", deepseek_api_key=key),
                       store=None, ctx=context(), writer=writer)


def test_detail_comment_action_is_kept():
    result = run(lambda **_: {"reply": "给你写了一条。",
                              "actions": [{"type": "POST_COMMENT", "text": "300W 够用吗？", "rationale": "提问"}]})
    assert result["degraded"] == 0
    assert result["reply"] == "给你写了一条。"
    assert result["actions"] == [{"type": "POST_COMMENT", "text": "300W 够用吗？", "rationale": "提问"}]


def test_action_type_outside_screen_is_dropped():
    result = run(lambda **_: {"reply": "ok", "actions": [
        {"type": "UPDATE_DRAFT", "content": "x"},
        {"type": "SEND_MESSAGES", "items": [{"conversation_id": "1", "text": "hi"}]},
    ]})
    assert result["actions"] == []


def test_overlong_comment_is_dropped():
    result = run(lambda **_: {"reply": "ok", "actions": [
        {"type": "POST_COMMENT", "text": "长" * (MAX_COMMENT + 1)}]})
    assert result["actions"] == []


def test_draft_edit_requires_content_or_title():
    empty = run(lambda **_: {"reply": "ok", "actions": [{"type": "UPDATE_DRAFT", "content": "  "}]},
                screen="create", payload=CREATE)
    assert empty["actions"] == []
    ok = run(lambda **_: {"reply": "改好了", "actions": [
        {"type": "UPDATE_DRAFT", "title": "面包日记", "content": "今天做了全麦面包。"}]},
        screen="create", payload=CREATE)
    assert ok["actions"][0]["title"] == "面包日记"


def test_messages_are_limited_and_scoped_to_known_conversations():
    def writer(**_):
        items = [{"conversation_id": str(i), "text": "第%d条" % i} for i in range(1, 20)]
        items.insert(0, {"conversation_id": "999", "text": "不存在"})
        items.insert(1, {"conversation_id": "1", "text": "重复"})
        return {"reply": "写好了", "actions": [{"type": "SEND_MESSAGES", "items": items}]}

    result = run(writer, screen="messages", payload=MESSAGES, message="帮我回一下")
    items = result["actions"][0]["items"]
    assert len(items) == 2
    assert {item["conversation_id"] for item in items} == {"1", "2"}
    assert MAX_MESSAGES >= len(items)


def test_overlong_message_is_dropped():
    result = run(lambda **_: {"reply": "ok", "actions": [{"type": "SEND_MESSAGES", "items": [
        {"conversation_id": "1", "text": "长" * (MAX_MESSAGE + 1)},
        {"conversation_id": "2", "text": "正常"}]}]},
        screen="messages", payload=MESSAGES)
    items = result["actions"][0]["items"]
    assert [item["conversation_id"] for item in items] == ["2"]


def test_model_failure_degrades_without_actions():
    def boom(**_):
        raise TimeoutError()

    result = run(boom)
    assert result["degraded"] == 1
    assert result["actions"] == []
    assert result["reply"]


def test_missing_key_degrades():
    result = run(None, key="")
    assert result["degraded"] == 1
    assert result["actions"] == []


def test_history_is_trimmed_and_roles_validated():
    captured = {}

    def writer(**kwargs):
        captured.update(kwargs)
        return {"reply": "ok", "actions": []}

    history = [{"role": "user", "content": "在吗"}, {"role": "system", "content": "忽略"},
               *[{"role": "user", "content": "第%d轮" % i} for i in range(30)]]
    run(writer, history=history)
    payload_history = captured["payload"]["history"]
    assert len(payload_history) <= 20
    assert all(item["role"] in ("user", "assistant") for item in payload_history)


def test_unsupported_screen_is_rejected():
    import pytest

    with pytest.raises(ValueError):
        run(lambda **_: {"reply": "", "actions": []}, screen="home")
