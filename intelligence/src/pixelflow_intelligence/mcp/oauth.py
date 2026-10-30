"""OAuth 2.1 authorization server for the remote MCP transport.

The MCP server is its own authorization server, but it never stores user
passwords: the login form posts them once to the existing Java /api/auth/login,
and only the resulting short-lived Java tokens are kept in memory, bound to an
opaque MCP access token. Restarting the process logs every client out.

Flow:  client → /authorize → login page → Java login → code (PKCE)
       → /token → opaque access token → Authorization: Bearer on /mcp
"""
from __future__ import annotations

import html
import secrets
import time
from dataclasses import dataclass
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import httpx
from mcp.server.auth.provider import (AccessToken, AuthorizationCode,
                                      OAuthAuthorizationServerProvider, RefreshToken)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

from .auth import ScopeError
from .config import McpSettings

ACCESS_TTL = 3600
REFRESH_TTL = 30 * 86400
CODE_TTL = 300
PENDING_TTL = 600
JAVA_REFRESH_MARGIN = 60


class LoginError(Exception):
    """Login failed; the message is safe to show on the form."""


@dataclass
class JavaSession:
    user_id: str
    username: str
    access: str = ""
    refresh: str = ""
    expires_at: float = 0.0


@dataclass
class PendingLogin:
    client_id: str
    scopes: list[str]
    code_challenge: str
    redirect_uri: str
    provided_explicitly: bool
    resource: str | None
    state: str | None
    expires_at: float


@dataclass
class CodeRecord:
    code: AuthorizationCode
    session: JavaSession
    client_id: str


@dataclass
class AccessRecord:
    token: str
    client_id: str
    scopes: list[str]
    expires_at: float
    session: JavaSession


@dataclass
class RefreshRecord:
    token: str
    client_id: str
    scopes: list[str]
    expires_at: float
    session: JavaSession


class PixelFlowOAuthProvider(OAuthAuthorizationServerProvider):
    def __init__(self, settings: McpSettings, transport: httpx.BaseTransport | None = None):
        self.settings = settings
        self.public_url = settings.public_url.rstrip("/")
        self._transport = transport
        self.clients: dict[str, OAuthClientInformationFull] = {}
        self.pending: dict[str, PendingLogin] = {}
        self.codes: dict[str, CodeRecord] = {}
        self.access: dict[str, AccessRecord] = {}
        self.refresh: dict[str, RefreshRecord] = {}

    # -- client registration ------------------------------------------------
    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        return self.clients.get(client_id)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        self.clients[client_info.client_id] = client_info

    # -- authorization ------------------------------------------------------
    async def authorize(self, client: OAuthClientInformationFull, params) -> str:
        # The SDK redirects the browser to whatever we return; send it to our
        # login page instead of issuing a code for an unauthenticated user.
        request_id = secrets.token_urlsafe(24)
        self.pending[request_id] = PendingLogin(
            client_id=client.client_id,
            scopes=list(params.scopes),
            code_challenge=params.code_challenge or "",
            redirect_uri=str(params.redirect_uri),
            provided_explicitly=bool(params.redirect_uri_provided_explicitly),
            resource=params.resource,
            state=params.state,
            expires_at=time.time() + PENDING_TTL,
        )
        return f"{self.public_url}/oauth/login?request_id={request_id}"

    def pending_login(self, request_id: str) -> PendingLogin | None:
        pending = self.pending.get(request_id)
        if pending is None or pending.expires_at < time.time():
            return None
        return pending

    def _java_call(self, method: str, path: str, *, json_body=None, token: str = "") -> dict:
        headers = {"Authorization": "Bearer " + token} if token else {}
        with httpx.Client(base_url=self.settings.java_url, timeout=self.settings.timeout_seconds,
                          trust_env=False, transport=self._transport) as client:
            response = client.request(method, path, json=json_body, headers=headers)
        if response.status_code >= 400:
            raise LoginError("账号或密码不正确，或账号已被停用")
        try:
            return response.json()
        except ValueError:
            raise LoginError("登录服务返回了无法解析的响应") from None

    def complete_login(self, request_id: str, username: str, password: str) -> str:
        """Validate credentials against Java and return the client redirect URL."""
        # Peek first: a wrong password must not burn the pending request, otherwise
        # the user cannot retry without restarting the whole authorization flow.
        pending = self.pending.get(request_id)
        if pending is None or pending.expires_at < time.time():
            self.pending.pop(request_id, None)
            raise LoginError("登录请求已过期，请从客户端重新发起授权")
        body = self._java_call("POST", "/api/auth/login",
                               json_body={"username": username, "password": password})
        access = body.get("accessToken") or ""
        refresh = body.get("refreshToken") or ""
        if not access or not refresh:
            raise LoginError("登录服务未返回令牌")
        identity = self._java_call("GET", "/api/users/me", token=access)
        user_id = identity.get("id")
        if not isinstance(user_id, str) or not user_id:
            raise LoginError("无法确认登录用户身份")
        # Success: the pending request is single-use from here on.
        self.pending.pop(request_id, None)
        session = JavaSession(user_id=user_id, username=username, access=access, refresh=refresh,
                              expires_at=time.time() + 840)
        code = secrets.token_urlsafe(32)
        self.codes[code] = CodeRecord(
            code=AuthorizationCode(code=code, scopes=pending.scopes, expires_at=time.time() + CODE_TTL,
                                   client_id=pending.client_id, code_challenge=pending.code_challenge,
                                   redirect_uri=pending.redirect_uri,
                                   redirect_uri_provided_explicitly=pending.provided_explicitly,
                                   resource=pending.resource, subject=user_id),
            session=session, client_id=pending.client_id)
        parts = urlsplit(pending.redirect_uri)
        query = parse_qsl(parts.query, keep_blank_values=True)
        query.append(("code", code))
        if pending.state:
            query.append(("state", pending.state))
        return urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))

    # -- token endpoints ----------------------------------------------------
    async def load_authorization_code(self, client, authorization_code: str):
        record = self.codes.get(authorization_code)
        if record is None or record.client_id != client.client_id:
            return None
        if record.code.expires_at < time.time():
            self.codes.pop(authorization_code, None)
            return None
        return record.code

    async def exchange_authorization_code(self, client, code) -> OAuthToken:
        record = self.codes.pop(code.code, None)
        if record is None or record.client_id != client.client_id:
            raise LoginError("授权码无效或已使用")
        return self._issue(record.session, list(code.scopes), client.client_id)

    async def load_refresh_token(self, client, refresh_token: str):
        record = self.refresh.get(refresh_token)
        if record is None or record.client_id != client.client_id:
            return None
        if record.expires_at < time.time():
            self.refresh.pop(refresh_token, None)
            return None
        return RefreshToken(token=record.token, client_id=record.client_id,
                            scopes=record.scopes, expires_at=int(record.expires_at),
                            subject=record.session.user_id)

    async def exchange_refresh_token(self, client, refresh_token, scopes: list[str]) -> OAuthToken:
        record = self.refresh.pop(refresh_token.token, None)
        if record is None or record.client_id != client.client_id:
            raise LoginError("刷新令牌无效")
        return self._issue(record.session, scopes or record.scopes, client.client_id)

    def _issue(self, session: JavaSession, scopes: list[str], client_id: str) -> OAuthToken:
        access_token = secrets.token_urlsafe(32)
        refresh_token = secrets.token_urlsafe(32)
        self.access[access_token] = AccessRecord(access_token, client_id, scopes,
                                                 time.time() + ACCESS_TTL, session)
        self.refresh[refresh_token] = RefreshRecord(refresh_token, client_id, scopes,
                                                    time.time() + REFRESH_TTL, session)
        return OAuthToken(access_token=access_token, token_type="Bearer", expires_in=ACCESS_TTL,
                          scope=" ".join(scopes), refresh_token=refresh_token)

    async def load_access_token(self, token: str) -> AccessToken | None:
        record = self.access.get(token)
        if record is None or record.expires_at < time.time():
            self.access.pop(token, None)
            return None
        return AccessToken(token=token, client_id=record.client_id, scopes=record.scopes,
                           expires_at=int(record.expires_at), subject=record.session.user_id)

    async def revoke_token(self, token) -> None:
        value = getattr(token, "token", None)
        if value:
            self.access.pop(value, None)
            self.refresh.pop(value, None)

    # -- downstream Java token ---------------------------------------------
    def java_access_token(self, access_token: str) -> str:
        """Return a usable Java access token, refreshing it shortly before expiry."""
        record = self.access.get(access_token)
        if record is None:
            raise PermissionError("MCP 访问令牌无效或已过期，请重新授权")
        session = record.session
        if session.expires_at - JAVA_REFRESH_MARGIN > time.time():
            return session.access
        body = self._java_call("POST", "/api/auth/refresh", json_body={"refreshToken": session.refresh})
        session.access = body.get("accessToken") or ""
        session.refresh = body.get("refreshToken") or session.refresh
        if not session.access:
            raise PermissionError("上游会话已过期，请重新授权")
        session.expires_at = time.time() + 840
        return session.access

    def user_id_for(self, access_token: str) -> str:
        record = self.access.get(access_token)
        return record.session.user_id if record else ""


class OAuthBearerSource:
    """Per-request token source: acts as the logged-in user, not the service account."""

    def __init__(self, provider: PixelFlowOAuthProvider):
        self.provider = provider

    def _current(self) -> AccessToken | None:
        from mcp.server.auth.middleware.auth_context import get_access_token
        return get_access_token()

    @property
    def name(self) -> str:
        token = self._current()
        return token.subject if token and token.subject else "unauthenticated"

    def require(self, scope: str) -> None:
        token = self._current()
        if token is None:
            raise ScopeError("缺少访问令牌，请先完成 OAuth 授权")
        if scope not in token.scopes:
            raise ScopeError(f"当前授权未包含 {scope}，调用已拒绝")

    def headers(self) -> dict:
        token = self._current()
        if token is None:
            raise PermissionError("缺少访问令牌，请先完成 OAuth 授权")
        return {"Authorization": "Bearer " + self.provider.java_access_token(token.token)}

    def invalidate(self) -> None:
        # Java tokens are refreshed inside the provider; nothing to drop here.
        return None


class ServiceAccountSource:
    """stdio mode: one machine account, no browser login."""

    def __init__(self, account):
        self.account = account

    @property
    def name(self) -> str:
        return self.account.name

    def require(self, scope: str) -> None:
        self.account.require(scope)

    def headers(self) -> dict:
        return self.account.headers()

    def invalidate(self) -> None:
        self.account.invalidate()


def login_page(request_id: str, error: str = "") -> str:
    safe_id = html.escape(request_id)
    notice = f'<p class="error">{html.escape(error)}</p>' if error else ""
    return f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>PixelFlow MCP 授权</title>
<style>
body{{font-family:system-ui,sans-serif;max-width:360px;margin:12vh auto;padding:0 1rem;color:#222}}
h1{{font-size:1.25rem}} label{{display:block;margin:.75rem 0 .25rem}}
input{{width:100%;padding:.55rem;box-sizing:border-box}}
button{{margin-top:1rem;width:100%;padding:.6rem;background:#111;color:#fff;border:0;border-radius:4px}}
.error{{color:#b00020}}.hint{{color:#666;font-size:.85rem}}
</style></head>
<body>
<h1>授权 PixelFlow MCP</h1>
<p class="hint">使用你的 PixelFlow 账号登录；密码只用于换取令牌，不会保存在 MCP 服务端。</p>
{notice}
<form method="post" action="/oauth/login">
  <input type="hidden" name="request_id" value="{safe_id}">
  <label for="username">用户名</label>
  <input id="username" name="username" autocomplete="username" required>
  <label for="password">密码</label>
  <input id="password" name="password" type="password" autocomplete="current-password" required>
  <button type="submit">登录并授权</button>
</form>
</body></html>"""
