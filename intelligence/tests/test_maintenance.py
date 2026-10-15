import httpx
import pytest
from pixelflow_intelligence.maintenance import run, main, validate_config, MaintenanceError


def sync(more=False, cursor=1):
    return dict(imported=1, updated_users=1, next_cursor=cursor, has_more=more)


def refresh(more=False, cursor="alice"):
    return dict(scanned_users=1, updated_users=1, next_cursor=cursor, has_more=more, evaluated_at=100)


def client_for(payloads, calls):
    def handler(request):
        calls.append(request)
        value = payloads.pop(0)
        if isinstance(value, int):
            return httpx.Response(value, text="secret upstream diagnostic")
        return httpx.Response(200, json=value)
    return httpx.Client(base_url="http://localhost", transport=httpx.MockTransport(handler))


def test_sync_before_refresh_and_complete_resets_refresh_cursor():
    calls = []
    with client_for([sync(True), sync(cursor=2), refresh(True), refresh(cursor="bob")], calls) as client:
        result = run(client)
    assert result["status"] == "complete" and result["refresh_after"] == ""
    assert result["imported"] == 2 and result["refreshed_users"] == 2
    assert [r.url.path for r in calls] == ["/internal/sync"] * 2 + ["/internal/profiles/refresh"] * 2
    assert b'"after":"alice"' in calls[-1].content


def test_sync_limit_skips_refresh():
    calls = []
    with client_for([sync(True)], calls) as client:
        result = run(client, max_sync_pages=1)
    assert result["status"] == "incomplete" and result["reason"] == "SYNC_PAGE_LIMIT"
    assert result["refresh_pages"] == 0 and len(calls) == 1


def test_refresh_limit_preserves_resume_cursor():
    with client_for([sync(), refresh(True, "bob")], []) as client:
        result = run(client, max_refresh_pages=1, refresh_after="alice")
    assert result["reason"] == "REFRESH_PAGE_LIMIT" and result["refresh_after"] == "bob"


@pytest.mark.parametrize("status", [302, 401, 503])
def test_failed_request_stops_and_does_not_expose_body(status):
    with client_for([sync(), status], []) as client:
        result = run(client, refresh_after="alice")
    assert result["status"] == "failed" and result["reason"] == f"HTTP_{status}"
    assert result["refresh_after"] == "alice" and "secret" not in str(result)


@pytest.mark.parametrize("pages,reason", [
    ([sync(True), sync(True)], "INVALID_SYNC_PROGRESS"),
    ([sync(), refresh(True), refresh(True)], "INVALID_REFRESH_PROGRESS"),
    ([{"imported": "secret"}], "REQUEST_OR_RESPONSE_FAILED"),
])
def test_invalid_response_or_cursor_does_not_loop(pages, reason):
    with client_for(pages, []) as client:
        assert run(client)["reason"] == reason


@pytest.mark.parametrize("origin", ["http://example.com", "https://user:secret@example.com",
                                    "https://example.com/path", "https://example.com?token=secret",
                                    "http://localhost:bad", "file:///tmp"])
def test_unsafe_configuration_is_rejected_without_echo(origin):
    with pytest.raises(MaintenanceError) as error:
        validate_config(origin, "x" * 32)
    assert str(error.value) == "INVALID_CONFIGURATION"


def test_cli_requires_secret_and_reports_nonzero_without_echo(monkeypatch, capsys):
    monkeypatch.setenv("PIXELFLOW_INTELLIGENCE_TOKEN", "short-secret")
    assert main([]) == 1
    assert "short-secret" not in capsys.readouterr().out


def test_deadline_stops_before_network(monkeypatch):
    values = iter([0, 121])
    monkeypatch.setattr("pixelflow_intelligence.maintenance.time.monotonic", lambda: next(values))
    calls = []
    with client_for([], calls) as client:
        assert run(client)["reason"] == "TIME_BUDGET_EXHAUSTED"
    assert not calls
