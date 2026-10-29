"""本地冒烟：用 MCP 创作者工具走通“上传图片 → 建草稿 → 提交审核 → 撤回”。

前置：Java 服务已启动（真实 MySQL），并已注册服务账号（脚本可用 --register 代注册）。
本脚本只调用 MCP 工具层，不直接访问数据库，也不修改 Java 代码。

用法::

    intelligence/.venv/Scripts/python.exe intelligence/scripts/smoke_mcp_creator.py `
        --java-url http://127.0.0.1:18080 --username mcp_creator --password <密码> --register
"""
from __future__ import annotations

import argparse
import json
import tempfile
import time
from pathlib import Path

import httpx

from pixelflow_intelligence.mcp.audit import AuditLog
from pixelflow_intelligence.mcp.auth import ServiceAccount
from pixelflow_intelligence.mcp.config import SUPPORTED_SCOPES, McpSettings
from pixelflow_intelligence.mcp.gateway import JavaGateway
from pixelflow_intelligence.mcp.tools import CreatorTools

# 1x1 PNG：Java 端用 ImageIO 重新编码校验，必须是可解码的真实图片。
PNG_1X1 = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000a49444154789c63000100000500010d0a2db40000000049454e44ae426082")


def register(base: str, username: str, password: str) -> None:
    with httpx.Client(base_url=base, timeout=10, trust_env=False) as client:
        response = client.post("/api/auth/register",
                              json={"username": username, "password": password,
                                    "nickname": "MCP 创作者"})
    if response.status_code not in (201, 409):
        raise SystemExit(f"注册服务账号失败：HTTP {response.status_code}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Smoke test the PixelFlow creator MCP tools")
    parser.add_argument("--java-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="mcp_creator")
    parser.add_argument("--password", required=True)
    parser.add_argument("--register", action="store_true", help="账号不存在时先注册")
    parser.add_argument("--audit-log", default="")
    args = parser.parse_args(argv)

    if args.register:
        register(args.java_url, args.username, args.password)

    settings = McpSettings(java_url=args.java_url, username=args.username,
                           password=args.password, scopes=frozenset(SUPPORTED_SCOPES))
    account = ServiceAccount(settings)
    tools = CreatorTools(JavaGateway(settings, account), account,
                         AuditLog(args.audit_log or str(Path(tempfile.mkdtemp()) / "audit.jsonl")))
    steps = []

    with tempfile.TemporaryDirectory(prefix="mcp-smoke-") as folder:
        image = Path(folder) / "cover.png"
        image.write_bytes(PNG_1X1)

        media = tools.upload_media(str(image))
        steps.append({"step": "upload_media", "media_id": media["media_id"],
                      "size": media["size"]})

        draft = tools.create_post_draft(
            title="MCP 冒烟：手冲咖啡",
            content="由 MCP 创作者工具创建的草稿，用于验证上传、建稿、提交与撤回链路。",
            media_ids=[media["media_id"]])
        steps.append({"step": "create_post_draft", "post_id": draft["post_id"],
                      "status": draft["status"]})

        found = tools.list_my_posts(status="DRAFT", query="MCP", limit=5)
        steps.append({"step": "list_my_posts", "draft_hits": len(found["items"])})

        submitted = tools.submit_post_for_review(draft["post_id"])
        steps.append({"step": "submit_post_for_review", "status": submitted["status"]})

        withdrawn = tools.withdraw_post(draft["post_id"])
        steps.append({"step": "withdraw_post", "status": withdrawn["status"]})

    ok = (media["media_id"] and draft["post_id"] and draft["status"] == "DRAFT"
          and any(item["post_id"] == draft["post_id"] for item in found["items"])
          and submitted["status"] == "PENDING" and withdrawn["status"] == "WITHDRAWN")
    print(json.dumps({"ok": bool(ok), "steps": steps,
                      "finished_at": time.strftime("%Y-%m-%dT%H:%M:%S")},
                     ensure_ascii=False, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
