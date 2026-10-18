import copy
import time
import pytest
from fastapi.testclient import TestClient
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.analytics.profile import build_profile
from pixelflow_intelligence.clients.java import JavaClient, UpstreamError, AuthenticationError
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.recommendation.preview import preview


def candidate():
    return dict(post_id="1", author_id="author", title="咖啡", media_type="image",
                like_count=1, create_time=int(time.time()), status="PUBLISHED", author_enabled=True)


class Source:
    def __init__(self, change=None):
        self.row = candidate()
        self.calls = 0
        self.change = change

    def me(self, token):
        if token != "alice-token":
            raise AuthenticationError()
        return "alice"

    def preferences(self, token):
        return {"user_id": "alice", "personalized": True, "version": 0,
                "excluded_post_ids": [], "blocked_author_ids": [], "suppressed_topics": []}

    def public_candidates(self, token):
        assert token == "alice-token"
        self.calls += 1
        if self.calls == 2 and self.change == "failure":
            raise UpstreamError("private upstream diagnostic")
        if self.calls == 2 and self.change == "removed":
            return []
        row = copy.deepcopy(self.row)
        if self.calls == 2 and self.change == "edited":
            row["title"] = "键盘"
        return [row]


@pytest.mark.parametrize("change,expected", [(None, 1), ("removed", 0), ("edited", 0)])
def test_preview_rechecks_and_never_fills_with_unchecked_content(change, expected):
    upstream = Source(change)
    result = preview("alice", build_profile("alice", [], 0, int(time.time())), upstream, "alice-token",
                     lambda user: {"user_id": user, "personalized": True, "version": 0,
                                   "excluded_post_ids": [], "blocked_author_ids": [], "suppressed_topics": []})
    assert len(result["items"]) == expected
    assert result["removed_on_recheck"] == 1 - expected
    assert upstream.calls == 2
    assert result["strategy"] == "popular"
    assert len(result["steps"]) == 3
    assert {step["trace_id"] for step in result["steps"]} == {result["trace_id"]}


def test_http_preview_uses_verified_identity_and_no_cache(tmp_path):
    upstream = Source()
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), upstream)) as client:
        path = "/recommendations/me/preview"
        assert client.get(path).status_code == 401
        assert client.get(path, headers={"Authorization": "Bearer service-token"}).status_code == 401
        assert upstream.calls == 0
        response = client.get(path + "?user_id=bob", headers={"Authorization": "Bearer alice-token"})
        assert response.status_code == 200
        assert response.json()["user_id"] == "alice"
        assert response.headers["cache-control"] == "no-store"
        assert response.json()["mode"] == "preview"


def test_recheck_failure_fails_closed_and_sanitizes_errors(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Source("failure"))) as client:
        response = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice-token"})
        assert response.status_code == 503
        assert "private upstream" not in response.text
        assert "items" not in response.json()


def feed_row():
    return dict(post_id="1", author={"user_id": "author"}, title="咖啡", clips=[{"type": 1}],
                like_count=0, create_time=100, status="PUBLISHED")


@pytest.mark.parametrize("kind", ["private", "duplicate", "oversize", "missing", "bad_clip", "bad_id"])
def test_candidate_adapter_rejects_invalid_upstream(kind):
    row = feed_row()
    rows = [row]
    if kind == "private":
        row["status"] = "DRAFT"
    elif kind == "duplicate":
        rows *= 2
    elif kind == "oversize":
        rows *= 51
    elif kind == "missing":
        del row["author"]
    elif kind == "bad_clip":
        row["clips"] = [{"type": True}]
    else:
        row["post_id"] = "../../admin"
    upstream = JavaClient(Settings())
    upstream._get = lambda *args: {"status_code": 0, "post_list": rows}
    with pytest.raises(UpstreamError):
        upstream.public_candidates("alice-token")


def test_candidate_adapter_uses_bounded_public_feed():
    upstream = JavaClient(Settings())
    def get(path, token, params):
        assert path == "/feed/" and token == "alice-token"
        assert params == {"count": 50, "accept_video_clip": "true"}
        return {"status_code": 0, "post_list": [feed_row()]}
    upstream._get = get
    result = upstream.public_candidates("alice-token")
    assert result[0]["media_type"] == "video"
    assert result[0]["author_enabled"] is True
