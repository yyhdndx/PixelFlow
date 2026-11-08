import secrets
import time
import uuid
from typing import Annotated, Literal
from ..analytics.database import DATABASE_ERRORS
from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import ValidationError, BaseModel, ConfigDict, Field, StringConstraints
from ..common.settings import Settings
from ..clients.java import JavaClient, UpstreamError, AuthenticationError
from ..analytics.store import Store
from ..recommendation.preview import preview
from ..skills import Context, SkillError, create_registry
from ..skills.models import DraftSnapshot
from ..clients.deepseek import DeepSeekAssist
from ..agents.search import answer_question
from ..agents.moderation import suggest_moderation
from ..agents.comment_reply import decide_reply
from ..agents.auto_reply_suggest import suggest_rules
from ..agents.post_summary import summarize_post
from ..agents.copilot import run_copilot


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
    previous_result: DraftSnapshot | None = None


Question = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]


class AgentSearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    question: Question
    session_id: str = Field(default="", max_length=36)


class ModerationSuggestionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    post_id: str = Field(min_length=1, max_length=18, pattern=r"^[1-9][0-9]*$")
    title: str = Field(max_length=120)
    content: str = Field(max_length=5000)
    tags: list[str] = Field(default_factory=list, max_length=20)


class CommentReplyRule(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    question: str = Field(min_length=1, max_length=200)
    answer: str = Field(min_length=1, max_length=300)


class CommentReplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    post_id: str = Field(min_length=1, max_length=18, pattern=r"^[1-9][0-9]*$")
    title: str = Field(max_length=120)
    content: str = Field(max_length=5000)
    comment: str = Field(min_length=1, max_length=500)
    rules: list[CommentReplyRule] = Field(min_length=1, max_length=10)


class CopilotHistoryItem(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    role: str = Field(pattern=r"^(user|assistant)$")
    content: str = Field(max_length=500)


class CopilotRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    screen: str = Field(pattern=r"^(detail|create|messages)$")
    message: str = Field(min_length=1, max_length=2000)
    history: list[CopilotHistoryItem] = Field(default_factory=list, max_length=20)
    context: dict = Field(default_factory=dict)


class PostSummaryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    post_id: str = Field(min_length=1, max_length=18, pattern=r"^[1-9][0-9]*$")
    title: str = Field(max_length=120)
    content: str = Field(max_length=5000)


class AutoReplySuggestRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    post_id: str = Field(min_length=1, max_length=18, pattern=r"^[1-9][0-9]*$")
    title: str = Field(max_length=120)
    content: str = Field(max_length=5000)
    prompt: str = Field(min_length=1, max_length=500)


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
    from .search_stream import router as search_stream_router
    app.include_router(search_stream_router(settings, upstream, store))
    from .summary_stream import router as summary_stream_router
    app.include_router(summary_stream_router(settings, upstream, store))
    from .assist_stream import router as assist_stream_router
    app.include_router(assist_stream_router(settings, upstream, store))
    bearer = HTTPBearer(auto_error=False)

    @app.middleware("http")
    async def private_no_cache(request, call_next):
        response = await call_next(request)
        if request.url.path.startswith(("/recommendations/", "/profiles/", "/internal/", "/agent/")):
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

    @app.get("/internal/traces")
    def agent_traces(count: str = "20", cursor: str | None = None,
                     credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        try:
            if not count.isdigit() or not 1 <= int(count) <= 50:
                raise ValueError("count 应为 1–50")
            before = None
            if cursor is not None:
                if not cursor.isdigit() or int(cursor) <= 0:
                    raise ValueError("cursor 应为正整数")
                before = int(cursor)
            return store.agent_trace_page(int(count), before)
        except ValueError as error:
            raise HTTPException(400, str(error)) from None
        except (KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Agent trace store unavailable") from None

    @app.get("/internal/traces/{trace_id}")
    def agent_trace_detail(trace_id: str,
                           credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        try:
            trace = store.agent_trace_detail(trace_id)
        except ValueError as error:
            raise HTTPException(400, str(error)) from None
        except (KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Agent trace store unavailable") from None
        if trace is None:
            raise HTTPException(404, "Agent trace not found")
        return {"trace": trace}

    @app.post("/internal/moderation-suggestion")
    def moderation_suggestion(body: ModerationSuggestionRequest,
                              credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        actor = "moderation-service"
        context = Context(actor, frozenset({"content:check"}), int(time.time()),
                          time.monotonic() + 12)
        try:
            suggestion = suggest_moderation(actor=actor, post_id=body.post_id, title=body.title,
                                            content=body.content, tags=body.tags, settings=settings,
                                            store=store, context=context)
        except (SkillError, TimeoutError, ValidationError):
            raise HTTPException(502, "Moderation suggestion unavailable") from None
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Moderation suggestion store unavailable") from None
        return {"status_code": 0, "suggestion": suggestion}

    @app.post("/internal/comment-reply")
    def comment_reply(body: CommentReplyRequest,
                      credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        actor = "comment-reply-service"
        context = Context(actor, frozenset({"comment:reply"}), int(time.time()),
                          time.monotonic() + 8)
        rules = [{"question": r.question, "answer": r.answer} for r in body.rules]
        try:
            decision = decide_reply(post_id=body.post_id, post_title=body.title,
                                    post_content=body.content, comment=body.comment,
                                    rules=rules, settings=settings, store=store, context=context)
        except (SkillError, TimeoutError, ValidationError):
            raise HTTPException(502, "Comment reply decision unavailable") from None
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Comment reply store unavailable") from None
        return {"status_code": 0, "reply": decision}

    @app.post("/internal/copilot")
    def copilot(body: CopilotRequest,
                credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        context = Context("copilot-service", frozenset({"posts:read", "drafts:write", "messages:write"}),
                          int(time.time()), time.monotonic() + 45)
        try:
            result = run_copilot(screen=body.screen, context=body.context, message=body.message,
                                 history=[item.model_dump() for item in body.history],
                                 settings=settings, store=store, ctx=context)
        except (SkillError, TimeoutError, ValidationError, ValueError):
            raise HTTPException(502, "Copilot unavailable") from None
        except (KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Copilot store unavailable") from None
        return {"status_code": 0, "copilot": result}
    @app.post("/internal/post-summary")
    def post_summary(body: PostSummaryRequest,
                     credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        actor = "post-summary-service"
        context = Context(actor, frozenset({"posts:read"}), int(time.time()),
                          time.monotonic() + 25)
        try:
            result = summarize_post(post_id=body.post_id, title=body.title, content=body.content,
                                    settings=settings, store=store, context=context)
        except (SkillError, TimeoutError, ValidationError):
            raise HTTPException(502, "Post summary unavailable") from None
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Post summary store unavailable") from None
        return {"status_code": 0, "summary": result}
    @app.post("/internal/auto-reply-suggest")
    def auto_reply_suggest(body: AutoReplySuggestRequest,
                           credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        actor = "auto-reply-suggest-service"
        context = Context(actor, frozenset({"comment:reply"}), int(time.time()),
                          time.monotonic() + 25)
        try:
            result = suggest_rules(post_id=body.post_id, post_title=body.title,
                                   post_content=body.content, prompt=body.prompt,
                                   settings=settings, store=store, context=context)
        except (SkillError, TimeoutError, ValidationError):
            raise HTTPException(502, "Auto reply suggestion unavailable") from None
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(502, "Auto reply suggestion store unavailable") from None
        return {"status_code": 0, "suggestion": result}

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
            if not (body.draft.title.strip() or body.draft.content.strip() or body.prompt.strip()):
                raise HTTPException(422, "Draft or writing instructions required")
            if body.goal == "polish_content" and not body.draft.content.strip():
                raise HTTPException(422, "Polishing requires draft content")
            if body.previous_result is not None and not body.prompt.strip():
                raise HTTPException(422, "Revision instructions required")
            if body.previous_result is not None and not settings.deepseek_api_key:
                raise HTTPException(503, "Revision requires model service")
            registry = create_registry(DeepSeekAssist(settings) if settings.deepseek_api_key else None)
            context = Context(user, frozenset({"content:check", "content:assist"}),
                              int(time.time()), time.monotonic() + 40)
            assist = registry.invoke("creator_assist", {
                "user_id": user, "draft": body.draft.model_dump(), "goal": body.goal,
                "prompt": body.prompt, "audience": body.audience,
                "previous_result": body.previous_result.model_dump() if body.previous_result is not None else None}, context)["output"]
            safety = registry.invoke("content_safety", {
                "user_id": user, "title": body.draft.title, "content": body.draft.content,
                "tags": body.draft.tags}, context)["output"]
            # Inspect generated candidates as well as the original text; never mark unsafe output safe.
            checks = [safety]
            for text in assist["title_candidates"] + assist["content_candidates"] + assist["tag_suggestions"]:
                checks.append(registry.invoke("content_safety", {
                    "user_id": user, "title": "", "content": text, "tags": []}, context)["output"])
            safety = dict(max(checks, key=lambda c: {"low": 0, "medium": 1, "high": 2, "unknown": 3}[c["risk_level"]]))
            safety["rule_hits"] = sorted({hit for c in checks for hit in c["rule_hits"]})
            safety["suggestions"] = list(dict.fromkeys(s for c in checks for s in c["suggestions"]))
            return {
                "user_id": user, "trace_id": context.trace_id,
                "suggestion_id": str(uuid.uuid4()), "goal": body.goal,
                "title_candidates": assist["title_candidates"],
                "tag_suggestions": assist["tag_suggestions"],
                "edit_suggestions": assist["edit_suggestions"],
                "content_candidates": assist["content_candidates"],
                "policy_version": assist["policy_version"],
                "provider": "deepseek" if settings.deepseek_api_key else "rules",
                "safety": safety,
            }
        except (SkillError, TimeoutError, ValidationError):
            raise HTTPException(503, "Creation assist unavailable") from None

    @app.post("/agent/search")
    def agent_search(body: AgentSearchRequest, response: Response,
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
        context = Context(user, frozenset({"posts:search"}), int(time.time()),
                          time.monotonic() + 45)
        try:
            return answer_question(user=user, question=body.question,
                                   session_id=body.session_id, upstream=upstream,
                                   token=credentials.credentials, settings=settings,
                                   store=store, context=context)
        except (SkillError, TimeoutError, ValidationError, UpstreamError):
            # 检索/上游失败不编造回答；Java 侧映射为 502，前端显示降级文案。
            raise HTTPException(503, "Agent search unavailable") from None
        except (ValueError, KeyError, TypeError, *DATABASE_ERRORS):
            raise HTTPException(503, "Agent search store unavailable") from None

    return app
