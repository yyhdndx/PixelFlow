"""One bounded maintenance pass. Does not install or start a scheduler."""
import argparse
import json
import os
import time
from urllib.parse import urlsplit
import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError


class MaintenanceError(Exception):
    pass


class BudgetExhausted(Exception):
    """Raised internally when the time budget runs out mid-pass."""


class SyncPage(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    imported: int = Field(ge=0, le=50)
    updated_users: int = Field(ge=0, le=50)
    next_cursor: int = Field(ge=0)
    has_more: bool


class RefreshPage(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    scanned_users: int = Field(ge=0, le=100)
    updated_users: int = Field(ge=0, le=100)
    next_cursor: str = Field(max_length=36)
    has_more: bool
    evaluated_at: int = Field(ge=0)


def validate_config(origin, token):
    try:
        url = urlsplit(origin)
        if (url.scheme not in ("http", "https") or not url.hostname or url.username is not None
                or url.password is not None or url.path not in ("", "/") or url.query or url.fragment
                or any(c.isspace() for c in origin)):
            raise ValueError()
        _ = url.port
        if url.scheme == "http" and url.hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError()
        if len(token) < 32 or any(not 33 <= ord(c) <= 126 for c in token):
            raise ValueError()
    except ValueError:
        raise MaintenanceError("INVALID_CONFIGURATION") from None


def run(client, *, max_sync_pages=20, max_refresh_pages=20, refresh_after="", budget_seconds=120):
    if (type(max_sync_pages) is not int or not 1 <= max_sync_pages <= 1000
            or type(max_refresh_pages) is not int or not 1 <= max_refresh_pages <= 1000
            or type(budget_seconds) is not int or not 1 <= budget_seconds <= 3600
            or not isinstance(refresh_after, str) or len(refresh_after) > 36):
        raise MaintenanceError("INVALID_OPTIONS")
    deadline = time.monotonic() + budget_seconds
    result = {"status": "incomplete", "sync_pages": 0, "imported": 0,
              "refresh_pages": 0, "refreshed_users": 0, "refresh_after": refresh_after}

    def post(path, body, model):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BudgetExhausted
        try:
            response = client.post(path, json=body, timeout=min(30, remaining))
            if response.status_code != 200:
                raise MaintenanceError("HTTP_" + str(response.status_code))
            return model.model_validate(response.json())
        except (httpx.HTTPError, ValueError, ValidationError):
            raise MaintenanceError("REQUEST_OR_RESPONSE_FAILED") from None

    try:
        previous = None
        for _ in range(max_sync_pages):
            page = post("/internal/sync", None, SyncPage)
            if (page.updated_users > page.imported or (page.has_more and not page.imported)
                    or (previous is not None and (page.next_cursor < previous
                        or (page.imported > 0 and page.next_cursor == previous)))):
                raise MaintenanceError("INVALID_SYNC_PROGRESS")
            result["sync_pages"] += 1
            result["imported"] += page.imported
            previous = page.next_cursor
            if not page.has_more:
                break
        else:
            result["reason"] = "SYNC_PAGE_LIMIT"
            return result
        after = refresh_after
        for _ in range(max_refresh_pages):
            page = post("/internal/profiles/refresh", {"after": after, "count": 50}, RefreshPage)
            if (page.scanned_users > 50 or page.updated_users > page.scanned_users
                    or (page.scanned_users and page.next_cursor <= after)
                    or (not page.scanned_users and (page.next_cursor != after or page.has_more))):
                raise MaintenanceError("INVALID_REFRESH_PROGRESS")
            result["refresh_pages"] += 1
            result["refreshed_users"] += page.updated_users
            after = page.next_cursor
            result["refresh_after"] = after if page.has_more else ""
            if not page.has_more:
                result["status"] = "complete"
                return result
        result["reason"] = "REFRESH_PAGE_LIMIT"
    except BudgetExhausted:
        # Running out of time is a normal bounded stop, not a failure: report
        # incomplete with the last confirmed cursor so the next pass resumes
        # from it instead of rescanning earlier pages.
        result["reason"] = "TIME_BUDGET_EXHAUSTED"
    except MaintenanceError as error:
        result.update(status="failed", reason=str(error))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run one bounded sync and profile maintenance pass")
    parser.add_argument("--max-sync-pages", type=int, default=20)
    parser.add_argument("--max-refresh-pages", type=int, default=20)
    parser.add_argument("--refresh-after", default="")
    parser.add_argument("--budget-seconds", type=int, default=120)
    args = parser.parse_args(argv)
    # No command-line token option: credentials must not appear in process listings.
    origin = os.getenv("PIXELFLOW_MAINTENANCE_URL", "http://127.0.0.1:8001")
    token = os.getenv("PIXELFLOW_INTELLIGENCE_TOKEN", "")
    try:
        validate_config(origin, token)
        with httpx.Client(base_url=origin, headers={"Authorization": "Bearer " + token},
                          follow_redirects=False, trust_env=False) as client:
            result = run(client, **vars(args))
    except MaintenanceError as error:
        result = {"status": "failed", "reason": str(error)}
    except KeyboardInterrupt:
        result = {"status": "failed", "reason": "INTERRUPTED"}
    print(json.dumps(result, ensure_ascii=True))
    return {"complete": 0, "incomplete": 2, "failed": 1}[result["status"]]


if __name__ == "__main__":
    raise SystemExit(main())
