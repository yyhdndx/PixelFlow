"""Entry point: ``python -m pixelflow_intelligence.mcp``.

stdio is what desktop clients launch locally. ``--transport streamable-http``
serves a remote endpoint and **requires** ``PIXELFLOW_MCP_HTTP_TOKEN``; the
process refuses to start without it. stdout stays reserved for protocol frames.
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys

from .remote import require_http_token
from .server import create_server


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="PixelFlow MCP server (creator tools)")
    parser.add_argument("--transport", choices=("stdio", "streamable-http"),
                        default="stdio", help="stdio for desktop clients, streamable-http for remote")
    parser.add_argument("--host", default="127.0.0.1",
                        help="仅对 streamable-http 生效；对外暴露时应放在 Nginx/TLS 之后")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)

    http_token = ""
    if args.transport == "streamable-http":
        try:
            http_token = require_http_token(os.getenv("PIXELFLOW_MCP_HTTP_TOKEN", ""))
        except ValueError as error:
            print(f"PixelFlow MCP server 拒绝启动：{error}", file=sys.stderr)
            return 2

    try:
        server = create_server(http_token=http_token)
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
