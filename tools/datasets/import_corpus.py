"""Validated, repeatable MySQL import/rollback for the private WuKong corpus."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import time
from pathlib import Path
from wukong_corpus import DATASET, TERMS_URL, normalize, sql_text

OWNER = "pixelflow-research-corpus"


class Mysql:
    def __init__(self, defaults_file, database):
        if not re.fullmatch(r"pixelflow_corpus(?:_test_[a-z0-9]+)?", database):
            raise ValueError("Only dedicated corpus databases are allowed")
        self.database = database
        self.args = ["mysql", "--defaults-extra-file=" + str(defaults_file), "--batch", "--skip-column-names", "--default-character-set=utf8mb4"]

    def query(self, sql, select_database=True):
        args = self.args + (["--database=" + self.database] if select_database else [])
        result = subprocess.run(args, input=sql, capture_output=True, text=True, timeout=60)
        if result.returncode:
            # SQL errors can include source content. Keep details in private operator context, not reports.
            code = re.search(r"ERROR (\d+)", result.stderr)
            raise RuntimeError("MySQL operation failed" + (" (code " + code.group(1) + ")" if code else ""))
        return [line.split("\t") for line in result.stdout.splitlines() if line]

    def ensure_schema(self, apply):
        exists = self.query("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=" + sql_text(self.database), False)
        if exists:
            marker = self.query("SELECT version,owner FROM corpus_schema_version")
            if marker != [["1", OWNER]]: raise ValueError("Unrecognized database owner/schema")
            return True
        if apply:
            schema = Path(__file__).with_name("corpus_schema.sql").read_text(encoding="utf-8")
            self.query(schema.replace("pixelflow_corpus", self.database), False)
            return True
        return False


def load_input(records_path, manifest_path, checks_path):
    raw = records_path.read_bytes()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    checks = json.loads(checks_path.read_text(encoding="utf-8"))
    digest = hashlib.sha256(raw).hexdigest()
    if manifest.get("records_sha256") != digest or checks.get("records_sha256") != digest:
        raise ValueError("Input checksum mismatch")
    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines()]
    if not 1 <= len(rows) <= 10000 or len(rows) != manifest.get("accepted") or len(rows) != checks.get("count"):
        raise ValueError("Input count mismatch")
    ids, hashes = set(), set()
    for row in rows:
        if not isinstance(row.get("source_row"), int) or isinstance(row["source_row"], bool) or row["source_row"] < 1:
            raise ValueError("Invalid source row")
        if not re.fullmatch(r"wukong_release/wukong_100m_\d+\.csv", row.get("source_file", "")):
            raise ValueError("Invalid source file")
        expected = normalize({"caption": row.get("original_caption"), "url": row.get("source_url")}, row["source_file"], row["source_row"])
        if expected is None or row != expected:
            raise ValueError("Normalized fields do not match the source")
        if row["source_record_id"] in ids or row["content_hash"] in hashes: raise ValueError("Duplicate source/content")
        ids.add(row["source_record_id"]); hashes.add(row["content_hash"])
        check = checks.get("records", {}).get(row["source_record_id"], {})
        if check.get("resource_status") not in {"REACHABLE", "UNREACHABLE", "BLOCKED"}:
            raise ValueError("Every resource must have a check result")
    if set(checks["records"]) != ids: raise ValueError("Unexpected resource check records")
    return rows, manifest, checks


def ingest(db, batch_id, rows, manifest, checks, apply=False):
    if not re.fullmatch(r"[a-z0-9_-]{1,64}", batch_id): raise ValueError("Invalid batch ID")
    schema_exists = db.ensure_schema(apply)
    if schema_exists:
        batches = db.query("SELECT records_sha256 FROM corpus_batches WHERE batch_id=" + sql_text(batch_id))
        if batches:
            if batches != [[manifest["records_sha256"]]]: raise ValueError("Batch ID already belongs to different input")
            count = int(db.query("SELECT COUNT(*) FROM corpus_batch_items WHERE batch_id=" + sql_text(batch_id))[0][0])
            if count != len(rows): raise ValueError("Existing batch is incomplete")
            return {"status": "already_imported", "batch": batch_id, "documents": count, "inserted": 0}
        ids = ",".join(sql_text(r["source_record_id"]) for r in rows)
        hashes = ",".join(sql_text(r["content_hash"]) for r in rows)
        existing = db.query("SELECT source_record_id,content_hash FROM corpus_documents WHERE source_record_id IN (" + ids + ") OR content_hash IN (" + hashes + ")")
    else:
        existing = []
    old_ids = dict(existing)
    old_hashes = {row[1] for row in existing}
    for row in rows:
        if row["source_record_id"] in old_ids and old_ids[row["source_record_id"]] != row["content_hash"]:
            raise ValueError("Source row changed; explicit new dataset version required")
    new_rows = [row for row in rows if row["content_hash"] not in old_hashes]
    report = {"status": "imported" if apply else "dry_run", "batch": batch_id, "database": db.database,
              "documents": len(rows), "inserted": len(new_rows), "reused": len(rows) - len(new_rows), "public_posts_created": 0}
    if not apply: return report
    now = int(time.time())
    commands = ["START TRANSACTION", "INSERT INTO corpus_batches VALUES (" + ",".join([
        sql_text(batch_id), sql_text(DATASET), sql_text(manifest["records_sha256"]),
        sql_text(json.dumps(manifest, ensure_ascii=False)), str(now)]) + ")"]
    columns = "source_record_id,content_hash,source_dataset,source_file,source_row,source_url,original_caption,title,content,clips_json,has_video,topics_json,rule_version,author_id,source_create_time,ingested_at,status,license,terms_url,resource_status,resource_check_json"
    for row in new_rows:
        check = checks["records"][row["source_record_id"]]
        values = [sql_text(row[key]) for key in ["source_record_id", "content_hash", "source_dataset", "source_file"]]
        values += [str(row["source_row"])]
        values += [sql_text(row[key]) for key in ["source_url", "original_caption", "title", "content"]]
        values += [sql_text(json.dumps(row["clips"], ensure_ascii=False)), "FALSE", sql_text(json.dumps(row["topics"], ensure_ascii=False)), sql_text(row["rule_version"]), "NULL", "NULL", str(now), sql_text("RESEARCH_ONLY"), sql_text(row["license"]), sql_text(TERMS_URL), sql_text(check["resource_status"]), sql_text(json.dumps(check, ensure_ascii=False))]
        commands.append("INSERT INTO corpus_documents (" + columns + ") VALUES (" + ",".join(values) + ")")
    for row in rows:
        commands.append("INSERT INTO corpus_batch_items SELECT " + sql_text(batch_id) + ",id FROM corpus_documents WHERE content_hash=" + sql_text(row["content_hash"]))
    commands.append("COMMIT")
    db.query(";\n".join(commands) + ";")
    actual = int(db.query("SELECT COUNT(*) FROM corpus_batch_items WHERE batch_id=" + sql_text(batch_id))[0][0])
    if actual != len(rows): raise RuntimeError("Post-import verification failed")
    return report


def rollback(db, batch_id, apply=False):
    if not re.fullmatch(r"[a-z0-9_-]{1,64}", batch_id): raise ValueError("Invalid batch ID")
    if not db.ensure_schema(False): return {"status": "absent", "documents": 0}
    key = sql_text(batch_id)
    ids = [str(int(r[0])) for r in db.query("SELECT document_id FROM corpus_batch_items WHERE batch_id=" + key)]
    report = {"status": "rolled_back" if apply else "rollback_dry_run", "batch": batch_id, "batch_memberships": len(ids)}
    if apply:
        statements = ["START TRANSACTION", "DELETE FROM corpus_batch_items WHERE batch_id=" + key]
        if ids:
            statements.append("DELETE FROM corpus_documents WHERE id IN (" + ",".join(ids) + ") AND NOT EXISTS (SELECT 1 FROM corpus_batch_items WHERE document_id=corpus_documents.id)")
        statements += ["DELETE FROM corpus_batches WHERE batch_id=" + key, "COMMIT"]
        db.query(";\n".join(statements) + ";")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--defaults-file", type=Path, required=True)
    parser.add_argument("--database", default="pixelflow_corpus")
    parser.add_argument("--batch", required=True)
    parser.add_argument("--records", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--checks", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--rollback", action="store_true")
    args = parser.parse_args()
    db = Mysql(args.defaults_file, args.database)
    if args.rollback:
        result = rollback(db, args.batch, args.apply)
    else:
        if not all([args.records, args.manifest, args.checks]): parser.error("Import needs records, manifest and checks")
        result = ingest(db, args.batch, *load_input(args.records, args.manifest, args.checks), apply=args.apply)
    print(json.dumps(result, ensure_ascii=False))
