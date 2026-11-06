import json

from fastapi.testclient import TestClient

from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import AuthenticationError
from pixelflow_intelligence.common.settings import Settings


class Source:
    status = "PUBLISHED"

    def me(self, token):
        if token != "test":
            raise AuthenticationError()
        return "alice"

    def _get(self, path, token):
        return {"title": "长文", "content": "正文" * 150, "status": self.status}


def test_summary_stream_auth_visibility_and_complete_blocks(tmp_path, monkeypatch):
    def model(*args, **kwargs):
        yield json.dumps({"summary": "概括"}) + "\n"
        for index in range(3):
            yield json.dumps({"point": "要点" + str(index)}) + "\n"

    monkeypatch.setattr("pixelflow_intelligence.agents.summary_stream.stream_chat", model)
    source = Source()
    client = TestClient(create_app(Settings(database=str(tmp_path / "summary.db"), deepseek_api_key="test"), source))
    path = "/agent/posts/1/summary/stream"
    assert client.get(path).status_code == 401
    headers = {"Authorization": "Bearer test"}
    response = client.get(path, headers=headers)
    assert response.status_code == 200
    assert response.headers["x-accel-buffering"] == "no"
    assert [line for line in response.text.splitlines() if line.startswith("event:")] == [
        "event: summary", "event: point", "event: point", "event: point", "event: done"]
    source.status = "WITHDRAWN"
    assert client.get(path, headers=headers).status_code == 404
