import time

import pytest
from fastapi.testclient import TestClient

from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings

TOKEN = "internal-test-token-1234567890123456789"


def make_store(tmp_path):
    return Store(str(tmp_path / "traces.db"))


def save(store, trace_id, question="风景", created_at=None, **overrides):
    options = dict(trace_id=trace_id, user_id="user-1", question=question,
                   answer="回答", source_count=1, degraded=0,
                   steps=[{"step": "plan"}], created_at=created_at or int(time.time()))
    options.update(overrides)
    store.save_agent_trace(**options)


def rich_steps():
    return [{"step": "plan", "decision": {"intent": "community_search"}},
            {"step": "retrieve", "skill": "search_posts", "query": "夜景",
             "candidate_count": 20, "selected_ids": ["855", "893"],
             "sources": [{"post_id": "855", "title": "夜景摄影"},
                         {"post_id": "893", "title": "城市灯光"}]},
            {"step": "synthesize", "provider": "deepseek", "ok": True,
             "latency_ms": 1800, "model": "deepseek-chat"}]


def test_page_orders_newest_first_and_paginates(tmp_path):
    store = make_store(tmp_path)
    for index in range(3):
        save(store, f"trace-{index}", question=f"问题{index}", created_at=1000 + index)
    page = store.agent_trace_page(2)
    assert [row["question"] for row in page["traces"]] == ["问题2", "问题1"]
    assert page["next_cursor"] is not None
    second = store.agent_trace_page(2, int(page["next_cursor"]))
    assert [row["question"] for row in second["traces"]] == ["问题0"]
    assert second["next_cursor"] is None


def test_page_validates_arguments(tmp_path):
    store = make_store(tmp_path)
    with pytest.raises(ValueError):
        store.agent_trace_page(0)
    with pytest.raises(ValueError):
        store.agent_trace_page(51)
    with pytest.raises(ValueError):
        store.agent_trace_page(10, 0)


def test_page_exposes_latency_and_provider(tmp_path):
    store = make_store(tmp_path)
    save(store, "trace-latency", latency_ms=2400, provider="deepseek", session_id="s-1")
    row = store.agent_trace_page(1)["traces"][0]
    assert row["latency_ms"] == 2400 and row["provider"] == "deepseek"


def test_detail_returns_retrieval_llm_and_sources(tmp_path):
    store = make_store(tmp_path)
    save(store, "trace-detail", steps=rich_steps(), latency_ms=2400,
         provider="deepseek", session_id="s-1")
    detail = store.agent_trace_detail("trace-detail")
    assert detail["session_id"] == "s-1"
    assert detail["retrieval"] == {"query": "夜景", "candidate_count": 20,
                                   "selected_ids": ["855", "893"]}
    assert detail["llm"] == {"model": "deepseek-chat", "latency_ms": 1800}
    assert detail["sources"] == [{"post_id": "855", "title": "夜景摄影"},
                                 {"post_id": "893", "title": "城市灯光"}]


def test_detail_missing_returns_none_and_bad_input_rejected(tmp_path):
    store = make_store(tmp_path)
    assert store.agent_trace_detail("absent") is None
    with pytest.raises(ValueError):
        store.agent_trace_detail("x" * 37)


def test_old_trace_without_rich_steps_still_reads(tmp_path):
    store = make_store(tmp_path)
    save(store, "trace-legacy", steps=[{"step": "plan"}], degraded=1,
         degraded_reason="模型调用失败，规则降级")
    detail = store.agent_trace_detail("trace-legacy")
    assert detail["degraded"] == 1
    assert detail["retrieval"]["selected_ids"] == []
    assert detail["llm"]["model"] == ""


def make_client(tmp_path):
    settings = Settings(database=str(tmp_path / "api.db"), sync_token=TOKEN)
    return TestClient(create_app(settings))


def test_internal_traces_require_service_token(tmp_path):
    client = make_client(tmp_path)
    assert client.get("/internal/traces").status_code == 401
    assert client.get("/internal/traces",
                      headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_internal_traces_end_to_end(tmp_path):
    store = Store(str(tmp_path / "api.db"))
    save(store, "trace-api", steps=rich_steps(), latency_ms=2400, provider="deepseek")
    client = make_client(tmp_path)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    listing = client.get("/internal/traces?count=10", headers=headers)
    assert listing.status_code == 200
    body = listing.json()
    assert body["next_cursor"] is None
    assert body["traces"][0]["trace_id"] == "trace-api"
    detail = client.get("/internal/traces/trace-api", headers=headers)
    assert detail.status_code == 200
    assert detail.json()["trace"]["retrieval"]["query"] == "夜景"
    assert client.get("/internal/traces/missing", headers=headers).status_code == 404
    assert client.get("/internal/traces?count=99", headers=headers).status_code == 400
    assert client.get("/internal/traces?cursor=abc", headers=headers).status_code == 400
