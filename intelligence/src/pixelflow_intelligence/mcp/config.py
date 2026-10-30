"""Configuration for the PixelFlow MCP server.

Only OAuth is configured here: the public URL doubles as the OAuth issuer and
resource server. The Java URL stays server-side; no service account and no
pre-shared token exist any more.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit

SUPPORTED_SCOPES = ("media:write", "post:write", "post:read")


@dataclass(frozen=True)
class McpSettings:
    java_url: str = "http://127.0.0.1:8080"
    public_url: str = ""
    scopes: frozenset[str] = frozenset(SUPPORTED_SCOPES)
    audit_log: str = field(default="", repr=False)
    timeout_seconds: float = 8.0

    def __post_init__(self):
        url = urlsplit(self.java_url)
        if (url.scheme not in {"http", "https"} or not url.hostname or url.username
                or url.password or url.query or url.fragment or url.path not in {"", "/"}):
            raise ValueError("PIXELFLOW_MCP_JAVA_URL 必须是 HTTP(S) origin")
        public = urlsplit(self.public_url)
        if (public.scheme not in {"http", "https"} or not public.hostname or public.username
                or public.password or public.query or public.fragment
                or public.path not in {"", "/"}):
            raise ValueError("PIXELFLOW_MCP_PUBLIC_URL 必须是 HTTP(S) origin，"
                             "且外部可达（OAuth 的 issuer 与回调都基于它）")
        unknown = set(self.scopes) - set(SUPPORTED_SCOPES)
        if unknown:
            raise ValueError(f"未知 scope：{sorted(unknown)}")
        if not 1.0 <= float(self.timeout_seconds) <= 30.0:
            raise ValueError("PIXELFLOW_MCP_TIMEOUT 必须在 1 到 30 秒之间")

    @classmethod
    def from_env(cls) -> "McpSettings":
        raw_scopes = os.getenv("PIXELFLOW_MCP_SCOPES", ",".join(SUPPORTED_SCOPES))
        return cls(
            java_url=os.getenv("PIXELFLOW_MCP_JAVA_URL", "http://127.0.0.1:8080"),
            public_url=os.getenv("PIXELFLOW_MCP_PUBLIC_URL", ""),
            scopes=frozenset(item.strip() for item in raw_scopes.split(",") if item.strip()),
            audit_log=os.getenv("PIXELFLOW_MCP_AUDIT_LOG", ""),
            timeout_seconds=float(os.getenv("PIXELFLOW_MCP_TIMEOUT", "8")),
        )
