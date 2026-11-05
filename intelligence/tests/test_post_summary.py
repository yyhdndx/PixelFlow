import time

from pixelflow_intelligence.agents.post_summary import MIN_CONTENT_LENGTH, summarize_post
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills import Context

LONG = "宿舍低预算做饭经验。" + "用电煮锅煮面、买菜看折扣、食材分装冷冻。" * 12


def context(seconds=25):
    return Context("summary-service", frozenset({"posts:read"}),
                   int(time.time()), time.monotonic() + seconds)


def run(writer=None, key="", content=LONG):
    return summarize_post(post_id="7", title="宿舍做饭", content=content,
                          settings=Settings(database=":memory:", deepseek_api_key=key),
                          store=None, context=context(), writer=writer)


def test_short_text_skips_model():
    called = []
    result = run(lambda **_: called.append(1) or {}, content="短正文")
    assert result["reason"] == "SHORT_TEXT"
    assert result["summary"] == "" and result["points"] == []
    assert called == []


def test_summary_and_points_are_parsed_and_capped():
    writer = lambda **_: {"summary": "作者分享宿舍低成本做饭的经验。",
                          "points": [f"要点{i}" for i in range(8)]}
    result = run(writer)
    assert result["degraded"] == 0
    assert result["summary"].startswith("作者分享")
    assert len(result["points"]) == 5
    assert result["content_length"] >= MIN_CONTENT_LENGTH


def test_invalid_items_are_dropped():
    writer = lambda **_: {"summary": "一句话", "points": ["", "  ", "长" * 201, "保留的要点"]}
    result = run(writer)
    assert result["points"] == ["保留的要点"]


def test_model_failure_degrades_to_no_summary():
    def boom(**_):
        raise TimeoutError()

    result = run(boom)
    assert result["degraded"] == 1
    assert result["summary"] == "" and result["points"] == []


def test_missing_key_degrades():
    result = run(None, key="")
    assert result["degraded"] == 1
    assert "模型" in result["reason"] or result["reason"] == "AI 暂不可用"


def test_incomplete_model_output_degrades():
    result = run(lambda **_: {"summary": "只有概括"})
    assert result["degraded"] == 1
    assert result["points"] == []
