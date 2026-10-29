"""Entry point: ``python -m pixelflow_intelligence.mcp``.

stdio is the default because that is what desktop MCP clients (Claude Desktop,
Cherry Studio, Cursor, Trae) launch directly. Use ``--transport
streamable-http`` for a remote/shared server; stdout stays clean in both modes
because MCP protocol frames and logs are kept apart.
"""
from __future__ import annotations

import argparse
import asyncio
import sys

from .server import create_server


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="PixelFlow MCP server (creator tools)")
    parser.add_argument("--transport", choices=("stdio", "streamable-http"),
                        default="stdio", help="stdio for desktop clients, streamable-http for remote")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)

    server = create_server()
    try:
        if args.transport == "stdio":
            server.run("stdio")
        else:
            asyncio.run(server.run_streamable_http_async(host=args.host, port=args.port))
    except KeyboardInterrupt:
        return 130
    except Exception as error:  # noqa: BLE001 - startup failures must reach stderr
        print(f"PixelFlow MCP server 启动失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
