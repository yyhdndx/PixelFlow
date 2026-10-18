"""Explicit per-account import from a legacy SQLite snapshot; never edits that file."""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import httpx
from .clients.preferences import PreferenceSnapshot
from .maintenance import validate_config, MaintenanceError


class MigrationError(Exception):
    pass


def read_legacy(database, user):
    path = Path(database).resolve()
    if not path.is_file():
        raise MigrationError("LEGACY_DATABASE_NOT_FOUND")
    try:
        # mode=ro prevents accidental creation or modification; one read snapshot
        # covers the parent preferences and both child tables.
        with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as db:
            db.execute("BEGIN")
            tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if not {"recommendation_preferences", "recommendation_exclusions"} <= tables:
                raise MigrationError("LEGACY_SCHEMA_MISSING")
            row = db.execute("SELECT personalized,version FROM recommendation_preferences WHERE user_id=?", (user,)).fetchone()
            posts = [r[0] for r in db.execute(
                "SELECT post_id FROM recommendation_exclusions WHERE user_id=? ORDER BY length(post_id),post_id LIMIT 1001", (user,))]
            authors = ([r[0] for r in db.execute(
                "SELECT author_id FROM recommendation_blocked_authors WHERE user_id=? ORDER BY author_id LIMIT 501", (user,))]
                if "recommendation_blocked_authors" in tables else [])
            if row is None and not posts and not authors:
                raise MigrationError("NO_LEGACY_PREFERENCES_FOR_ACCOUNT")
            if row is not None and row[0] not in (0, 1):
                raise MigrationError("INVALID_LEGACY_PREFERENCES")
            return PreferenceSnapshot.model_validate(dict(user_id=user, personalized=bool(row[0]) if row else True,
                version=row[1] if row else 0, excluded_post_ids=posts,
                blocked_author_ids=authors, suppressed_topics=[])).model_dump()
    except (sqlite3.Error, ValueError):
        raise MigrationError("INVALID_LEGACY_PREFERENCES") from None


def content(snapshot):
    return (snapshot["personalized"], set(snapshot["excluded_post_ids"]),
            set(snapshot["blocked_author_ids"]), set(snapshot["suppressed_topics"]))


def run(client, database, apply=False):
    def request(method, path, body=None):
        try:
            response = client.request(method, path, json=body)
            if response.status_code != 200:
                raise MigrationError("HTTP_" + str(response.status_code))
            return response.json()
        except (httpx.HTTPError, ValueError):
            raise MigrationError("REQUEST_OR_RESPONSE_FAILED") from None
    try:
        identity = request("GET", "/api/users/me")
        user = identity["id"]
        if not isinstance(user, str) or not 1 <= len(user) <= 36 or identity.get("enabled") is not True:
            raise MigrationError("INVALID_IDENTITY")
        legacy = read_legacy(database, user)
        current = PreferenceSnapshot.model_validate(request("GET", "/api/recommendations/me/preferences")).model_dump()
        if current["user_id"] != user:
            raise MigrationError("OWNER_MISMATCH")
        summary = {"excluded_posts": len(legacy["excluded_post_ids"]), "blocked_authors": len(legacy["blocked_author_ids"]),
                   "personalized": legacy["personalized"], "java_version": current["version"]}
        if content(current) == content(legacy):
            return {**summary, "status": "already_matches"}
        if current["version"] != 0 or content(current) != (True, set(), set(), set()):
            raise MigrationError("PREFERENCE_MIGRATION_CONFLICT")
        if not apply:
            return {**summary, "status": "ready_no_changes"}
        payload = {"source_user_id": user, "personalized": legacy["personalized"],
                   "excluded_post_ids": legacy["excluded_post_ids"], "blocked_author_ids": legacy["blocked_author_ids"]}
        result = PreferenceSnapshot.model_validate(request("POST", "/api/recommendations/me/preferences/import", payload)).model_dump()
        if result["user_id"] != user or content(result) != content(legacy):
            raise MigrationError("IMPORT_RESULT_MISMATCH")
        return {**summary, "status": "imported", "java_version": result["version"]}
    except (KeyError, TypeError, ValueError):
        raise MigrationError("INVALID_RESPONSE") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description="Check/import this account's legacy preferences; dry-run by default")
    parser.add_argument("--database", required=True, help="Path to a backed-up legacy SQLite database")
    parser.add_argument("--apply", action="store_true", help="Import explicitly; conflicts are never overwritten")
    args = parser.parse_args(argv)
    try:
        origin = os.getenv("JAVA_BASE_URL", "http://127.0.0.1:8080")
        token = os.getenv("PIXELFLOW_MIGRATION_USER_TOKEN", "")
        validate_config(origin, token)
        with httpx.Client(base_url=origin, headers={"Authorization": "Bearer " + token},
                          timeout=10, follow_redirects=False, trust_env=False) as client:
            summary = run(client, args.database, args.apply)
        print(json.dumps(summary, ensure_ascii=True))
        return 0
    except (MigrationError, MaintenanceError) as error:
        print(json.dumps({"status": "failed", "code": str(error)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
