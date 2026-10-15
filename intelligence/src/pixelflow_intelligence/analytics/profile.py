from collections import defaultdict
from datetime import datetime, timezone

RULE_VERSION = "profile-rules-v1"
TOPICS = {
    "美食": ("咖啡", "面包", "早餐", "草莓", "美食", "烘焙"),
    "宠物": ("猫", "狗", "宠物"),
    "旅行": ("山海", "旅行", "风景", "瀑布", "日落"),
    "阅读": ("图书馆", "阅读", "书籍"),
    "数码": ("键盘", "编程", "电脑", "数码"),
    "运动": ("篮球", "骑行", "运动"),
    "园艺": ("多肉", "植物", "花丛", "园艺"),
}


def build_profile(user_id, events, version, now):
    """Deterministic, explainable signals; no demographic or sensitive inference."""
    recent = [e for e in events if now - 30 * 86400 <= e["occurred_at"] <= now + 300]
    click_days, dwell_days, likes = {}, {}, {}
    counts, hours = defaultdict(int), defaultdict(int)
    for event in recent:
        kind = event["event_type"]
        counts[kind] += 1
        hour = datetime.fromtimestamp(event["occurred_at"], timezone.utc).hour
        hours[str(hour)] += 1
        key = (event["post_id"], event["occurred_at"] // 86400)
        if kind == "CLICK":
            click_days[key] = event
        if kind == "DWELL" and event.get("duration_ms"):
            previous = dwell_days.get(key, (event, 0))[1]
            dwell_days[key] = (event, min(120000, previous + event["duration_ms"]))
        if kind in {"LIKE", "UNLIKE"} and event["source"] == "SERVER":
            likes[event["post_id"]] = event
    weighted = [(e, 1.0) for e in click_days.values()]
    weighted += [(e, duration / 30000) for e, duration in dwell_days.values()]
    weighted += [(e, 3.0) for e in likes.values() if e["event_type"] == "LIKE"]
    scores = {name: defaultdict(float) for name in ("topics", "authors", "media")}
    for event, weight in weighted:
        if not event["eligible"]:
            continue
        topics = [topic for topic, words in TOPICS.items() if any(word in event["title"] for word in words)]
        for topic in topics or ["其他"]:
            scores["topics"][topic] += weight / max(1, len(topics))
        scores["authors"][event["author_id"]] += weight
        scores["media"]["video" if event["has_video"] else "image"] += weight
    def summarize(values):
        total = sum(values.values())
        return [{"name": name, "score": round(score, 4), "share": round(score / total, 4)}
                for name, score in sorted(values.items(), key=lambda pair: (-pair[1], pair[0]))[:10]]
    return {
        "user_id": user_id, "version": version, "rule_version": RULE_VERSION,
        "generated_at": now, "window_days": 30, "timezone": "UTC",
        "cold_start": not bool(scores["authors"]),
        "topics": summarize(scores["topics"]), "authors": summarize(scores["authors"]),
        "media": summarize(scores["media"]), "event_counts": dict(counts),
        "active_hours": dict(sorted(hours.items(), key=lambda pair: int(pair[0]))),
        "explanation": "点击每作品每天最多计一次；停留每作品每天最多120秒；服务端有效点赞加3分，取消后移除。曝光不增加兴趣分。",
    }
