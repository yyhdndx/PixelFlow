"""Creator-facing tools exposed over MCP.

Every tool validates its own inputs, checks the service-account scope before
touching the business API, and writes one audit line whether it succeeds, is
denied or fails. Business state still lives in Java; this module never writes
to a database directly.
"""
from __future__ import annotations

import re
import time
import uuid
from pathlib import Path

from .audit import AuditLog
from .auth import ScopeError, ServiceAccount
from .gateway import GatewayError, JavaGateway

POST_ID = re.compile(r"^[1-9][0-9]{0,17}$")
MEDIA_ID = re.compile(r"^[A-Za-z0-9_-]{1,36}$")
ALLOWED_IMAGES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}
MAX_IMAGE_BYTES = 8 * 1024 * 1024
POST_STATUSES = ("DRAFT", "PENDING", "PUBLISHED", "REJECTED", "WITHDRAWN")
LIST_WINDOW_PAGES = 3
LIST_PAGE_SIZE = 50


class ToolError(RuntimeError):
    """Stable, client-safe tool error."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _require_post_id(value: str) -> str:
    if not isinstance(value, str) or not POST_ID.match(value):
        raise ToolError("INVALID_ARGUMENT", "post_id 必须是十进制正整数")
    return value


def _require_text(value: str, name: str, limit: int, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str):
        raise ToolError("INVALID_ARGUMENT", f"{name} 必须是字符串")
    if not allow_empty and not value.strip():
        raise ToolError("INVALID_ARGUMENT", f"{name} 不能为空")
    if len(value) > limit:
        raise ToolError("INVALID_ARGUMENT", f"{name} 超过 {limit} 字符上限")
    return value


def _require_media_ids(values) -> list[str]:
    if values is None:
        return []
    if not isinstance(values, list):
        raise ToolError("INVALID_ARGUMENT", "media_ids 必须是字符串数组")
    if len(values) > 9:
        raise ToolError("INVALID_ARGUMENT", "media_ids 最多 9 个")
    result = []
    for value in values:
        if not isinstance(value, str) or not MEDIA_ID.match(value):
            raise ToolError("INVALID_ARGUMENT", "media_id 格式无效")
        if value in result:
            raise ToolError("INVALID_ARGUMENT", "media_ids 不能重复")
        result.append(value)
    return result


class CreatorTools:
    def __init__(self, gateway: JavaGateway, account: ServiceAccount, audit: AuditLog,
                 clock=time.perf_counter):
        self.gateway = gateway
        self.account = account
        self.audit = audit
        self.clock = clock

    # -- plumbing ----------------------------------------------------------
    def _run(self, tool: str, scope: str, params: dict, action):
        started = self.clock()
        try:
            self.account.require(scope)
            result = action()
        except ScopeError as error:
            self.audit.record(tool=tool, account=self.account.name, status="denied",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error="FORBIDDEN")
            raise ToolError("FORBIDDEN", str(error)) from None
        except ToolError as error:
            self.audit.record(tool=tool, account=self.account.name, status="error",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error=error.code)
            raise
        except GatewayError as error:
            self.audit.record(tool=tool, account=self.account.name, status="error",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error=error.code)
            raise ToolError(error.code, error.message) from None
        self.audit.record(tool=tool, account=self.account.name, status="ok",
                          params=params, duration_ms=(self.clock() - started) * 1000)
        return result

    # -- 1. media ----------------------------------------------------------
    def upload_media(self, file_path: str) -> dict:
        """上传一张本地图片（PNG/JPEG，≤8MB），返回 media_id 供草稿引用。"""
        def action():
            path = Path(file_path).expanduser()
            if not path.is_absolute():
                raise ToolError("INVALID_ARGUMENT", "file_path 必须是绝对路径")
            if not path.is_file():
                raise ToolError("INVALID_ARGUMENT", "file_path 指向的文件不存在")
            content_type = ALLOWED_IMAGES.get(path.suffix.lower())
            if not content_type:
                raise ToolError("INVALID_ARGUMENT", "仅支持 PNG 或 JPEG 图片")
            size = path.stat().st_size
            if size > MAX_IMAGE_BYTES:
                raise ToolError("MEDIA_TOO_LARGE", "图片超过 8MB 上限")
            content = path.read_bytes()
            body = self.gateway.upload_media(path.name, content, content_type)
            return {
                "media_id": body.get("mediaId"),
                "width": body.get("width"),
                "height": body.get("height"),
                "size": body.get("size"),
                "content_type": body.get("contentType"),
            }

        return self._run("upload_media", "media:write", {"file_path": str(file_path)}, action)

    # -- 2. create draft ---------------------------------------------------
    def create_post_draft(self, title: str, content: str, media_ids: list[str] | None = None,
                          idempotency_key: str | None = None) -> dict:
        """创建一篇草稿，返回 post_id；同一 idempotency_key 重试不会重复建稿。"""
        def action():
            media = _require_media_ids(media_ids)
            draft = {
                "title": _require_text(title, "title", 120),
                "content": _require_text(content, "content", 5000),
                "mediaIds": media,
            }
            key = idempotency_key or str(uuid.uuid4())
            if not re.match(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                            r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", key):
                raise ToolError("INVALID_ARGUMENT", "idempotency_key 必须是 UUID")
            body = self.gateway.create_post(draft, key.lower())
            return {
                "post_id": body.get("post_id"),
                "status": body.get("status"),
                "title": body.get("title"),
                "media_ids": body.get("media_ids", media),
                "idempotency_key": key.lower(),
            }

        return self._run("create_post_draft", "post:write",
                         {"title_len": len(title or ""), "media_count": len(media_ids or [])},
                         action)

    # -- 3. update draft ---------------------------------------------------
    def update_post_draft(self, post_id: str, title: str, content: str,
                          media_ids: list[str] | None = None) -> dict:
        """修改自己的草稿；仅草稿、被驳回或已下架的作品可改。"""
        def action():
            identifier = _require_post_id(post_id)
            media = _require_media_ids(media_ids)
            draft = {
                "title": _require_text(title, "title", 120),
                "content": _require_text(content, "content", 5000),
                "mediaIds": media,
            }
            body = self.gateway.edit_post(identifier, draft)
            return {
                "post_id": body.get("post_id", identifier),
                "status": body.get("status"),
                "title": body.get("title"),
                "media_ids": body.get("media_ids", media),
            }

        return self._run("update_post_draft", "post:write",
                         {"post_id": post_id, "title_len": len(title or ""),
                          "media_count": len(media_ids or [])}, action)

    # -- 4. submit ---------------------------------------------------------
    def submit_post_for_review(self, post_id: str) -> dict:
        """提交审核；作品进入 PENDING，需管理员审核后才会公开。"""
        def action():
            body = self.gateway.publish_post(_require_post_id(post_id))
            return {"post_id": body.get("postId", post_id), "status": body.get("status")}

        return self._run("submit_post_for_review", "post:write", {"post_id": post_id}, action)

    # -- 5. withdraw -------------------------------------------------------
    def withdraw_post(self, post_id: str) -> dict:
        """撤回/下架自己的作品，状态变为 WITHDRAWN，可再次编辑后重新提交。"""
        def action():
            body = self.gateway.withdraw_post(_require_post_id(post_id))
            return {"post_id": body.get("postId", post_id), "status": body.get("status")}

        return self._run("withdraw_post", "post:write", {"post_id": post_id}, action)

    # -- 6. list -----------------------------------------------------------
    def list_my_posts(self, status: str | None = None, query: str | None = None,
                      limit: int = 10) -> dict:
        """列出自己的作品（含草稿），可按状态与标题/正文关键词过滤。"""
        def action():
            if not isinstance(limit, int) or not 1 <= limit <= 20:
                raise ToolError("INVALID_ARGUMENT", "limit 必须在 1 到 20 之间")
            wanted = status.upper() if isinstance(status, str) and status else None
            if wanted is not None and wanted not in POST_STATUSES:
                raise ToolError("INVALID_ARGUMENT",
                                "status 只能是 " + "、".join(POST_STATUSES))
            keyword = query.strip().casefold() if isinstance(query, str) and query.strip() else None
            if keyword and len(keyword) > 60:
                raise ToolError("INVALID_ARGUMENT", "query 超过 60 字符上限")
            collected: list[dict] = []
            cursor = None
            for _ in range(LIST_WINDOW_PAGES):
                page = self.gateway.list_my_posts(count=LIST_PAGE_SIZE, cursor=cursor)
                collected.extend(page)
                if len(page) < LIST_PAGE_SIZE:
                    break
                try:
                    cursor = int(page[-1]["post_id"])
                except (KeyError, TypeError, ValueError):
                    break
            matched = []
            for post in collected:
                if wanted and post.get("status") != wanted:
                    continue
                if keyword:
                    haystack = f"{post.get('title', '')}\n{post.get('content', '')}".casefold()
                    if keyword not in haystack:
                        continue
                matched.append({
                    "post_id": post.get("post_id"),
                    "title": post.get("title"),
                    "status": post.get("status"),
                    "create_time": post.get("create_time"),
                    "media_count": len(post.get("media_ids") or []),
                })
                if len(matched) >= limit:
                    break
            return {"total_scanned": len(collected), "items": matched,
                    "has_more": len(matched) >= limit and len(collected) >= limit}

        return self._run("list_my_posts", "post:read",
                         {"status": (status or "").upper() or None, "limit": limit,
                          "query": (query or "").strip() or None}, action)
