"""P0 修复：审核建议安全下限 + 内容安全多命中（issue 25）。"""
import time

import pytest
from pydantic import ValidationError

from pixelflow_intelligence.agents.moderation import suggest_moderation
from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.clients.deepseek import ModerationDecision
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.skills import Context, create_registry

ACTOR = "moderation-service"


def context(user="alice", scopes=None, seconds=5):
    return Context(user, frozenset(scopes if scopes is not None else ("content:check",)),
                   int(time.time()), time.monotonic() + seconds)


def safety(title="", content="", tags=None):
    return create_registry().invoke("content_safety", {
        "user_id": "alice", "title": title, "content": content, "tags": tags or []},
        context())["output"]


def test_content_safety_reports_banned_and_contact_together():
    out = safety(content="宣传赌博，加微信 abc12345 详聊")
    assert out["risk_level"] == "high"
    assert "banned_word" in out["rule_hits"] and "contact_info" in out["rule_hits"]


def test_moderation_decision_rejects_inconsistent_pair():
    with pytest.raises(ValidationError):
        ModerationDecision(risk_level="high", recommended_action="approve", reason="矛盾")


def test_moderation_decision_accepts_consistent_pair():
    decision = ModerationDecision(risk_level="high", recommended_action="reject", reason="含违禁内容")
    assert decision.risk_level == "high" and decision.recommended_action == "reject"


def test_suggest_clamps_high_model_risk_even_when_action_says_approve(tmp_path):
    """模型返回 risk=high + action=approve（矛盾）时，最终不得低于 reject。"""
    store = Store(str(tmp_path / "mod.db"))
    settings = Settings(database=str(tmp_path / "mod.db"), deepseek_api_key="sk-test")
    result = suggest_moderation(
        actor=ACTOR, post_id="855", title="风景", content="今日随拍", tags=[],
        settings=settings, store=store, context=context(ACTOR),
        advisor=lambda **kwargs: {"risk_level": "high", "recommended_action": "approve",
                                  "reason": "模型称高风险却建议通过"})
    assert result["risk_level"] == "high"
    assert result["recommended_action"] == "reject"
