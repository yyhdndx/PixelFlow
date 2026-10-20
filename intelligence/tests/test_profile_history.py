import json
import sqlite3
from contextlib import closing
import pytest
from fastapi.testclient import TestClient
from pixelflow_intelligence.analytics.profile import build_profile
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from test_analytics import event, NOW, FakeJava

MAX_BEFORE_VERSION = 9223372036854775807
ITEM_FIELDS = {"version", "rule_version", "generated_at", "window_days", "timezone",
               "cold_start", "topics", "media", "event_counts", "explanation"}


@pytest.fixture
def store(tmp_path):
    return Store(str(tmp_path / "analytics.db"))


def snapshot(user, version, now=NOW):
    return build_profile(user, [event(seq=version, user=user, occurred_at=now)], version, now)


def insert_snapshot(store, user, version, document=None, now=NOW):
    document = document if document is not None else snapshot(user, version, now)
    with closing(store.connect()) as db, db:
        db.execute("INSERT INTO profiles VALUES(?,?,?)",
                   (user, version, json.dumps(document, ensure_ascii=False) if not isinstance(document, str) else document))


def snapshot_count(store):
    with closing(store.connect()) as db:
        return db.execute("SELECT COUNT(*) FROM profiles").fetchone()[0]


def test_store_empty_history(store):
    page = store.profile_history("alice", 20)
    assert page == {"user_id": "alice", "items": [], "has_more": False, "next_cursor": None}


def test_store_numeric_descending_and_cursor_paging(store):
    for version in (1, 2, 10, 100):
        insert_snapshot(store, "alice", version)
    first = store.profile_history("alice", 2)
    assert [item["version"] for item in first["items"]] == [100, 10]
    assert first["has_more"] is True
    assert first["next_cursor"] == "10"
    second = store.profile_history("alice", 2, int(first["next_cursor"]))
    assert [item["version"] for item in second["items"]] == [2, 1]
    assert second["has_more"] is False
    assert second["next_cursor"] is None


def test_store_rejects_invalid_arguments(store):
    for args in (("", 20), ("x" * 37, 20), ("alice", 0), ("alice", 51),
                 ("alice", True), ("alice", 20, 0), ("alice", 20, -1), ("alice", 20, 1.5)):
        with pytest.raises(ValueError):
            store.profile_history(*args)


def test_store_exact_count_page_has_no_more(store):
    for version in (1, 2):
        insert_snapshot(store, "alice", version)
    page = store.profile_history("alice", 2)
    assert page["has_more"] is False and page["next_cursor"] is None


def test_store_first_page_keeps_max_version_without_cursor(store):
    insert_snapshot(store, "alice", MAX_BEFORE_VERSION)
    page = store.profile_history("alice", 20)
    assert [item["version"] for item in page["items"]] == [MAX_BEFORE_VERSION]


@pytest.fixture
def client(store):
    settings = Settings(database=store.database, sync_token="s" * 32)
    with TestClient(create_app(settings, FakeJava())) as test_client:
        yield test_client


def seed_versions(store, user="alice", versions=(1, 2, 10, 100)):
    for version in versions:
        insert_snapshot(store, user, version)


def test_http_empty_history_and_default_count(client, store):
    response = client.get("/profiles/me/history", headers={"Authorization": "Bearer alice-token"})
    assert response.status_code == 200
    assert response.json() == {"user_id": "alice", "items": [], "has_more": False, "next_cursor": None}
    assert response.headers["cache-control"] == "no-store"


def test_http_paging_traverses_all_versions_without_overlap(client, store):
    seed_versions(store)
    headers = {"Authorization": "Bearer alice-token"}
    seen = []
    cursor = None
    for _ in range(4):
        params = {"count": 2} if cursor is None else {"count": 2, "before_version": cursor}
        page = client.get("/profiles/me/history", params=params, headers=headers).json()
        seen += [item["version"] for item in page["items"]]
        if not page["has_more"]:
            assert page["next_cursor"] is None
            break
        cursor = page["next_cursor"]
    assert seen == [100, 10, 2, 1]


def test_http_item_whitelist_and_contents(client, store):
    seed_versions(store)
    response = client.get("/profiles/me/history", headers={"Authorization": "Bearer alice-token"})
    item = response.json()["items"][0]
    assert set(item) == ITEM_FIELDS
    assert item["version"] == 100
    assert item["rule_version"] == "profile-rules-v1"
    assert item["generated_at"] == NOW
    assert item["timezone"] == "UTC"
    assert item["cold_start"] is False
    assert item["topics"][0]["name"] == "美食"
    assert item["event_counts"] == {"CLICK": 1}
    assert "authors" not in item and "active_hours" not in item


def test_http_old_rule_version_and_stored_extra_fields_are_shaped_not_trusted(client, store):
    document = snapshot("alice", 1)
    document["rule_version"] = "profile-rules-v0"
    document["authors"] = [{"name": "someone", "score": 1, "share": 1}]
    document["active_hours"] = {"0": 1}
    document["internal_note"] = "do not leak"
    insert_snapshot(store, "alice", 1, document)
    response = client.get("/profiles/me/history", headers={"Authorization": "Bearer alice-token"})
    assert response.status_code == 200
    item = response.json()["items"][0]
    assert item["rule_version"] == "profile-rules-v0"
    assert "authors" not in item and "active_hours" not in item and "internal_note" not in item


def test_http_zero_counts_and_cold_start_snapshot(client, store):
    document = build_profile("alice", [], 1, NOW)
    insert_snapshot(store, "alice", 1, document)
    response = client.get("/profiles/me/history", headers={"Authorization": "Bearer alice-token"})
    item = response.json()["items"][0]
    assert item["cold_start"] is True
    assert item["topics"] == [] and item["media"] == [] and item["event_counts"] == {}


def test_http_invalid_pagination_parameters(client, store):
    headers = {"Authorization": "Bearer alice-token"}
    for params in ({"count": "0"}, {"count": "51"}, {"count": ""}, {"count": "abc"},
                   {"count": "1.5"}, {"count": "-1"}, {"count": "２０"},
                   {"before_version": "0"}, {"before_version": "-1"},
                   {"before_version": ""}, {"before_version": "1.5"},
                   {"before_version": "abc"}, {"before_version": "9223372036854775808"}):
        response = client.get("/profiles/me/history", params=params, headers=headers)
        assert response.status_code == 422, params
        assert response.headers["cache-control"] == "no-store"
    for params in ({"count": "1"}, {"count": "50"},
                   {"before_version": str(MAX_BEFORE_VERSION)}):
        assert client.get("/profiles/me/history", params=params,
                          headers=headers).status_code == 200
    duplicate = client.get("/profiles/me/history?count=2&count=3", headers=headers)
    assert duplicate.status_code == 422
    duplicate_cursor = client.get(
        "/profiles/me/history?before_version=10&before_version=20", headers=headers)
    assert duplicate_cursor.status_code == 422


def test_http_large_before_version_is_valid(client, store):
    seed_versions(store)
    response = client.get("/profiles/me/history",
                          params={"before_version": str(MAX_BEFORE_VERSION)},
                          headers={"Authorization": "Bearer alice-token"})
    assert [item["version"] for item in response.json()["items"]] == [100, 10, 2, 1]


@pytest.mark.parametrize("parameter", ["count", "before_version"])
def test_http_very_long_decimal_parameters_are_bounded(client, parameter):
    headers = {"Authorization": "Bearer alice-token"}
    for value in ("9" * 5000, "0" * 5000):
        response = client.get("/profiles/me/history", params={parameter: value}, headers=headers)
        assert response.status_code == 422
        assert response.headers["cache-control"] == "no-store"
    response = client.get("/profiles/me/history", params={parameter: "0" * 5000 + "1"}, headers=headers)
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"


def test_http_identity_scope_and_forged_user_id(client, store):
    seed_versions(store)
    seed_versions(store, user="bob", versions=(1, 2))
    headers = {"Authorization": "Bearer bob-token"}
    response = client.get("/profiles/me/history", params={"user_id": "alice"}, headers=headers)
    assert response.status_code == 200
    assert response.json()["user_id"] == "bob"
    assert [item["version"] for item in response.json()["items"]] == [2, 1]
    assert client.get("/profiles/me/history").status_code == 401
    assert client.get("/profiles/me/history", headers={
        "Authorization": "Bearer " + "s" * 32}).status_code == 401
    assert client.get("/profiles/me/history", headers={
        "Authorization": "Bearer nope"}).status_code == 401


def test_http_get_does_not_mutate_snapshots_or_cursor(client, store):
    seed_versions(store)
    before_rows = store.status()
    client.get("/profiles/me/history", headers={"Authorization": "Bearer alice-token"})
    client.get("/profiles/me/history", params={"count": 1},
               headers={"Authorization": "Bearer alice-token"})
    assert store.status() == before_rows
    assert snapshot_count(store) == 4


def test_http_new_higher_version_does_not_disturb_cursor_paging(client, store):
    insert_snapshot(store, "alice", 10)
    insert_snapshot(store, "alice", 2)
    headers = {"Authorization": "Bearer alice-token"}
    first = client.get("/profiles/me/history", params={"count": 1}, headers=headers).json()
    assert first["items"][0]["version"] == 10
    insert_snapshot(store, "alice", 11)
    second = client.get("/profiles/me/history",
                        params={"count": 1, "before_version": first["next_cursor"]},
                        headers=headers).json()
    assert [item["version"] for item in second["items"]] == [2]
    assert second["has_more"] is False


def corrupt_variants():
    base = snapshot("alice", 5)
    cases = {
        "not-json": "{not json",
        "json-scalar": "7",
        "json-array": "[1,2]",
        "missing-field": {k: v for k, v in base.items() if k != "explanation"},
        "version-bool": {**base, "version": True},
        "version-string": {**base, "version": "5"},
        "generated-at-string": {**base, "generated_at": str(NOW)},
        "cold-start-int": {**base, "cold_start": 1},
        "negative-count": {**base, "event_counts": {"CLICK": -1}},
        "count-bool": {**base, "event_counts": {"CLICK": True}},
        "topic-score-string": {**base, "topics": [{"name": "美食", "score": "6", "share": 1.0}]},
        "topic-score-nan": {**base, "topics": [{"name": "美食", "score": float("nan"), "share": 1.0}]},
        "topic-score-inf": {**base, "topics": [{"name": "美食", "score": float("inf"), "share": 1.0}]},
        "topic-score-overflow": {**base, "topics": [{"name": "美食", "score": 10**400, "share": 1.0}]},
        "topic-share-overflow": {**base, "topics": [{"name": "美食", "score": 1.0, "share": 10**400}]},
        "media-score-overflow": {**base, "media": [{"name": "image", "score": 10**400, "share": 1.0}]},
        "user-id-mismatch": snapshot("bob", 5),
        "version-mismatch": snapshot("alice", 4),
    }
    return cases


@pytest.mark.parametrize("name", sorted(corrupt_variants()))
def test_http_corrupt_snapshot_fails_whole_page_sanitized(client, store, name):
    document = corrupt_variants()[name]
    insert_snapshot(store, "alice", 5, document)
    response = client.get("/profiles/me/history", headers={"Authorization": "Bearer alice-token"})
    assert response.status_code == 503
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == {"detail": "Profile history unavailable"}
    assert "someone" not in response.text


def test_http_corrupt_snapshot_outside_page_does_not_block(client, store):
    insert_snapshot(store, "alice", 100, "{not json")
    insert_snapshot(store, "alice", 10)
    insert_snapshot(store, "alice", 1)
    response = client.get("/profiles/me/history",
                          params={"count": 1, "before_version": "100"},
                          headers={"Authorization": "Bearer alice-token"})
    assert response.status_code == 200
    assert [item["version"] for item in response.json()["items"]] == [10]


def test_http_corrupt_lookahead_fails_but_beyond_limit_does_not(client, store):
    insert_snapshot(store, "alice", 3)
    insert_snapshot(store, "alice", 2, "{broken")
    headers = {"Authorization": "Bearer alice-token"}
    response = client.get("/profiles/me/history?count=1", headers=headers)
    assert response.status_code == 503
    assert response.json() == {"detail": "Profile history unavailable"}
    assert response.headers["cache-control"] == "no-store"
    insert_snapshot(store, "alice", 4)
    response = client.get("/profiles/me/history?count=1", headers=headers)
    assert response.status_code == 200
    assert response.json()["has_more"] is True
    assert response.json()["next_cursor"] == "4"


def test_http_java_identity_unavailable(tmp_path):
    from pixelflow_intelligence.clients.java import UpstreamError

    class DownJava:
        def me(self, token):
            raise UpstreamError()

    settings = Settings(database=str(tmp_path / "db"), sync_token="s" * 32)
    with TestClient(create_app(settings, DownJava())) as client:
        response = client.get("/profiles/me/history",
                              headers={"Authorization": "Bearer alice-token"})
    assert response.status_code == 503
    assert response.headers["cache-control"] == "no-store"


def test_http_openapi_documents_response_model_and_pagination(tmp_path):
    settings = Settings(database=str(tmp_path / "db"), sync_token="s" * 32)
    with TestClient(create_app(settings, FakeJava())) as client:
        schema = client.get("/openapi.json").json()
    operation = schema["paths"]["/profiles/me/history"]["get"]
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"] \
        .endswith("ProfileHistoryPage")
    parameter_names = {parameter["name"] for parameter in operation["parameters"]}
    assert {"count", "before_version"} <= parameter_names
    assert "ProfileHistoryPage" in schema["components"]["schemas"]
