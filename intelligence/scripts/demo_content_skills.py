"""Offline demo for the two creator-side skills (content_safety / creator_assist).

Not an Agent and not a live endpoint: it only shows the deterministic contract.
"""
import json
import time

from pixelflow_intelligence.skills import Context, create_registry

NOW = 1791000000


def context(user="demo-creator"):
    return Context(user_id=user, scopes=frozenset({"content:check", "content:assist"}),
                   now=NOW, deadline=time.monotonic() + 5)


def main():
    registry = create_registry()
    user = "demo-creator"
    safety_cases = [
        {"title": "冬天第一杯手冲咖啡", "content": "记录今天的水温与粉水比，欢迎交流做法。",
         "tags": ["美食"]},
        {"title": "出闲置相机", "content": "九成新，有意加微信 13800138000 详聊，支持私下转账。",
         "tags": ["数码"]},
        {"title": "测试", "content": "短", "tags": []},
    ]
    safety = [registry.invoke("content_safety", {"user_id": user, **case}, context())
              for case in safety_cases]
    assist = registry.invoke("creator_assist", {
        "user_id": user, "title": "", "media_count": 0,
        "content": "周末煮了一杯手冲咖啡，配上面包和草莓，记录一下。"}, context())
    print(json.dumps({"safety": safety, "assist": assist}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
