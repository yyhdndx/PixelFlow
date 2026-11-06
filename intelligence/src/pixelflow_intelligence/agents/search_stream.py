import hashlib
import json
import logging
import threading
import time
import uuid
from collections import OrderedDict

from ..clients.deepseek import DeepSeekAnswer, stream_chat
from ..skills.search_posts import rank_posts
from .search import NO_ANSWER, _fallback_answer

_LOG = logging.getLogger(__name__)


def digest(result):
    return hashlib.sha256(json.dumps(result, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode()).hexdigest()


def validate_sources(upstream, token, sources):
    for source in sources:
        post = upstream._get("/api/posts/" + source["post_id"], token)
        author = post.get("author") or {}
        if (post.get("status") != "PUBLISHED" or post.get("title") != source["title"]
                or not str(post.get("content") or "").startswith(source["excerpt"])
                or author.get("nickname") != source["author_name"]):
            raise ValueError("Source changed")


class PendingSearches:
    def __init__(self):
        self._lock = threading.RLock()
        self._items = OrderedDict()

    def reserve(self, request_id, user, deadline):
        self._expire()
        with self._lock:
            if request_id in self._items or len(self._items) >= 32:
                raise ValueError("Search capacity exceeded")
            item = {"user": user, "deadline": deadline,
                    "cancelled": threading.Event(), "result": None,
                    "committed": False, "confirming": False, "lock": threading.Lock()}
            self._items[request_id] = item
            timer = threading.Timer(max(0, deadline - time.monotonic()), self._timeout,
                                    args=(request_id, item))
            timer.daemon = True
            item["timer"] = timer
            timer.start()
            return item

    def _timeout(self, request_id, item):
        with self._lock:
            if self._items.get(request_id) is not item:
                return
            item["cancelled"].set()
            self._items.pop(request_id)
            trace = not item["confirming"] and not item["committed"]
        if trace and item.get("failure_trace"):
            item["failure_trace"]()

    def _expire(self):
        with self._lock:
            expired = [(key, item) for key, item in self._items.items()
                       if time.monotonic() >= item["deadline"]]
        for key, item in expired:
            self._timeout(key, item)

    def cancel(self, request_id, user):
        with self._lock:
            item = self._items.get(request_id)
            if item is None or item["user"] != user:
                return
            item["cancelled"].set()
            item["timer"].cancel()
            self._items.pop(request_id)
            trace = not item["confirming"] and not item["committed"]
        if trace and item.get("failure_trace"):
            item["failure_trace"]()

    def confirm(self, request_id, user, trace_id, expected_digest, upstream, token, store):
        with self._lock:
            item = self._items.get(request_id)
        if item is None or item["user"] != user:
            raise ValueError("Invalid search confirmation")

        def checkpoint():
            if item["cancelled"].is_set() or time.monotonic() >= item["deadline"]:
                raise TimeoutError()

        with item["lock"]:
            checkpoint()
            if (item["result"] is None or item["result"]["trace_id"] != trace_id
                    or digest(item["result"]) != expected_digest):
                raise ValueError("Invalid search confirmation")
            if item["committed"]:
                return {"request_id": request_id, "trace_id": trace_id, "committed": True}
            with self._lock:
                checkpoint()
                item["confirming"] = True
            try:
                validate_sources(upstream, token, item["result"]["sources"])
                checkpoint()
                if store is None:
                    raise ValueError("Search store unavailable")
                store.commit_search_stream(result=item["result"], user_id=user, question=item["question"],
                                           history=item["history"], now=item["created"], checkpoint=checkpoint)
                item["committed"] = True
                return {"request_id": request_id, "trace_id": trace_id, "committed": True}
            finally:
                with self._lock:
                    item["confirming"] = False
                if item["cancelled"].is_set() and not item["committed"] and item.get("failure_trace"):
                    item["failure_trace"]()


def search_events(*, question, session_id, user, token, upstream, settings, store,
                  request_id, item, model=None):
    seq = 0
    trace_id = str(uuid.uuid4())
    session_id = session_id or str(uuid.uuid4())
    item.update(question=question, created=int(time.time()), history=[])
    prepared = False
    trace_lock = threading.Lock()
    failure_recorded = False

    def failure_trace():
        nonlocal failure_recorded
        with trace_lock:
            if failure_recorded or store is None or item["committed"]:
                return
            try:
                store.save_agent_trace(trace_id=trace_id, user_id=user, question=question,
                    answer="搜索未完成", source_count=0, degraded=1,
                    steps=[{"step": "stream", "status": "incomplete"}],
                    created_at=item["created"], session_id=session_id,
                    degraded_reason="流式搜索取消、中断或失败")
                failure_recorded = True
            except Exception:
                _LOG.warning("agent_search_trace_unavailable request_id=%s", request_id)

    item["failure_trace"] = failure_trace

    def checkpoint():
        if item["cancelled"].is_set():
            raise InterruptedError()
        if time.monotonic() >= item["deadline"]:
            raise TimeoutError()

    def event(name, **fields):
        nonlocal seq
        checkpoint()
        seq += 1
        data = {"request_id": request_id, "seq": seq, **fields}
        return name, data

    try:
        yield event("meta", session_id=session_id, trace_id=trace_id, user_id=user)
        yield event("phase", phase="retrieving")
        posts = upstream.public_posts(token, pages=3)
        checkpoint()
        selected = rank_posts(question, posts, 5)
        sources = [{key: row[key] for key in ("post_id", "title", "excerpt", "author_name")}
                   for row in selected]
        validate_sources(upstream, token, sources)
        checkpoint()
        yield event("sources", sources=sources)
        if store is not None and session_id:
            old = store.load_agent_session(session_id, user)
            if old is not None:
                item["history"] = old["turns"][-3:]
        answer, degraded = "", 0
        if not sources:
            answer = NO_ANSWER
            yield event("delta", text=answer)
        elif not settings.deepseek_api_key and model is None:
            answer, degraded = _fallback_answer(selected), 1
            yield event("delta", text=answer)
        else:
            yield event("phase", phase="generating")
            payload = {"question": question, "history": item["history"],
                       "sources": [{"n": i + 1, **row} for i, row in enumerate(sources)]}
            system = DeepSeekAnswer.SYSTEM.replace('只输出 json 对象，格式：{"answer":"..."}。',
                                                   "直接输出回答正文，不输出JSON或代码围栏。")
            chunks = model() if model is not None else stream_chat(settings, system=system,
                user_content=json.dumps(payload, ensure_ascii=False), max_tokens=900,
                deadline=item["deadline"], cancelled=item["cancelled"])
            try:
                for text in chunks:
                    checkpoint()
                    answer += text
                    if len(answer) > 1500:
                        raise ValueError("Answer too long")
                    yield event("delta", text=text)
            finally:
                close = getattr(chunks, "close", None)
                if close is not None:
                    close()
        checkpoint()
        if not answer.strip():
            raise ValueError("Empty answer")
        import re
        refs = re.findall(r"〔([^〕]*)〕", answer)
        if (answer.count("〔") != len(refs) or answer.count("〕") != len(refs)
                or any(ref not in {str(i) for i in range(1, len(sources) + 1)} for ref in refs)
                or (sources and not refs)):
            raise ValueError("Invalid citations")
        yield event("phase", phase="validating")
        validate_sources(upstream, token, sources)
        result = {"answer": answer, "sources": sources, "session_id": session_id,
                  "trace_id": trace_id, "degraded": degraded}
        checkpoint()
        item["result"] = result
        prepared_event = event("prepared", **result)
        prepared = True
        yield prepared_event
    except GeneratorExit:
        raise
    except Exception:
        seq += 1
        yield "error", {"request_id": request_id, "seq": seq, "code": "AGENT_SEARCH_INCOMPLETE",
                         "message": "AI 搜索未完成，请重试", "incomplete": True}
    finally:
        if not prepared:
            item["cancelled"].set()
            failure_trace()
