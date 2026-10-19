"""Periodic scheduler wrapper around maintenance.run.

Holds the schedule state (resume cursor for the profile scan), a per-state-dir
process lock, and exit-code semantics. Credentials and per-user details are
never written to the state file or the log line.
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import httpx

from . import maintenance

STATE_VERSION = 1
STATE_FILENAME = "state.json"
LOCK_FILENAME = ".scheduled.lock"
DEFAULT_STATE_DIR = "/var/lib/pixelflow/python/maintenance"


class StateError(Exception):
    pass


def state_dir_from_env():
    return Path(os.environ.get("PIXELFLOW_MAINTENANCE_STATE_DIR") or DEFAULT_STATE_DIR)


def load_state(state_dir):
    path = state_dir / STATE_FILENAME
    if not path.exists():
        return {"version": STATE_VERSION, "refresh_after": "",
                "last_run_at": None, "last_status": None}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise StateError(
            f"CORRUPT_STATE_FILE: {path} is not readable JSON "
            f"({error}); restore it from backup or delete it to restart the scan from scratch"
        ) from None
    if (not isinstance(data, dict) or data.get("version") != STATE_VERSION
            or not isinstance(data.get("refresh_after", ""), str)
            or len(data.get("refresh_after", "")) > 36):
        raise StateError(
            f"CORRUPT_STATE_FILE: {path} has an unknown or malformed format; "
            f"restore it from backup or delete it to restart the scan from scratch"
        )
    return data


def save_state(state_dir, state):
    state_dir.mkdir(parents=True, exist_ok=True)
    path = state_dir / STATE_FILENAME
    tmp = state_dir / (STATE_FILENAME + f".tmp.{os.getpid()}")
    tmp.write_text(json.dumps(state, ensure_ascii=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def acquire_lock(state_dir):
    state_dir.mkdir(parents=True, exist_ok=True)
    lock_path = state_dir / LOCK_FILENAME
    try:
        fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        return None
    os.write(fd, str(os.getpid()).encode("ascii"))
    os.close(fd)
    return lock_path


def _pid_alive(pid):
    if hasattr(os, "kill"):
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    return True


def remove_stale_lock(state_dir):
    lock_path = state_dir / LOCK_FILENAME
    try:
        raw = lock_path.read_text(encoding="ascii", errors="replace").strip()
        pid = int(raw)
    except (OSError, ValueError):
        pid = None
    if pid is None or not _pid_alive(pid):
        try:
            lock_path.unlink()
        except OSError:
            pass


def release_lock(lock_path):
    try:
        lock_path.unlink()
    except OSError:
        pass


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run one scheduled maintenance pass with a persistent resume cursor")
    parser.add_argument("--state-dir", default=None,
                        help="Directory holding state.json and the lock file")
    parser.add_argument("--max-sync-pages", type=int, default=20)
    parser.add_argument("--max-refresh-pages", type=int, default=20)
    parser.add_argument("--budget-seconds", type=int, default=120)
    args = parser.parse_args(argv)

    state_dir = Path(args.state_dir) if args.state_dir else state_dir_from_env()
    started = time.monotonic()
    entry = {"entry": "scheduled_maintenance", "state_dir": str(state_dir), "lock_held": True}

    try:
        state = load_state(state_dir)
    except StateError as error:
        entry.update(lock_held=False, status="failed", reason=str(error), duration=0.0)
        print(json.dumps(entry, ensure_ascii=True))
        return 1

    cursor_in = state["refresh_after"]
    lock_path = acquire_lock(state_dir)
    if lock_path is None:
        remove_stale_lock(state_dir)
        lock_path = acquire_lock(state_dir)
    if lock_path is None:
        entry.update(status="failed", reason="LOCK_BUSY",
                     detail="another scheduled_maintenance process holds the lock for this state directory",
                     duration=round(time.monotonic() - started, 3))
        print(json.dumps(entry, ensure_ascii=True))
        return 3

    origin = os.getenv("PIXELFLOW_MAINTENANCE_URL", "http://127.0.0.1:8001")
    token = os.getenv("PIXELFLOW_INTELLIGENCE_TOKEN", "")
    result = None
    exit_code = 1
    try:
        try:
            maintenance.validate_config(origin, token)
            with httpx.Client(base_url=origin, headers={"Authorization": "Bearer " + token},
                              follow_redirects=False, trust_env=False) as client:
                result = maintenance.run(
                    client,
                    max_sync_pages=args.max_sync_pages,
                    max_refresh_pages=args.max_refresh_pages,
                    refresh_after=cursor_in,
                    budget_seconds=args.budget_seconds,
                )
        except maintenance.MaintenanceError as error:
            result = {"status": "failed", "reason": str(error)}
        except KeyboardInterrupt:
            result = {"status": "failed", "reason": "INTERRUPTED"}

        status = result.get("status", "failed")
        exit_code = {"complete": 0, "incomplete": 2, "failed": 1}[status]
        advanced = False
        if status == "complete":
            state["refresh_after"] = ""
            advanced = cursor_in != ""
        elif status == "incomplete":
            # Only a successfully returned cursor advances the stored position;
            # failures keep the previous cursor so the pass can be replayed safely.
            new_cursor = result.get("refresh_after", "")
            if new_cursor and new_cursor != cursor_in:
                state["refresh_after"] = new_cursor
                advanced = True
        state["last_run_at"] = int(time.time())
        state["last_status"] = status
        save_state(state_dir, state)
    finally:
        release_lock(lock_path)

    entry.update(
        status=status,
        complete=status == "complete",
        cursor_before=cursor_in,
        cursor_after=state["refresh_after"],
        cursor_advanced=advanced,
        result=result,
        duration=round(time.monotonic() - started, 3),
        exit_code=exit_code,
    )
    print(json.dumps(entry, ensure_ascii=True))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
