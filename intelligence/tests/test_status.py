import sqlite3
import pytest
from fastapi.testclient import TestClient
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from test_analytics import NOW, event, page, FakeJava


def test_empty_status_is_unknown_not_caught_up(tmp_path):
    result = Store(str(tmp_path / "db")).status(NOW)
    assert result["event_count"] == result["profile_users"] == 0
    assert result["sync"] is None and result["refresh"] is None
    assert result["oldest_profile_at"] is None and not result["source_bound"]
    assert result["upstream_backlog"] == "unknown"


def test_status_counts_latest_profiles_and_refresh_aging(tmp_path):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event(), event(2, "bob")], more=True), NOW)
    store.sync_page(lambda _: page([event(3)]), NOW + 10)
    status = store.status(NOW + 86400)
    assert status["event_count"] == status["event_cursor"] == 3
    assert status["profile_users"] == 2 and status["profile_snapshots"] == 3
    assert status["stale_profile_users"] == 1
    assert status["oldest_profile_at"] == NOW and status["newest_profile_at"] == NOW + 10
    assert status["sync"] == {"last_success_at": NOW + 10, "last_page_has_more": False}
    store.refresh_profiles(now=NOW + 31 * 86400)
    result = store.status(NOW + 31 * 86400)
    assert result["cold_start_users"] == 2 and result["stale_profile_users"] == 0
    assert result["refresh"]["last_success_at"] == NOW + 31 * 86400
    assert result["upstream_backlog"] == "unknown"
    assert "alice" not in str(result) and "bob" not in str(result)


def test_failed_sync_preserves_last_success_and_restart(tmp_path):
    path = str(tmp_path / "db")
    store = Store(path)
    store.sync_page(lambda _: page([event()]), NOW)
    with pytest.raises(ValueError):
        store.sync_page(lambda _: page([event(2)], cursor=9), NOW + 10)
    result = Store(path).status(NOW + 10)
    assert result["sync"]["last_success_at"] == NOW and result["event_cursor"] == 1


def test_http_status_is_service_only_readonly_and_sanitized(tmp_path, monkeypatch):
    upstream = FakeJava()
    settings = Settings(database=str(tmp_path / "db"), sync_token="s" * 32, export_token="e" * 32)
    with TestClient(create_app(settings, upstream)) as client:
        for token in (None, "alice-token", "e" * 32):
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            response = client.get("/internal/status", headers=headers)
            assert response.status_code == 401 and response.headers["cache-control"] == "no-store"
        headers = {"Authorization": "Bearer " + "s" * 32}
        response = client.get("/internal/status", headers=headers)
        assert response.status_code == 200 and upstream.calls == 0
        assert response.json()["sync"] is None
        def broken(*args):
            raise sqlite3.OperationalError("private database path")
        monkeypatch.setattr(Store, "status", broken)
        response = client.get("/internal/status", headers=headers)
        assert response.status_code == 503 and "private database" not in response.text


def test_refresh_failure_rolls_back_profiles_and_success_marker(tmp_path, monkeypatch):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event()]), NOW)
    store.refresh_profiles(now=NOW)
    def failed(*args):
        raise sqlite3.OperationalError("failed commit preparation")
    monkeypatch.setattr(store, "_record_operation", failed)
    with pytest.raises(sqlite3.Error):
        store.refresh_profiles(now=NOW + 86400)
    assert store.profile("alice")["version"] == 1
    assert store.status(NOW + 86400)["refresh"]["last_success_at"] == NOW
