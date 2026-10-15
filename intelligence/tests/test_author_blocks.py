from fastapi.testclient import TestClient
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from test_preferences import Java


def test_author_block_via_java_preferences_filters_preview(tmp_path):
    prefs = {"user_id": "alice", "personalized": True, "version": 1,
             "excluded_post_ids": [], "blocked_author_ids": ["author"], "suppressed_topics": []}
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java(prefs))) as client:
        r = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 200 and r.json()["items"] == []
    prefs["blocked_author_ids"] = []
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java(prefs))) as client:
        r = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice"})
        assert len(r.json()["items"]) == 1
