"""Local integration smoke test. Uses fresh ignored databases and ephemeral credentials."""
import os
import json
from pathlib import Path
import secrets
import socket
import sqlite3
from contextlib import closing
import subprocess
import sys
import tempfile
import time
import httpx
from pixelflow_intelligence.migrate_preferences import run as migrate_preferences


def check_legacy_migration(client, java_url, python_url, runtime, post_id, author_id):
    password = secrets.token_hex(16)
    client.post(java_url+"/api/auth/register", json={"username": "migration_demo", "password": password,
                                                  "nickname": "迁移联调"}).raise_for_status()
    login = client.post(java_url+"/api/auth/login", json={"username": "migration_demo", "password": password})
    login.raise_for_status()
    headers = {"Authorization": "Bearer " + login.json()["accessToken"]}
    identity = client.get(java_url+"/api/users/me", headers=headers)
    identity.raise_for_status()
    user_id = identity.json()["id"]
    legacy = runtime / "legacy-preferences.sqlite3"
    with closing(sqlite3.connect(legacy)) as db, db:
        db.executescript("""CREATE TABLE recommendation_preferences(user_id TEXT PRIMARY KEY,personalized INTEGER,version INTEGER);
            CREATE TABLE recommendation_exclusions(user_id TEXT,post_id TEXT);
            CREATE TABLE recommendation_blocked_authors(user_id TEXT,author_id TEXT);""")
        db.execute("INSERT INTO recommendation_preferences VALUES(?,0,3)", (user_id,))
        db.execute("INSERT INTO recommendation_exclusions VALUES(?,?)", (user_id,post_id))
        db.execute("INSERT INTO recommendation_blocked_authors VALUES(?,?)", (user_id,author_id))
    before = legacy.read_bytes()
    with httpx.Client(base_url=java_url, headers=headers, timeout=8, trust_env=False, follow_redirects=False) as migration:
        assert migrate_preferences(migration, legacy)["status"] == "ready_no_changes"
        assert migration.get("/api/recommendations/me/preferences").json()["version"] == 0
        assert migrate_preferences(migration, legacy, apply=True)["status"] == "imported"
        assert migrate_preferences(migration, legacy, apply=True)["status"] == "already_matches"
    assert legacy.read_bytes() == before
    result = client.get(python_url+"/recommendations/me/preview", headers=headers)
    result.raise_for_status()
    assert result.json()["strategy"] == "popular"
    assert all(item["post_id"] != post_id and item["author_id"] != author_id for item in result.json()["items"])


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_healthy(client, url, process):
    for _ in range(150):
        if process.poll() is not None:
            raise RuntimeError("Service exited before becoming healthy; inspect smoke logs")
        try:
            if client.get(url + "/health").status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(0.2)
    raise RuntimeError("Service startup timed out")


def main():
    root = Path(__file__).resolve().parents[2]
    jar = root / "server/target/pixelflow-server-0.1.0.jar"
    if not jar.is_file():
        raise RuntimeError("Build the Java JAR first")
    runtime = Path(tempfile.mkdtemp(prefix="analytics-smoke-", dir=root / "server/target"))
    java_port, python_port = free_port(), free_port()
    java_url, python_url = f"http://127.0.0.1:{java_port}", f"http://127.0.0.1:{python_port}"
    export_key, sync_key = secrets.token_hex(32), secrets.token_hex(32)
    environment = os.environ.copy()
    environment.update(
        PIXELFLOW_ANALYTICS_EXPORT_TOKEN=export_key, PIXELFLOW_INTELLIGENCE_TOKEN=sync_key,
        PIXELFLOW_SEED="true", PIXELFLOW_ADMIN_PASSWORD="", SERVER_ADDRESS="127.0.0.1",
        SERVER_PORT=str(java_port), PIXELFLOW_PUBLIC_BASE_URL=java_url, JAVA_BASE_URL=java_url,
        DB_URL=f"jdbc:h2:file:{(runtime/'business').as_posix()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        ANALYTICS_DB=str(runtime / "analytics.sqlite3"),
        PIXELFLOW_INTELLIGENCE_BASE_URL=python_url,
    )
    processes = []
    try:
        with (runtime / "java.log").open("w") as java_log, (runtime / "python.log").open("w") as python_log, httpx.Client(timeout=8, trust_env=False) as client:
            java = subprocess.Popen(["java", "-jar", str(jar)], cwd=root/"server", env=environment,
                                    stdout=java_log, stderr=subprocess.STDOUT,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            processes.append(java)
            wait_healthy(client, java_url, java)
            python = subprocess.Popen([sys.executable, "-m", "uvicorn", "pixelflow_intelligence.api.app:create_app",
                                       "--factory", "--host", "127.0.0.1", "--port", str(python_port)],
                                      cwd=root/"intelligence", env=environment, stdout=python_log,
                                      stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            processes.append(python)
            wait_healthy(client, python_url, python)
            password = secrets.token_hex(16)
            response = client.post(java_url+"/api/auth/register",
                                   json={"username": "pipeline_demo", "password": password, "nickname": "联调用户"})
            response.raise_for_status()
            tokens = client.post(java_url+"/api/auth/login", json={"username": "pipeline_demo", "password": password})
            tokens.raise_for_status()
            user = {"Authorization": "Bearer "+tokens.json()["accessToken"]}
            sync = {"Authorization": "Bearer "+sync_key}
            assert client.get(python_url+"/profiles/me", headers=user).json()["cold_start"]
            posts = client.get(java_url+"/feed/?count=50&accept_video_clip=true").json()["post_list"]
            post_id = next(post["post_id"] for post in posts if "咖啡" in post["title"])
            for kind, duration in [("CLICK", None), ("DWELL", 60000)]:
                event = {"eventId": secrets.token_hex(16), "postId": post_id, "type": kind,
                         "occurredAt": int(time.time()), "scene": "DETAIL"}
                if duration:
                    event["durationMs"] = duration
                result = client.post(java_url+"/api/events", headers=user, json=event)
                result.raise_for_status()
                duplicate = client.post(java_url+"/api/events", headers=user, json=event)
                assert duplicate.json()["duplicate"] is True
            client.put(java_url+"/api/interactions/likes", headers=user,
                       json={"targetId": post_id, "active": True}).raise_for_status()
            result = client.post(python_url+"/internal/sync", headers=sync)
            result.raise_for_status()
            assert result.json()["imported"] == 3
            profile = client.get(python_url+"/profiles/me", headers=user).json()
            assert profile["topics"][0]["name"] == "美食"
            assert profile["topics"][0]["score"] == 6
            assert profile["version"] == 1
            refreshed = client.post(python_url+"/internal/profiles/refresh", headers=sync, json={})
            refreshed.raise_for_status()
            assert refreshed.json()["scanned_users"] == 1
            assert refreshed.json()["updated_users"] == 0
            assert client.post(python_url+"/internal/profiles/refresh", headers=user, json={}).status_code == 401
            maintenance_env = {**environment, "PIXELFLOW_MAINTENANCE_URL": python_url}
            maintenance = subprocess.run(
                [sys.executable, "-m", "pixelflow_intelligence.maintenance"],
                cwd=root/"intelligence", env=maintenance_env, capture_output=True, text=True,
                timeout=30, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            assert maintenance.returncode == 0, "Maintenance command failed; output withheld"
            summary = json.loads(maintenance.stdout)
            assert summary["status"] == "complete" and summary["sync_pages"] == 1
            assert summary["imported"] == 0 and summary["refreshed_users"] == 0
            status_response = client.get(python_url+"/internal/status", headers=sync)
            status_response.raise_for_status()
            status = status_response.json()
            assert status["event_count"] == 3 and status["profile_users"] == 1
            assert status["sync"]["last_success_at"] > 0 and status["refresh"]["last_success_at"] > 0
            assert status["upstream_backlog"] == "unknown"
            assert status_response.headers["cache-control"] == "no-store"
            assert client.get(python_url+"/internal/status", headers=user).status_code == 401
            recommendation = client.get(python_url+"/recommendations/me/preview", headers=user)
            recommendation.raise_for_status()
            recommended = recommendation.json()
            assert recommended["strategy"] == "personalized"
            assert recommended["profile_version"] == profile["version"]
            assert recommended["items"] and recommended["candidate_count"] > 0
            assert len(recommended["steps"]) == 3
            assert all(step["trace_id"] == recommended["trace_id"] for step in recommended["steps"])
            assert all(item["post_id"] in {post["post_id"] for post in posts} for item in recommended["items"])
            assert recommendation.headers["cache-control"] == "no-store"
            assert client.get(python_url+"/recommendations/me/preview").status_code == 401
            excluded_id = recommended["items"][0]["post_id"]
            blocked_author = recommended["items"][0]["author_id"]
            block_url = java_url+"/api/recommendations/me/blocked-authors/"+blocked_author
            client.put(block_url, headers=user, json={"blocked": True}).raise_for_status()
            blocked = client.get(python_url+"/recommendations/me/preview", headers=user)
            blocked.raise_for_status()
            assert all(item["author_id"] != blocked_author for item in blocked.json()["items"])
            client.put(block_url, headers=user, json={"blocked": False}).raise_for_status()
            control_url = java_url+"/api/interactions/not-interested"
            control = client.put(control_url, headers=user, json={"targetId": excluded_id, "active": True})
            control.raise_for_status()
            assert excluded_id in client.get(python_url+"/recommendations/me/preferences", headers=user).json()["excluded_post_ids"]
            filtered = client.get(python_url+"/recommendations/me/preview", headers=user)
            filtered.raise_for_status()
            assert excluded_id not in {item["post_id"] for item in filtered.json()["items"]}
            client.put(control_url, headers=user, json={"targetId": excluded_id, "active": False}).raise_for_status()
            preferences_url = java_url+"/api/recommendations/me/preferences"
            client.put(preferences_url, headers=user, json={"personalized": False}).raise_for_status()
            disabled = client.get(python_url+"/recommendations/me/preview", headers=user)
            disabled.raise_for_status()
            assert disabled.json()["reason"] == "personalization_disabled"
            assert disabled.json()["strategy"] == "popular"
            client.put(preferences_url, headers=user, json={"personalized": True}).raise_for_status()
            gateway = client.get(java_url+"/api/users/me/profile", headers=user)
            gateway.raise_for_status()
            assert gateway.json() == {**profile, "suppressed_topics": []}
            assert gateway.headers["cache-control"] == "no-store"
            assert client.get(java_url+"/api/users/me/profile").status_code == 401
            topic_url = java_url+"/api/users/me/profile/topics/美食"
            client.put(topic_url, headers=user, json={"suppressed": True}).raise_for_status()
            suppressed = client.get(python_url+"/recommendations/me/preview", headers=user)
            suppressed.raise_for_status()
            assert suppressed.json()["items"]
            assert all(item["components"]["topic"] == 0 for item in suppressed.json()["items"])
            visible_profile = client.get(java_url+"/api/users/me/profile", headers=user).json()
            assert visible_profile["topics"] == [] and visible_profile["suppressed_topics"] == ["美食"]
            client.put(topic_url, headers=user, json={"suppressed": False}).raise_for_status()
            restored = client.get(python_url+"/recommendations/me/preview", headers=user).json()
            assert any(item["components"]["topic"] > 0 for item in restored["items"])
            assert client.put(python_url+"/recommendations/me/preferences", headers=user,
                              json={"personalized": False}).status_code == 410
            # Java now emits NOT_INTERESTED; its attribution field must import as well.
            assert client.post(python_url+"/internal/sync", headers=sync).json()["imported"] == 1
            assert client.post(python_url+"/internal/sync", headers=sync).json()["imported"] == 0
            assert client.get(python_url+"/profiles/me", headers=user).json()["version"] == 2
            client.put(java_url+"/api/interactions/likes", headers=user,
                       json={"targetId": post_id, "active": False}).raise_for_status()
            assert client.post(python_url+"/internal/sync", headers=sync).json()["imported"] == 1
            assert client.get(python_url+"/profiles/me", headers=user).json()["topics"][0]["score"] == 3
            assert client.post(python_url+"/internal/sync", headers=user).status_code == 401
            assert client.get(java_url+"/api/internal/analytics/events", headers=user).status_code == 401
            check_legacy_migration(client, java_url, python_url, runtime, excluded_id, blocked_author)
            print("PASS: Java events -> Python profile -> Skills -> preview; authoritative preferences; topic suppression/undo; legacy migration; maintenance; service scopes isolated.")
            print("Runtime data and logs retained under:", runtime)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


if __name__ == "__main__":
    main()
