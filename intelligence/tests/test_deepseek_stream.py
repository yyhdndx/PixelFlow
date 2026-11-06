import json
import time

import httpx
import pytest

from pixelflow_intelligence.clients.deepseek import chat_once, stream_chat
from pixelflow_intelligence.common.settings import Settings


def frame(text="", finish=None):
    return "data: " + json.dumps({"choices": [{"delta": {"content": text}, "finish_reason": finish}]}) + "\n\n"


def consume(body):
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=body.encode()))
    return list(stream_chat(Settings(deepseek_api_key="test-only"), system="test", user_content="test",
                            max_tokens=10, deadline=time.monotonic() + 2, transport=transport))


def test_normal_stop_requires_done():
    assert consume(frame("第一段") + frame("第二段", "stop") + "data: [DONE]\n\n") == ["第一段", "第二段"]


@pytest.mark.parametrize("body", [
    frame("partial", "length") + "data: [DONE]\n\n",
    frame("partial") + "data: [DONE]\n\n",
    frame("partial", "stop"),
    frame("partial"),
    "event: error\ndata: {}\n\n",
    'data: {"error":{"message":"internal"}}\n\n',
    "data: invalid-json\n\n",
    frame("x" * 17000),
    ":" + "x" * 17000,
])
def test_invalid_and_incomplete_streams_fail(body):
    with pytest.raises((ValueError, UnicodeError)):
        consume(body)


def test_response_limit_counts_utf8_and_protocol_bytes():
    body = "".join(frame("字" * 1000) for _ in range(30))
    with pytest.raises(ValueError):
        consume(body)


def test_nonstreaming_response_size_is_bounded():
    transport = httpx.MockTransport(lambda request: httpx.Response(200, content=b"x" * 65537))
    with pytest.raises(ValueError):
        chat_once(Settings(deepseek_api_key="test-only"), system="test", user_content="test",
                  max_tokens=10, timeout=2, transport=transport)


def test_deadline_closes_stalled_response():
    import threading

    class Stalled(httpx.SyncByteStream):
        def __init__(self):
            self.closed = threading.Event()

        def __iter__(self):
            yield frame("first").encode()
            self.closed.wait(2)
            if self.closed.is_set():
                raise OSError("closed")

        def close(self):
            self.closed.set()

    stream = Stalled()
    transport = httpx.MockTransport(lambda request: httpx.Response(200, stream=stream))
    started = time.monotonic()
    chunks = stream_chat(Settings(deepseek_api_key="test-only"), system="test", user_content="test",
                         max_tokens=10, deadline=started + 0.15, transport=transport)
    assert next(chunks) == "first"
    with pytest.raises((OSError, TimeoutError, ValueError)):
        list(chunks)
    assert stream.closed.is_set()
    assert time.monotonic() - started < 1
