import json
import threading
import time

from pixelflow_intelligence.agents.summary_stream import summary_events
from pixelflow_intelligence.common.settings import Settings


class Source:
    def __init__(self):
        self.post = {"title": "长文", "content": "原文内容" * 60, "status": "PUBLISHED"}

    def _get(self, path, token):
        return dict(self.post)


def generate(model, source=None):
    source = source or Source()
    return summary_events(post_id="1", post=dict(source.post), token="test", upstream=source,
        settings=Settings(deepseek_api_key="test"), store=None, user="alice", cancelled=threading.Event(),
        deadline=time.monotonic() + 5, model=model)


def test_summary_is_available_before_points_are_generated():
    produced = []

    def model():
        yield json.dumps({"summary": "完整概括"}) + "\n"
        produced.append(True)
        for i in range(3):
            yield json.dumps({"point": "要点" + str(i)}) + "\n"

    events = generate(model)
    name, data = next(events)
    assert name == "summary" and data["summary"] == "完整概括"
    assert not produced
    rest = list(events)
    assert [name for name, _ in rest] == ["point", "point", "point", "done"]
    assert rest[-1][1]["validated"]


def test_truncated_or_malformed_blocks_never_complete():
    for text in ['{"summary":"概括"}\n{"point":', '{"summary":"概括"}\n{"point":"仅一条"}\n', 'raw JSON error']:
        events = list(generate(lambda: iter([text])))
        assert events[-1][0] == "error"
        assert all(name != "done" for name, _ in events)


def test_changed_source_invalidates_partial_summary():
    source = Source()

    def model():
        yield '{"summary":"概括"}\n'
        source.post["status"] = "WITHDRAWN"
        yield '{"point":"要点"}\n'

    events = list(generate(model, source))
    assert [name for name, _ in events] == ["summary", "error"]
