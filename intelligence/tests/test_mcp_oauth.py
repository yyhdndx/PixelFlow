"""OAuth 2.1 provider tests: authorization code + PKCE, token issue, refresh, revoke.

The Java backend is replaced by an httpx MockTransport, so no network is used.
"""
import asyncio
import time
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

import httpx
import pytest

from pixelflow_intelligence.mcp.config import McpSettings
from pixelflow_intelligence.mcp.oauth import LoginError, PixelFlowOAuthProvider


def settings(**overrides):
    values = dict(java_url="http://java.test", public_url="http://127.0.0.1:8765")
    values.update(overrides)
    return McpSettings(**values)


def handler(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path == "/api/auth/login":
        payload = request.read().decode()
        if "wrong" in payload:
            return httpx.Response(401, json={"code": "UNAUTHORIZED"})
        return httpx.Response(200, json={"accessToken": "java-access-1",
                                         "refreshToken": "java-refresh-1"})
    if path == "/api/users/me":
        return httpx.Response(200, json={"id": "user-1", "enabled": True})
    if path == "/api/auth/refresh":
        return httpx.Response(200, json={"accessToken": "java-access-2",
                                         "refreshToken": "java-refresh-2"})
    return httpx.Response(404)


def provider(**overrides):
    return PixelFlowOAuthProvider(settings(**overrides),
                                  transport=httpx.MockTransport(handler))


CLIENT = SimpleNamespace(client_id="client-1")
PARAMS = SimpleNamespace(scopes=["post:read", "post:write"], code_challenge="challenge-1",
                         redirect_uri="http://127.0.0.1:9999/callback",
                         redirect_uri_provided_explicitly=True, resource=None, state="state-1")


def authorize_url(instance):
    return asyncio.run(instance.authorize(CLIENT, PARAMS))


def test_authorize_redirects_to_login_page_with_pending_request():
    instance = provider()
    url = authorize_url(instance)
    assert url.startswith("http://127.0.0.1:8765/oauth/login?request_id=")
    request_id = parse_qs(urlsplit(url).query)["request_id"][0]
    pending = instance.pending_login(request_id)
    assert pending is not None and pending.state == "state-1"
    assert pending.scopes == ["post:read", "post:write"]
    assert pending.code_challenge == "challenge-1"


def test_complete_login_issues_code_and_keeps_java_tokens_server_side():
    instance = provider()
    request_id = parse_qs(urlsplit(authorize_url(instance)).query)["request_id"][0]
    redirect = instance.complete_login(request_id, "alice", "good-password")
    parts = urlsplit(redirect)
    assert parts.netloc == "127.0.0.1:9999" and parts.path == "/callback"
    query = parse_qs(parts.query)
    assert query["state"] == ["state-1"]
    code = query["code"][0]
    record = instance.codes[code]
    assert record.code.subject == "user-1"
    assert record.session.access == "java-access-1"
    # 一次性：同一条 pending 不能再登录一次
    with pytest.raises(LoginError):
        instance.complete_login(request_id, "alice", "good-password")


def test_wrong_password_is_a_safe_login_error():
    instance = provider()
    request_id = parse_qs(urlsplit(authorize_url(instance)).query)["request_id"][0]
    with pytest.raises(LoginError) as error:
        instance.complete_login(request_id, "alice", "wrong-password")
    assert "密码" in str(error.value)
    assert instance.codes == {}


def test_code_exchange_issues_access_and_refresh_tokens():
    instance = provider()
    request_id = parse_qs(urlsplit(authorize_url(instance)).query)["request_id"][0]
    redirect = instance.complete_login(request_id, "alice", "good-password")
    code_value = parse_qs(urlsplit(redirect).query)["code"][0]
    code = asyncio.run(instance.load_authorization_code(CLIENT, code_value))
    token = asyncio.run(instance.exchange_authorization_code(CLIENT, code))
    assert token.token_type == "Bearer" and token.expires_in == 3600
    assert set(token.scope.split()) == {"post:read", "post:write"}

    access = asyncio.run(instance.load_access_token(token.access_token))
    assert access is not None and access.subject == "user-1"
    assert access.scopes == ["post:read", "post:write"]
    # 授权码只能用一次
    assert asyncio.run(instance.load_authorization_code(CLIENT, code_value)) is None


def test_java_access_token_is_refreshed_before_expiry():
    instance = provider()
    request_id = parse_qs(urlsplit(authorize_url(instance)).query)["request_id"][0]
    redirect = instance.complete_login(request_id, "alice", "good-password")
    code_value = parse_qs(urlsplit(redirect).query)["code"][0]
    token = asyncio.run(instance.exchange_authorization_code(
        CLIENT, asyncio.run(instance.load_authorization_code(CLIENT, code_value))))
    assert instance.java_access_token(token.access_token) == "java-access-1"

    record = instance.access[token.access_token]
    record.session.expires_at = time.time() - 1
    assert instance.java_access_token(token.access_token) == "java-access-2"
    assert record.session.refresh == "java-refresh-2"


def test_refresh_token_rotates_and_revoke_invalidates():
    instance = provider()
    request_id = parse_qs(urlsplit(authorize_url(instance)).query)["request_id"][0]
    redirect = instance.complete_login(request_id, "alice", "good-password")
    code_value = parse_qs(urlsplit(redirect).query)["code"][0]
    token = asyncio.run(instance.exchange_authorization_code(
        CLIENT, asyncio.run(instance.load_authorization_code(CLIENT, code_value))))

    refresh = asyncio.run(instance.load_refresh_token(CLIENT, token.refresh_token))
    renewed = asyncio.run(instance.exchange_refresh_token(CLIENT, refresh, []))
    assert renewed.access_token != token.access_token
    # 刷新不会连带作废旧 access token；显式撤销才失效。
    assert asyncio.run(instance.load_access_token(token.access_token)) is not None
    old_access = asyncio.run(instance.load_access_token(token.access_token))
    asyncio.run(instance.revoke_token(old_access))
    assert asyncio.run(instance.load_access_token(token.access_token)) is None

    access = asyncio.run(instance.load_access_token(renewed.access_token))
    asyncio.run(instance.revoke_token(access))
    assert asyncio.run(instance.load_access_token(renewed.access_token)) is None


def test_unknown_or_expired_tokens_are_rejected():
    instance = provider()
    assert asyncio.run(instance.load_access_token("nope")) is None

    request_id = parse_qs(urlsplit(authorize_url(instance)).query)["request_id"][0]
    redirect = instance.complete_login(request_id, "alice", "good-password")
    code_value = parse_qs(urlsplit(redirect).query)["code"][0]
    token = asyncio.run(instance.exchange_authorization_code(
        CLIENT, asyncio.run(instance.load_authorization_code(CLIENT, code_value))))
    instance.access[token.access_token].expires_at = time.time() - 1
    assert asyncio.run(instance.load_access_token(token.access_token)) is None
