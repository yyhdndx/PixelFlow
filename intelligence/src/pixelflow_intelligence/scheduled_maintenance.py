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
from contextlib import contextmanager
from pathlib import Path

import httpx

from . import maintenance

try:
    import fcntl
except ImportError:  # pragma: no cover - platform dependent
    fcntl = None

try:
    import msvcrt
except ImportError:  # pragma: no cover - platform dependent
    msvcrt = None

STATE_VERSION = 1
STATE_FILENAME = "state.json"
LOCK_FILENAME = ".scheduled.lock"
DEFAULT_STATE_DIR = "/var/lib/pixelflow/python/maintenance"


class StateError(Exception):
    pass


class StateLockBusy(Exception):
    pass


def state_dir_from_env():
    return Path(os.environ.get("PIXELFLOW_MAINTENANCE_STATE_DIR") or DEFAULT_STATE_DIR)


def fresh_state():
    return {"version": STATE_VERSION, "refresh_after": "",
            "last_run_at": None, "last_status": None}


def load_state(state_dir):
    path = state_dir / STATE_FILENAME
    if not path.exists():
        return fresh_state()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise StateError(
            f"CORRUPT_STATE_FILE: {path} is not readable JSON "
            f"({error}); restore it from backup or delete it to restart the scan from scratch"
        ) from None
    if not isinstance(data, dict):
        raise StateError(
            f"CORRUPT_STATE_FILE: {path} is not a JSON object; "
            f"restore it from backup or delete it to restart the scan from scratch"
        )
    version = data.get("version")
    refresh_after = data.get("refresh_after")
    last_run_at = data.get("last_run_at")
    last_status = data.get("last_status")
    if (type(version) is not int or version != STATE_VERSION
            or type(refresh_after) is not str or len(refresh_after) > 36
            or (last_run_at is not None and type(last_run_at) is not int)
            or (last_status is not None and last_status not in ("complete", "incomplete", "failed"))):
        raise StateError(
            f"CORRUPT_STATE_FILE: {path} has an unknown or malformed format; "
            f"restore it from backup or delete it to restart the scan from scratch"
        )
    return {"version": version, "refresh_after": refresh_after,
            "last_run_at": last_run_at, "last_status": last_status}


def state_lock_path(state_dir):
    return state_dir / LOCK_FILENAME


@contextmanager
def hold_state_lock(state_dir):
    """Hold an OS-level exclusive lock for the whole pass.

    Uses fcntl.flock on POSIX and msvcrt.locking on Windows, so the lock is
    held by the open file description: it is released automatically when the
    process exits (even on crash), and concurrent contenders block instead of
    racing on lock-file deletion. Raises StateLockBusy if another process
    already holds the lock.
    """
    state_dir.mkdir(parents=True, exist_ok=True)
    lock_path = state_lock_path(state_dir)
    handle = open(lock_path, "a+b")
    try:
        if fcntl is not None:
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise StateLockBusy() from None
        elif msvcrt is not None:
            handle.seek(0)
            written = handle.write(b"\0" * 64)
            handle.flush()
            if written < 64:
                raise StateError("LOCK_FILE_NOT_WRITABLE")
            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 64)
            except OSError:
                raise StateLockBusy() from None
        else:  # pragma: no cover - no supported lock backend
            raise StateError("LOCK_BACKEND_UNSUPPORTED")
        yield handle
    finally:
        try:
            handle.close()
        except OSError:
            pass


def save_state(state_dir, state):
    state_dir.mkdir(parents=True, exist_ok=True)
    path = state_dir / STATE_FILENAME
    tmp = state_dir / (STATE_FILENAME + f".tmp.{os.getpid()}")
    tmp.write_text(json.dumps(state, ensure_ascii=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


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

    origin = os.getenv("PIXELFLOW_MAINTENANCE_URL", "http://127.0.0.1:8001")
    token = os.getenv("PIXELFLOW_INTELLIGENCE_TOKEN", "")
    try:
        with hold_state_lock(state_dir):
            # Locked scope: reading the state, running the maintenance pass and
            # saving the state are all protected by the same lock, so the pass
            # can never run against a stale cursor saved by a previous pass.
            cursor_in = state["refresh_after"]
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
                    # Only a successfully returned cursor advances the stored
                    # position; failures keep the previous cursor so the pass
                    # can be replayed safely.
                    new_cursor = result.get("refresh_after", "")
                    if new_cursor and new_cursor != cursor_in:
                        state["refresh_after"] = new_cursor
                        advanced = True
                state["last_run_at"] = int(time.time())
                state["last_status"] = status
                try:
                    save_state(state_dir, state)
                except OSError as error:
                    # Keep the previous state file untouched and report a clear
                    # failure instead of crashing with a bare exception.
                    entry.update(
                        status="failed",
                        reason=f"STATE_SAVE_FAILED: cannot persist state to {state_dir} ({error})",
                        complete=False,
                        cursor_before=cursor_in,
                        cursor_after=cursor_in,
                        cursor_advanced=False,
                        result=result,
                        duration=round(time.monotonic() - started, 3),
                        exit_code=1,
                    )
                    print(json.dumps(entry, ensure_ascii=True))
                    return 1
            except StateError as error:
                entry.update(status="failed", reason=str(error),
                             duration=round(time.monotonic() - started, 3), exit_code=1)
                print(json.dumps(entry, ensure_ascii=True))
                return 1
    except StateLockBusy:
        entry.update(status="failed", reason="LOCK_BUSY",
                     detail="another scheduled_maintenance process holds the lock for this state directory",
                     duration=round(time.monotonic() - started, 3), exit_code=3)
        print(json.dumps(entry, ensure_ascii=True))
        return 3

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
