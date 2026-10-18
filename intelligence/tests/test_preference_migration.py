from contextlib import closing
import json
import sqlite3
import httpx
import pytest
from pixelflow_intelligence.migrate_preferences import read_legacy, run, MigrationError


def legacy(tmp_path, authors=True):
    path = tmp_path / "legacy.db"
    with closing(sqlite3.connect(path)) as db, db:
        db.executescript("""CREATE TABLE recommendation_preferences(user_id TEXT PRIMARY KEY,personalized INTEGER,version INTEGER);
            CREATE TABLE recommendation_exclusions(user_id TEXT,post_id TEXT);
            INSERT INTO recommendation_preferences VALUES('alice',0,3);
            INSERT INTO recommendation_exclusions VALUES('alice','123');
            INSERT INTO recommendation_exclusions VALUES('bob','999');""")
        if authors:
            db.executescript("""CREATE TABLE recommendation_blocked_authors(user_id TEXT,author_id TEXT);
                INSERT INTO recommendation_blocked_authors VALUES('alice','author');""")
    return path


def defaults(**changes):
    return dict(user_id="alice", personalized=True, version=0, excluded_post_ids=[],
                blocked_author_ids=[], suppressed_topics=[], **changes)


def client(current, calls, import_status=200):
    def handler(request):
        calls.append(request)
        if request.url.path == "/api/users/me":
            return httpx.Response(200, json={"id": "alice", "enabled": True})
        if request.method == "GET":
            return httpx.Response(200, json=current)
        body = json.loads(request.content)
        assert body["source_user_id"] == "alice"
        return httpx.Response(import_status, json=dict(user_id="alice", version=1, suppressed_topics=[],
            personalized=body["personalized"], excluded_post_ids=body["excluded_post_ids"],
            blocked_author_ids=body["blocked_author_ids"]))
    return httpx.Client(base_url="http://localhost", transport=httpx.MockTransport(handler))


@pytest.mark.parametrize("authors", [True, False])
def test_legacy_read_is_account_scoped_and_does_not_modify_file(tmp_path, authors):
    path = legacy(tmp_path, authors)
    before = path.read_bytes()
    result = read_legacy(path, "alice")
    assert result["personalized"] is False and result["excluded_post_ids"] == ["123"]
    assert result["blocked_author_ids"] == (["author"] if authors else [])
    assert path.read_bytes() == before
    with pytest.raises(MigrationError, match="NO_LEGACY_PREFERENCES"):
        read_legacy(path, "nobody")


def test_dry_run_never_writes_and_apply_is_explicit(tmp_path):
    path, calls = legacy(tmp_path), []
    with client(defaults(), calls) as upstream:
        assert run(upstream, path)["status"] == "ready_no_changes"
        assert all(req.method == "GET" for req in calls)
        assert run(upstream, path, apply=True)["status"] == "imported"
    assert sum(req.method == "POST" for req in calls) == 1


def test_conflict_is_not_merged_or_overwritten(tmp_path):
    path, calls = legacy(tmp_path), []
    current = {**defaults(), "version": 2, "excluded_post_ids": ["456"]}
    with client(current, calls) as upstream, pytest.raises(MigrationError, match="PREFERENCE_MIGRATION_CONFLICT"):
        run(upstream, path, apply=True)
    assert all(req.method == "GET" for req in calls)


def test_replay_matches_without_writing_and_race_conflict_is_reported(tmp_path):
    path, calls = legacy(tmp_path), []
    current = {**read_legacy(path, "alice"), "version": 1}
    with client(current, calls) as upstream:
        assert run(upstream, path, apply=True)["status"] == "already_matches"
    assert all(req.method == "GET" for req in calls)
    with client(defaults(), [], import_status=409) as upstream, pytest.raises(MigrationError, match="HTTP_409"):
        run(upstream, path, apply=True)


def test_missing_database_is_not_created(tmp_path):
    path = tmp_path / "missing.db"
    with pytest.raises(MigrationError, match="LEGACY_DATABASE_NOT_FOUND"):
        read_legacy(path, "alice")
    assert not path.exists()


def test_invalid_legacy_data_fails_before_http_write(tmp_path):
    path = legacy(tmp_path)
    with closing(sqlite3.connect(path)) as db, db:
        db.execute("INSERT INTO recommendation_exclusions VALUES('alice','bad-id')")
    calls = []
    with client(defaults(), calls) as upstream, pytest.raises(MigrationError, match="INVALID_LEGACY_PREFERENCES"):
        run(upstream, path, apply=True)
    assert all(req.method == "GET" for req in calls)
