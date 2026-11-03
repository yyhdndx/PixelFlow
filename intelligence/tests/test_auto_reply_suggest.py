import time

from pixelflow_intelligence.agents.auto_reply_suggest import suggest_rules
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills import Context


def context(seconds=25):
    return Context("suggest-service", frozenset({"comment:reply"}),
                   int(time.time()), time.monotonic() + seconds)


def run(writer=None, key="", prompt="价格 99 元，48 小时发货"):
    return suggest_rules(post_id="7", post_title="手冲咖啡豆", post_content="中度烘焙",
                         prompt=prompt, settings=Settings(database=":memory:", deepseek_api_key=key),
                         store=None, context=context(), writer=writer)


def test_writer_output_is_cleaned_and_capped():
    writer = lambda **_: {"rules": [
        {"question": f"场景{i}", "answer": f"答案{i}"} for i in range(8)
    ]}
    result = run(writer)
    assert result["degraded"] == 0
    assert len(result["suggestions"]) == 5          # 最多 5 条
    assert result["suggestions"][0] == {"question": "场景0", "answer": "答案0"}


def test_invalid_items_are_dropped():
    writer = lambda **_: {"rules": [
        {"question": "", "answer": "x"},
        {"question": "有人问价格", "answer": ""},
        {"question": "长" * 201, "answer": "x"},
        {"question": "有人问价格", "answer": "99 元"},
        {"question": "有人问价格", "answer": "重复场景"},
        "not-a-dict",
    ]}
    result = run(writer)
    assert result["suggestions"] == [{"question": "有人问价格", "answer": "99 元"}]


def test_model_failure_degrades():
    def boom(**_):
        raise TimeoutError()

    result = run(boom)
    assert result["degraded"] == 1
    assert result["suggestions"] == []


def test_missing_key_degrades_without_calling_model():
    result = run(None, key="")
    assert result["degraded"] == 1
    assert "模型不可用" in result["reason"]


def test_empty_rules_explains_how_to_improve():
    result = run(lambda **_: {"rules": []})
    assert result["suggestions"] == []
    assert "更具体" in result["reason"]
