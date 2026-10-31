"""Entry point: ``python -m pixelflow_intelligence.mcp``.

The server always speaks Streamable HTTP and always uses OAuth 2.1: the client
opens a browser, the user logs in with a real PixelFlow account, and every tool
call runs as that user. There is no stdio entry point and no shared token.
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys

from .config import McpSettings
from .server import create_server


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="PixelFlow MCP server (creator tools, OAuth)")
    parser.add_argument("--host", default="127.0.0.1",
                        help="监听地址；对外暴露时应放在 Nginx/TLS 之后")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)

    if os.getenv("PIXELFLOW_MCP_HTTP_TOKEN"):
        print("提示：静态 token 模式已移除，PIXELFLOW_MCP_HTTP_TOKEN 被忽略；"
              "远程一律使用 OAuth 2.1", file=sys.stderr)
    if os.getenv("PIXELFLOW_MCP_SERVICE_USERNAME") or os.getenv("PIXELFLOW_MCP_SERVICE_PASSWORD"):
        print("提示：服务账号模式已移除，PIXELFLOW_MCP_SERVICE_* 被忽略", file=sys.stderr)

    try:
        settings = McpSettings.from_env()
    except ValueError as error:
        print(f"PixelFlow MCP server 拒绝启动：{error}", file=sys.stderr)
        return 2
    if not settings.public_url:
        print("PixelFlow MCP server 拒绝启动：必须设置 PIXELFLOW_MCP_PUBLIC_URL", file=sys.stderr)
        return 2

    try:
        server = create_server(settings=settings)
    except (ValueError, PermissionError) as error:
        print(f"PixelFlow MCP server 启动失败：{error}", file=sys.stderr)
        return 1
    try:
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
