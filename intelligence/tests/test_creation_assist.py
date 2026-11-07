from fastapi.testclient import TestClient
import pytest
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

@pytest.mark.parametrize("body", [
    {"draft": draft(), "goal": "all"},
    {"draft": draft(title="标题"), "goal": "polish_content", "prompt":"润色"},
    {"draft": draft(content="原稿"), "goal":"all", "previous_result":draft(content="上一版")},
])
def test_empty_input_or_missing_revision_instructions_are_rejected(tmp_path, body):
    response = client(tmp_path).post('/agent/create-assist', headers={'Authorization':'Bearer alice-token'}, json=body)
    assert response.status_code == 422

def test_rules_mode_does_not_pretend_to_understand_revision(tmp_path):
    response = client(tmp_path).post('/agent/create-assist', headers={'Authorization':'Bearer alice-token'},
        json={'draft':draft(content='原稿'),'goal':'all','prompt':'更口语化','previous_result':draft(content='上一版')})
    assert response.status_code == 503

def test_revision_route_forwards_validated_snapshot_to_model(tmp_path, monkeypatch):
    from pixelflow_intelligence.clients.deepseek import DeepSeekAssist
    from pixelflow_intelligence.skills.models import CreateAssistOutput
    def fake(self, data, context):
        assert data.draft.content == '原稿'
        assert data.previous_result.content == '上一版'
        assert data.previous_result.tags == []
        assert data.prompt == '更口语化'
        return CreateAssistOutput(user_id=data.user_id, title_candidates=[], content_candidates=['再改一版'],
            tag_suggestions=[], edit_suggestions=[], policy_version='test')
    monkeypatch.setattr(DeepSeekAssist, '__call__', fake)
    api=TestClient(create_app(Settings(database=str(tmp_path/'db'),deepseek_api_key='test'),Source()))
    payload={'draft':draft(content='原稿'),'goal':'polish_content','prompt':'更口语化',
        'previous_result':{'title':'','content':'上一版'}}
    response=api.post('/agent/create-assist',headers={'Authorization':'Bearer alice-token'},json=payload)
    assert response.status_code == 200 and response.json()['content_candidates']==['再改一版']
    payload['previous_result']['tags']=['x'*33]
    assert api.post('/agent/create-assist',headers={'Authorization':'Bearer alice-token'},json=payload).status_code==422
