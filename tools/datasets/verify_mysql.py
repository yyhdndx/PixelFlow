"""Exercise import and rollback in a new, disposable corpus database (MySQL 8)."""
import argparse
import hashlib
import json
import uuid
from import_corpus import Mysql, ingest, rollback
from wukong_corpus import normalize


def verify(defaults_file):
    name = "pixelflow_corpus_test_" + uuid.uuid4().hex
    db = Mysql(defaults_file, name)
    assert not db.ensure_schema(False)
    rows = [normalize({"caption": caption, "url": "https://example.com/%d.jpg" % i},
                      "wukong_release/wukong_100m_0.csv", i + 1)
            for i, caption in enumerate(["咖啡面包美食记录", "猫咪宠物日常记录", "雪山旅行风景记录"])]
    manifest = {"records_sha256": hashlib.sha256(json.dumps(rows).encode()).hexdigest(), "accepted": 3}
    checks = {"records": {r["source_record_id"]: {"resource_status": "UNREACHABLE"} for r in rows}}
    passed = []
    try:
        assert ingest(db, "test-a", rows, manifest, checks)["status"] == "dry_run"
        assert not db.ensure_schema(False)
        passed.append("dry_run_creates_nothing")
        assert ingest(db, "test-a", rows, manifest, checks, True)["inserted"] == 3
        assert ingest(db, "test-a", rows, manifest, checks, True)["inserted"] == 0
        passed.append("import_and_idempotent_replay")
        assert db.query("SELECT COUNT(*) FROM corpus_documents WHERE MATCH(title,content) AGAINST('咖啡' IN BOOLEAN MODE)") == [["1"]]
        passed.append("chinese_ngram_search")
        assert ingest(db, "test-b", rows, manifest, checks, True)["reused"] == 3
        assert db.query("SELECT COUNT(*) FROM corpus_documents") == [["3"]]
        passed.append("cross_batch_deduplication")
        try:
            ingest(db, "test-a", rows, {**manifest, "records_sha256": "0" * 64}, checks, True)
        except ValueError:
            passed.append("conflicting_batch_rejected")
        else:
            raise AssertionError("Conflicting batch accepted")
        rollback(db, "test-a", True)
        assert db.query("SELECT COUNT(*) FROM corpus_documents") == [["3"]]
        passed.append("rollback_preserves_shared_documents")
        rollback(db, "test-b", True)
        assert db.query("SELECT COUNT(*) FROM corpus_documents") == [["0"]]
        assert db.query("SELECT COUNT(*) FROM corpus_batches") == [["0"]]
        passed.append("rollback_removes_unreferenced_documents")
        # A mid-transaction constraint error must roll back all earlier writes.
        try:
            db.query("START TRANSACTION; INSERT INTO corpus_schema_version VALUES (2,'test'); INSERT INTO corpus_schema_version VALUES (1,'duplicate'); COMMIT;")
        except RuntimeError:
            assert db.query("SELECT COUNT(*) FROM corpus_schema_version WHERE version=2") == [["0"]]
            passed.append("sql_error_rolls_back_transaction")
        else:
            raise AssertionError("Expected duplicate-key error")
    finally:
        # Exact generated name + ownership marker required before dropping anything.
        if db.ensure_schema(False):
            assert db.database == name and name.startswith("pixelflow_corpus_test_")
            db.query("DROP DATABASE `" + name + "`", False)
    return {"passed": passed, "count": len(passed), "temporary_database_removed": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--defaults-file", required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.defaults_file)))
