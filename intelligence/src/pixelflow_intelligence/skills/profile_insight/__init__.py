from ..models import Insight, Preference


def execute(data, context):
    snapshot = data.snapshot
    reason = "available"
    if snapshot is None:
        reason = "missing"
    elif snapshot.generated_at > context.now + 300:
        reason = "future_snapshot"
    elif context.now - snapshot.generated_at > data.max_age_seconds:
        reason = "stale"
    elif snapshot.cold_start or snapshot.version == 0:
        reason = "cold_start"
    elif not any(p.score > 0 for values in (snapshot.topics, snapshot.authors, snapshot.media) for p in values):
        reason = "empty_preferences"
    def normalize(values):
        scores = {}
        for item in values:
            scores[item.name] = scores.get(item.name, 0.0) + item.score
        # Scaling avoids overflow when valid but very large input scores are supplied.
        maximum = max(scores.values(), default=0)
        if not maximum:
            return []
        total = sum(value / maximum for value in scores.values())
        return [Preference(name=name, score=value/maximum, share=(value/maximum)/total)
                for name, value in sorted(scores.items(), key=lambda p: (-p[1], p[0])) if value > 0]
    ready = reason == "available"
    return Insight(user_id=data.user_id, profile_version=snapshot.version if snapshot else 0,
                   strategy="personalized" if ready else "popular", reason=reason,
                   topics=normalize(snapshot.topics) if ready else [],
                   authors=normalize(snapshot.authors) if ready else [],
                   media=normalize(snapshot.media) if ready else [])
