"""Bearer-token verification for the remote (Streamable HTTP) transport.

stdio trusts the operating system: whoever can launch the process owns it. A
remote endpoint has no such boundary, so the HTTP transport refuses to start
without a shared bearer token and rejects every request that does not carry it.
"""
from __future__ import annotations

import secrets

from mcp.server.auth.provider import AccessToken, TokenVerifier

MIN_TOKEN_LENGTH = 32


class StaticTokenVerifier(TokenVerifier):
    """Accept exactly one pre-shared bearer token.

    A single shared token is deliberate: the course deployment has one MCP
    server and one client (the operator's Agent). Per-client OAuth is described
    in contracts/mcp/README.md as the next step, not implemented here.
    """

    def __init__(self, token: str, client_id: str = "pixelflow-agent"):
        if not token or len(token) < MIN_TOKEN_LENGTH:
            raise ValueError(f"HTTP bearer token 至少需要 {MIN_TOKEN_LENGTH} 个字符")
        self._token = token
        self._client_id = client_id

    async def verify_token(self, token: str) -> AccessToken | None:
        if not token or not secrets.compare_digest(token, self._token):
            return None
        return AccessToken(token=token, client_id=self._client_id,
                           scopes=["creator:write"])


def require_http_token(value: str) -> str:
    """Fail closed: remote mode must never start without a bearer token."""
    if not value:
        raise ValueError(
            "远程模式必须设置 PIXELFLOW_MCP_HTTP_TOKEN（至少 32 位 ASCII）；"
            "stdio 模式不需要该变量")
    if len(value) < MIN_TOKEN_LENGTH or not value.isascii() or value.strip() != value:
        raise ValueError("PIXELFLOW_MCP_HTTP_TOKEN 必须是至少 32 位可打印 ASCII，且首尾无空白")
    return value
