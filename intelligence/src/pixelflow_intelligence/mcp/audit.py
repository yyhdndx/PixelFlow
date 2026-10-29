"""Append-only audit log for MCP tool calls.

One JSON object per line. Query text, titles and free-form content are never
stored: they are reduced to a digest or a length so the log stays shareable.
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path

# Values that are safe and useful for debugging when stored verbatim.
VERBATIM = {"post_id", "limit", "active", "action", "user_id_ref"}
DIGEST = {"query", "user_id"}


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


class AuditLog:
    def __init__(self, path: str = "", stream=None):
        self.path = path
        self.stream = stream or sys.stderr

    def _summarize(self, params: dict) -> dict:
        summary = {}
        for key, value in (params or {}).items():
            if key in VERBATIM and isinstance(value, (str, int, bool)):
                summary[key] = value
            elif key in DIGEST and isinstance(value, str):
                summary[key] = digest(value)
            elif isinstance(value, str):
                summary[f"{key}_len"] = len(value)
        return summary

    def record(self, *, tool: str, account: str, status: str, params: dict | None = None,
               duration_ms: float = 0.0, error: str | None = None) -> dict:
        entry = {
            "ts": round(time.time(), 3),
            "event": "mcp_tool",
            "tool": tool,
            "account": account,
            "status": status,
            "params": self._summarize(params or {}),
            "duration_ms": round(duration_ms, 1),
        }
        if error:
            entry["error"] = error
        line = json.dumps(entry, ensure_ascii=False, sort_keys=True)
        if self.path:
            path = Path(self.path)
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")
        else:
            print(line, file=self.stream, flush=True)
        return entry
