"""任务23：审核建议代理与 /internal/moderation-suggestion 端点。"""
import time

import pytest
from fastapi.testclient import TestClient

from pixelflow_intelligence.agents.moderation import suggest_moderation
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.skills import Context

TOKEN = "internal-test-token-1234567890123456789"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}
ACTOR = "moderation-service"


def make_context():
    return Context(ACTOR, frozenset({"content:check"}), int(time.time()),
                   time.monotonic() + 10)


def make_settings(tmp_path, key=""):
    return Settings(database=str(tmp_path / "mod.db"), sync_token=TOKEN,
                    deepseek_api_key=key)


def suggest(store, context, settings, **overrides):
    options = dict(actor=ACTOR, post_id="855", title="风景", content="今日随拍",
                   tags=[], settings=settings, store=store, context=context)
    options.update(overrides)
    return suggest_moderation(**options)


def test_rules_only_low_risk_approves(tmp_path):
    store = Store(str(tmp_path / "mod.db"))
    result = suggest(store, make_context(), make_settings(tmp_path))
    assert result["risk_level"] == "low"
    assert result["recommended_action"] == "approve"
    assert result["provider"] == "rules" and result["degraded"] == 1
    assert result["rule_hits"] == [] and "合规" in result["reason"]
    assert result["policy_version"] == "content-safety-v1"


def test_banned_word_maps_to_reject(tmp_path):
    store = Store(str(tmp_path / "mod.db"))
    result = suggest(store, make_context(), make_settings(tmp_path),
                     title="低价办证", content="专业办证，快速出证")
    assert result["risk_level"] == "high"
    assert result["recommended_action"] == "reject"
    assert result["rule_hits"] == ["banned_word"]


def test_contact_info_maps_to_review(tmp_path):
    store = Store(str(tmp_path / "mod.db"))
    result = suggest(store, make_context(), make_settings(tmp_path),
                     content="加微信 vx12345 详谈合作")
    assert result["risk_level"] == "medium"
    assert result["recommended_action"] == "review"
    assert result["rule_hits"] == ["contact_info"]


def test_llm_advisor_success_marks_deepseek(tmp_path):
    store = Store(str(tmp_path / "mod.db"))

    def advisor(**kwargs):
        assert kwargs["rule_result"]["risk_level"] == "low"
        return {"risk_level": "low", "recommended_action": "approve",
                "reason": "画面与文案均为正常风景分享，无违规特征"}

    result = suggest(store, make_context(), make_settings(tmp_path, key="sk-test"),
                     advisor=advisor)
    assert result["provider"] == "deepseek" and result["degraded"] == 0
    assert result["recommended_action"] == "approve"
    assert result["latency_ms"] >= 0 and result["trace_id"]
    page = store.agent_trace_page(5)
    assert page["traces"][0]["provider"] == "deepseek"
    assert page["traces"][0]["degraded"] == 0


def test_rule_floor_overrides_optimistic_model(tmp_path):
    store = Store(str(tmp_path / "mod.db"))
    result = suggest(store, make_context(), make_settings(tmp_path, key="sk-test"),
                     title="博彩推广", content="在线博彩，稳赚不赔",
                     advisor=lambda **kwargs: {"risk_level": "low",
                                               "recommended_action": "approve",
                                               "reason": "模型低估了风险"})
    assert result["risk_level"] == "high"
    assert result["recommended_action"] == "reject"
    assert "违禁词" in result["reason"]


def test_advisor_failure_degrades_to_rules(tmp_path):
    store = Store(str(tmp_path / "mod.db"))

    def broken(**kwargs):
        raise RuntimeError("boom")

    result = suggest(store, make_context(), make_settings(tmp_path, key="sk-test"),
                     advisor=broken)
    assert result["provider"] == "rules" and result["degraded"] == 1
    assert result["recommended_action"] == "approve"


def test_trace_records_safety_check_and_synthesize_steps(tmp_path):
    store = Store(str(tmp_path / "mod.db"))
    result = suggest(store, make_context(), make_settings(tmp_path, key="sk-test"),
                     advisor=lambda **kwargs: {"risk_level": "medium",
                                               "recommended_action": "review",
                                               "reason": "图片无法核验，建议人工复核"})
    detail = store.agent_trace_detail(result["trace_id"])
    assert detail["provider"] == "deepseek" and detail["latency_ms"] > 0
    assert detail["degraded"] == 0
    assert "审核建议" in detail["question"] and detail["answer"].startswith("建议")
    assert "人工复核" in detail["answer"]


def make_client(tmp_path):
    settings = Settings(database=str(tmp_path / "api.db"), sync_token=TOKEN)
    return TestClient(create_app(settings))


def test_endpoint_requires_service_token(tmp_path):
    client = make_client(tmp_path)
    assert client.post("/internal/moderation-suggestion",
                       json={"post_id": "855", "title": "t", "content": "c"}).status_code == 401


@pytest.mark.parametrize("body", [
    {"post_id": "0", "title": "t", "content": "c"},
    {"post_id": "abc", "title": "t", "content": "c"},
    {"post_id": "855", "title": "t" * 121, "content": "c"},
    {"post_id": "855", "title": "t", "content": "c", "tags": ["x"] * 21},
    {"post_id": "855", "title": "t", "content": "c", "extra": 1},
])
def test_endpoint_validates_request(tmp_path, body):
    client = make_client(tmp_path)
    assert client.post("/internal/moderation-suggestion", json=body,
                       headers=HEADERS).status_code == 422


def test_endpoint_end_to_end_and_trace_queryable(tmp_path):
    client = make_client(tmp_path)
    response = client.post("/internal/moderation-suggestion", headers=HEADERS, json={
        "post_id": "855", "title": "风景随拍", "content": "今天的晚霞真美",
        "tags": ["#风景"]})
    assert response.status_code == 200
    suggestion = response.json()["suggestion"]
    assert suggestion["post_id"] == "855"
    assert suggestion["recommended_action"] == "approve"
    assert suggestion["provider"] == "rules" and suggestion["degraded"] == 1
    for key in ("suggestion_id", "trace_id", "policy_version", "latency_ms"):
        assert suggestion[key] not in (None, "")
    listing = client.get("/internal/traces?count=5", headers=HEADERS)
    assert listing.status_code == 200
    assert any(row["trace_id"] == suggestion["trace_id"]
               for row in listing.json()["traces"])
