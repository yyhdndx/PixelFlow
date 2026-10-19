import json
import os
import threading

import pytest

from pixelflow_intelligence import scheduled_maintenance as sm


def run_main(monkeypatch, tmp_path, run_impl, capsys, argv=None, token="x" * 32):
    monkeypatch.setenv("PIXELFLOW_INTELLIGENCE_TOKEN", token)
    monkeypatch.setattr("pixelflow_intelligence.maintenance.run", run_impl)
    argv = argv if argv is not None else ["--state-dir", str(tmp_path)]
    code = sm.main(argv)
    return code, capsys.readouterr().out


def ok_run(client, **kwargs):
    calls.append(kwargs)
    result = {"status": "complete", "sync_pages": 1, "imported": 1, "refresh_pages": 1,
              "refreshed_users": 1, "refresh_after": ""}
    result.update(next_responses.pop(0))
    return result


calls = []
next_responses = []


@pytest.fixture(autouse=True)
def _reset():
    calls.clear()
    next_responses.clear()


def test_complete_run_resets_cursor(monkeypatch, tmp_path, capsys):
    state = tmp_path / "state.json"
    state.write_text(json.dumps({"version": 1, "refresh_after": "alice",
                                 "last_run_at": 1, "last_status": "incomplete"}))
    next_responses.append({"refresh_after": ""})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 0
    saved = json.loads(state.read_text())
    assert saved["refresh_after"] == "" and saved["last_status"] == "complete"
    assert calls[0]["refresh_after"] == "alice"


def test_fresh_state_scans_from_beginning(monkeypatch, tmp_path, capsys):
    next_responses.append({"refresh_after": ""})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 0 and calls[0]["refresh_after"] == ""
    assert json.loads((tmp_path / "state.json").read_text())["refresh_after"] == ""


def test_incomplete_run_persists_resume_cursor(monkeypatch, tmp_path, capsys):
    next_responses.append({"status": "incomplete", "reason": "REFRESH_PAGE_LIMIT",
                           "refresh_after": "bob"})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 2
    saved = json.loads((tmp_path / "state.json").read_text())
    assert saved["refresh_after"] == "bob" and saved["last_status"] == "incomplete"


def test_second_run_resumes_from_saved_cursor(monkeypatch, tmp_path, capsys):
    next_responses.append({"status": "incomplete", "reason": "REFRESH_PAGE_LIMIT",
                           "refresh_after": "bob"})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 2
    next_responses.append({"status": "complete", "refresh_after": ""})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 0
    assert calls[1]["refresh_after"] == "bob"
    assert json.loads((tmp_path / "state.json").read_text())["refresh_after"] == ""


def test_incomplete_without_cursor_keeps_previous_cursor(monkeypatch, tmp_path, capsys):
    (tmp_path / "state.json").write_text(json.dumps({"version": 1, "refresh_after": "alice"}))
    next_responses.append({"status": "incomplete", "reason": "SYNC_PAGE_LIMIT",
                           "refresh_after": "alice"})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 2
    assert json.loads((tmp_path / "state.json").read_text())["refresh_after"] == "alice"


def test_failed_run_does_not_advance_cursor(monkeypatch, tmp_path, capsys):
    (tmp_path / "state.json").write_text(json.dumps({"version": 1, "refresh_after": "alice"}))

    def failing(client, **kwargs):
        return {"status": "failed", "reason": "HTTP_503", "refresh_after": ""}

    code, _ = run_main(monkeypatch, tmp_path, failing, capsys)
    assert code == 1
    saved = json.loads((tmp_path / "state.json").read_text())
    assert saved["refresh_after"] == "alice" and saved["last_status"] == "failed"


def test_run_exception_does_not_advance_cursor(monkeypatch, tmp_path, capsys):
    (tmp_path / "state.json").write_text(json.dumps({"version": 1, "refresh_after": "alice"}))

    def raising(client, **kwargs):
        raise sm.maintenance.MaintenanceError("REQUEST_OR_RESPONSE_FAILED")

    code, _ = run_main(monkeypatch, tmp_path, raising, capsys)
    assert code == 1
    assert json.loads((tmp_path / "state.json").read_text())["refresh_after"] == "alice"


def test_corrupt_state_file_reports_error_without_resetting(monkeypatch, tmp_path, capsys):
    (tmp_path / "state.json").write_text("{not json")
    code, out = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 1 and "CORRUPT_STATE_FILE" in out
    assert not (tmp_path / "state.json").exists() or "{not json" in (tmp_path / "state.json").read_text()


def test_wrong_version_state_file_reports_error(monkeypatch, tmp_path, capsys):
    (tmp_path / "state.json").write_text(json.dumps({"version": 99, "refresh_after": ""}))
    code, out = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 1 and "CORRUPT_STATE_FILE" in out


def test_lock_busy_returns_3(monkeypatch, tmp_path, capsys):
    (tmp_path / sm.LOCK_FILENAME).write_text(str(os.getpid()))
    code, out = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 3 and "LOCK_BUSY" in out
    assert not calls  # maintenance.run must not be invoked


def test_stale_lock_is_replaced(monkeypatch, tmp_path, capsys):
    (tmp_path / sm.LOCK_FILENAME).write_text("999999999")
    next_responses.append({"refresh_after": ""})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 0
    assert not (tmp_path / sm.LOCK_FILENAME).exists()


def test_lock_released_after_run(monkeypatch, tmp_path, capsys):
    next_responses.append({"refresh_after": ""})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 0
    assert not (tmp_path / sm.LOCK_FILENAME).exists()


def test_concurrent_same_directory_only_one_runs(monkeypatch, tmp_path, capsys):
    entered = threading.Event()
    finish = threading.Event()
    codes = []

    def slow_run(client, **kwargs):
        entered.set()
        finish.wait(5)
        return {"status": "complete", "refresh_after": ""}

    def second():
        codes.append(sm.main(["--state-dir", str(tmp_path)]))

    monkeypatch.setenv("PIXELFLOW_INTELLIGENCE_TOKEN", "x" * 32)
    monkeypatch.setattr("pixelflow_intelligence.maintenance.run", slow_run)
    thread = threading.Thread(target=second)
    thread.start()
    assert entered.wait(5)
    codes.append(sm.main(["--state-dir", str(tmp_path)]))
    finish.set()
    thread.join(5)
    assert sorted(codes) == [0, 3]


def test_state_write_is_atomic(monkeypatch, tmp_path, capsys):
    seen = []
    original_replace = os.replace

    def spy_replace(src, dst):
        seen.append((str(src), str(dst)))
        state = json.loads(open(dst, encoding="utf-8").read()) if os.path.exists(dst) else None
        if state is not None:
            seen.append(state)
        return original_replace(src, dst)

    monkeypatch.setattr(sm.os, "replace", spy_replace)
    next_responses.append({"refresh_after": ""})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 0
    assert any(name.endswith(".tmp." + str(os.getpid())) for name, _ in seen)


def test_output_log_contains_no_token(monkeypatch, tmp_path, capsys):
    next_responses.append({"status": "incomplete", "reason": "REFRESH_PAGE_LIMIT",
                           "refresh_after": "bob"})
    code, out = run_main(monkeypatch, tmp_path, ok_run, capsys)
    assert code == 2
    log = json.loads(out.strip().splitlines()[-1])
    assert log["complete"] is False and log["cursor_advanced"] is True
    assert "x" * 32 not in out


def test_invalid_config_fails_without_echo(monkeypatch, tmp_path, capsys):
    code, out = run_main(monkeypatch, tmp_path, ok_run, capsys, token="short")
    assert code == 1 and "short" not in out
    assert json.loads((tmp_path / "state.json").read_text())["last_status"] == "failed"


def test_time_budget_exhausted_result(monkeypatch, tmp_path, capsys):
    next_responses.append({"status": "failed", "reason": "TIME_BUDGET_EXHAUSTED"})
    code, _ = run_main(monkeypatch, tmp_path, ok_run, capsys, argv=[
        "--state-dir", str(tmp_path), "--budget-seconds", "1"])
    assert code == 1


def test_state_dir_env_override(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv("PIXELFLOW_MAINTENANCE_STATE_DIR", str(tmp_path))
    monkeypatch.setenv("PIXELFLOW_INTELLIGENCE_TOKEN", "x" * 32)
    monkeypatch.setattr("pixelflow_intelligence.maintenance.run", ok_run)
    next_responses.append({"refresh_after": ""})
    assert sm.main([]) == 0
    assert (tmp_path / "state.json").exists()
