import json
import time
import httpx
import pytest
from fastapi.testclient import TestClient
from pixelflow_intelligence.clients.deepseek import DeepSeekAssist
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills.models import CreateAssistInput
from pixelflow_intelligence.skills.runtime import Context, SkillError
from pixelflow_intelligence.api.app import create_app

OUTPUT = {"title_candidates": ["午后咖啡"], "content_candidates": ["给午后留一杯咖啡的时间。"],
          "tag_suggestions": ["#咖啡"], "edit_suggestions": ["突出主题"]}

def invoke(handler, goal="all"):
    settings = Settings(deepseek_api_key="test-secret", deepseek_model="test-model")
    data = CreateAssistInput(user_id="alice", draft={"title": "咖啡", "content": "", "tags": []},
                             goal=goal, audience="同学")
    return DeepSeekAssist(settings, httpx.MockTransport(handler))(
        data, Context("alice", frozenset({"content:assist"}), int(time.time()), time.monotonic()+10))

def response(content=None, reason="stop"):
    return httpx.Response(200, json={"choices": [{"finish_reason": reason, "message": {
        "content": json.dumps(OUTPUT if content is None else content, ensure_ascii=False)}}]})

def test_structured_request_and_goal_filter():
    def handler(request):
        assert str(request.url) == "https://api.deepseek.com/chat/completions"
        assert request.headers["authorization"] == "Bearer test-secret"
        payload = json.loads(request.content)
        assert payload["response_format"] == {"type": "json_object"}
        user = json.loads(payload["messages"][1]["content"])
        assert user["audience"] == "同学" and "user_id" not in user
        return response()
    result = invoke(handler, "improve_title")
    assert result.title_candidates == ["午后咖啡"] and not result.content_candidates

@pytest.mark.parametrize("bad", [
    {**OUTPUT, "title_candidates": ["x"*121]}, {**OUTPUT, "extra": "unsafe"},
    {**OUTPUT, "content_candidates": [123]}, {**OUTPUT, "tag_suggestions": ["#bad tag"]},
    {k: [] for k in OUTPUT},
])
def test_rejects_invalid_model_output(bad):
    with pytest.raises(SkillError, match="MODEL_UNAVAILABLE"):
        invoke(lambda _: response(bad))

@pytest.mark.parametrize("status", [401, 402, 429, 500, 302])
def test_upstream_errors_are_sanitized(status):
    with pytest.raises(SkillError) as error:
        invoke(lambda _: httpx.Response(status, text="secret provider detail", headers={"location":"https://example.com"}))
    assert "secret" not in str(error.value)

def test_timeout_and_truncated_json():
    def timeout(_): raise httpx.ReadTimeout("secret")
    for handler in [timeout, lambda _: response(reason="length"), lambda _: httpx.Response(200, text="broken")]:
        with pytest.raises(SkillError): invoke(handler)

def test_generated_content_is_safety_checked(tmp_path, monkeypatch):
    from pixelflow_intelligence.skills.models import CreateAssistOutput
    def fake(self, data, context):
        return CreateAssistOutput(user_id=data.user_id, policy_version="test", **{**OUTPUT, "content_candidates":["宣传赌博"]})
    monkeypatch.setattr(DeepSeekAssist, "__call__", fake)
    class Source:
        def me(self, token): return "alice"
    client = TestClient(create_app(Settings(database=str(tmp_path/"db"), deepseek_api_key="test"), Source()))
    result = client.post("/agent/create-assist", headers={"Authorization":"Bearer fake"},
                         json={"draft":{"title":"咖啡","content":"","tags":[]},"goal":"all"})
    assert result.status_code == 200
    assert result.json()["provider"] == "deepseek"
    assert result.json()["safety"]["risk_level"] == "high"
    assert result.json()["safety"]["requires_human_review"]

def test_model_failure_preserves_failure_semantics(tmp_path, monkeypatch):
    def fail(*args): raise SkillError("MODEL_UNAVAILABLE", "creator_assist")
    monkeypatch.setattr(DeepSeekAssist, "__call__", fail)
    class Source:
        def me(self, token): return "alice"
    client = TestClient(create_app(Settings(database=str(tmp_path/"db"), deepseek_api_key="test"), Source()))
    result = client.post("/agent/create-assist", headers={"Authorization":"Bearer fake"},
                         json={"draft":{"title":"原稿","content":"","tags":[]},"goal":"all"})
    assert result.status_code == 503
