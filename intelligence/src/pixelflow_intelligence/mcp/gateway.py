"""Sanitized HTTP access to the existing Java business API.

Only the endpoints the creator tools need are wrapped. Upstream response bodies
are never echoed into errors, so a tool failure cannot leak tokens or SQL
details to the Agent client.
"""
from __future__ import annotations

import httpx

from .config import McpSettings

STATUS_CODES = {
    400: ("INVALID_ARGUMENT", "请求参数不符合业务规则"),
    401: ("UNAUTHORIZED", "服务账号会话已失效，请重新登录"),
    403: ("FORBIDDEN", "服务账号无权执行该操作"),
    404: ("NOT_FOUND", "对象不存在或当前账号不可见"),
    409: ("CONFLICT", "当前作品状态不允许该操作"),
    413: ("MEDIA_TOO_LARGE", "媒体文件超过大小限制"),
    422: ("INVALID_ARGUMENT", "请求参数校验失败"),
}


class GatewayError(RuntimeError):
    def __init__(self, code: str, message: str, status: int | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


class JavaGateway:
    def __init__(self, settings: McpSettings, tokens,
                 transport: httpx.BaseTransport | None = None):
        self.settings = settings
        self.tokens = tokens
        self._transport = transport

    def _client(self) -> httpx.Client:
        return httpx.Client(base_url=self.settings.java_url,
                            timeout=self.settings.timeout_seconds,
                            trust_env=False, transport=self._transport)

    def _request(self, method: str, path: str, *, params=None, json_body=None,
                 files=None, headers=None, retry: bool = True):
        request_headers = self.tokens.headers()
        request_headers.update(headers or {})
        try:
            with self._client() as client:
                response = client.request(method, path, params=params, json=json_body,
                                          files=files, headers=request_headers)
        except httpx.TimeoutException:
            raise GatewayError("UPSTREAM_TIMEOUT", "业务服务响应超时，请稍后重试") from None
        except httpx.HTTPError:
            raise GatewayError("UPSTREAM_UNAVAILABLE", "业务服务暂时不可用") from None
        if response.status_code == 401 and retry:
            self.tokens.invalidate()
            return self._request(method, path, params=params, json_body=json_body,
                                 files=files, headers=headers, retry=False)
        if response.status_code >= 400:
            code, message = STATUS_CODES.get(
                response.status_code,
                ("UPSTREAM_ERROR", "业务服务返回错误") if response.status_code >= 500
                else ("REQUEST_REJECTED", "业务请求被拒绝"))
            raise GatewayError(code, message, response.status_code)
        if response.status_code == 204 or not response.content:
            return {}
        try:
            return response.json()
        except ValueError:
            raise GatewayError("INVALID_UPSTREAM_RESPONSE", "业务服务返回了无法解析的响应") from None

    # -- media -------------------------------------------------------------
    def upload_media(self, filename: str, content: bytes, content_type: str) -> dict:
        return self._request("POST", "/api/media",
                             files={"file": (filename, content, content_type)})

    # -- posts -------------------------------------------------------------
    def create_post(self, draft: dict, idempotency_key: str) -> dict:
        return self._request("POST", "/api/posts", json_body=draft,
                             headers={"Idempotency-Key": idempotency_key})

    def edit_post(self, post_id: str, draft: dict) -> dict:
        return self._request("PUT", f"/api/posts/{post_id}", json_body=draft)

    def publish_post(self, post_id: str) -> dict:
        return self._request("POST", f"/api/posts/{post_id}/publish")

    def withdraw_post(self, post_id: str) -> dict:
        return self._request("POST", f"/api/posts/{post_id}/withdraw")

    def list_my_posts(self, count: int = 50, cursor: int | None = None) -> list[dict]:
        params = {"count": count}
        if cursor is not None:
            params["cursor"] = cursor
        body = self._request("GET", "/api/posts", params=params)
        if not isinstance(body, list):
            raise GatewayError("INVALID_UPSTREAM_RESPONSE", "业务服务返回了非预期的作品列表")
        return body

