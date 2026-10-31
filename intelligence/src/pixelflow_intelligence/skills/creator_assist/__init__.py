"""Deterministic creation assistance rules; candidates are suggestions, never applied directly."""
import re
from ..models import CreateAssistOutput

_HASHTAG = re.compile(r"#([\u4e00-\u9fa5A-Za-z0-9_]{1,20})")
_SENTENCE_SPLIT = re.compile(r"[。！？!?\n]+")

_POLICY_VERSION = "creator-assist-v1"


def _first_sentence(content):
    for part in _SENTENCE_SPLIT.split(content):
        part = part.strip()
        if part:
            return part
    return content.strip()


def _topic(draft, prompt):
    if prompt.strip():
        return prompt.strip()[:50]
    if draft.title.strip():
        return draft.title.strip()[:50]
    if draft.content.strip():
        return _first_sentence(draft.content)[:50]
    return "此刻"


def _write_content_candidates(topic):
    # 规则版仅模板生成，接模型后替换为自然文案。
    return [
        f"今天想和大家分享一下「{topic}」的一些感受。",
        f"「{topic}」——记录一下我最近的一点小心得。",
        f"关于「{topic}」，我有些想说的话。",
    ]


def execute(data, context):
    title = data.draft.title.strip()
    content = data.draft.content.strip()
    tags = [t.strip() for t in data.draft.tags if t.strip()]
    goal = data.goal

    title_candidates, tag_suggestions, edit_suggestions, content_candidates = [], [], [], []

    if goal in ("improve_title", "all"):
        if not title and content:
            title_candidates.append(_first_sentence(content)[:20])
        elif 0 < len(title) < 5 and content:
            title_candidates.append(f"{title}｜{_first_sentence(content)[:12]}")
        if len(title) > 30:
            edit_suggestions.append("标题偏长，建议精简到 30 字以内")
        if not title and not content:
            edit_suggestions.append("标题和正文都为空，建议先补充内容")

    if goal in ("suggest_tags", "all"):
        found = _HASHTAG.findall(content)
        tag_suggestions.extend(f"#{t}" for t in found if f"#{t}" not in tags)
        if not tags and not tag_suggestions:
            edit_suggestions.append("建议添加 1-3 个话题标签，方便被检索")

    if goal in ("polish_content", "all"):
        if not content:
            edit_suggestions.append("正文为空，建议补充一段描述")
        elif len(content) < 10:
            edit_suggestions.append("正文较短，建议展开细节")

    if goal == "write_content":
        content_candidates = _write_content_candidates(_topic(data.draft, data.prompt))

    return CreateAssistOutput(
        user_id=data.user_id,
        title_candidates=_dedupe(title_candidates),
        tag_suggestions=_dedupe(tag_suggestions),
        edit_suggestions=_dedupe(edit_suggestions),
        content_candidates=_dedupe(content_candidates),
        policy_version=_POLICY_VERSION,
    )


def _dedupe(values):
    seen, result = set(), []
    for value in values:
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result
