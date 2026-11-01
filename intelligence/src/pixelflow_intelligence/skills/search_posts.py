"""社区内作品检索：单次有界扫描，规则打分，无循环、无外部检索组件。

检索源是 Java `/feed/` 的公开分页（Java 侧已过滤 PUBLISHED + 作者启用），
本模块只做内存内的词项匹配与排序，适配 4GB 演示服务器（不引入 ES/分词器）。
"""
import re

from pydantic import ValidationError

from .models import SearchPostsInput, SearchPostsOutput
from .runtime import SkillError

_CJK = re.compile(r"[\u4e00-\u9fff]")
_ASCII_RUN = re.compile(r"[a-z0-9]+")

TITLE_WEIGHT = 2.0
CONTENT_WEIGHT = 1.0


def extract_terms(question: str) -> list[str]:
    """ASCII 词（≥2 字符）+ 中文连续串的二字滑窗；保序去重，全小写。"""
    terms: list[str] = []
    seen: set[str] = set()
    pending_cjk: list[str] = []

    def flush_cjk():
        if not pending_cjk:
            return
        if len(pending_cjk) == 1:
            candidates = pending_cjk
        else:
            candidates = ["".join(pending_cjk[i:i + 2]) for i in range(len(pending_cjk) - 1)]
        for term in candidates:
            if term not in seen:
                seen.add(term)
                terms.append(term)
        pending_cjk.clear()

    for char in question.lower():
        if _CJK.match(char):
            pending_cjk.append(char)
        else:
            flush_cjk()
            # 非字母数字字符是分隔符；ASCII 词在第二遍统一提取
    flush_cjk()
    for token in _ASCII_RUN.findall(question.lower()):
        if len(token) >= 2 and token not in seen:
            seen.add(token)
            terms.append(token)
    return terms


def rank_posts(question: str, posts: list[dict], limit: int = 20) -> list[dict]:
    """按词项命中打分：标题 ×2，正文 ×1；同分按 post_id 升序稳定排序。"""
    terms = extract_terms(question)
    if not terms or limit < 1:
        return []
    scored = []
    for post in posts:
        title = str(post.get("title") or "").lower()
        content = str(post.get("content") or "").lower()
        score = 0.0
        for term in terms:
            if term in title:
                score += TITLE_WEIGHT
            elif term in content:
                score += CONTENT_WEIGHT
        if score <= 0:
            continue
        excerpt = str(post.get("content") or "")[:100]
        scored.append({"post_id": str(post["post_id"]), "author_id": str(post["author_id"]),
                       "author_name": str(post.get("author_name") or "")[:64],
                       "title": str(post.get("title") or "")[:120],
                       "excerpt": excerpt, "score": round(score, 3)})
    scored.sort(key=lambda row: (-row["score"], int(row["post_id"])))
    return scored[:limit]


class SearchPostsSource:
    """有界检索源：最多翻 `pages` 页公开 Feed（默认 3 页 × 50 = 150 篇）。"""

    def __init__(self, upstream, token, pages: int = 3):
        if not 1 <= pages <= 5:
            raise ValueError("pages out of range")
        self.upstream, self.token, self.pages = upstream, token, pages

    def __call__(self, data: SearchPostsInput, context):
        context.checkpoint()
        posts = self.upstream.public_posts(self.token, pages=self.pages)
        context.checkpoint()
        hits = rank_posts(data.question, posts, data.limit)
        try:
            return SearchPostsOutput(user_id=data.user_id, items=hits,
                                     inspected=len(posts), policy_version="search-posts-v1")
        except ValidationError:
            raise SkillError("INVALID_OUTPUT", "search_posts") from None


def unavailable(data, context):
    """Registry 占位：未注入检索源时明确失败，绝不静默编造。"""
    raise SkillError("UPSTREAM_UNAVAILABLE", "search_posts")
