"""分析库/身份服务故障必须在 HTTP 层显式失败。

验收要求“Java/Python 服务不可用、分析库错误、无数据三者可区分”，且错误不得
降级为 200 的空画像或空历史伪装成功。这里固定住 Python 侧的行为，供真实链路
联调脚本 ``scripts/verify_profile_pipeline.py`` 的端到端断言对照。
"""
import sqlite3

import pytest
from fastapi.testclient import TestClient

from pixelflow_intelligence.analytics.store import Store
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import AuthenticationError, UpstreamError
from pixelflow_intelligence.common.settings import Settings


class FakeJava:
    def __init__(self, identity_error=None):
        self.identity_error = identity_error

    def me(self, token):
        if self.identity_error is not None:
            raise self.identity_error
        if token != "user-token":
            raise AuthenticationError()
        return "alice"


def make_client(tmp_path, upstream=None):
    settings = Settings(database=str(tmp_path / "db"), sync_token="s" * 32)
    return TestClient(create_app(settings, upstream or FakeJava()))


@pytest.mark.parametrize("path", ["/profiles/me", "/profiles/me/history?count=1"])
def test_store_errors_are_sanitized_503_not_empty_success(tmp_path, monkeypatch, path):
    def failed(*_args, **_kwargs):
        raise sqlite3.OperationalError("unable to open database file /secret/analytics.sqlite3")

    monkeypatch.setattr(Store, "profile", failed)
    monkeypatch.setattr(Store, "profile_history", failed)
    with make_client(tmp_path) as client:
        response = client.get(path, headers={"Authorization": "Bearer user-token"})
        assert response.status_code == 503
        assert response.headers["cache-control"] == "no-store"
        assert "secret" not in response.text
        assert "unable to open" not in response.text


@pytest.mark.parametrize("path", ["/profiles/me", "/profiles/me/history?count=1"])
def test_java_identity_failure_is_503_not_cold_start(tmp_path, path):
    with make_client(tmp_path, FakeJava(identity_error=UpstreamError("java down"))) as client:
        response = client.get(path, headers={"Authorization": "Bearer user-token"})
        assert response.status_code == 503
        assert response.headers["cache-control"] == "no-store"


def test_true_empty_store_still_returns_cold_start_and_empty_history(tmp_path):
    """无数据是合法状态：与故障不同，仍返回 200。"""
    with make_client(tmp_path) as client:
        profile = client.get("/profiles/me", headers={"Authorization": "Bearer user-token"})
        history = client.get("/profiles/me/history?count=1",
                             headers={"Authorization": "Bearer user-token"})
        assert profile.status_code == 200 and profile.json()["version"] == 0
        assert profile.json()["cold_start"] is True
        assert history.status_code == 200 and history.json()["items"] == []
