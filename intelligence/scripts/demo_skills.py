"""Offline fixture demo; not an Agent or a live recommendation endpoint."""
import json
import time
from pixelflow_intelligence.analytics.profile import build_profile
from pixelflow_intelligence.skills import Context, create_registry


def run(topic):
    now = 1791000000
    user = "demo-user"
    registry = create_registry()
    context = Context(user, frozenset({"profile:read", "candidates:read"}),
                      now, time.monotonic() + 5)
    snapshot = build_profile(user, [], 1, now)
    if topic:
        snapshot.update(cold_start=False,
                        topics=[{"name": topic, "score": 3.0, "share": 1.0}])
    pool = [dict(post_id=str(i), author_id=f"author-{i}", title=title,
                 media_type="image", like_count=likes, create_time=now,
                 status="PUBLISHED", author_enabled=True)
            for i, title, likes in [(1, "手冲咖啡", 10), (2, "机械键盘", 50), (3, "旅行日落", 100)]]
    first = registry.invoke("profile_insight", {"user_id": user, "snapshot": snapshot}, context)
    second = registry.invoke("candidate_recall", {
        "user_id": user, "insight": first["output"], "candidates": pool,
        "excluded_post_ids": ["3"]}, context)
    third = registry.invoke("feed_rank", {
        "user_id": user, "insight": first["output"], "recalled": second["output"]["items"],
        "excluded_post_ids": ["3"]}, context)
    return {"scenario": topic or "冷启动", "steps": [first, second, third]}


if __name__ == "__main__":
    print(json.dumps([run("美食"), run("数码"), run(None)], ensure_ascii=False, indent=2))
