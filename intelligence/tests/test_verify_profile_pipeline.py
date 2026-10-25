"""Regression coverage for the real-pipeline verifier's cleanup and evidence."""
from contextlib import contextmanager
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re

import pymysql
import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/verify_profile_pipeline.py"
spec = importlib.util.spec_from_file_location("verify_profile_pipeline", SCRIPT)
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)

JAVA_DB = "pf_issue12_java_c0111de0_test"
ANALYTICS_DB = "pf_issue12_analytics_c0111de0_test"
USER_ID = "12345678-1234-5678-9abc-123456789abc"


@pytest.mark.parametrize("preexisting,deny_second", [
    ({JAVA_DB}, False),
    ({ANALYTICS_DB}, False),
    ({JAVA_DB, ANALYTICS_DB}, False),
    (set(), True),
    (set(), False),
])
def test_cleanup_only_removes_databases_created_by_this_run(
        monkeypatch, tmp_path, capsys, preexisting, deny_second):
    # Model MySQL CREATE semantics, including the old IF NOT EXISTS behavior:
    # restoring that behavior makes the collision cases delete the sentinel.
    databases = {name: {"sentinel": 1} for name in preexisting}
    deleted = []
    started = []

    class Cursor:
        def execute(self, sql):
            name = re.search(r"`([^`]+)`", sql).group(1)
            if sql.startswith("CREATE DATABASE"):
                if name in databases:
                    if "IF NOT EXISTS" in sql:
                        return
                    raise pymysql.err.ProgrammingError(1007, "Database exists")
                if deny_second and name == ANALYTICS_DB:
                    raise pymysql.err.OperationalError(1044, "CREATE denied")
                databases[name] = {}
            else:
                assert sql.startswith("DROP DATABASE IF EXISTS")
                deleted.append(name)
                databases.pop(name, None)

    class Connection:
        @contextmanager
        def cursor(self):
            yield Cursor()

    @contextmanager
    def connect(*args, **kwargs):
        yield Connection()

    def start_java(*args, **kwargs):
        started.append(True)
        raise RuntimeError(f"Injected startup failure: {USER_ID} audit-password")

    monkeypatch.setattr(pipeline, "mysql_connect", connect)
    original_token_hex = pipeline.secrets.token_hex
    monkeypatch.setattr(pipeline.secrets, "token_hex",
                        lambda size: "c0111de0" if size == 4 else original_token_hex(size))
    monkeypatch.setattr(pipeline.Services, "start_java", start_java)
    jar = tmp_path / "server.jar"
    jar.touch()
    before = datetime.now(timezone.utc)
    result = pipeline.main(["--jar", str(jar), "--mysql-user", "test",
                            "--mysql-password", "audit-password",
                            "--artifacts-dir", str(tmp_path)])
    after = datetime.now(timezone.utc)

    assert result == 1
    assert databases == {name: {"sentinel": 1} for name in preexisting}
    assert not set(deleted) & preexisting
    assert bool(started) == (not preexisting and not deny_second)
    output = capsys.readouterr().out
    report_text = (tmp_path / "summary.json").read_text(encoding="utf-8")
    assert USER_ID not in output + report_text
    assert "audit-password" not in output + report_text
    report = json.loads(report_text)
    start = datetime.fromisoformat(report["started_at"])
    assert start.tzinfo is not None
    assert before.timestamp() - 1 <= start.timestamp() <= after.timestamp()
    assert report["duration_seconds"] >= 0


@pytest.mark.parametrize("ok", [True, False])
def test_report_and_stdout_redact_nested_payloads_and_failure_details(tmp_path, capsys, ok):
    redactor = pipeline.ReportRedactor("test-access-token")
    redactor.add("test-refresh-token")
    report = {
        "ok": ok,
        "accounts": {"a": USER_ID},
        "checks": [{"detail": repr({"user_id": USER_ID})}],
        "nested": {"items": [{"author_id": USER_ID}]},
        "error": f"upstream user_id={USER_ID} test-access-token",
        "logs": {"java": f"user {USER_ID} test-refresh-token"},
    }
    pipeline.write_report(tmp_path, report, redactor)
    saved = (tmp_path / "summary.json").read_text(encoding="utf-8")
    printed = capsys.readouterr().out
    assert json.loads(saved) == json.loads(printed)
    for sensitive in (USER_ID, "test-access-token", "test-refresh-token"):
        assert sensitive not in saved + printed
    assert "12345678…" in saved
    assert "[REDACTED]" in saved
    assert report["accounts"]["a"] == USER_ID  # Do not alter values used by assertions.


def test_equality_failure_keeps_diagnostics_without_full_account_id():
    evidence = pipeline.Evidence()
    with pytest.raises(pipeline.VerificationError) as failure:
        evidence.equality("history", "version comparison", {"user_id": USER_ID, "version": 1},
                          {"user_id": USER_ID, "version": 2})
    assert USER_ID not in str(failure.value)
    assert "version" in str(failure.value)
    assert "12345678…" in evidence.rows[0]["detail"]


def test_successful_equality_does_not_dump_the_profile():
    evidence = pipeline.Evidence()
    profile = {"user_id": USER_ID, "topics": [{"name": "food", "weight": 1}]}
    evidence.equality("profile", "same profile", profile, profile)
    assert evidence.rows[0]["ok"] is True
    assert USER_ID not in evidence.rows[0]["detail"]
    assert "topics" not in evidence.rows[0]["detail"]
