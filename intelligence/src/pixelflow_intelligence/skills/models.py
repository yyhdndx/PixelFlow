from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False,
                              revalidate_instances="always")


class Preference(Model):
    name: str = Field(min_length=1, max_length=64)
    score: float = Field(ge=0, le=1000000000)
    share: float = Field(ge=0, le=1)


class Snapshot(Model):
    user_id: str = Field(min_length=1, max_length=36)
    version: int = Field(ge=0)
    generated_at: int = Field(ge=0)
    cold_start: bool
    topics: list[Preference] = Field(max_length=10)
    authors: list[Preference] = Field(max_length=10)
    media: list[Preference] = Field(max_length=10)
    rule_version: str = Field(max_length=64)
    window_days: int = Field(ge=1, le=365)
    timezone: str = Field(max_length=40)
    event_counts: dict[str, int]
    active_hours: dict[str, int]
    explanation: str = Field(max_length=1000)


class Insight(Model):
    user_id: str
    profile_version: int = Field(ge=0)
    strategy: Literal["personalized", "popular"]
    reason: Literal["available", "missing", "cold_start", "stale", "future_snapshot", "empty_preferences"]
    topics: list[Preference] = Field(max_length=10)
    authors: list[Preference] = Field(max_length=10)
    media: list[Preference] = Field(max_length=10)

    @model_validator(mode="after")
    def consistent(self):
        if (self.strategy == "personalized") != (self.reason == "available"):
            raise ValueError("Strategy and reason disagree")
        for values in (self.topics, self.authors, self.media):
            if len({p.name for p in values}) != len(values) or sum(p.share for p in values) > 1.0001:
                raise ValueError("Preferences must be unique and normalized")
        if self.strategy == "popular" and (self.topics or self.authors or self.media):
            raise ValueError("Fallback insight must not carry preferences")
        return self


class ProfileInput(Model):
    user_id: str = Field(min_length=1, max_length=36)
    snapshot: Snapshot | None = None
    max_age_seconds: int = Field(default=86400, ge=60, le=2592000)


class Candidate(Model):
    post_id: str = Field(pattern=r"^[1-9][0-9]{0,17}$")
    author_id: str = Field(min_length=1, max_length=36)
    title: str = Field(max_length=120)
    media_type: Literal["image", "video"]
    like_count: int = Field(ge=0, le=2147483647)
    create_time: int = Field(ge=0)
    status: Literal["DRAFT", "PENDING", "PUBLISHED", "REJECTED", "WITHDRAWN"]
    author_enabled: bool


class RecallInput(Model):
    user_id: str = Field(min_length=1, max_length=36)
    insight: Insight
    candidates: list[Candidate] = Field(max_length=500)
    excluded_post_ids: list[str] = Field(max_length=1000)
    limit: int = Field(default=50, ge=1, le=100)


class Recalled(Model):
    candidate: Candidate
    topics: list[str]
    channels: list[Literal["interest", "popular"]]


class RecallOutput(Model):
    user_id: str
    profile_version: int
    strategy: Literal["personalized", "popular"]
    items: list[Recalled]
    inspected: int
    rejected: int


class RankInput(Model):
    user_id: str = Field(min_length=1, max_length=36)
    insight: Insight
    recalled: list[Recalled] = Field(max_length=100)
    excluded_post_ids: list[str] = Field(max_length=1000)
    limit: int = Field(default=10, ge=1, le=50)
    max_per_author: int = Field(default=2, ge=1, le=10)
    max_per_topic: int = Field(default=3, ge=1, le=50)


class Ranked(Model):
    post_id: str
    author_id: str
    topics: list[str]
    score: float
    components: dict[str, float]
    reason: str


class RankOutput(Model):
    user_id: str
    profile_version: int
    strategy: Literal["personalized", "popular"]
    policy_version: str
    items: list[Ranked]
    filtered: int
    truncated_by_diversity: int


class DraftSnapshot(Model):
    title: str = Field(max_length=120)
    content: str = Field(max_length=5000)
    tags: list[str] = Field(max_length=20)


class ContentSafetyInput(Model):
    user_id: str = Field(min_length=1, max_length=36)
    title: str = Field(max_length=120)
    content: str = Field(max_length=5000)
    tags: list[str] = Field(max_length=20)


class ContentSafetyOutput(Model):
    user_id: str
    risk_level: Literal["low", "medium", "high", "unknown"]
    rule_hits: list[str]
    suggestions: list[str]
    requires_human_review: bool
    policy_version: str


class CreateAssistInput(Model):
    user_id: str = Field(min_length=1, max_length=36)
    draft: DraftSnapshot
    goal: Literal["improve_title", "suggest_tags", "polish_content", "write_content", "all"]
    audience: str = Field(default="", max_length=64)
    prompt: str = Field(default="", max_length=200)


class CreateAssistOutput(Model):
    user_id: str
    title_candidates: list[str]
    tag_suggestions: list[str]
    edit_suggestions: list[str]
    content_candidates: list[str]
    policy_version: str
