"""Agent 编排层：审核建议 = content_safety 规则先行 → (可选) DeepSeek 汇总 → 安全下限钳制 → trace 落库。

只建议、不自动审核：最终通过/驳回永远由管理员人工决定。
规则结果提供确定性下限——规则判 high 时，无论模型输出什么，最终建议都不低于 reject 档位。
"""
import time
import uuid

from ..skills import create_registry

ACTION_BY_RISK = {"low": "approve", "medium": "review", "high": "reject"}
RISK_ORDER = {"low": 0, "medium": 1, "high": 2}
ACTION_ORDER = {"approve": 0, "review": 1, "reject": 2}
RISK_BY_SEVERITY = ["low", "medium", "high"]
ACTION_BY_SEVERITY = ["approve", "review", "reject"]


def _rule_reason(output):
    hits = output["rule_hits"]
    if "banned_word" in hits:
        return "命中违禁词规则，内容存在高风险违规信息，建议驳回"
    if "contact_info" in hits:
        return "检测到联系方式/引流特征，需人工复核后再决定"
    return "未命中任何规则，内容合规"


def suggest_moderation(*, actor, post_id, title, content, tags, settings, store, context,
                       advisor=None):
    """生成一次审核建议；任何一步失败都退化为纯规则结果，不编造。

    advisor 可注入（测试用），签名 advisor(*, title, content, tags, rule_result, deadline)
    -> {"risk_level", "recommended_action", "reason"}。
    """
    registry = create_registry()
    plan = {"intent": "moderation_suggestion", "policy": "content-safety-v1", "auto_act": False}
    context.checkpoint()
    checked = registry.invoke("content_safety",
                              {"user_id": actor, "title": title, "content": content, "tags": tags},
                              context)
    safety_out = checked["output"]
    rule_risk = safety_out["risk_level"] if safety_out["risk_level"] in ACTION_BY_RISK else "low"
    rule_action = ACTION_BY_RISK[rule_risk]
    rule_reason = _rule_reason(safety_out)
    steps = [{"step": "plan", "decision": plan},
             {"step": "safety_check", "skill": checked["skill"],
              "skill_version": checked["skill_version"], "trace_id": checked["trace_id"],
              "elapsed_ms": checked["elapsed_ms"], "risk_level": rule_risk,
              "rule_hits": safety_out["rule_hits"], "policy_version": safety_out["policy_version"]}]

    degraded, degraded_reason, provider_name, llm_latency_ms = 0, "", "rules", 0
    risk, action, reason = rule_risk, rule_action, rule_reason
    provider = advisor
    if provider is None and settings.deepseek_api_key:
        from ..clients.deepseek import DeepSeekModerationAdvisor
        provider = DeepSeekModerationAdvisor(settings)
    if provider is None:
        degraded, degraded_reason = 1, "未配置模型密钥，规则降级"
    else:
        llm_start = time.monotonic()
        try:
            decision = provider(title=title, content=content, tags=tags,
                                rule_result={"risk_level": rule_risk,
                                             "rule_hits": safety_out["rule_hits"],
                                             "suggestions": safety_out["suggestions"]},
                                deadline=context.deadline)
            provider_name = "deepseek"
            # 安全下限：最终档位 = 规则、模型动作、模型风险等级三者最高者，理由取更严格一侧。
            model_action = decision.get("recommended_action") \
                if decision.get("recommended_action") in ACTION_ORDER else "review"
            risk_action = ACTION_BY_RISK.get(decision.get("risk_level"), model_action)
            severity = max(ACTION_ORDER[rule_action], ACTION_ORDER[model_action], ACTION_ORDER[risk_action])
            risk = RISK_BY_SEVERITY[severity]
            action = ACTION_BY_SEVERITY[severity]
            reason = decision["reason"] if ACTION_ORDER[model_action] >= ACTION_ORDER[rule_action] \
                else rule_reason
        except Exception:
            degraded, degraded_reason = 1, "模型调用失败，规则降级"
        llm_latency_ms = int((time.monotonic() - llm_start) * 1000)
        steps.append({"step": "synthesize", "provider": provider_name, "ok": not degraded,
                      "latency_ms": llm_latency_ms, "model": settings.deepseek_model})

    latency_ms = max(0, int((time.time() - context.now) * 1000))
    session_id = str(uuid.uuid4())
    if store is not None:
        store.save_agent_trace(
            trace_id=context.trace_id, user_id=actor,
            question=f"审核建议 作品{post_id}：{title}"[:200],
            answer=f"建议{action}：{reason}"[:1500], source_count=len(safety_out["rule_hits"]),
            degraded=degraded, steps=steps, created_at=context.now, session_id=session_id,
            latency_ms=latency_ms, provider=provider_name, degraded_reason=degraded_reason)
    return {"suggestion_id": str(uuid.uuid4()), "post_id": post_id, "risk_level": risk,
            "recommended_action": action, "reason": reason,
            "rule_hits": safety_out["rule_hits"], "suggestions": safety_out["suggestions"],
            "policy_version": safety_out["policy_version"], "provider": provider_name,
            "latency_ms": latency_ms, "trace_id": context.trace_id, "degraded": degraded}
