from fastapi.testclient import TestClient
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import AuthenticationError
from pixelflow_intelligence.common.settings import Settings


class Source:
    def me(self, token):
        if token != "alice-token":
            raise AuthenticationError()
        return "alice"


def client(tmp_path):
    return TestClient(create_app(Settings(database=str(tmp_path / "db")), Source()))


def draft(title="", content="", tags=None):
    return {"title": title, "content": content, "tags": tags or []}


def test_create_assist_requires_auth(tmp_path):
    response = client(tmp_path).post("/agent/create-assist",
                                     json={"draft": draft(), "goal": "all"})
    assert response.status_code == 401


def test_create_assist_returns_candidates_and_safety(tmp_path):
    response = client(tmp_path).post("/agent/create-assist",
                                     headers={"Authorization": "Bearer alice-token"},
                                     json={"draft": draft(content="今天的咖啡很好喝"), "goal": "all"})
    assert response.status_code == 200
    body = response.json()
    assert body["user_id"] == "alice"
    assert body["goal"] == "all"
    assert body["suggestion_id"] and body["trace_id"]
    assert body["title_candidates"] == ["今天的咖啡很好喝"]
    assert body["safety"]["risk_level"] == "low"
    assert body["safety"]["requires_human_review"] is False
    assert response.headers["cache-control"] == "no-store"


def test_create_assist_write_content_goal(tmp_path):
    response = client(tmp_path).post("/agent/create-assist",
                                     headers={"Authorization": "Bearer alice-token"},
                                     json={"draft": draft(), "goal": "write_content", "prompt": "咖啡"})
    assert response.status_code == 200
    assert response.json()["content_candidates"][0] == "今天想和大家分享一下「咖啡」的一些感受。"


def test_create_assist_flags_banned_content(tmp_path):
    response = client(tmp_path).post("/agent/create-assist",
                                     headers={"Authorization": "Bearer alice-token"},
                                     json={"draft": draft(content="这里在宣传赌博平台"), "goal": "all"})
    assert response.status_code == 200
    assert response.json()["safety"]["risk_level"] == "high"
    assert response.json()["safety"]["requires_human_review"] is True


def test_create_assist_rejects_unknown_goal(tmp_path):
    response = client(tmp_path).post("/agent/create-assist",
                                     headers={"Authorization": "Bearer alice-token"},
                                     json={"draft": draft(), "goal": "hack"})
    assert response.status_code == 422
