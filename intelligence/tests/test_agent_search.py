import json
import sqlite3
import time
from contextlib import closing

from fastapi.testclient import TestClient
from pixelflow_intelligence.agents.search import NO_ANSWER, answer_question
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import AuthenticationError
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills import Context

FEED = [
    {"post_id": "1", "title": "苏州小众机位", "content": "金鸡湖夜景拍摄参数分享",
     "author_id": "a1", "author_name": "阿伟", "create_time": 1},
    {"post_id": "2", "title": "城市夜景", "content": "苏州东方之门灯光秀机位",
     "author_id": "a2", "author_name": "小林", "create_time": 2},
    {"post_id": "3", "title": "红烧肉做法", "content": "周末家常菜",
     "author_id": "a3", "author_name": "厨神", "create_time": 3},
]


class Source:
    def me(self, token):
        if token != "alice-token":
            raise AuthenticationError()
        return "alice"

    def public_posts(self, token, pages=3):
        return FEED


def agent(user="alice", **overrides):
    options = dict(user=user, question="问题", session_id="", upstream=Source(),
                   token="alice-token",
                   settings=Settings(database=":memory:", deepseek_api_key=""),
                   store=None, context=Context(user, frozenset({"posts:search"}),
                                               int(time.time()), time.monotonic() + 30))
    options.update(overrides)
    return answer_question(**options)


def test_zero_results_gives_explicit_no_answer_without_llm():
    called = []

    def answerer(**kwargs):
        called.append(kwargs)
        return "不该被调用"

    result = agent(question="完全不相关的话题", answerer=answerer)
    assert result["answer"] == NO_ANSWER
    assert result["sources"] == [] and result["degraded"] == 0
    assert called == []


def test_sources_capped_at_five_and_shape():
    class Many(Source):
        def public_posts(self, token, pages=3):
            return [{"post_id": str(i), "title": "机位攻略", "content": "机位机位机位",
                     "author_id": "a", "author_name": "n", "create_time": i}
                    for i in range(1, 30)]

    seen = {}

    def answerer(**kwargs):
        seen.update(kwargs)
        return "综合回答〔1〕"

    result = agent(question="机位", upstream=Many(), answerer=answerer)
    assert len(result["sources"]) == 5
    assert result["answer"] == "综合回答〔1〕"
    assert len(seen["sources"]) == 5
    assert seen["sources"][0]["excerpt"] == "机位机位机位"


def test_no_deepseek_key_falls_back_to_degraded_listing():
    result = agent(question="苏州")
    assert result["degraded"] == 1
    assert "《苏州小众机位》" in result["answer"]
    assert len(result["sources"]) == 2


def test_llm_failure_degrades_instead_of_erroring():
    from pixelflow_intelligence.skills import SkillError

    def broken(**kwargs):
        raise SkillError("MODEL_UNAVAILABLE", "deepseek_answer")

    result = agent(question="苏州", answerer=broken)
    assert result["degraded"] == 1 and result["sources"]


def test_session_round_trip_via_store(tmp_path):
    store = Store(str(tmp_path / "db"))
    first = agent(question="苏州机位在哪", store=store)
    session_id = first["session_id"]
    assert session_id

    def recorder(**kwargs):
        assert kwargs["history"][-1]["question"] == "苏州机位在哪"
        assert "金鸡湖" in kwargs["sources"][0]["excerpt"] or kwargs["sources"]
        return "追问回答"

    second = agent(question="几点去最好", session_id=session_id, store=store, answerer=recorder)
    assert second["session_id"] == session_id
    session = store.load_agent_session(session_id, "alice")
    assert [turn["question"] for turn in session["turns"]] == ["苏州机位在哪", "几点去最好"]


def test_session_of_other_user_is_not_loaded(tmp_path):
    store = Store(str(tmp_path / "db"))
    first = agent(question="苏州", store=store)
    assert store.load_agent_session(first["session_id"], "mallory") is None


def test_trace_persisted_for_review(tmp_path):
    store = Store(str(tmp_path / "db"))
    result = agent(question="苏州", store=store)
    with closing(sqlite3.connect(str(tmp_path / "db"))) as db:
        row = db.execute("SELECT user_id,question,answer,source_count,degraded,steps,created_at"
                         " FROM agent_traces WHERE trace_id=?", (result["trace_id"],)).fetchone()
    assert row[0] == "alice" and row[1] == "苏州" and row[2] == result["answer"]
    steps = json.loads(row[5])
    assert steps[0]["step"] == "plan"
    assert any(step.get("skill") == "search_posts" for step in steps)


def client(tmp_path):
    return TestClient(create_app(Settings(database=str(tmp_path / "db")), Source()))


def test_endpoint_requires_auth(tmp_path):
    response = client(tmp_path).post("/agent/search", json={"question": "苏州"})
    assert response.status_code == 401


def test_endpoint_returns_answer_sources_trace(tmp_path):
    response = client(tmp_path).post("/agent/search",
                                     headers={"Authorization": "Bearer alice-token"},
                                     json={"question": "苏州"})
    assert response.status_code == 200
    body = response.json()
    assert body["user_id"] == "alice"
    assert body["answer"]
    assert body["trace_id"] and body["session_id"]
    assert {source["post_id"] for source in body["sources"]} == {"1", "2"}
    assert body["sources"][0]["author_name"] == "阿伟"
    assert body["degraded"] in (0, 1)
    assert response.headers["cache-control"] == "no-store"


def test_endpoint_upstream_failure_is_503_not_half_answer(tmp_path):
    class Down(Source):
        def public_posts(self, token, pages=3):
            from pixelflow_intelligence.clients.java import UpstreamError
            raise UpstreamError("java down")

    app = create_app(Settings(database=":memory:"), Down())
    response = TestClient(app).post("/agent/search",
                                    headers={"Authorization": "Bearer alice-token"},
                                    json={"question": "苏州"})
    assert response.status_code == 503


def test_endpoint_blank_question_rejected(tmp_path):
    response = client(tmp_path).post("/agent/search",
                                     headers={"Authorization": "Bearer alice-token"},
                                     json={"question": "   "})
    assert response.status_code == 422
