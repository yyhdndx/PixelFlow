"""Errors shared by the MCP layer.

Identity is always a real user: the remote server runs OAuth 2.1, so there is no
service-account mode any more. Scope checks still happen per tool before any
business request is forwarded.
"""


class ScopeError(PermissionError):
    """Tool call rejected because the granted token lacks the scope."""
