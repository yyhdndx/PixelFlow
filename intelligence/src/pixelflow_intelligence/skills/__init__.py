"""Application business skills, not Codex instruction skills or an autonomous agent."""
from .runtime import Context, Definition, Registry, SkillError
from .models import ProfileInput, Insight, RecallInput, RecallOutput, RankInput, RankOutput
from .models import ContentSafetyInput, ContentSafetyOutput, CreateAssistInput, CreateAssistOutput
from .models import SearchPostsInput, SearchPostsOutput
from .profile_insight import execute as profile
from .candidate_recall import execute as recall
from .feed_rank import execute as rank
from .content_safety import execute as safety
from .creator_assist import execute as assist
from .search_posts import SearchPostsSource, unavailable as search_unavailable


def create_registry(assist_handler=None, search_handler=None):
    return Registry([
        Definition("profile_insight", "校验画像新鲜度并提取偏好/冷启动策略", "profile:read", ProfileInput, Insight, profile),
        Definition("candidate_recall", "从受控候选快照召回兴趣与热门内容", "candidates:read", RecallInput, RecallOutput, recall),
        Definition("feed_rank", "过滤、可解释排序及作者/主题多样性约束", "candidates:read", RankInput, RankOutput, rank),
        Definition("content_safety", "对草稿标题/正文/标签做规则安全校验", "content:check", ContentSafetyInput, ContentSafetyOutput, safety),
        Definition("creator_assist", "根据草稿给出标题/标签/修改建议候选", "content:assist", CreateAssistInput, CreateAssistOutput, assist_handler or assist),
        Definition("search_posts", "按自然语言问题检索社区公开作品（有界单轮）", "posts:search",
                   SearchPostsInput, SearchPostsOutput, search_handler or search_unavailable),
    ])
