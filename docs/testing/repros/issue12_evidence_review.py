"""Validate the real pipeline artifact and one acceptance case absent from the submitted tests."""
import datetime
import json
import os
from pathlib import Path
import re
import time

ROOT = Path(os.environ.get("ISSUE12_REVIEW_ROOT", Path(__file__).resolve().parents[1]))
SUMMARY = ROOT / "server/target/issue12-independent-review/summary.json"


def test_summary_start_time_is_a_wall_clock_date():
    summary = json.loads(SUMMARY.read_text(encoding="utf-8"))
    started = datetime.datetime.fromisoformat(summary["started_at"])
    written = datetime.datetime.fromtimestamp(SUMMARY.stat().st_mtime)
    assert abs((written - started).total_seconds()) < 3600, "Audit time must describe this run, not a 1970 monotonic-clock date"


def test_summary_checks_do_not_reveal_complete_account_ids():
    summary = json.loads(SUMMARY.read_text(encoding="utf-8"))
    details = "\n".join(row.get("detail", "") for row in summary["checks"])
    full_account_ids = re.findall(r"[\"']user_id[\"']:\s*[\"']([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})", details)
    count = len(full_account_ids)
    assert count == 0, "Account IDs inside checks must follow the documented redaction policy"


def test_preference_change_during_ranking_does_not_deliver_old_items():
    from pixelflow_intelligence.recommendation.preview import preview
    class Source:
        def public_candidates(self, token):
            return [{"post_id": "1", "author_id": "author", "title": "coffee", "media_type": "image",
                     "like_count": 1, "create_time": int(time.time()) - 1, "status": "PUBLISHED", "author_enabled": True}]
    calls = 0
    def preferences(user):
        nonlocal calls
        calls += 1
        return {"user_id": user, "version": calls - 1, "personalized": True, "excluded_post_ids": [],
                "blocked_author_ids": [], "suppressed_topics": []}
    result = preview("audit-user", None, Source(), "test-token", preferences)
    assert calls == 2
    assert result["preferences_changed"] is True
    assert result["items"] == []
