"""MCP server exposing PixelFlow capabilities as tools.

The Agent (planning, multi-turn, follow-up) stays on the client side; this
package only owns the Skill/MCP layers: a service-account authenticated
gateway to the Java business API plus five audited MCP tools.
"""
from .server import create_server

__all__ = ["create_server"]
