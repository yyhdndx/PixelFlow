"""MCP server assembly: six creator tools over the remote OAuth transport.

There is exactly one transport (Streamable HTTP) and one identity source
(OAuth 2.1 browser login). Each client acts as the human who logged in; no
shared machine account and no stdio entry point exist any more.
"""
from __future__ import annotations

from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse

from .audit import AuditLog
from .config import SUPPORTED_SCOPES, McpSettings
from .gateway import JavaGateway
from .oauth import (LoginError, OAuthBearerSource, PixelFlowOAuthProvider, login_page)
from .tools import CreatorTools

INSTRUCTIONS = (
    "PixelFlow 创作者工具。调用顺序：upload_media（可选）→ create_post_draft → "
    "submit_post_for_review。提交后作品进入 PENDING，必须由管理员审核才会公开；"
    "withdraw_post 可撤回自己的作品。所有写操作都以登录用户身份执行并留审计日志。"
)

TOOL_DESCRIPTIONS = {
    "upload_media": "上传一张 PNG/JPEG 图片（≤8MB）：本地路径或 base64 二选一，返回 media_id。",
    "create_post_draft": "创建一篇作品草稿，返回 post_id；支持 idempotency_key 重试去重。",
    "update_post_draft": "修改自己的草稿/被驳回作品；仅 DRAFT、REJECTED、WITHDRAWN 可改。",
    "submit_post_for_review": "提交作品审核，状态变为 PENDING（不会直接公开）。",
    "withdraw_post": "撤回/下架自己的作品，状态变为 WITHDRAWN，可再次编辑。",
    "list_my_posts": "列出自己的作品（含草稿），可按 status 与标题/正文关键词过滤。",
}

TOOL_NAMES = ("upload_media", "create_post_draft", "update_post_draft",
              "submit_post_for_review", "withdraw_post", "list_my_posts")


def create_server(settings: McpSettings | None = None,
                  tools: CreatorTools | None = None) -> MCPServer:
    settings = settings or McpSettings.from_env()
    provider = PixelFlowOAuthProvider(settings)

    if tools is None:
        tokens = OAuthBearerSource(provider)
        tools = CreatorTools(JavaGateway(settings, tokens), tokens,
                             AuditLog(settings.audit_log))

    auth = AuthSettings(
        issuer_url=settings.public_url,
        resource_server_url=settings.public_url,
        validate_token_resource=False,
        required_scopes=["post:read"],
        client_registration_options=ClientRegistrationOptions(
            enabled=True, valid_scopes=list(SUPPORTED_SCOPES),
            default_scopes=["post:read"]),
        revocation_options=RevocationOptions(enabled=True),
    )
    server = MCPServer(name="pixelflow-creator", title="PixelFlow 创作者工具",
                       version="0.3.0", instructions=INSTRUCTIONS,
                       auth_server_provider=provider, auth=auth)

    for name in TOOL_NAMES:
        server.add_tool(getattr(tools, name), name=name,
                        description=TOOL_DESCRIPTIONS[name])

    @server.custom_route("/oauth/login", methods=["GET", "POST"], include_in_schema=False)
    async def oauth_login(request: Request):
        if request.method == "GET":
            request_id = request.query_params.get("request_id", "")
            if provider.pending_login(request_id) is None:
                return HTMLResponse(
                    login_page(request_id, "登录请求已过期，请从客户端重新发起授权"),
                    status_code=400)
            return HTMLResponse(login_page(request_id))
        form = await request.form()
        request_id = str(form.get("request_id", ""))
        username = str(form.get("username", ""))
        password = str(form.get("password", ""))
        try:
            # Java login is blocking; keep the event loop free.
            target = await run_in_threadpool(provider.complete_login,
                                             request_id, username, password)
        except LoginError as error:
            return HTMLResponse(login_page(request_id, str(error)), status_code=401)
        return RedirectResponse(target, status_code=302)

    return server
