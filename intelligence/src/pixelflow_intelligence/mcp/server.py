"""MCP server assembly: six creator tools over stdio or Streamable HTTP.

stdio trusts the OS process boundary. The HTTP transport additionally requires a
pre-shared bearer token, verified before any tool runs.
"""
from __future__ import annotations

from mcp.server.auth.settings import AuthSettings
from mcp.server.mcpserver import MCPServer

from .audit import AuditLog
from .auth import ServiceAccount
from .config import McpSettings
from .gateway import JavaGateway
from .remote import StaticTokenVerifier
from .tools import CreatorTools

INSTRUCTIONS = (
    "PixelFlow 创作者工具。调用顺序：upload_media（可选）→ create_post_draft → "
    "submit_post_for_review。提交后作品进入 PENDING，必须由管理员审核才会公开；"
    "withdraw_post 可撤回自己的作品。所有写操作都以独立服务账号身份执行并留审计日志。"
)

TOOL_DESCRIPTIONS = {
    "upload_media": "上传一张 PNG/JPEG 图片（≤8MB）：本地路径或 base64 二选一，返回 media_id。",
    "create_post_draft": "创建一篇作品草稿，返回 post_id；支持 idempotency_key 重试去重。",
    "update_post_draft": "修改自己的草稿/被驳回作品；仅 DRAFT、REJECTED、WITHDRAWN 可改。",
    "submit_post_for_review": "提交作品审核，状态变为 PENDING（不会直接公开）。",
    "withdraw_post": "撤回/下架自己的作品，状态变为 WITHDRAWN，可再次编辑。",
    "list_my_posts": "列出自己的作品（含草稿），可按 status 与标题/正文关键词过滤。",
}


def create_server(settings: McpSettings | None = None,
                  tools: CreatorTools | None = None,
                  http_token: str = "") -> MCPServer:
    settings = settings or McpSettings.from_env()
    settings.require_credentials()
    account = ServiceAccount(settings)
    creator = tools or CreatorTools(JavaGateway(settings, account), account,
                                    AuditLog(settings.audit_log))
    verifier = StaticTokenVerifier(http_token) if http_token else None
    auth = AuthSettings(issuer_url=settings.public_url,
                        resource_server_url=settings.public_url,
                        validate_token_resource=False) if http_token else None
    server = MCPServer(name="pixelflow-creator", title="PixelFlow 创作者工具",
                       version="0.1.0", instructions=INSTRUCTIONS,
                       token_verifier=verifier, auth=auth)
    for name in ("upload_media", "create_post_draft", "update_post_draft",
                 "submit_post_for_review", "withdraw_post", "list_my_posts"):
        server.add_tool(getattr(creator, name), name=name,
                        description=TOOL_DESCRIPTIONS[name])
    return server
