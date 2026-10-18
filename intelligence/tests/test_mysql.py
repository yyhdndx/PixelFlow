"""Opt-in real MySQL tests. MYSQL_TEST_ANALYTICS_DB must name a disposable database."""
import os
from contextlib import closing
from concurrent.futures import ThreadPoolExecutor
import pytest
import pymysql
from fastapi.testclient import TestClient
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.migrate_mysql import migrate
from test_analytics import event, page, NOW, FakeJava

pytestmark = pytest.mark.skipif(not os.getenv("MYSQL_TEST_ANALYTICS_DB"), reason="MySQL test database not configured")


@pytest.fixture
def store():
    target = os.environ["MYSQL_TEST_ANALYTICS_DB"]
    # Guard accidental use of a shared business database.
    from urllib.parse import urlsplit
    assert urlsplit(target).path.endswith("_test")
    store = Store(target)
    with closing(store.connect()) as db, db:
        for table in ("events", "profiles", "maintenance_state"):
            db.execute(f"DELETE FROM {table}")
        db.execute("UPDATE checkpoint SET source_id=NULL,`cursor`=0 WHERE id=1")
    return store


def test_sync_refresh_and_concurrent_versions(store):
    store.sync_page(lambda _: page([event(title="咖啡☕😀")]), NOW)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: store.refresh_profiles(now=NOW + 86400), range(4)))
    assert sum(r["updated_users"] for r in results) == 1
    assert store.profile("alice")["version"] == 2
    assert store.status(NOW)["event_cursor"] == 1
    reopened = Store(store.database)
    assert reopened.sync_page(lambda cursor: page([], cursor), NOW)["imported"] == 0
    assert reopened.status()["profile_snapshots"] == 2


def test_duplicate_page_rolls_back_and_can_retry(store):
    with pytest.raises(pymysql.Error):
        store.sync_page(lambda _: page([event(), event(2, event_id="event-1")]), NOW)
    assert store.status()["event_cursor"] == 0
    assert store.status()["event_count"] == 0
    assert store.sync_page(lambda _: page([event()]), NOW)["imported"] == 1


def test_sqlite_import_preserves_cursor_documents_and_refuses_overwrite(store, tmp_path):
    source = tmp_path / "legacy.sqlite3"
    legacy = Store(str(source))
    legacy.sync_page(lambda _: page([event()]), NOW)
    counts = migrate(source, store.database)
    assert counts["events"] == 1
    assert store.profile("alice") == legacy.profile("alice")
    assert store.status(NOW) == legacy.status(NOW)
    with pytest.raises(ValueError):
        migrate(source, store.database)
    assert legacy.status(NOW)["event_cursor"] == 1


def test_mysql_errors_are_sanitized(store, monkeypatch):
    settings = Settings(database=store.database, sync_token="s" * 32)
    app = create_app(settings, FakeJava())
    def failed(self, now=None):
        raise pymysql.OperationalError("secret database detail")
    monkeypatch.setattr(Store, "status", failed)
    with TestClient(app) as client:
        response = client.get("/internal/status", headers={"Authorization": "Bearer " + "s" * 32})
    assert response.status_code == 503
    assert "secret" not in response.text
