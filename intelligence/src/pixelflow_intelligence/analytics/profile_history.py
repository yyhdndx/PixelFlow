"""Read-only history queries over persisted profile snapshots.

Returned summaries describe the interests as generated at the time of each
version; they are never recomputed or merged with current preferences.
"""
import json
import math


class InvalidProfileHistory(ValueError):
    """A stored snapshot cannot be served; callers must not echo its content."""


# Whitelist of build_profile output fields exposed in history summaries.
SUMMARY_FIELDS = ("rule_version", "generated_at", "window_days", "timezone",
                  "cold_start", "topics", "media", "event_counts", "explanation")
_TOPIC_FIELDS = ("name", "score", "share")


def _require_int(value, field):
    # bool is a subclass of int; snapshots must store real integers.
    if type(value) is not int:
        raise InvalidProfileHistory("Invalid stored profile history")
    return value


def _require_summary_topics(value):
    if type(value) is not list:
        raise InvalidProfileHistory("Invalid stored profile history")
    for entry in value:
        if type(entry) is not dict or set(entry) != set(_TOPIC_FIELDS):
            raise InvalidProfileHistory("Invalid stored profile history")
        if type(entry["name"]) is not str:
            raise InvalidProfileHistory("Invalid stored profile history")
        for field in ("score", "share"):
            number = entry[field]
            if type(number) is bool or not isinstance(number, (int, float)):
                raise InvalidProfileHistory("Invalid stored profile history")
            try:
                finite = math.isfinite(number)
            except OverflowError:
                raise InvalidProfileHistory("Invalid stored profile history") from None
            if not finite:
                raise InvalidProfileHistory("Invalid stored profile history")
    return [{"name": entry["name"], "score": entry["score"], "share": entry["share"]}
            for entry in value]


def validate_history_snapshot(document, user_id, version):
    """Validate one parsed snapshot and return the whitelisted summary."""
    if type(document) is not dict:
        raise InvalidProfileHistory("Invalid stored profile history")
    if _require_int(document.get("version"), "version") != version \
            or type(document.get("user_id")) is not str \
            or document["user_id"] != user_id:
        raise InvalidProfileHistory("Invalid stored profile history")
    if type(document.get("cold_start")) is not bool:
        raise InvalidProfileHistory("Invalid stored profile history")
    for field in ("rule_version", "timezone", "explanation"):
        if type(document.get(field)) is not str:
            raise InvalidProfileHistory("Invalid stored profile history")
    _require_int(document.get("generated_at"), "generated_at")
    _require_int(document.get("window_days"), "window_days")
    counts = document.get("event_counts")
    if type(counts) is not dict:
        raise InvalidProfileHistory("Invalid stored profile history")
    for name, count in counts.items():
        if type(name) is not str or type(count) is not int or count < 0:
            raise InvalidProfileHistory("Invalid stored profile history")
    summary = {"version": version}
    for field in SUMMARY_FIELDS:
        if field in ("topics", "media"):
            summary[field] = _require_summary_topics(document.get(field))
        else:
            summary[field] = document[field]
    return summary


def parse_history_page(user_id, count, rows):
    """Parse, validate and shape one history page.

    ``rows`` holds at most count+1 (version, document JSON text) rows in
    descending version order; a corrupt snapshot inside the page fails the
    whole page.
    """
    has_more = len(rows) > count
    items = []
    for row_version, text in rows:
        try:
            document = json.loads(text)
        except (ValueError, TypeError):
            raise InvalidProfileHistory("Invalid stored profile history") from None
        items.append(validate_history_snapshot(document, user_id, row_version))
    items = items[:count]
    return {"user_id": user_id,
            "items": items,
            "has_more": has_more,
            "next_cursor": str(items[-1]["version"]) if has_more else None}
