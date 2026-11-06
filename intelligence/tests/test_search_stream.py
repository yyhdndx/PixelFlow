import time
import uuid

import pytest

from pixelflow_intelligence.agents.search_stream import PendingSearches, digest, search_events
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.common.settings import Settings


class Source:
    visible = True

    def public_posts(self, token, pages=3):
        return [{"post_id": "1", "title": "咖啡", "content": "咖啡制作方法", "author_id": "author",
                 "author_name": "作者", "create_time": 1}]

    def _get(self, path, token):
        return {"status": "PUBLISHED" if self.visible else "WITHDRAWN", "title": "咖啡",
                "content": "咖啡制作方法", "author": {"nickname": "作者"}}


def generate(store=None, model=None, question="咖啡"):
    pending = PendingSearches()
    request = str(uuid.uuid4())
    item = pending.reserve(request, "alice", time.monotonic() + 20)
    source = Source()
    events = search_events(question=question, session_id=None, user="alice", token="test",
        upstream=source, settings=Settings(deepseek_api_key=""), store=store,
        request_id=request, item=item, model=model)
    return pending, request, item, source, events


def test_first_delta_precedes_model_completion_and_confirmation(tmp_path):
    store = Store(str(tmp_path / "stream.db"))
    finished = []

    def model():
        yield "先展示〔1〕"
        finished.append(True)
        yield "后完成。"

    pending, request, item, source, events = generate(store, model)
    received = []
    for name, data in events:
        received.append(name)
        if name == "delta" and not finished:
            assert data["text"] == "先展示〔1〕"
            assert item["result"] is None
    assert finished
    assert received[-1] == "prepared"
    result = item["result"]
    assert store.load_agent_session(result["session_id"], "alice") is None
    response = pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)
    assert response["committed"]
    pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)
    assert len(store.load_agent_session(result["session_id"], "alice")["turns"]) == 1


def test_source_changes_prevent_commit(tmp_path):
    store = Store(str(tmp_path / "stream.db"))
    pending, request, item, source, events = generate(store)
    list(events)
    result = item["result"]
    source.visible = False
    with pytest.raises(ValueError):
        pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)
    assert store.load_agent_session(result["session_id"], "alice") is None


def test_truncated_model_yields_error_and_never_prepares():
    def model():
        yield "不完整〔1〕"
        raise ValueError("truncated")
    _, _, item, _, events = generate(model=model)
    received = list(events)
    assert received[-1][0] == "error"
    assert received[-1][1]["incomplete"]
    assert item["result"] is None


def test_cancelled_or_wrong_account_cannot_confirm(tmp_path):
    store = Store(str(tmp_path / "stream.db"))
    pending, request, item, source, events = generate(store)
    list(events)
    result = item["result"]
    with pytest.raises(ValueError):
        pending.confirm(request, "bob", result["trace_id"], digest(result), source, "test", store)
    pending.cancel(request, "alice")
    with pytest.raises(ValueError):
        pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)


def test_zero_results_do_not_call_model():
    def model():
        raise AssertionError("No model call for zero results")
    _, _, item, _, events = generate(model=model, question="不存在的话题")
    received = list(events)
    assert received[-1][0] == "prepared"
    assert item["result"]["sources"] == []


def test_confirmation_transaction_rolls_back_trace_when_session_write_fails(tmp_path):
    import sqlite3
    store = Store(str(tmp_path / "atomic.db"))
    pending, request, item, source, events = generate(store)
    list(events)
    result = item["result"]
    with store.connect() as db:
        db.execute("CREATE TRIGGER refuse_session BEFORE INSERT ON agent_sessions BEGIN SELECT RAISE(ABORT, 'test failure'); END")
    with pytest.raises(sqlite3.IntegrityError):
        pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)
    with store.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM agent_traces").fetchone()[0] == 0
        db.execute("DROP TRIGGER refuse_session")
    assert pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)["committed"]


def test_cancel_after_prepared_records_failure_without_successful_session(tmp_path):
    store = Store(str(tmp_path / "cancel-prepared.db"))
    pending, request, item, source, events = generate(store)
    list(events)
    result = item["result"]
    pending.cancel(request, "alice")
    assert store.load_agent_session(result["session_id"], "alice") is None
    with store.connect() as db:
        row = db.execute("SELECT degraded FROM agent_traces WHERE trace_id=?", (result["trace_id"],)).fetchone()
        assert row is not None and row[0] == 1


@pytest.mark.parametrize("text", ["正文〔9〕", "正文〔1", "正文〕", "正文〔01〕", "正文无引用", "x" * 1501])
def test_invalid_complete_answers_never_prepare(text):
    _, _, item, _, events = generate(model=lambda: iter([text]))
    received = list(events)
    assert received[-1][0] == "error"
    assert item["result"] is None


@pytest.mark.parametrize("status", ["DRAFT", "PENDING", "REJECTED", "WITHDRAWN", "DELETED"])
def test_nonpublished_source_never_reaches_client(status):
    _, _, item, source, events = generate()
    source._get = lambda path, token: {"status": status, "title": "咖啡", "content": "咖啡制作方法", "author": {"nickname": "作者"}}
    received = list(events)
    assert all(name not in {"sources", "delta", "prepared"} for name, _ in received)
    assert received[-1][0] == "error"


def test_expired_stream_cannot_publish_or_commit():
    _, _, item, _, events = generate()
    item["deadline"] = time.monotonic() - 1
    received = list(events)
    assert received[-1][0] == "error"
    assert item["result"] is None


def test_pending_result_expires_without_another_request():
    import threading
    pending = PendingSearches()
    expired = threading.Event()
    item = pending.reserve("expires", "alice", time.monotonic() + 0.1)
    item["failure_trace"] = expired.set
    assert expired.wait(2)
    assert item["cancelled"].is_set()
    assert "expires" not in pending._items


def test_cancel_during_source_validation_does_not_block_or_commit(tmp_path):
    import threading
    store = Store(str(tmp_path / "cancel-validation.db"))
    pending, request, item, source, events = generate(store)
    list(events)
    result = item["result"]
    entered, release = threading.Event(), threading.Event()
    original = source._get

    def blocked(path, token):
        entered.set()
        assert release.wait(3)
        return original(path, token)

    source._get = blocked
    errors = []

    def confirm():
        try:
            pending.confirm(request, "alice", result["trace_id"], digest(result), source, "test", store)
        except Exception as error:
            errors.append(error)

    worker = threading.Thread(target=confirm)
    worker.start()
    try:
        assert entered.wait(2)
        pending.cancel(request, "alice")
        assert item["cancelled"].is_set()
        other = pending.reserve("other", "bob", time.monotonic() + 5)
        pending.cancel("other", "bob")
        assert other["cancelled"].is_set()
    finally:
        release.set()
        worker.join(3)
    assert not worker.is_alive()
    assert errors and isinstance(errors[0], TimeoutError)
    assert store.load_agent_session(result["session_id"], "alice") is None
    with store.connect() as db:
        assert db.execute("SELECT degraded FROM agent_traces WHERE trace_id=?", (result["trace_id"],)).fetchone()[0] == 1


def test_cancel_checkpoint_rolls_back_successful_session_and_trace(tmp_path):
    store = Store(str(tmp_path / "cancel-transaction.db"))
    _, _, item, _, events = generate()
    list(events)
    result = item["result"]

    def cancelled():
        raise InterruptedError()

    with pytest.raises(InterruptedError):
        store.commit_search_stream(result=result, user_id="alice", question="咖啡", history=[],
                                   now=1, checkpoint=cancelled)
    assert store.load_agent_session(result["session_id"], "alice") is None
    with store.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM agent_traces").fetchone()[0] == 0
