import random

from ..models import RecallOutput, Recalled
from ..common import unique_safe, matches, topics_for, components


def execute(data, context):
    safe = unique_safe(data.candidates, set(data.excluded_post_ids), context.now, context)
    popular = sorted(safe, key=lambda c: (-c.like_count, -c.create_time, int(c.post_id)))
    interest = []
    if data.insight.strategy == "personalized":
        interest = [c for c in safe if matches(c, data.insight)]
        interest.sort(key=lambda c: (-(components(c, data.insight, context.now)["topic"]
                                      + components(c, data.insight, context.now)["author"]),
                                     -c.create_time, int(c.post_id)))
    # Reserve half the budget for interest; fill remaining slots with popular/exploration.
    # 随机抽样兴趣子集 + 打乱热门，避免每次刷新召回完全相同的作品（探索性）
    half = (data.limit + 1) // 2
    chosen = random.sample(interest, min(half, len(interest))) if interest else []
    random.shuffle(popular)
    seen = {c.post_id for c in chosen}
    for candidate in popular:
        context.checkpoint()
        if len(chosen) >= data.limit:
            break
        if candidate.post_id not in seen:
            chosen.append(candidate)
            seen.add(candidate.post_id)
    interested = {c.post_id for c in interest}
    return RecallOutput(user_id=data.user_id, profile_version=data.insight.profile_version,
                        strategy=data.insight.strategy, inspected=len(data.candidates),
                        rejected=len(data.candidates)-len(safe),
                        items=[Recalled(candidate=c, topics=topics_for(c),
                                        channels=["interest", "popular"] if c.post_id in interested else ["popular"])
                               for c in chosen])
