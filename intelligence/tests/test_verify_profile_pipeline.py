"""Regression coverage for the real-pipeline verifier's cleanup and evidence."""
from contextlib import contextmanager
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import zipfile

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
    # 产物必须是可读的 JAR（含迁移），否则运行前检查就会失败，测不到清理路径。
    jar = build_jar(tmp_path, CURRENT, "server.jar")
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


def build_jar(tmp_path, names, filename="server.jar"):
    """构造只含迁移条目的最小 JAR，避免测试依赖真实构建产物。"""
    jar = tmp_path / filename
    with zipfile.ZipFile(jar, "w") as archive:
        for name in names:
            archive.writestr(f"{pipeline.JAR_MIGRATION_DIR}/{name}", "-- migration")
    return jar


CURRENT = ["V1__business_schema.sql", "V2__user_status_audit.sql", "V3__uploaded_images.sql",
           "V4__demo_avatars_and_seed_batches.sql", "V5__analytics_events.sql",
           "V6__recommendation_preferences.sql", "V7__user_bio.sql",
           "V9__post_creation_idempotency.sql"]


def test_jar_migrations_reads_versions_not_counts(tmp_path):
    jar = build_jar(tmp_path, CURRENT + ["V12__later_change.sql"])
    migrations = pipeline.jar_migrations(jar)
    assert sorted(migrations, key=int) == ["1", "2", "3", "4", "5", "6", "7", "9", "12"]
    # 版本 9 是最大版本，不是迁移数量；8 号缺口是合法的。
    assert len(migrations) == 9
    assert "8" not in migrations


def test_migration_check_accepts_current_master_v1_to_v7_plus_v9(tmp_path):
    jar = build_jar(tmp_path, CURRENT)
    applied = [(str(int(v[1:].split("__")[0])), 1) for v in CURRENT]
    report = pipeline.verify_migrations(pipeline.jar_migrations(jar), applied,
                                        source=pipeline.jar_migrations(jar))
    assert report["ok"] is True
    assert report["expected_count"] == report["applied_count"] == 8
    assert report["expected_versions"] == ["1", "2", "3", "4", "5", "6", "7", "9"]


def test_next_legal_migration_passes_without_code_change(tmp_path):
    names = CURRENT + ["V10__next_feature.sql"]
    jar = build_jar(tmp_path, names)
    migrations = pipeline.jar_migrations(jar)
    applied = [(version, 1) for version in migrations]
    assert pipeline.verify_migrations(migrations, applied, source=migrations)["ok"] is True

    # 只新增文件、数据库尚未升级时，必须报“缺少迁移”而不是放过。
    report = pipeline.verify_migrations(migrations, applied[:-1], source=migrations)
    assert report["ok"] is False and report["missing"] == ["10"]


def test_missing_next_migration_in_jar_fails_as_stale_build(tmp_path):
    source = pipeline.jar_migrations(build_jar(tmp_path, CURRENT + ["V10__next_feature.sql"],
                                               "source.jar"))
    jar = pipeline.jar_migrations(build_jar(tmp_path, CURRENT, "stale.jar"))
    report = pipeline.verify_migrations(jar, [(v, 1) for v in jar], source=source)
    assert report["ok"] is False
    assert report["source_not_in_jar"] == ["10"]
    assert "重新打包" in report["reason"]


@pytest.mark.parametrize("applied,missing,unexpected,failed,keyword", [
    ([(v, 1) for v in ("1", "2", "3", "4", "5", "6", "9")], ["7"], [], [], "缺少"),
    ([(v, 1) for v in ("1", "2", "3", "4", "5", "6", "7", "8", "9")], [], ["8"], [], "之外"),
    ([(v, 1) for v in ("1", "2", "3", "4", "5", "6", "7")] + [("9", 0)], ["9"], [], ["9"], "失败"),
])
def test_migration_set_is_compared_exactly(tmp_path, applied, missing, unexpected, failed, keyword):
    jar = build_jar(tmp_path, CURRENT)
    migrations = pipeline.jar_migrations(jar)
    report = pipeline.verify_migrations(migrations, applied, source=migrations)
    assert report["ok"] is False
    assert report["missing"] == missing
    assert report["unexpected"] == unexpected
    assert report["failed"] == failed
    assert keyword in report["reason"]


def test_jar_and_source_must_match_both_ways(tmp_path):
    source = pipeline.jar_migrations(build_jar(tmp_path, CURRENT + ["V10__next_feature.sql"],
                                               "source.jar"))
    jar = pipeline.jar_migrations(build_jar(tmp_path, CURRENT + ["V11__unexpected.sql"], "jar.jar"))
    report = pipeline.verify_migrations(jar, [(v, 1) for v in jar], source=source)
    assert report["ok"] is False
    assert report["source_not_in_jar"] == ["10"]
    assert report["jar_not_in_source"] == ["11"]


def test_migration_check_reports_versions_without_secrets(tmp_path):
    jar = build_jar(tmp_path, CURRENT)
    migrations = pipeline.jar_migrations(jar)
    report = pipeline.verify_migrations(migrations, [(v, 1) for v in migrations],
                                        source=migrations)
    serialized = json.dumps(report, ensure_ascii=False)
    assert "V1__" not in serialized           # 报告保留版本，不倾倒文件名清单以外的内容
    assert "password" not in serialized and "token" not in serialized
    assert report["expected_versions"] == ["1", "2", "3", "4", "5", "6", "7", "9"]

