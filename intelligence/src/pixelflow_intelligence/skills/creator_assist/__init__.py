"""Creator assist skill: deterministic title/tag suggestions from platform topics.

The wording stays rule-based and explainable: it reuses the same seven topic
keywords as the profile rules, so suggestions never invent a topic the platform
does not model. A client-side model can rewrite the prose later; this skill only
guarantees a stable contract and platform-consistent tags.
"""
from ...analytics.profile import TOPICS
from ..models import AssistOutput

TITLE_LIMIT = 120
NOTE_LIMIT = 6


def _matched_topics(text):
    return [topic for topic, words in TOPICS.items() if any(word in text for word in words)]


def execute(data, context):
    text = "\n".join((data.title, data.content))
    topics = _matched_topics(text) or ["其他"]
    tags = [topic for topic in topics if topic != "其他"]
    for words in TOPICS.values():
        for word in words:
            if word in text and word not in tags:
                tags.append(word)
    tags = tags[:5]

    first_line = next((line.strip() for line in data.content.splitlines() if line.strip()), "")
    label = topics[0] if topics[0] != "其他" else "日常"
    base = data.title.strip().rstrip("。！？.!?")
    suggestions = []
    candidates = []
    if base:
        if label not in base:
            candidates.append(f"{label}｜{base}")
        candidates.append(f"{base}｜{label}")
    else:
        candidates.append(f"{label}｜{first_line[:20]}")
        candidates.append(f"{label}记录：{first_line[:18]}")
    for candidate in candidates:
        candidate = candidate.strip()[:TITLE_LIMIT]
        if candidate and candidate not in suggestions:
            suggestions.append(candidate)

    notes = []
    if not base:
        notes.append("标题为空，建议补充一个能概括主题的短标题")
    elif len(base) > 20:
        notes.append("标题超过 20 字，移动端列表可能被截断")
    if data.media_count == 0:
        notes.append("未选择图片，发布前至少需要一张")
    if not tags:
        notes.append("正文未命中平台主题，建议补充话题标签")
    if len(data.content.strip()) < 30:
        notes.append("正文偏短，补充细节可提升可读性")

    return AssistOutput(user_id=data.user_id, topics=topics[:7],
                        title_suggestions=suggestions[:3], tags=tags,
                        notes=notes[:NOTE_LIMIT])
