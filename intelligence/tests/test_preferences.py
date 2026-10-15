from fastapi.testclient import TestClient
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import UpstreamError, AuthenticationError
from pixelflow_intelligence.common.settings import Settings


class Java:
    def __init__(self, prefs=None):
        self._prefs = prefs or {"user_id": "alice", "personalized": True, "version": 0,
                                "excluded_post_ids": [], "blocked_author_ids": [], "suppressed_topics": []}

    def me(self, token):
        if token != "alice":
            raise AuthenticationError()
        return "alice"

    def preferences(self, token):
        if token != "alice":
            raise AuthenticationError()
        return self._prefs

    def public_candidates(self, token):
        return [dict(post_id="1", author_id="author", title="咖啡", media_type="image",
                     like_count=0, create_time=100, status="PUBLISHED", author_enabled=True)]


def test_get_preferences_proxies_java(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java())) as client:
        r = client.get("/recommendations/me/preferences", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 200
        assert r.headers["cache-control"] == "no-store"
        assert r.json()["user_id"] == "alice" and r.json()["version"] == 0
        assert client.get("/recommendations/me/preferences").status_code == 401


def test_write_endpoints_are_disabled(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java())) as client:
        assert client.put("/recommendations/me/preferences", json={"personalized": False}).status_code == 410
        assert client.put("/recommendations/me/exclusions/1", json={"excluded": True}).status_code == 410
        assert client.put("/recommendations/me/blocked-authors/a", json={"blocked": True}).status_code == 410


def test_preview_filters_using_java_preferences(tmp_path):
    prefs = {"user_id": "alice", "personalized": True, "version": 1,
             "excluded_post_ids": ["1"], "blocked_author_ids": [], "suppressed_topics": []}
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java(prefs))) as client:
        r = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 200 and r.json()["items"] == []


def test_preferences_proxy_returns_401_when_java_revokes_token(tmp_path):
    class RevokingJava(Java):
        def preferences(self, token):
            raise AuthenticationError()
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), RevokingJava())) as client:
        r = client.get("/recommendations/me/preferences", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 401
