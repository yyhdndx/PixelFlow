import json
import time
import uuid

from ..clients.deepseek import stream_chat
from .post_summary import MIN_CONTENT_LENGTH, SYSTEM


def summary_events(*, post_id, post, token, upstream, settings, store, user, cancelled,
                   deadline, model=None):
    trace_id = str(uuid.uuid4())
    sequence = 0
    summary, points = "", []
    completed = False
    created = int(time.time())

    def check():
        if cancelled.is_set() or time.monotonic() >= deadline:
            raise TimeoutError()
        current = upstream._get("/api/posts/" + post_id, token)
        if (current.get("status") != "PUBLISHED" or current.get("title") != post["title"]
                or current.get("content") != post["content"]):
            raise ValueError("Source changed")

    def event(name, **fields):
        nonlocal sequence
        sequence += 1
        return name, {"seq": sequence, **fields}

    try:
        if len(post["content"].strip()) < MIN_CONTENT_LENGTH:
            raise ValueError("Short text")
        check()
        system = SYSTEM + '\n本次输出使用逐行JSON协议替代上述对象：第一行仅含summary键，后续每行仅含point键。概括最多40字，每条要点最多60字，3至5条。每个对象独占一行，不加代码围栏。'
        chunks = model() if model else stream_chat(settings, system=system,
            user_content=json.dumps({"title": post["title"], "content": post["content"]}, ensure_ascii=False),
            max_tokens=700, deadline=deadline, cancelled=cancelled)
        pending = ""
        try:
            for chunk in chunks:
                if cancelled.is_set() or time.monotonic() >= deadline:
                    raise TimeoutError()
                pending += chunk
                if len(pending) > 2048:
                    raise ValueError("Oversized block")
                while "\n" in pending:
                    line, pending = pending.split("\n", 1)
                    if not line.strip():
                        continue
                    block = json.loads(line)
                    check()
                    if not summary:
                        if set(block) != {"summary"} or not isinstance(block["summary"], str) or not 1 <= len(block["summary"].strip()) <= 60:
                            raise ValueError("Invalid summary")
                        summary = block["summary"].strip()
                        yield event("summary", summary=summary)
                    else:
                        if set(block) != {"point"} or not isinstance(block["point"], str) or not 1 <= len(block["point"].strip()) <= 80 or len(points) >= 5:
                            raise ValueError("Invalid point")
                        points.append(block["point"].strip())
                        yield event("point", point=points[-1])
            if pending.strip():
                block = json.loads(pending)
                if not summary or set(block) != {"point"} or not isinstance(block["point"], str) or not 1 <= len(block["point"].strip()) <= 80 or len(points) >= 5:
                    raise ValueError("Invalid final point")
                check()
                points.append(block["point"].strip())
                yield event("point", point=points[-1])
            if not summary or not 3 <= len(points) <= 5:
                raise ValueError("Incomplete summary")
            check()
            completed = True
            yield event("done", summary=summary, points=points, content_length=len(post["content"]),
                        trace_id=trace_id, validated=True)
        finally:
            close = getattr(chunks, "close", None)
            if close is not None:
                close()
    except GeneratorExit:
        raise
    except Exception:
        yield event("error", code="SUMMARY_INCOMPLETE", message="提炼未完成，可以先读原文", incomplete=True)
    finally:
        if store is not None:
            store.save_agent_trace(trace_id=trace_id, user_id=user, question=("长文提炼 " + post_id)[:200],
                answer=(summary + "；" + "；".join(points))[:1500] if completed else "提炼未完成",
                source_count=1, degraded=0 if completed else 1,
                steps=[{"step": "summary_stream", "status": "completed" if completed else "incomplete"}],
                created_at=created, provider="deepseek")
