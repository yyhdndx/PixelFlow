"""Unit tests for the creator MCP tools (no network, no Java process)."""
import asyncio
import base64
import json
from pathlib import Path

import pytest

from pixelflow_intelligence.mcp.audit import AuditLog
from pixelflow_intelligence.mcp.auth import ScopeError
from pixelflow_intelligence.mcp.config import McpSettings
from pixelflow_intelligence.mcp.gateway import GatewayError
from pixelflow_intelligence.mcp.remote import StaticTokenVerifier, require_http_token
from pixelflow_intelligence.mcp.server import create_server
from pixelflow_intelligence.mcp.tools import CreatorTools, ToolError

PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082")


class FakeAccount:
    name = "mcp_creator"

    def __init__(self, scopes=("media:write", "post:write", "post:read")):
        self.scopes = set(scopes)

    def require(self, scope):
        if scope not in self.scopes:
            raise ScopeError(f"服务账号未被授予 {scope} 权限，调用已拒绝")

    def headers(self):
        return {"Authorization": "Bearer fake-service-token"}

    def invalidate(self):
        return None


class FakeGateway:
    def __init__(self):
        self.calls = []
        self.pages = [[], []]
        self.created = 0

    def upload_media(self, filename, content, content_type):
        self.calls.append(("upload", filename, content_type, len(content)))
        return {"mediaId": "m_1", "url": "/api/media/m_1/content", "width": 1, "height": 1,
                "type": 0, "size": len(content), "contentType": "image/png"}

    def create_post(self, draft, idempotency_key):
        self.calls.append(("create", draft, idempotency_key))
        self.created += 1
        return {"post_id": "1001", "status": "DRAFT", "title": draft["title"],
                "media_ids": draft["mediaIds"]}

    def edit_post(self, post_id, draft):
        self.calls.append(("edit", post_id, draft))
        return {"post_id": post_id, "status": "DRAFT", "title": draft["title"],
                "media_ids": draft["mediaIds"]}

    def publish_post(self, post_id):
        self.calls.append(("publish", post_id))
        return {"postId": post_id, "status": "PENDING"}

    def withdraw_post(self, post_id):
        self.calls.append(("withdraw", post_id))
        return {"postId": post_id, "status": "WITHDRAWN"}

    def list_my_posts(self, count=50, cursor=None):
        index = 0 if cursor is None else 1
        return self.pages[index] if index < len(self.pages) else []


def make_tools(tmp_path, scopes=None):
    audit_path = tmp_path / "audit.jsonl"
    tools = CreatorTools(FakeGateway(), FakeAccount(scopes or ("media:write", "post:write", "post:read")),
                         AuditLog(str(audit_path)))
    return tools, audit_path


def audit_lines(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def test_create_draft_passes_media_and_records_audit(tmp_path):
    tools, audit = make_tools(tmp_path)
    result = tools.create_post_draft("冬天第一杯手冲", "正文内容", ["m_1"])
    assert result["post_id"] == "1001" and result["status"] == "DRAFT"
    kind, draft, key = tools.gateway.calls[0]
    assert kind == "create" and draft["mediaIds"] == ["m_1"] and len(key) == 36
    entry = audit_lines(audit)[0]
    assert entry["tool"] == "create_post_draft" and entry["status"] == "ok"
    assert "冬天第一杯手冲" not in audit.read_text(encoding="utf-8")


def test_create_draft_rejects_bad_inputs_without_calling_java(tmp_path):
    tools, audit = make_tools(tmp_path)
    with pytest.raises(ToolError) as bad_media:
        tools.create_post_draft("标题", "正文", ["bad id!"])
    assert bad_media.value.code == "INVALID_ARGUMENT"
    with pytest.raises(ToolError):
        tools.create_post_draft("", "正文")
    with pytest.raises(ToolError):
        tools.create_post_draft("标题" * 61, "正文")
    assert tools.gateway.calls == []
    assert [line["status"] for line in audit_lines(audit)] == ["error", "error", "error"]


def test_upload_media_returns_id_and_rejects_other_formats(tmp_path):
    tools, _ = make_tools(tmp_path)
    image = tmp_path / "cover.png"
    image.write_bytes(PNG)
    result = tools.upload_media(str(image))
    assert result == {"media_id": "m_1", "width": 1, "height": 1,
                      "size": len(PNG), "content_type": "image/png"}

    text = tmp_path / "cover.gif"
    text.write_bytes(b"GIF89a")
    with pytest.raises(ToolError) as error:
        tools.upload_media(str(text))
    assert error.value.code == "INVALID_ARGUMENT"
    with pytest.raises(ToolError):
        tools.upload_media("relative.png")


def test_update_submit_withdraw_and_post_id_validation(tmp_path):
    tools, _ = make_tools(tmp_path)
    assert tools.update_post_draft("1001", "新标题", "新正文")["status"] == "DRAFT"
    assert tools.submit_post_for_review("1001")["status"] == "PENDING"
    assert tools.withdraw_post("1001")["status"] == "WITHDRAWN"
    for bad in ("0", "-1", "abc", ""):
        with pytest.raises(ToolError) as error:
            tools.submit_post_for_review(bad)
        assert error.value.code == "INVALID_ARGUMENT"


def test_list_my_posts_filters_status_and_keyword(tmp_path):
    tools, _ = make_tools(tmp_path)
    tools.gateway.pages = [[
        {"post_id": "3", "title": "咖啡手冲", "content": "冬季", "status": "DRAFT", "create_time": 3,
         "media_ids": ["m_1"]},
        {"post_id": "2", "title": "咖啡店", "content": "探店", "status": "PUBLISHED", "create_time": 2,
         "media_ids": []},
        {"post_id": "1", "title": "旧草稿", "content": "别的", "status": "DRAFT", "create_time": 1,
         "media_ids": []},
    ]]
    drafts = tools.list_my_posts(status="draft", limit=10)
    assert [item["post_id"] for item in drafts["items"]] == ["3", "1"]
    assert drafts["items"][0]["media_count"] == 1
    found = tools.list_my_posts(query="咖啡", limit=10)
    assert [item["post_id"] for item in found["items"]] == ["3", "2"]
    assert tools.list_my_posts(status="DRAFT", limit=1)["items"][0]["post_id"] == "3"
    with pytest.raises(ToolError):
        tools.list_my_posts(status="UNKNOWN")
    with pytest.raises(ToolError):
        tools.list_my_posts(limit=0)


def test_missing_scope_is_denied_before_any_javascript_call(tmp_path):
    tools, audit = make_tools(tmp_path, scopes=("post:read",))
    with pytest.raises(ToolError) as error:
        tools.create_post_draft("标题", "正文")
    assert error.value.code == "FORBIDDEN"
    assert tools.gateway.calls == []
    entry = audit_lines(audit)[0]
    assert entry["status"] == "denied" and entry["error"] == "FORBIDDEN"


def test_upstream_error_is_sanitized(tmp_path):
    class BrokenGateway(FakeGateway):
        def publish_post(self, post_id):
            raise GatewayError("CONFLICT", "当前作品状态不允许该操作", 409)

    tools = CreatorTools(BrokenGateway(), FakeAccount(), AuditLog(str(tmp_path / "a.jsonl")))
    with pytest.raises(ToolError) as error:
        tools.submit_post_for_review("1001")
    assert error.value.code == "CONFLICT"
    assert "trace" not in str(error.value).lower()


def test_server_registers_six_named_tools(tmp_path):
    tools, _ = make_tools(tmp_path)
    settings = McpSettings(username="mcp_creator", password="secret-value")
    server = create_server(settings, tools)
    listed = asyncio.run(server.list_tools())
    assert {tool.name for tool in listed} == {
        "upload_media", "create_post_draft", "update_post_draft",
        "submit_post_for_review", "withdraw_post", "list_my_posts"}
    assert all(tool.description for tool in listed)


def test_upload_media_accepts_base64_for_remote_mode(tmp_path):
    tools, _ = make_tools(tmp_path)
    result = tools.upload_media(content_base64=base64.b64encode(PNG).decode(),
                                filename="cover.png")
    assert result["media_id"] == "m_1" and result["content_type"] == "image/png"
    with pytest.raises(ToolError) as both:
        tools.upload_media(file_path=str(tmp_path / "a.png"),
                           content_base64=base64.b64encode(PNG).decode())
    assert both.value.code == "INVALID_ARGUMENT"
    with pytest.raises(ToolError):
        tools.upload_media(content_base64="not-base64!", filename="cover.png")
    with pytest.raises(ToolError):
        tools.upload_media(content_base64=base64.b64encode(PNG).decode(), filename="cover.gif")


def test_remote_mode_requires_a_strong_bearer_token():
    with pytest.raises(ValueError):
        require_http_token("")
    with pytest.raises(ValueError):
        require_http_token("too-short")
    token = "t" * 40
    assert require_http_token(token) == token
    with pytest.raises(ValueError):
        McpSettings(http_token="short")


def test_static_token_verifier_accepts_only_the_configured_token():
    token = "s" * 40
    verifier = StaticTokenVerifier(token)
    accepted = asyncio.run(verifier.verify_token(token))
    assert accepted is not None and accepted.client_id == "pixelflow-agent"
    assert asyncio.run(verifier.verify_token("x" * 40)) is None
    assert asyncio.run(verifier.verify_token("")) is None


def test_http_server_is_built_with_the_verifier(tmp_path):
    tools, _ = make_tools(tmp_path)
    settings = McpSettings(username="mcp_creator", password="secret-value")
    server = create_server(settings, tools, http_token="h" * 40)
    listed = asyncio.run(server.list_tools())
    assert len(listed) == 6
