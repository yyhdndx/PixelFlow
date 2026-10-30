"""Content safety skill: deterministic, explainable pre-publish risk hints.

This skill never changes a post's status and never calls a model. It only reports
rule hits so the client can ask the author to revise, and so a human moderator
keeps the final decision (see contracts/skills/README.md).
"""
import re

from ..models import SafetyHit, SafetyOutput

PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
LINK = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
REPEAT = re.compile(r"(.)\1{9,}")

CONTACT_WORDS = ("微信", "vx", "v信", "加我", "私聊", "扫码", "qq号")
TRADE_WORDS = ("代购", "刷单", "返利", "私下交易", "转账", "收款", "定金", "低价出")
ABUSE_WORDS = ("傻逼", "去死", "滚蛋", "人渣")


def execute(data, context):
    text = "\n".join((data.title, data.content, " ".join(data.tags)))
    lowered = text.casefold()
    hits, suggestions = [], []

    if PHONE.search(text) or EMAIL.search(text) or any(word in lowered for word in CONTACT_WORDS):
        hits.append(SafetyHit(rule="CONTACT_INFO", level="high",
                              message="包含手机号、邮箱或社交账号等站外联系方式"))
        suggestions.append("删除联系方式后再提交；平台内沟通请使用站内互动")
    if LINK.search(text):
        hits.append(SafetyHit(rule="EXTERNAL_LINK", level="medium", message="包含站外链接"))
        suggestions.append("移除外链，或确认链接指向可信来源")
    if any(word in text for word in TRADE_WORDS):
        hits.append(SafetyHit(rule="PRIVATE_TRADE", level="high",
                              message="包含代购、刷单、返利或私下转账等高风险表述"))
        suggestions.append("移除交易引导；商品交易请使用平台提供的流程")
    if any(word in text for word in ABUSE_WORDS):
        hits.append(SafetyHit(rule="ABUSIVE_LANGUAGE", level="high", message="包含人身攻击或侮辱性用语"))
        suggestions.append("修改为就事论事的表达")
    if REPEAT.search(text) or text.count("!") > 20 or text.count("！") > 20:
        hits.append(SafetyHit(rule="SPAM_REPEAT", level="medium", message="存在重复字符或过量标点"))
        suggestions.append("精简重复内容，避免被判定为刷屏")
    if len(data.title.strip()) < 4 or len(data.content.strip()) < 10:
        hits.append(SafetyHit(rule="LOW_INFORMATION", level="low", message="标题或正文信息量过少"))
        suggestions.append("补充标题与正文，便于他人理解内容")

    risk = "high" if any(hit.level == "high" for hit in hits) else (
        "medium" if any(hit.level == "medium" for hit in hits) else "low")
    return SafetyOutput(user_id=data.user_id, risk=risk, hits=hits,
                        suggestions=suggestions[:10],
                        decision="human_review_required" if risk != "low" else "allowed")
