"""Versioned MySQL schema. Add migrations for changes; do not edit an applied V1."""
MIGRATIONS = [(1, """
CREATE TABLE IF NOT EXISTS checkpoint (
 id INTEGER PRIMARY KEY CHECK(id=1), source_id VARCHAR(36), `cursor` BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO checkpoint VALUES(1,NULL,0) ON DUPLICATE KEY UPDATE id=id;
CREATE TABLE IF NOT EXISTS events (
 source_id VARCHAR(36) NOT NULL, seq BIGINT NOT NULL, user_id VARCHAR(36) NOT NULL,
 event_id VARCHAR(64) NOT NULL, document LONGTEXT NOT NULL,
 PRIMARY KEY(source_id,seq), UNIQUE(source_id,user_id,event_id), INDEX events_user(user_id,seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS profiles (
 user_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, document LONGTEXT NOT NULL,
 PRIMARY KEY(user_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE IF NOT EXISTS maintenance_state (
 operation VARCHAR(32) PRIMARY KEY, completed_at BIGINT NOT NULL, has_more INTEGER NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
""")]


def initialize(db):
    # MySQL DDL commits implicitly; use a connection-level lock.
    if db.execute("SELECT GET_LOCK('pixelflow_analytics_schema',30)").fetchone()[0] != 1:
        raise RuntimeError("Analytics schema migration lock unavailable")
    try:
        db.execute("CREATE TABLE IF NOT EXISTS analytics_schema_version (version INTEGER PRIMARY KEY)")
        current = db.execute("SELECT COALESCE(MAX(version),0) FROM analytics_schema_version").fetchone()[0]
        if current > MIGRATIONS[-1][0]:
            raise RuntimeError("Analytics schema is newer than this application")
        for version, sql in MIGRATIONS:
            if version > current:
                db.executescript(sql)
                db.execute("INSERT INTO analytics_schema_version VALUES(?)", (version,))
                db.commit()
    finally:
        db.execute("SELECT RELEASE_LOCK('pixelflow_analytics_schema')")
