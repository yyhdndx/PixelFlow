import json
import sqlite3
import time
from contextlib import closing
from pathlib import Path
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field
from .profile import build_profile, RULE_VERSION
from .profile_history import parse_history_page
from .database import MySQLConnection


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    seq: int = Field(gt=0)
    event_id: str = Field(min_length=1, max_length=64)
    user_id: str = Field(min_length=1, max_length=36)
    post_id: int = Field(gt=0)
    event_type: Literal["EXPOSURE", "CLICK", "DWELL", "LIKE", "UNLIKE", "FAVORITE",
                        "FOLLOW", "SHARE", "NOT_INTERESTED", "PUBLISH"]
    occurred_at: int = Field(ge=0)
    received_at: int = Field(ge=0)
    duration_ms: int | None = Field(default=None, ge=1, le=1800000)
    scene: str | None = None
    source: Literal["CLIENT", "SERVER"]
    recommendation_id: str | None = Field(default=None, max_length=36)
    author_id: str = Field(min_length=1, max_length=36)
    has_video: bool
    title: str = Field(max_length=120)
    eligible: bool


class Page(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    source_id: str = Field(min_length=1, max_length=36)
    items: list[Event] = Field(max_length=50)
    next_cursor: int = Field(ge=0)
    has_more: bool


class Store:
    def __init__(self, database):
        self.database = database
        self.mysql = str(database).startswith("mysql://")
        if not self.mysql:
            if "://" in str(database):
                raise ValueError("Unsupported analytics database URL")
            Path(database).parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as db, db:
            if self.mysql:
                from .mysql_schema import initialize
                initialize(db)
            else:
                db.executescript("""
                CREATE TABLE IF NOT EXISTS checkpoint (
                    id INTEGER PRIMARY KEY CHECK(id=1), source_id TEXT, cursor BIGINT NOT NULL);
                INSERT INTO checkpoint VALUES(1,NULL,0) ON CONFLICT(id) DO NOTHING;
                CREATE TABLE IF NOT EXISTS events (
                    source_id TEXT NOT NULL, seq BIGINT NOT NULL, user_id TEXT NOT NULL,
                    event_id TEXT NOT NULL, document TEXT NOT NULL,
                    PRIMARY KEY(source_id,seq), UNIQUE(source_id,user_id,event_id));
                CREATE INDEX IF NOT EXISTS events_user ON events(user_id,seq);
                CREATE TABLE IF NOT EXISTS profiles (
                    user_id TEXT NOT NULL, version BIGINT NOT NULL, document TEXT NOT NULL,
                    PRIMARY KEY(user_id,version));
                CREATE TABLE IF NOT EXISTS maintenance_state (
                    operation TEXT PRIMARY KEY, completed_at BIGINT NOT NULL, has_more INTEGER NOT NULL);
            """)

    def connect(self):
        if self.mysql:
            return MySQLConnection(self.database)
        db = sqlite3.connect(self.database, timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def _begin_write(self, db):
        if self.mysql:
            # Both sync and refresh lock the singleton before reading versions.
            db.execute("SELECT id FROM checkpoint WHERE id=1 FOR UPDATE")
        else:
            db.execute("BEGIN IMMEDIATE")

    def sync_page(self, fetch, now=None):
        now = int(time.time()) if now is None else now
        with closing(self.connect()) as db:
            # Serializes both multiple requests and multiple local workers. Page + profiles +
            # checkpoint commit together; a crash or malformed page cannot skip source events.
            self._begin_write(db)
            try:
                checkpoint = db.execute("SELECT * FROM checkpoint WHERE id=1").fetchone()
                cursor = checkpoint["cursor"]
                page = Page.model_validate(fetch(cursor))
                if checkpoint["source_id"] not in (None, page.source_id):
                    raise ValueError("Source changed; use a separate analytics database")
                previous = cursor
                for event in page.items:
                    if event.seq <= previous:
                        raise ValueError("Non-monotonic event sequence")
                    previous = event.seq
                if page.next_cursor != previous or (page.has_more and not page.items):
                    raise ValueError("Invalid page watermark")
                users = set()
                for event in page.items:
                    db.execute("INSERT INTO events VALUES(?,?,?,?,?)",
                               (page.source_id, event.seq, event.user_id, event.event_id, event.model_dump_json()))
                    users.add(event.user_id)
                for user in sorted(users):
                    events = [json.loads(row[0]) for row in db.execute(
                        "SELECT document FROM events WHERE user_id=? ORDER BY seq", (user,))]
                    version = db.execute("SELECT COALESCE(MAX(version),0)+1 FROM profiles WHERE user_id=?", (user,)).fetchone()[0]
                    profile = build_profile(user, events, version, now)
                    db.execute("INSERT INTO profiles VALUES(?,?,?)", (user, version, json.dumps(profile, ensure_ascii=False)))
                db.execute("UPDATE checkpoint SET source_id=?,`cursor`=? WHERE id=1", (page.source_id, page.next_cursor))
                self._record_operation(db, "sync", now, page.has_more)
                db.commit()
                return {"imported": len(page.items), "updated_users": len(users),
                        "next_cursor": page.next_cursor, "has_more": page.has_more}
            except Exception:
                db.rollback()
                raise

    def profile(self, user_id):
        with closing(self.connect()) as db:
            row = db.execute("SELECT document FROM profiles WHERE user_id=? ORDER BY version DESC LIMIT 1", (user_id,)).fetchone()
            return json.loads(row[0]) if row else build_profile(user_id, [], 0, int(time.time()))

    def profile_history(self, user_id, count, before_version=None):
        """One read-only page of the user's persisted snapshots, newest first."""
        if type(user_id) is not str or not user_id or len(user_id) > 36 \
                or type(count) is not int or not 1 <= count <= 50 \
                or (before_version is not None
                    and (type(before_version) is not int or before_version < 1)):
            raise ValueError("Invalid profile history page")
        with closing(self.connect()) as db:
            # No shared lock with sync/refresh: a newly committed higher version
            # does not disturb paging over older versions.
            if before_version is None:
                rows = db.execute(
                    "SELECT version,document FROM profiles WHERE user_id=? "
                    "ORDER BY version DESC LIMIT ?", (user_id, count + 1)).fetchall()
            else:
                rows = db.execute(
                    "SELECT version,document FROM profiles WHERE user_id=? "
                    "AND version<? ORDER BY version DESC LIMIT ?",
                    (user_id, before_version, count + 1)).fetchall()
            return parse_history_page(user_id, count, [(row[0], row[1]) for row in rows])

    def refresh_profiles(self, after="", count=50, max_age_seconds=86400, now=None):
        """Recompute one bounded user page from imported events, without moving the event cursor."""
        if not isinstance(after, str) or len(after) > 36 or type(count) is not int or not 1 <= count <= 100:
            raise ValueError("Invalid refresh page")
        if type(max_age_seconds) is not int or not 3600 <= max_age_seconds <= 2592000:
            raise ValueError("Invalid refresh age")
        now = int(time.time()) if now is None else now
        with closing(self.connect()) as db, db:
            # Shares the writer lock with sync; no duplicate versions or lost profile updates.
            self._begin_write(db)
            rows = db.execute("""SELECT p.user_id,p.version,p.document FROM profiles p
                JOIN (SELECT user_id,MAX(version) AS version FROM profiles
                      WHERE user_id>? GROUP BY user_id ORDER BY user_id LIMIT ?) latest
                ON p.user_id=latest.user_id AND p.version=latest.version ORDER BY p.user_id""",
                (after, count + 1)).fetchall()
            selected = rows[:count]
            updated = 0
            for row in selected:
                previous = json.loads(row["document"])
                if (previous["rule_version"] == RULE_VERSION
                        and now - max_age_seconds < previous["generated_at"] <= now + 300):
                    continue
                events = [json.loads(event[0]) for event in db.execute(
                    "SELECT document FROM events WHERE user_id=? ORDER BY seq", (row["user_id"],))]
                profile = build_profile(row["user_id"], events, row["version"] + 1, now)
                db.execute("INSERT INTO profiles VALUES(?,?,?)",
                           (row["user_id"], profile["version"], json.dumps(profile, ensure_ascii=False)))
                updated += 1
            self._record_operation(db, "refresh", now, len(rows) > count)
            return {"scanned_users": len(selected), "updated_users": updated,
                    "next_cursor": selected[-1]["user_id"] if selected else after,
                    "has_more": len(rows) > count, "evaluated_at": now}

    def _record_operation(self, db, operation, now, more):
        sql = ("INSERT INTO maintenance_state VALUES(?,?,?) ON DUPLICATE KEY UPDATE "
               "completed_at=VALUES(completed_at),has_more=VALUES(has_more)" if self.mysql else
               "INSERT INTO maintenance_state VALUES(?,?,?) ON CONFLICT(operation) "
               "DO UPDATE SET completed_at=excluded.completed_at,has_more=excluded.has_more")
        db.execute(sql,
            (operation, now, int(more)))

    def status(self, now=None):
        now = int(time.time()) if now is None else now
        with closing(self.connect()) as db:
            # All counters and watermarks come from the same read transaction.
            if self.mysql:
                db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                db.execute("START TRANSACTION READ ONLY")
            else:
                db.execute("BEGIN")
            checkpoint = db.execute("SELECT source_id,`cursor` FROM checkpoint WHERE id=1").fetchone()
            events = db.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            snapshots = db.execute("SELECT COUNT(*) FROM profiles").fetchone()[0]
            users = stale = cold = 0
            oldest = newest = None
            for row in db.execute("""SELECT p.document FROM profiles p JOIN
                    (SELECT user_id,MAX(version) version FROM profiles GROUP BY user_id) latest
                    ON p.user_id=latest.user_id AND p.version=latest.version"""):
                profile = json.loads(row[0])
                generated = profile["generated_at"]
                if type(generated) is not int or type(profile["cold_start"]) is not bool:
                    raise ValueError("Invalid stored profile")
                users += 1
                cold += int(profile["cold_start"])
                stale += int(not (now - 86400 < generated <= now + 300)
                             or profile["rule_version"] != RULE_VERSION)
                oldest = generated if oldest is None else min(oldest, generated)
                newest = generated if newest is None else max(newest, generated)
            operations = {row["operation"]: {"last_success_at": row["completed_at"],
                                             "last_page_has_more": bool(row["has_more"])}
                          for row in db.execute("SELECT * FROM maintenance_state")}
            return {"evaluated_at": now, "source_bound": checkpoint["source_id"] is not None,
                    "event_cursor": checkpoint["cursor"], "event_count": events,
                    "profile_users": users, "profile_snapshots": snapshots,
                    "cold_start_users": cold, "stale_profile_users": stale,
                    "profile_max_age_seconds": 86400,
                    "oldest_profile_at": oldest, "newest_profile_at": newest,
                    "sync": operations.get("sync"), "refresh": operations.get("refresh"),
                    "upstream_backlog": "unknown"}
