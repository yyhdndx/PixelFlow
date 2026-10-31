import secrets
import time
import uuid
from typing import Literal
from ..analytics.database import DATABASE_ERRORS
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import ValidationError, BaseModel, ConfigDict, Field
from ..common.settings import Settings
from ..clients.java import JavaClient, UpstreamError, AuthenticationError
from ..analytics.store import Store
from ..recommendation.preview import preview
from ..skills import Context, SkillError, create_registry
from ..skills.models import DraftSnapshot


class ProfileRefreshInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    after: str = Field(default="", max_length=36)
    count: int = Field(default=50, ge=1, le=100)
    max_age_seconds: int = Field(default=86400, ge=3600, le=2592000)


class ProfileTopicWeight(BaseModel):
    name: str
    score: float
    share: float


class ProfileHistoryItem(BaseModel):
    version: int
    rule_version: str
    generated_at: int
    window_days: int
    timezone: str
    cold_start: bool
    topics: list[ProfileTopicWeight]
    media: list[ProfileTopicWeight]
    event_counts: dict[str, int]
    explanation: str


class ProfileHistoryPage(BaseModel):
    user_id: str
    items: list[ProfileHistoryItem]
    has_more: bool
    next_cursor: str | None = None


class CreateAssistRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    draft: DraftSnapshot
    goal: Literal["improve_title", "suggest_tags", "polish_content", "write_content", "all"]
    prompt: str = Field(default="", max_length=200)
    audience: str = Field(default="", max_length=64)


HISTORY_COUNT_DEFAULT = 20
MAX_BEFORE_VERSION = 9223372036854775807


def _parse_history_count(raw):
    if raw is None:
        return HISTORY_COUNT_DEFAULT
    return _parse_decimal(raw, "count", 1, 50)


def _parse_history_cursor(raw):
    return _parse_decimal(raw, "before_version", 1, MAX_BEFORE_VERSION)


def _parse_decimal(raw, name, minimum, maximum):
    # Accept ASCII decimal positive integers only: explicit empty values, 0,
    # negatives, decimals, other bases and long overflow all fail validation.
    if type(raw) is not str or not raw or not raw.isascii() or not raw.isdigit():
        raise HTTPException(422, f"Invalid {name}; expected a decimal positive integer")
    # Strip leading zeros before comparing lengths, so valid decimal values
    # retain their meaning without feeding unbounded input to int().
    digits = raw.lstrip("0") or "0"
    if len(digits) > len(str(maximum)):
        raise HTTPException(422, f"{name} out of range")
    value = int(digits)
    if not minimum <= value <= maximum:
        raise HTTPException(422, f"{name} out of range")
    return value


def create_app(settings=None, upstream=None):
    settings = settings or Settings.from_env()
    store = Store(settings.database)
    upstream = upstream or JavaClient(settings)
    app = FastAPI(title="PixelFlow analytics", version="0.1.0")
    bearer = HTTPBearer(auto_error=False)

    @app.middleware("http")
    async def private_no_cache(request, call_next):
        response = await call_next(request)
        if request.url.path.startswith(("/recommendations/", "/profiles/", "/internal/")):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/recommendations/me/preferences")
    def get_preferences(response: Response, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        response.headers["Cache-Control"] = "no-store"
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        try:
            user = upstream.me(credentials.credentials)
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java identity service unavailable") from None
        try:
            prefs = upstream.preferences(credentials.credentials)
            if prefs.get("user_id") != user:
                raise UpstreamError("Preference owner mismatch")
            return prefs
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java recommendation preferences unavailable") from None

    @app.put("/recommendations/me/preferences")
    def set_preferences_disabled():
        raise HTTPException(410, "Recommendation preferences are managed by Java")

    @app.put("/recommendations/me/exclusions/{post_id}")
    def set_exclusion_disabled(post_id: str):
        raise HTTPException(410, "Post exclusions are managed by Java (not-interested)")

    @app.put("/recommendations/me/blocked-authors/{author_id}")
    def set_author_block_disabled(author_id: str):
        raise HTTPException(410, "Author blocks are managed by Java")

    @app.get("/health")
    def health():
        return {"status": "UP"}

    @app.post("/internal/sync")
    def sync(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        try:
            return store.sync_page(upstream.events)
        except (UpstreamError, AuthenticationError):
            raise HTTPException(503, "Java export unavailable; checkpoint unchanged") from None
        except (ValueError, ValidationError, *DATABASE_ERRORS):
            raise HTTPException(502, "Invalid event batch or store unavailable; checkpoint unchanged") from None

    def verify_maintenance(credentials):
        if not settings.sync_token:
            raise HTTPException(503, "Synchronization is not configured")
        if (credentials is None or not secrets.compare_digest(
                credentials.credentials.encode(), settings.sync_token.encode())):
            raise HTTPException(401, "Service authentication required")

    @app.post("/internal/profiles/refresh")
    def refresh_profiles(body: ProfileRefreshInput,
                         credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        try:
            return store.refresh_profiles(body.after, body.count, body.max_age_seconds)
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(503, "Profile refresh unavailable; page unchanged") from None

    @app.get("/internal/status")
    def status(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        try:
            return store.status()
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(503, "Analytics status unavailable") from None

    @app.get("/profiles/me")
    def profile(response: Response, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        response.headers["Cache-Control"] = "no-store"
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        try:
            user = upstream.me(credentials.credentials)
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java identity service unavailable") from None
        try:
            return store.profile(user)
        except (ValueError, *DATABASE_ERRORS):
            raise HTTPException(503, "Profile store unavailable") from None

    @app.get("/profiles/me/history", response_model=ProfileHistoryPage)
    def profile_history(request: Request, response: Response,
                        count: str | None = Query(default=None),
                        before_version: str | None = Query(default=None),
                        credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        response.headers["Cache-Control"] = "no-store"
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        if len(request.query_params.getlist("count")) > 1 \
                or len(request.query_params.getlist("before_version")) > 1:
            raise HTTPException(422, "Duplicate pagination parameter")
        page_size = _parse_history_count(count)
        before = None if before_version is None else _parse_history_cursor(before_version)
        try:
            user = upstream.me(credentials.credentials)
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java identity service unavailable") from None
        try:
            return store.profile_history(user, page_size, before)
        except (ValueError, KeyError, TypeError, ValidationError, *DATABASE_ERRORS):
            raise HTTPException(503, "Profile history unavailable") from None

    @app.get("/recommendations/me/preview")
    def recommendations(response: Response, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        response.headers["Cache-Control"] = "no-store"
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        try:
            user = upstream.me(credentials.credentials)
            return preview(user, store.profile(user), upstream, credentials.credentials,
                           lambda _u: upstream.preferences(credentials.credentials))
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java recommendation source unavailable") from None
        except (SkillError, TimeoutError, *DATABASE_ERRORS):
            raise HTTPException(503, "Recommendation preview unavailable") from None

    @app.post("/agent/create-assist")
    def create_assist(body: CreateAssistRequest, response: Response,
                      credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        response.headers["Cache-Control"] = "no-store"
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        try:
            user = upstream.me(credentials.credentials)
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java identity service unavailable") from None
        try:
            registry = create_registry()
            context = Context(user, frozenset({"content:check", "content:assist"}),
                              int(time.time()), time.monotonic() + 10)
            assist = registry.invoke("creator_assist", {
                "user_id": user, "draft": body.draft.model_dump(), "goal": body.goal,
                "prompt": body.prompt, "audience": body.audience}, context)["output"]
            safety = registry.invoke("content_safety", {
                "user_id": user, "title": body.draft.title, "content": body.draft.content,
                "tags": body.draft.tags}, context)["output"]
            return {
                "user_id": user, "trace_id": context.trace_id,
                "suggestion_id": str(uuid.uuid4()), "goal": body.goal,
                "title_candidates": assist["title_candidates"],
                "tag_suggestions": assist["tag_suggestions"],
                "edit_suggestions": assist["edit_suggestions"],
                "content_candidates": assist["content_candidates"],
                "policy_version": assist["policy_version"],
                "safety": safety,
            }
        except (SkillError, TimeoutError, ValidationError):
            raise HTTPException(503, "Creation assist unavailable") from None

    return app
