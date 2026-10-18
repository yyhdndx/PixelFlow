"""Bounded, deterministic Skill composition; intentionally not an Agent."""
import time
from ..clients.java import UpstreamError
from ..skills import Context, create_registry


def preview(user, snapshot, upstream, token, read_preferences):
    registry = create_registry()
    context = Context(user, frozenset({"profile:read", "candidates:read"}),
                      int(time.time()), time.monotonic() + 15)
    preferences = read_preferences(user)
    if preferences.get("user_id") != user:
        raise UpstreamError("Preference owner mismatch")
    pool = upstream.public_candidates(token)
    blocked_authors = set(preferences["blocked_author_ids"])
    excluded_posts = set(preferences["excluded_post_ids"])
    # Limit the union to this candidate pool; a full 1000-item exclusion list must
    # not overflow the Skill contract when author blocks add more candidates.
    exclusions = sorted({row["post_id"] for row in pool
                         if row["post_id"] in excluded_posts or row["author_id"] in blocked_authors})
    if not preferences["personalized"]:
        snapshot = None
    elif snapshot is not None:
        # Suppression removes topic personalization, not all works in that topic.
        # Copy before filtering: stored/raw profile history must remain unchanged.
        suppressed = set(preferences.get("suppressed_topics", []))
        snapshot = {**snapshot, "topics": [topic for topic in snapshot["topics"]
                                          if topic["name"] not in suppressed]}
    first = registry.invoke("profile_insight", {"user_id": user, "snapshot": snapshot}, context)
    second = registry.invoke("candidate_recall", {
        "user_id": user, "insight": first["output"], "candidates": pool,
        "excluded_post_ids": exclusions}, context)
    third = registry.invoke("feed_rank", {
        "user_id": user, "insight": first["output"], "recalled": second["output"]["items"],
        "excluded_post_ids": exclusions}, context)
    # Re-read Java's public feed rather than detail: owners/admins can see private details.
    current = {row["post_id"]: row for row in upstream.public_candidates(token)}
    latest_preferences = read_preferences(user)
    if latest_preferences.get("user_id") != user:
        raise UpstreamError("Preference owner mismatch")
    preferences_changed = latest_preferences["version"] != preferences["version"]
    context.checkpoint()
    original = {row["post_id"]: row for row in pool}
    ranked = third["output"]["items"]
    # Changed/removed snapshots are dropped, not filled with unchecked alternatives.
    items = [row for row in ranked if not preferences_changed
             and current.get(row["post_id"]) == original[row["post_id"]]]
    return {
        "user_id": user, "trace_id": context.trace_id, "mode": "preview",
        "strategy": first["output"]["strategy"],
        "reason": first["output"]["reason"] if preferences["personalized"] else "personalization_disabled",
        "preferences_version": preferences["version"], "preferences_changed": preferences_changed,
        "profile_version": first["output"]["profile_version"],
        "policy_version": third["output"]["policy_version"],
        "candidate_count": len(pool), "removed_on_recheck": len(ranked) - len(items),
        "items": items,
        "steps": [{key: step[key] for key in ("skill", "skill_version", "trace_id", "elapsed_ms")}
                  for step in (first, second, third)],
        "limitations": ["latest_50_public_posts", "recommendation_only_blocking", "snapshot_visibility"],
    }
