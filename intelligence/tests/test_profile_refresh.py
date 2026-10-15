from concurrent.futures import ThreadPoolExecutor
import json
import sqlite3
from contextlib import closing
import pytest
from fastapi.testclient import TestClient
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from test_analytics import NOW, event, page, FakeJava


def test_aging_removes_old_interest_and_does_not_touch_cursor(tmp_path):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event()]), NOW)
    result = store.refresh_profiles(now=NOW + 31 * 86400)
    assert result["updated_users"] == 1
    profile = store.profile("alice")
    assert profile["version"] == 2 and profile["cold_start"]
    assert profile["topics"] == [] and profile["event_counts"] == {}
    def fetch(after):
        assert after == 1
        return page([], after)
    store.sync_page(fetch, NOW + 31 * 86400)
    assert store.refresh_profiles(now=NOW + 31 * 86400)["updated_users"] == 0
    assert store.profile("alice")["version"] == 2


def test_refresh_pagination_scans_fresh_users_and_preserves_new_events(tmp_path):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event(), event(2, "bob"), event(3, "carol")]), NOW)
    # Alice is fresh, but must still advance the scan cursor.
    store.sync_page(lambda _: page([event(4, "alice", occurred_at=NOW + 86400)]), NOW + 86400)
    first = store.refresh_profiles(count=1, now=NOW + 86400)
    assert first["next_cursor"] == "alice" and first["has_more"]
    assert first["updated_users"] == 0
    second = store.refresh_profiles(after=first["next_cursor"], count=1, now=NOW + 86400)
    assert second["next_cursor"] == "bob" and second["updated_users"] == 1
    third = store.refresh_profiles(after="bob", count=1, now=NOW + 86400)
    assert third["next_cursor"] == "carol" and not third["has_more"]
    assert store.profile("alice")["version"] == 2
    assert store.refresh_profiles(after="carol", now=NOW + 86400)["scanned_users"] == 0


def test_parallel_refresh_is_idempotent(tmp_path):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event()]), NOW)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: store.refresh_profiles(now=NOW + 86400), range(4)))
    assert sum(result["updated_users"] for result in results) == 1
    assert store.profile("alice")["version"] == 2


def test_refresh_rolls_back_entire_page(tmp_path, monkeypatch):
    import pixelflow_intelligence.analytics.store as module
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event(), event(2, "bob")]), NOW)
    build = module.build_profile
    def failed(user, *args):
        if user == "bob":
            raise ValueError("broken stored event")
        return build(user, *args)
    monkeypatch.setattr(module, "build_profile", failed)
    with pytest.raises(ValueError):
        store.refresh_profiles(now=NOW + 86400)
    assert store.profile("alice")["version"] == 1
    assert store.profile("bob")["version"] == 1


@pytest.mark.parametrize("change", ["rule", "future"])
def test_rule_change_and_future_snapshot_are_recomputed(tmp_path, change):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event()]), NOW)
    profile = store.profile("alice")
    if change == "rule":
        profile["rule_version"] = "obsolete"
    else:
        profile["generated_at"] = NOW + 1000
    with closing(store.connect()) as db, db:
        db.execute("UPDATE profiles SET document=?", (json.dumps(profile),))
    assert store.refresh_profiles(now=NOW)["updated_users"] == 1
    assert store.profile("alice")["generated_at"] == NOW


def test_http_refresh_auth_limits_and_error_sanitizing(tmp_path, monkeypatch):
    settings = Settings(database=str(tmp_path / "db"), sync_token="s" * 32, export_token="e" * 32)
    upstream = FakeJava()
    with TestClient(create_app(settings, upstream)) as client:
        path = "/internal/profiles/refresh"
        for token in (None, "alice-token", "e" * 32):
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            assert client.post(path, json={}, headers=headers).status_code == 401
        headers = {"Authorization": "Bearer " + "s" * 32}
        for body in ({"count": 101}, {"count": True}, {"max_age_seconds": 1}, {"user_id": "alice"}):
            assert client.post(path, json=body, headers=headers).status_code == 422
        result = client.post(path, json={}, headers=headers)
        assert result.status_code == 200 and result.json()["scanned_users"] == 0
        assert result.headers["cache-control"] == "no-store"
        assert upstream.calls == 0
        def failed(*args):
            raise sqlite3.OperationalError("private database detail")
        monkeypatch.setattr(Store, "refresh_profiles", failed)
        response = client.post(path, json={}, headers=headers)
        assert response.status_code == 503 and "private database" not in response.text


def test_refresh_disabled_without_service_key(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), FakeJava())) as client:
        assert client.post("/internal/profiles/refresh", json={}).status_code == 503
