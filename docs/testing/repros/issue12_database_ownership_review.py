"""Independent acceptance repro; only run against a dedicated local MySQL test instance."""
import importlib.util
import os
from pathlib import Path

import pymysql

ROOT = Path(os.environ.get("ISSUE12_REVIEW_ROOT", Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location("issue12_script", ROOT / "intelligence/scripts/verify_profile_pipeline.py")
script = importlib.util.module_from_spec(spec)
spec.loader.exec_module(script)


def test_script_does_not_delete_a_preexisting_same_name_database(monkeypatch, tmp_path):
    # These two databases are owned exclusively by this reproduction, not by the script under review.
    port = int(os.environ["ISSUE12_AUDIT_MYSQL_PORT"])
    assert port == 33083
    connection = pymysql.connect(host="127.0.0.1", port=port, user="root", password="", autocommit=True)
    java_db = "pf_issue12_java_c0111de0_test"
    analytics_db = "pf_issue12_analytics_c0111de0_test"
    made = []
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE `{java_db}`")
            made.append(java_db)
            cursor.execute(f"CREATE TABLE `{java_db}`.ownership_sentinel(id INTEGER PRIMARY KEY)")
            cursor.execute(f"INSERT INTO `{java_db}`.ownership_sentinel VALUES (1)")
        original = script.secrets.token_hex
        monkeypatch.setattr(script.secrets, "token_hex", lambda size: "c0111de0" if size == 4 else original(size))

        def cannot_start(*args, **kwargs):
            raise RuntimeError("Injected startup failure after database preparation")

        monkeypatch.setattr(script.Services, "start_java", cannot_start)
        result = script.main(["--mysql-port", str(port), "--mysql-user", "root",
                              "--artifacts-dir", str(tmp_path)])
        made.append(analytics_db)
        assert result == 1
        with connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (java_db,))
            assert cursor.fetchone()[0] == 1, "Script cleanup deleted a database that existed before this run"
            cursor.execute(f"SELECT COUNT(*) FROM `{java_db}`.ownership_sentinel")
            assert cursor.fetchone()[0] == 1
    finally:
        with connection.cursor() as cursor:
            for name in made:
                cursor.execute(f"DROP DATABASE IF EXISTS `{name}`")
        connection.close()
