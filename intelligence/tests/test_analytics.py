import copy
import sqlite3
import pytest
from fastapi.testclient import TestClient
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.analytics.profile import build_profile
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import AuthenticationError, UpstreamError

NOW = 1791000000


def event(seq=1, user="alice", kind="CLICK", **extra):
    data = dict(seq=seq, event_id=f"event-{seq}", user_id=user, post_id=1,
                event_type=kind, occurred_at=NOW, received_at=NOW,
                duration_ms=None, scene="DETAIL", source="CLIENT",
                author_id="author-1", has_video=False, title="咖啡日常", eligible=True)
    data.update(extra)
    return data


def page(items, cursor=None, source="source-1", more=False):
    return dict(source_id=source, items=items, next_cursor=cursor if cursor is not None else items[-1]["seq"],
                has_more=more)


@pytest.fixture
def store(tmp_path):
    return Store(str(tmp_path / "analytics.db"))


def test_incremental_restart_and_empty_page_are_idempotent(store):
    assert store.sync_page(lambda after: page([event()]), NOW)["imported"] == 1
    reopened = Store(store.database)
    def empty(after):
        assert after == 1
        return page([], after)
    assert reopened.sync_page(empty, NOW)["imported"] == 0
    assert reopened.profile("alice")["version"] == 1
    assert reopened.profile("alice")["topics"][0]["name"] == "美食"
    assert reopened.profile("nobody")["cold_start"]


def test_same_event_id_on_two_users_remains_separate(store):
    store.sync_page(lambda after: page([event(), event(2, "bob", event_id="event-1", title="键盘特写")]), NOW)
    assert store.profile("alice")["topics"][0]["name"] == "美食"
    assert store.profile("bob")["topics"][0]["name"] == "数码"


@pytest.mark.parametrize("bad", [
    page([event(2), event(1)], 1),
    page([event()], 9),
    page([], 0, more=True),
    page([event(duration_ms=-1)]),
    page([event(), event(2, event_id="event-1")]),
])
def test_bad_pages_roll_back_events_profiles_and_cursor(store, bad):
    with pytest.raises((ValueError, sqlite3.Error)):
        store.sync_page(lambda after: bad, NOW)
    assert store.profile("alice")["version"] == 0
    def valid(after):
        assert after == 0
        return page([event()])
    assert store.sync_page(valid, NOW)["imported"] == 1


def test_changed_source_and_upstream_failure_do_not_advance(store):
    store.sync_page(lambda after: page([event()]), NOW)
    with pytest.raises(ValueError):
        store.sync_page(lambda after: page([event(2)], source="other-db"), NOW)
    def failed(after):
        assert after == 1
        raise UpstreamError()
    with pytest.raises(UpstreamError):
        store.sync_page(failed, NOW)
    assert store.profile("alice")["version"] == 1


def test_server_like_state_not_client_claims_and_unlike_removes_weight(store):
    store.sync_page(lambda after: page([event(kind="LIKE"), event(2, kind="LIKE", source="SERVER")]), NOW)
    assert store.profile("alice")["topics"][0]["score"] == 3
    store.sync_page(lambda after: page([event(3, kind="UNLIKE", source="SERVER")]), NOW)
    assert store.profile("alice")["cold_start"]
    assert store.profile("alice")["version"] == 2


def test_click_and_dwell_caps_and_unknown_titles():
    events = [event(), event(2), event(3, kind="DWELL", duration_ms=120000),
              event(4, kind="DWELL", duration_ms=120000), event(5, kind="EXPOSURE")]
    result = build_profile("alice", events, 1, NOW)
    assert result["topics"][0]["score"] == 5
    assert sum(result["active_hours"].values()) == 5
    assert build_profile("alice", [event(title="未分类")], 1, NOW)["topics"][0]["name"] == "其他"


def test_ineligible_and_old_events_do_not_add_interest():
    result = build_profile("alice", [event(eligible=False), event(2, occurred_at=NOW-31*86400)], 1, NOW)
    assert result["cold_start"]


class FakeJava:
    def __init__(self):
        self.calls = 0
    def events(self, after):
        self.calls += 1
        return page([event()] if after == 0 else [], 1)
    def me(self, token):
        if token not in {"alice-token", "bob-token"}:
            raise AuthenticationError()
        return token.split("-")[0]


def test_http_auth_scope_and_private_profile(tmp_path):
    settings = Settings(database=str(tmp_path/"db"), export_token="e"*32, sync_token="s"*32)
    upstream = FakeJava()
    with TestClient(create_app(settings, upstream)) as client:
        assert client.post("/internal/sync").status_code == 401
        assert client.post("/internal/sync", headers={"Authorization": "Bearer "+"e"*32}).status_code == 401
        assert upstream.calls == 0
        assert client.post("/internal/sync", headers={"Authorization": "Bearer "+"s"*32}).status_code == 200
        assert client.get("/profiles/me").status_code == 401
        assert client.get("/profiles/me", headers={"Authorization": "Bearer "+"s"*32}).status_code == 401
        alice = client.get("/profiles/me", headers={"Authorization": "Bearer alice-token"})
        assert alice.status_code == 200
        assert alice.json()["user_id"] == "alice"
        assert alice.headers["cache-control"] == "no-store"
        assert client.get("/profiles/me", headers={"Authorization": "Bearer bob-token"}).json()["version"] == 0
        assert client.get("/profiles/alice", headers={"Authorization": "Bearer bob-token"}).status_code == 404


def test_disabled_sync_and_config_validation(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path/"db")), FakeJava())) as client:
        assert client.post("/internal/sync").status_code == 503
    with pytest.raises(ValueError):
        Settings(export_token="x"*32, sync_token="x"*32)
    with pytest.raises(ValueError):
        Settings(java_url="http://name:password@localhost")
