import math
from ..analytics.profile import TOPICS


def topics_for(candidate):
    return [topic for topic, words in TOPICS.items() if any(word in candidate.title for word in words)] or ["其他"]


def allowed(candidate, excluded, now):
    return (candidate.status == "PUBLISHED" and candidate.author_enabled
            and candidate.post_id not in excluded and candidate.create_time <= now + 300)


def matches(candidate, insight):
    return (any(p.share > 0 and p.name in topics_for(candidate) for p in insight.topics)
            or any(p.share > 0 and p.name == candidate.author_id for p in insight.authors))


def components(candidate, insight, now):
    topic_names = topics_for(candidate)
    personalized = insight.strategy == "personalized"
    return {
        "topic": sum(p.share for p in insight.topics if p.name in topic_names)/len(topic_names) if personalized else 0.0,
        "author": sum(p.share for p in insight.authors if p.name == candidate.author_id) if personalized else 0.0,
        "media": sum(p.share for p in insight.media if p.name == candidate.media_type) if personalized else 0.0,
        "popularity": min(1.0, math.log1p(candidate.like_count)/math.log1p(1000)),
        "freshness": 1.0/(1.0+max(0, now-candidate.create_time)/86400),
    }


def unique_safe(candidates, excluded, now, context):
    # Conflicting snapshots for one ID are rejected entirely, never first/last-wins.
    by_id, conflicts = {}, set()
    for candidate in candidates:
        context.checkpoint()
        old = by_id.get(candidate.post_id)
        if old is not None and old != candidate:
            conflicts.add(candidate.post_id)
        by_id[candidate.post_id] = candidate
    return [candidate for key, candidate in by_id.items()
            if key not in conflicts and allowed(candidate, excluded, now)]
