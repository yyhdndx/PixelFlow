"""Creator-facing tools exposed over MCP.

Every tool validates its own inputs, checks the granted token scope before
touching the business API, and writes one audit line whether it succeeds, is
denied or fails. Business state still lives in Java; this module never writes
to a database directly.
"""
from __future__ import annotations

import base64
import binascii
import re
import time
import uuid
from pathlib import Path

from .audit import AuditLog
from .auth import ScopeError
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
    def __init__(self, gateway: JavaGateway, tokens, audit: AuditLog,
                 clock=time.perf_counter):
        self.gateway = gateway
        self.tokens = tokens
        self.audit = audit
        self.clock = clock

    # -- plumbing ----------------------------------------------------------
    def _run(self, tool: str, scope: str, params: dict, action):
        started = self.clock()
        try:
            self.tokens.require(scope)
            result = action()
        except ScopeError as error:
            self.audit.record(tool=tool, account=self.tokens.name, status="denied",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error="FORBIDDEN")
            raise ToolError("FORBIDDEN", str(error)) from None
        except PermissionError as error:
            self.audit.record(tool=tool, account=self.tokens.name, status="error",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error="UNAUTHORIZED")
            raise ToolError("UNAUTHORIZED", str(error)) from None
        except ToolError as error:
            self.audit.record(tool=tool, account=self.tokens.name, status="error",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error=error.code)
            raise
        except GatewayError as error:
            self.audit.record(tool=tool, account=self.tokens.name, status="error",
                              params=params, duration_ms=(self.clock() - started) * 1000,
                              error=error.code)
            raise ToolError(error.code, error.message) from None
        self.audit.record(tool=tool, account=self.tokens.name, status="ok",
                          params=params, duration_ms=(self.clock() - started) * 1000)
        return result

    # -- 1. media ----------------------------------------------------------
    def upload_media(self, file_path: str | None = None, content_base64: str | None = None,
                     filename: str | None = None) -> dict:
        """上传一张图片（PNG/JPEG，≤8MB）：本地绝对路径或 base64 二选一。

        远程模式（MCP 跑在服务器上）用 ``content_base64`` + ``filename``；本地
        本机调试或运维脚本可以直接给 ``file_path``。
        """
        def action():
            if bool(file_path) == bool(content_base64):
                raise ToolError("INVALID_ARGUMENT",
                                "file_path 与 content_base64 必须二选一，且只能提供其中一个")
            if file_path:
                path = Path(file_path).expanduser()
                if not path.is_absolute():
                    raise ToolError("INVALID_ARGUMENT", "file_path 必须是绝对路径")
                if not path.is_file():
                    raise ToolError("INVALID_ARGUMENT", "file_path 指向的文件不存在")
                name = path.name
                content_type = ALLOWED_IMAGES.get(path.suffix.lower())
                if not content_type:
                    raise ToolError("INVALID_ARGUMENT", "仅支持 PNG 或 JPEG 图片")
                content = path.read_bytes()
            else:
                name = (filename or "").strip()
                if not name or "/" in name or "\\" in name:
                    raise ToolError("INVALID_ARGUMENT", "filename 必须是纯文件名")
                content_type = ALLOWED_IMAGES.get(Path(name).suffix.lower())
                if not content_type:
                    raise ToolError("INVALID_ARGUMENT", "filename 需以 .png/.jpg/.jpeg 结尾")
                try:
                    content = base64.b64decode(content_base64, validate=True)
                except (binascii.Error, ValueError):
                    raise ToolError("INVALID_ARGUMENT", "content_base64 不是合法的 base64") from None
            if len(content) > MAX_IMAGE_BYTES:
                raise ToolError("MEDIA_TOO_LARGE", "图片超过 8MB 上限")
            body = self.gateway.upload_media(name, content, content_type)
            return {
                "media_id": body.get("mediaId"),
                "width": body.get("width"),
                "height": body.get("height"),
                "size": body.get("size"),
                "content_type": body.get("contentType"),
            }

        params = {"filename": filename or "", "file_path": file_path or "",
                  "base64_len": len(content_base64 or "")}
        return self._run("upload_media", "media:write", params, action)

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

    # -- 7. 读取公开帖子 ---------------------------------------------------
    def get_post(self, post_id: str) -> dict:
        """查看一篇公开帖子的标题、正文与媒体列表。"""
        def action():
            pid = _require_post_id(post_id)
            post = self.gateway.get_post(pid)
            return {
                "post_id": str(post.get("post_id") or post.get("id") or pid),
                "title": post.get("title"),
                "content": post.get("content"),
                "author": (post.get("author") or {}).get("nickname") if isinstance(post.get("author"), dict) else post.get("author"),
                "like_count": post.get("like_count"),
                "media_ids": post.get("media_ids") or [],
                "create_time": post.get("create_time"),
            }
        return self._run("get_post", "post:read", {"post_id": post_id}, action)

    # -- 8. 站内搜索 -------------------------------------------------------
    def search_posts(self, query: str, limit: int = 10) -> dict:
        """按关键词搜索站内公开帖子，返回标题与摘要。"""
        def action():
            keyword = _require_text(query, "query", 60)
            if not isinstance(limit, int) or not 1 <= limit <= 20:
                raise ToolError("INVALID_ARGUMENT", "limit 必须在 1 到 20 之间")
            body = self.gateway.search_posts(keyword, limit)
            items = body.get("items") if isinstance(body, dict) else body
            results = []
            for row in (items or [])[:limit]:
                if not isinstance(row, dict):
                    continue
                results.append({
                    "post_id": str(row.get("post_id") or row.get("id") or ""),
                    "title": row.get("title"),
                    "excerpt": (row.get("content") or "")[:120],
                })
            return {"query": keyword, "items": results, "count": len(results)}
        return self._run("search_posts", "post:read", {"query": query, "limit": limit}, action)

    # -- 9. 自动回复 -------------------------------------------------------
    def list_auto_replies(self, post_id: str) -> dict:
        """查看某篇自己作品的自动回复规则。"""
        def action():
            pid = _require_post_id(post_id)
            body = self.gateway.list_auto_replies(pid)
            rules = body.get("rules") if isinstance(body, dict) else body
            return {"post_id": pid, "rules": rules or []}
        return self._run("list_auto_replies", "post:read", {"post_id": post_id}, action)

    def create_auto_reply(self, post_id: str, question: str, answer: str) -> dict:
        """为自己的作品新增一条自动回复规则（评论命中问题时自动回复）。"""
        def action():
            pid = _require_post_id(post_id)
            q = _require_text(question, "question", 200)
            a = _require_text(answer, "answer", 300)
            body = self.gateway.create_auto_reply(pid, q, a)
            return {"post_id": pid, "rule_id": body.get("id"), "question": q, "answer": a}
        return self._run("create_auto_reply", "post:write",
                         {"post_id": post_id, "question": question, "answer": answer}, action)

    # -- 10. 评论 / 点赞收藏 / 私信 ---------------------------------------
    def create_comment(self, post_id: str, content: str) -> dict:
        """在公开帖子下发表一条评论（<=500 字）。"""
        def action():
            pid = _require_post_id(post_id)
            text = _require_text(content, "content", 500)
            body = self.gateway.create_comment(pid, text)
            return {"post_id": pid, "comment_id": body.get("commentId") or body.get("comment_id"),
                    "content": text}
        return self._run("create_comment", "post:write", {"post_id": post_id}, action)

    def toggle_like(self, post_id: str, active: bool = True) -> dict:
        """点赞或取消点赞一篇公开帖子。"""
        def action():
            pid = _require_post_id(post_id)
            if not isinstance(active, bool):
                raise ToolError("INVALID_ARGUMENT", "active 必须是布尔值")
            body = self.gateway.set_interaction("likes", pid, active)
            return {"post_id": pid, "active": bool(body.get("active", active))}
        return self._run("toggle_like", "post:write", {"post_id": post_id, "active": active}, action)

    def toggle_favorite(self, post_id: str, active: bool = True) -> dict:
        """收藏或取消收藏一篇公开帖子。"""
        def action():
            pid = _require_post_id(post_id)
            if not isinstance(active, bool):
                raise ToolError("INVALID_ARGUMENT", "active 必须是布尔值")
            body = self.gateway.set_interaction("favorites", pid, active)
            return {"post_id": pid, "active": bool(body.get("active", active))}
        return self._run("toggle_favorite", "post:write", {"post_id": post_id, "active": active}, action)

    def send_message(self, conversation_id: str, content: str) -> dict:
        """在指定会话里发一条私信（<=1000 字）。"""
        def action():
            cid = _require_text(conversation_id, "conversation_id", 36)
            text = _require_text(content, "content", 1000)
            body = self.gateway.send_messages([{"conversation_id": cid, "content": text}])
            return {"conversation_id": cid, "content": text, "result": body}
        return self._run("send_message", "post:write",
                         {"conversation_id": conversation_id}, action)

