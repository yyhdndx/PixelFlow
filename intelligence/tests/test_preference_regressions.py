import copy
import time
import pytest
from pydantic import ValidationError
from pixelflow_intelligence.analytics.profile import build_profile
from pixelflow_intelligence.analytics.store import Store, Event
from pixelflow_intelligence.recommendation.preview import preview
from pixelflow_intelligence.clients.java import JavaClient, UpstreamError
from pixelflow_intelligence.common.settings import Settings
from test_analytics import event, page, NOW
from test_preferences import Java


@pytest.mark.parametrize("attribution", [None, "rec-1"])
def test_v6_export_field_imports_and_refreshes(tmp_path, attribution):
    store = Store(str(tmp_path / "db"))
    item = event(recommendation_id=attribution)
    assert store.sync_page(lambda _: page([item]), NOW)["imported"] == 1
    assert store.profile("alice")["topics"][0]["score"] == 1
    assert store.refresh_profiles(now=NOW + 86400)["updated_users"] == 1


def test_legacy_event_remains_valid_but_unknown_fields_are_rejected():
    assert Event.model_validate(event()).recommendation_id is None
    with pytest.raises(ValidationError):
        Event.model_validate(event(unexpected_column="not in contract"))


def test_suppression_removes_topic_weight_without_mutating_profile_and_undo_restores():
    snapshot = build_profile("alice", [], 1, int(time.time()))
    snapshot.update(cold_start=False, topics=[{"name": "美食", "score": 3.0, "share": 1.0}])
    original = copy.deepcopy(snapshot)
    source = Java()
    source._prefs["suppressed_topics"] = ["美食"]
    ranked = preview("alice", snapshot, source, "alice", lambda _: source.preferences("alice"))
    assert ranked["items"][0]["components"]["topic"] == 0
    assert ranked["reason"] == "empty_preferences"
    assert snapshot == original
    source._prefs["suppressed_topics"] = []
    ranked = preview("alice", snapshot, source, "alice", lambda _: source.preferences("alice"))
    assert ranked["items"][0]["components"]["topic"] == 1


@pytest.mark.parametrize("phase", [1, 2])
def test_owner_checked_on_both_preference_reads(phase):
    source = Java()
    calls = 0
    def read(user):
        nonlocal calls
        calls += 1
        return {**source._prefs, "user_id": "bob" if calls == phase else "alice"}
    with pytest.raises(UpstreamError):
        preview("alice", None, source, "alice", read)


@pytest.mark.parametrize("changes", [{"version": True}, {"version": -1}, {"excluded_post_ids": [{}]},
                                     {"suppressed_topics": ["unknown"]}, {"user_id": None}])
def test_preference_adapter_strictly_rejects_malformed_snapshots(changes):
    source = JavaClient(Settings())
    source._get = lambda *args: {**Java()._prefs, **changes}
    with pytest.raises(UpstreamError):
        source.preferences("token")
