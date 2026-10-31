"""Deterministic content safety rules; a model-assisted variant may extend this later."""
import re
from ..models import ContentSafetyOutput

# 内置违禁词表（演示版，可替换为服务端可配置词表）。命中即高风险、需人工复核。
BANNED_WORDS = (
    "赌博", "博彩", "色情", "诈骗", "违禁品", "办证", "假币", "毒品", "枪支",
    "代开发票", "刷单", "传销",
)

# 联系方式/引流特征：命中即中风险。
_CONTACT_PATTERNS = (
    re.compile(r"1[3-9]\d{9}"),
    re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    re.compile(r"https?://"),
    re.compile(r"(?:微信|WeChat|weixin|vx|VX)\s*[:：]?\s*[A-Za-z0-9_-]{5,}"),
    re.compile(r"(?:QQ|qq)\s*[:：]?\s*\d{5,}"),
)

_POLICY_VERSION = "content-safety-v1"


def execute(data, context):
    text = f"{data.title}\n{data.content}\n{' '.join(data.tags)}"
    hits = []
    if any(word in text for word in BANNED_WORDS):
        hits.append("banned_word")
    if not hits and any(pattern.search(text) for pattern in _CONTACT_PATTERNS):
        hits.append("contact_info")
    risk = "high" if "banned_word" in hits else ("medium" if hits else "low")
    suggestions = []
    if "banned_word" in hits:
        suggestions.append("内容含疑似违规信息，请修改后重新提交")
    if "contact_info" in hits:
        suggestions.append("正文含联系方式，可能被判定为广告或引流")
    return ContentSafetyOutput(
        user_id=data.user_id,
        risk_level=risk,
        rule_hits=hits,
        suggestions=suggestions,
        requires_human_review="banned_word" in hits,
        policy_version=_POLICY_VERSION,
    )
