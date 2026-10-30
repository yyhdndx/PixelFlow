"""MCP server assembly: six creator tools over stdio, static bearer or OAuth.

- stdio: one service account, no browser login (local development)
- streamable-http + PIXELFLOW_MCP_HTTP_TOKEN: pre-shared bearer (service-to-service)
- streamable-http without that token: OAuth 2.1 authorization code + PKCE, so each
  client acts as the human who logged in
"""
from __future__ import annotations

from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.mcpserver import MCPServer
from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse

from .audit import AuditLog
from .auth import ServiceAccount
from .config import SUPPORTED_SCOPES, McpSettings
from .gateway import JavaGateway
from .oauth import (LoginError, OAuthBearerSource, PixelFlowOAuthProvider,
                    ServiceAccountSource, login_page)
from .remote import StaticTokenVerifier
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
                  tools: CreatorTools | None = None,
                  http_token: str = "",
                  oauth: bool = False) -> MCPServer:
    settings = settings or McpSettings.from_env()
    provider = PixelFlowOAuthProvider(settings) if oauth else None

    if tools is None:
        if oauth:
            tokens = OAuthBearerSource(provider)
        else:
            settings.require_credentials()
            tokens = ServiceAccountSource(ServiceAccount(settings))
        tools = CreatorTools(JavaGateway(settings, tokens), tokens,
                             AuditLog(settings.audit_log))

    if oauth:
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
                           version="0.2.0", instructions=INSTRUCTIONS,
                           auth_server_provider=provider, auth=auth)
    else:
        verifier = StaticTokenVerifier(http_token) if http_token else None
        static_auth = AuthSettings(issuer_url=settings.public_url,
                                   resource_server_url=settings.public_url,
                                   validate_token_resource=False) if http_token else None
        server = MCPServer(name="pixelflow-creator", title="PixelFlow 创作者工具",
                           version="0.2.0", instructions=INSTRUCTIONS,
                           token_verifier=verifier, auth=static_auth)

    for name in TOOL_NAMES:
        server.add_tool(getattr(tools, name), name=name,
                        description=TOOL_DESCRIPTIONS[name])

    if oauth:
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
