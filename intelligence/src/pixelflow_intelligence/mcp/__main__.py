"""Entry point: ``python -m pixelflow_intelligence.mcp``.

stdio 是本地开发通道（服务账号，无浏览器登录）。
``--transport streamable-http`` 是部署通道，默认使用 OAuth 2.1 授权码 + PKCE：
客户端打开浏览器登录 PixelFlow，MCP 为每个用户签发独立令牌。
如果显式设置 ``PIXELFLOW_MCP_HTTP_TOKEN``（≥32 位），则退回预共享 Bearer，
用于服务对服务或临时排障。
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys

from .config import McpSettings
from .remote import require_http_token
from .server import create_server


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="PixelFlow MCP server (creator tools)")
    parser.add_argument("--transport", choices=("stdio", "streamable-http"),
                        default="stdio", help="stdio for local dev, streamable-http for deployment")
    parser.add_argument("--host", default="127.0.0.1",
                        help="仅对 streamable-http 生效；对外暴露时应放在 Nginx/TLS 之后")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)

    try:
        settings = McpSettings.from_env()
    except ValueError as error:
        print(f"PixelFlow MCP server 拒绝启动：{error}", file=sys.stderr)
        return 2

    http_token = ""
    oauth = False
    if args.transport == "streamable-http":
        raw_token = os.getenv("PIXELFLOW_MCP_HTTP_TOKEN", "")
        if raw_token:
            try:
                http_token = require_http_token(raw_token)
            except ValueError as error:
                print(f"PixelFlow MCP server 拒绝启动：{error}", file=sys.stderr)
                return 2
            print("MCP 鉴权：预共享 Bearer（静态 token）", file=sys.stderr)
        else:
            oauth = True
            print(f"MCP 鉴权：OAuth 2.1，issuer={settings.public_url}", file=sys.stderr)

    try:
        server = create_server(settings=settings, http_token=http_token, oauth=oauth)
    except (ValueError, PermissionError) as error:
        print(f"PixelFlow MCP server 启动失败：{error}", file=sys.stderr)
        return 1
    try:
        if args.transport == "stdio":
            server.run("stdio")
        else:
            # 8MB 图片经 base64 后约 11MB，默认 4MB 请求体上限会先拒绝。
            asyncio.run(server.run_streamable_http_async(
                host=args.host, port=args.port,
                streamable_http_path="/mcp",
                max_request_body_size=12 * 1024 * 1024))
    except KeyboardInterrupt:
        return 130
    except Exception as error:  # noqa: BLE001 - startup failures must reach stderr
        print(f"PixelFlow MCP server 启动失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
