"""Offline SQLite -> empty MySQL analytics import; never modifies the source."""
import argparse
import os
import sqlite3
from contextlib import closing
from pathlib import Path
from .analytics.store import Store
from .analytics.database import DATABASE_ERRORS

TABLES = {
    "checkpoint": ("id", "source_id", "cursor"),
    "events": ("source_id", "seq", "user_id", "event_id", "document"),
    "profiles": ("user_id", "version", "document"),
    "maintenance_state": ("operation", "completed_at", "has_more"),
}


def migrate(source, target, legacy_preferences_handled=False):
    path = Path(source).resolve(strict=True)
    if not target.startswith("mysql://"):
        raise ValueError("Target must be a MySQL URL")
    store = Store(target)
    counts = {}
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as src:
        src.execute("BEGIN")
        tables = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        legacy = tables & {"recommendation_preferences", "recommendation_exclusions", "recommendation_blocked_authors"}
        if not legacy_preferences_handled and any(src.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in legacy):
            raise ValueError("Migrate legacy preferences to Java before importing analytics")
        with closing(store.connect()) as dst, dst:
            store._begin_write(dst)
            checkpoint = dst.execute("SELECT * FROM checkpoint WHERE id=1").fetchone()
            if checkpoint["source_id"] is not None or checkpoint["cursor"] != 0:
                raise ValueError("Target checkpoint is already in use")
            for table in TABLES:
                if table != "checkpoint" and dst.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]:
                    raise ValueError("Target must be empty")
            if src.execute("SELECT COUNT(*) FROM checkpoint WHERE id=1").fetchone()[0] != 1:
                raise ValueError("Source checkpoint missing")
            dst.execute("DELETE FROM checkpoint")
            for table, columns in TABLES.items():
                names = ",".join(f"`{column}`" for column in columns)
                placeholders = ",".join("?" for _ in columns)
                count = 0
                for row in src.execute(f"SELECT {names} FROM {table}"):
                    dst.execute(f"INSERT INTO {table} ({names}) VALUES({placeholders})", row)
                    count += 1
                if dst.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] != count:
                    raise ValueError("Imported row count mismatch")
                counts[table] = count
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="Offline SQLite database file")
    parser.add_argument("--legacy-preferences-handled", action="store_true",
                        help="Confirm nonempty legacy preference tables were already migrated to Java")
    args = parser.parse_args()
    try:
        print(migrate(args.source, os.environ["ANALYTICS_DB"], args.legacy_preferences_handled))
    except (ValueError, KeyError, OSError, *DATABASE_ERRORS):
        # Drivers may include credentials/DSNs in their exceptions.
        parser.exit(1, "Migration failed; check source schema, credentials and empty target. Source unchanged.\n")


if __name__ == "__main__":
    main()
