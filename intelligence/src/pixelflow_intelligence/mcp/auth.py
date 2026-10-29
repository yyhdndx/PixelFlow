"""Service-account authentication for MCP tools.

The MCP server never reuses a human user's token. It logs in as its own Java
account (a real ``users`` row with the CREATOR role, per the course RBAC
model), caches the short-lived access token and refreshes it on demand.
"""
from __future__ import annotations

import time

import httpx

from .config import McpSettings


class ScopeError(PermissionError):
    """Tool call rejected because the service account lacks the scope."""


class ServiceAccount:
    def __init__(self, settings: McpSettings, transport: httpx.BaseTransport | None = None):
        self.settings = settings
        self._transport = transport
        self._access = ""
        self._refresh = ""
        self._obtained = 0.0
        self._clock = time.monotonic

    @property
    def name(self) -> str:
        return self.settings.username

    def require(self, scope: str) -> None:
        if scope not in self.settings.scopes:
            raise ScopeError(f"服务账号未被授予 {scope} 权限，调用已拒绝")

    def _login(self) -> None:
        self.settings.require_credentials()
        with httpx.Client(base_url=self.settings.java_url, timeout=self.settings.timeout_seconds,
                          trust_env=False, transport=self._transport) as client:
            response = client.post("/api/auth/login", json={
                "username": self.settings.username, "password": self.settings.password})
        if response.status_code != 200:
            raise PermissionError("MCP 服务账号登录失败，请检查账号密码与服务状态")
        body = response.json()
        self._access = body["accessToken"]
        self._refresh = body["refreshToken"]
        self._obtained = self._clock()

    def headers(self) -> dict:
        # Access tokens live 15 minutes; refresh a little earlier to avoid races.
        if not self._access or self._clock() - self._obtained > 480:
            self._login()
        return {"Authorization": "Bearer " + self._access}

    def invalidate(self) -> None:
        self._access = ""
