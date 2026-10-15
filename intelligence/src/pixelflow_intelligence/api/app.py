import secrets
import sqlite3
from fastapi import Depends, FastAPI, HTTPException, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import ValidationError, BaseModel, ConfigDict, Field
from ..common.settings import Settings
from ..clients.java import JavaClient, UpstreamError, AuthenticationError
from ..analytics.store import Store
from ..recommendation.preview import preview
from ..skills import SkillError


class ProfileRefreshInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    after: str = Field(default="", max_length=36)
    count: int = Field(default=50, ge=1, le=100)
    max_age_seconds: int = Field(default=86400, ge=3600, le=2592000)


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
        except (ValueError, ValidationError, sqlite3.Error):
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
        except (ValueError, KeyError, TypeError, sqlite3.Error):
            raise HTTPException(503, "Profile refresh unavailable; page unchanged") from None

    @app.get("/internal/status")
    def status(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        verify_maintenance(credentials)
        try:
            return store.status()
        except (ValueError, KeyError, TypeError, sqlite3.Error):
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
        except (ValueError, sqlite3.Error):
            raise HTTPException(503, "Profile store unavailable") from None

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
        except (SkillError, TimeoutError, sqlite3.Error):
            raise HTTPException(503, "Recommendation preview unavailable") from None

    return app
