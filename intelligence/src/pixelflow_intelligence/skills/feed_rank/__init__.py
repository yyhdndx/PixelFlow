from collections import Counter
from ..models import RankOutput, Ranked
from ..common import unique_safe, topics_for, components


def execute(data, context):
    safe = unique_safe([item.candidate for item in data.recalled],
                       set(data.excluded_post_ids), context.now, context)
    ranked = []
    for candidate in safe:
        context.checkpoint()
        values = components(candidate, data.insight, context.now)
        score = (4*values["topic"] + 2*values["author"] + values["media"]
                 + values["popularity"] + values["freshness"])
        # Derive explanations from computed features, never from candidate-supplied instructions.
        reason = "近期公开内容与热度"
        if values["topic"] > 0:
            reason = "与你的主题偏好相关：" + "、".join(topics_for(candidate))
        elif values["author"] > 0:
            reason = "与你的作者偏好相关"
        ranked.append((candidate, values, score, reason))
    ranked.sort(key=lambda item: (-item[2], -item[0].create_time, int(item[0].post_id)))
    authors, topics = Counter(), Counter()
    result, limited = [], 0
    for candidate, values, score, reason in ranked:
        context.checkpoint()
        if len(result) >= data.limit:
            break
        names = topics_for(candidate)
        if authors[candidate.author_id] >= data.max_per_author or any(topics[t] >= data.max_per_topic for t in names):
            limited += 1
            continue
        authors[candidate.author_id] += 1
        topics.update(names)
        result.append(Ranked(post_id=candidate.post_id, author_id=candidate.author_id, topics=names,
                             score=round(score, 6), components={k: round(v, 6) for k, v in values.items()},
                             reason=reason))
    return RankOutput(user_id=data.user_id, profile_version=data.insight.profile_version,
                      strategy=data.insight.strategy, policy_version="feed-rank-v1",
                      items=result, filtered=len(data.recalled)-len(safe), truncated_by_diversity=limited)
