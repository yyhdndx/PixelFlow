"""Application business skills, not Codex instruction skills or an autonomous agent."""
from .runtime import Context, Definition, Registry, SkillError
from .models import (ProfileInput, Insight, RecallInput, RecallOutput, RankInput, RankOutput,
                     SafetyInput, SafetyOutput, AssistInput, AssistOutput)
from .profile_insight import execute as profile
from .candidate_recall import execute as recall
from .feed_rank import execute as rank
from .content_safety import execute as safety
from .creator_assist import execute as assist


def create_registry():
    return Registry([
        Definition("profile_insight", "校验画像新鲜度并提取偏好/冷启动策略", "profile:read", ProfileInput, Insight, profile),
        Definition("candidate_recall", "从受控候选快照召回兴趣与热门内容", "candidates:read", RecallInput, RecallOutput, recall),
        Definition("feed_rank", "过滤、可解释排序及作者/主题多样性约束", "candidates:read", RankInput, RankOutput, rank),
        Definition("content_safety", "发布前确定性风险检查，只给建议不改状态", "content:check", SafetyInput, SafetyOutput, safety),
        Definition("creator_assist", "基于平台主题生成标题与标签建议", "content:assist", AssistInput, AssistOutput, assist),
    ])
