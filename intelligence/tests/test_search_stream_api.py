import json
import uuid

from fastapi.testclient import TestClient

from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import AuthenticationError
from pixelflow_intelligence.common.settings import Settings


class Source:
    def me(self, token):
        if token != "test":
            raise AuthenticationError()
        return "alice"

    def public_posts(self, token, pages=3):
        return []


def test_stream_requires_identity_and_valid_input(tmp_path):
    client = TestClient(create_app(Settings(database=str(tmp_path / "stream.db")), Source()))
    path = "/agent/search/stream"
    assert client.post(path, json={"question": "问题"}).status_code == 401
    headers = {"Authorization": "Bearer test", "X-Request-Id": str(uuid.uuid4())}
    assert client.post(path, headers=headers, json={"question": " "}).status_code == 400
    assert client.post(path, headers=headers, json={"question": "问题", "session_id": "bad"}).status_code == 400


def test_stream_route_emits_prepared_but_requires_matching_confirmation(tmp_path):
    client = TestClient(create_app(Settings(database=str(tmp_path / "stream.db")), Source()))
    request = str(uuid.uuid4())
    headers = {"Authorization": "Bearer test", "X-Request-Id": request}
    response = client.post("/agent/search/stream", headers=headers, json={"question": "问题"})
    assert response.status_code == 200
    assert response.headers["x-accel-buffering"] == "no"
    events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
    assert events[0]["user_id"] == "alice"
    assert events[-1]["sources"] == []
    assert "event: prepared" in response.text
    assert "event: done" not in response.text
    assert client.post("/agent/search/stream/confirm", headers=headers, json={
        "request_id": request, "trace_id": events[-1]["trace_id"], "digest": "0" * 64}).status_code == 503
    assert client.delete("/agent/search/stream/" + request, headers=headers).status_code == 200
