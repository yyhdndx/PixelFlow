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
                CREATE TABLE IF NOT EXISTS agent_traces (
                    trace_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, question TEXT NOT NULL,
                    answer TEXT NOT NULL, source_count INTEGER NOT NULL, degraded INTEGER NOT NULL,
                    steps TEXT NOT NULL, created_at BIGINT NOT NULL,
                    seq INTEGER, session_id TEXT NOT NULL DEFAULT '',
                    latency_ms INTEGER NOT NULL DEFAULT 0, provider TEXT NOT NULL DEFAULT '',
                    degraded_reason TEXT NOT NULL DEFAULT '');
                CREATE INDEX IF NOT EXISTS agent_traces_user ON agent_traces(user_id,created_at);
                CREATE TABLE IF NOT EXISTS agent_sessions (
                    session_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, turns TEXT NOT NULL,
                    created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL);
                """)
                # 兼容升级：为既有 sqlite 文件补齐任务22新增列（重复列报错可忽略）。
                for statement in (
                        "ALTER TABLE agent_traces ADD COLUMN seq INTEGER",
                        "ALTER TABLE agent_traces ADD COLUMN session_id TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE agent_traces ADD COLUMN latency_ms INTEGER NOT NULL DEFAULT 0",
                        "ALTER TABLE agent_traces ADD COLUMN provider TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE agent_traces ADD COLUMN degraded_reason TEXT NOT NULL DEFAULT ''"):
                    try:
                        db.execute(statement)
                    except sqlite3.OperationalError:
                        pass
                # 存量行按 created_at 顺序补 seq（逐行递增）。
                seq = db.execute("SELECT COALESCE(MAX(seq),0) FROM agent_traces").fetchone()[0]
                for row in db.execute(
                        "SELECT trace_id FROM agent_traces WHERE seq IS NULL ORDER BY created_at"):
                    seq += 1
                    db.execute("UPDATE agent_traces SET seq=? WHERE trace_id=?", (seq, row[0]))

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

    def save_agent_trace(self, *, trace_id, user_id, question, answer, source_count,
                         degraded, steps, created_at, session_id="", latency_ms=0,
                         provider="", degraded_reason=""):
        if type(trace_id) is not str or not 1 <= len(trace_id) <= 36 \
                or type(user_id) is not str or not 1 <= len(user_id) <= 36 \
                or type(question) is not str or not 1 <= len(question) <= 200 \
                or type(answer) is not str or not 1 <= len(answer) <= 1500 \
                or type(source_count) is not int or not 0 <= source_count <= 20 \
                or degraded not in (0, 1) or type(created_at) is not int \
                or type(session_id) is not str or len(session_id) > 36 \
                or type(latency_ms) is not int or not 0 <= latency_ms <= 3600000 \
                or type(provider) is not str or len(provider) > 64 \
                or type(degraded_reason) is not str or len(degraded_reason) > 500:
            raise ValueError("Invalid agent trace")
        with closing(self.connect()) as db, db:
            self._begin_write(db)
            seq = db.execute("SELECT COALESCE(MAX(seq),0)+1 FROM agent_traces").fetchone()[0]
            db.execute("INSERT INTO agent_traces (trace_id,user_id,question,answer,source_count,"
                       "degraded,steps,created_at,seq,session_id,latency_ms,provider,"
                       "degraded_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                trace_id, user_id, question, answer, source_count, int(degraded),
                json.dumps(steps, ensure_ascii=False), created_at, seq,
                session_id, latency_ms, provider, degraded_reason))

    def commit_search_stream(self, *, result, user_id, question, history, now, checkpoint=lambda: None):
        trace_id, session_id = result["trace_id"], result["session_id"]
        turns = history[-9:] + [{"question": question, "answer": result["answer"]}]
        sql = ("INSERT INTO agent_sessions VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE "
               "turns=VALUES(turns),updated_at=VALUES(updated_at)" if self.mysql else
               "INSERT INTO agent_sessions VALUES(?,?,?,?,?) ON CONFLICT(session_id) "
               "DO UPDATE SET turns=excluded.turns,updated_at=excluded.updated_at")
        with closing(self.connect()) as db, db:
            self._begin_write(db)
            existing = db.execute("SELECT user_id,answer,session_id,steps FROM agent_traces WHERE trace_id=?", (trace_id,)).fetchone()
            if existing is not None:
                if (existing["user_id"] != user_id or existing["answer"] != result["answer"]
                        or existing["session_id"] != session_id
                        or json.loads(existing["steps"]) != [{"step": "stream", "status": "completed"}]):
                    raise ValueError("Trace confirmation mismatch")
                return
            session = db.execute("SELECT user_id,created_at,turns FROM agent_sessions WHERE session_id=?", (session_id,)).fetchone()
            if session is not None:
                if session["user_id"] != user_id:
                    raise ValueError("Session owner mismatch")
                turns = json.loads(session["turns"])[-9:] + [{"question": question, "answer": result["answer"]}]
            seq = db.execute("SELECT COALESCE(MAX(seq),0)+1 FROM agent_traces").fetchone()[0]
            db.execute("INSERT INTO agent_traces (trace_id,user_id,question,answer,source_count,"
                       "degraded,steps,created_at,seq,session_id,latency_ms,provider,degraded_reason) "
                       "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                trace_id, user_id, question, result["answer"], len(result["sources"]), result["degraded"],
                json.dumps([{"step": "stream", "status": "completed"}]), now, seq, session_id, 0, "", ""))
            db.execute(sql, (session_id, user_id, json.dumps(turns, ensure_ascii=False),
                            session["created_at"] if session else now, now))
            checkpoint()

    @staticmethod
    def _trace_summary(row):
        return {"trace_id": row["trace_id"], "user_id": row["user_id"],
                "question": row["question"], "created_at": row["created_at"],
                "latency_ms": row["latency_ms"], "degraded": row["degraded"],
                "source_count": row["source_count"], "provider": row["provider"]}

    def agent_trace_page(self, count, before_seq=None):
        """一页轨迹摘要，seq 倒序（最新在前）；before_seq 为上一页 next_cursor。"""
        if type(count) is not int or not 1 <= count <= 50 \
                or (before_seq is not None
                    and (type(before_seq) is not int or before_seq < 1)):
            raise ValueError("Invalid agent trace page")
        with closing(self.connect()) as db:
            if before_seq is None:
                rows = db.execute("SELECT * FROM agent_traces ORDER BY seq DESC LIMIT ?",
                                  (count + 1,)).fetchall()
            else:
                rows = db.execute("SELECT * FROM agent_traces WHERE seq<? ORDER BY seq DESC LIMIT ?",
                                  (before_seq, count + 1)).fetchall()
            selected = rows[:count]
            cursor = str(selected[-1]["seq"]) if len(rows) > count and selected else None
            return {"traces": [self._trace_summary(row) for row in selected],
                    "next_cursor": cursor}

    def agent_trace_detail(self, trace_id):
        """单条轨迹详情：列表列 + steps JSON 中的检索/模型/来源细节；不存在返回 None。"""
        if type(trace_id) is not str or not 1 <= len(trace_id) <= 36:
            raise ValueError("Invalid agent trace lookup")
        with closing(self.connect()) as db:
            row = db.execute("SELECT * FROM agent_traces WHERE trace_id=?",
                             (trace_id,)).fetchone()
        if row is None:
            return None
        try:
            steps = json.loads(row["steps"])
        except (TypeError, json.JSONDecodeError):
            steps = []
        retrieve = next((s for s in steps if isinstance(s, dict) and s.get("step") == "retrieve"), {})
        synthesize = next((s for s in steps if isinstance(s, dict) and s.get("step") == "synthesize"), {})
        selected_ids = [str(v) for v in retrieve.get("selected_ids", [])
                        if str(v).isdigit() and 0 < len(str(v)) <= 18][:5]
        sources = [{"post_id": str(item["post_id"]), "title": str(item["title"])[:120]}
                   for item in retrieve.get("sources", [])
                   if isinstance(item, dict) and str(item.get("post_id", "")).isdigit()
                   and isinstance(item.get("title"), str)][:5]
        return {"trace_id": row["trace_id"], "user_id": row["user_id"],
                "session_id": row["session_id"], "question": row["question"],
                "created_at": row["created_at"], "latency_ms": row["latency_ms"],
                "degraded": row["degraded"], "degraded_reason": row["degraded_reason"],
                "provider": row["provider"],
                "retrieval": {"query": str(retrieve.get("query", ""))[:200],
                              "candidate_count": int(retrieve.get("candidate_count", 0) or 0),
                              "selected_ids": selected_ids},
                "llm": {"model": str(synthesize.get("model", ""))[:64],
                        "latency_ms": int(synthesize.get("latency_ms", 0) or 0)},
                "answer": row["answer"],
                "sources": sources}

    def load_agent_session(self, session_id, user_id):
        """Return the session's turns for this user, or None if not found/owner mismatch."""
        if type(session_id) is not str or not 1 <= len(session_id) <= 36 \
                or type(user_id) is not str or not 1 <= len(user_id) <= 36:
            raise ValueError("Invalid agent session lookup")
        with closing(self.connect()) as db:
            row = db.execute("SELECT user_id,turns FROM agent_sessions WHERE session_id=?",
                             (session_id,)).fetchone()
        if row is None or row["user_id"] != user_id:
            return None
        turns = json.loads(row["turns"])
        if not isinstance(turns, list):
            raise ValueError("Corrupt agent session")
        return {"turns": turns[-10:]}

    def save_agent_session(self, session_id, user_id, turns, now=None):
        now = int(time.time()) if now is None else now
        if type(session_id) is not str or not 1 <= len(session_id) <= 36 \
                or type(user_id) is not str or not 1 <= len(user_id) <= 36 \
                or not isinstance(turns, list) or len(turns) > 10 \
                or type(now) is not int:
            raise ValueError("Invalid agent session")
        document = json.dumps(turns[-10:], ensure_ascii=False)
        sql = ("INSERT INTO agent_sessions VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE "
               "turns=VALUES(turns),updated_at=VALUES(updated_at)" if self.mysql else
               "INSERT INTO agent_sessions VALUES(?,?,?,?,?) ON CONFLICT(session_id) "
               "DO UPDATE SET turns=excluded.turns,updated_at=excluded.updated_at")
        with closing(self.connect()) as db, db:
            row = db.execute("SELECT created_at FROM agent_sessions WHERE session_id=?",
                             (session_id,)).fetchone()
            created_at = row["created_at"] if row else now
            db.execute(sql, (session_id, user_id, document, created_at, now))


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
