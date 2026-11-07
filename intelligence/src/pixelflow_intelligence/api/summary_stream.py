import asyncio
import json
import queue
import threading
import time

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ..agents.summary_stream import summary_events
from ..clients.java import AuthenticationError, UpstreamError


def router(settings, upstream, store):
    routes = APIRouter()
    slots = threading.BoundedSemaphore(4)
    bearer = HTTPBearer(auto_error=False)

    @routes.get("/agent/posts/{post_id}/summary/stream")
    def stream(post_id: int, request: Request, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if credentials is None:
            raise HTTPException(401, "Authentication required")
        try:
            budget = int(request.headers.get("X-Remaining-Budget-Ms", "45000"))
            if not 1 <= budget <= 55000:
                raise ValueError()
        except ValueError:
            raise HTTPException(400, "Invalid budget") from None
        deadline = time.monotonic() + min(45, budget / 1000)
        try:
            user = upstream.me(credentials.credentials)
            post = upstream._get("/api/posts/" + str(post_id), credentials.credentials)
        except AuthenticationError:
            raise HTTPException(401, "Invalid session") from None
        except UpstreamError:
            raise HTTPException(503, "Source unavailable") from None
        if post.get("status") != "PUBLISHED":
            raise HTTPException(404, "Post unavailable")
        if len(str(post.get("content") or "").strip()) < 200:
            raise HTTPException(400, "Short text")
        if time.monotonic() >= deadline:
            raise HTTPException(503, "Summary deadline exceeded")
        if not settings.deepseek_api_key or not slots.acquire(blocking=False):
            raise HTTPException(503, "Summary unavailable")
        cancelled = threading.Event()
        frames = queue.Queue(maxsize=8)

        def put(frame):
            while not cancelled.is_set() and time.monotonic() < deadline:
                try:
                    frames.put(frame, timeout=0.1)
                    return True
                except queue.Full:
                    pass
            return False

        def produce():
            events = summary_events(post_id=str(post_id), post=post, token=credentials.credentials,
                upstream=upstream, settings=settings, store=store, user=user, cancelled=cancelled, deadline=deadline)
            try:
                for name, data in events:
                    frame = ("event: " + name + "\ndata: " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n\n").encode()
                    if not put(frame):
                        return
            finally:
                try:
                    events.close()
                finally:
                    put(None)
                    slots.release()

        async def content():
            threading.Thread(target=produce, name="summary-stream", daemon=True).start()
            heartbeat = time.monotonic() + 5
            try:
                while time.monotonic() < deadline:
                    if await request.is_disconnected():
                        break
                    try:
                        frame = frames.get_nowait()
                    except queue.Empty:
                        if time.monotonic() >= heartbeat:
                            yield b": heartbeat\n\n"
                            heartbeat = time.monotonic() + 5
                        await asyncio.sleep(0.05)
                        continue
                    if frame is None:
                        break
                    yield frame
            finally:
                cancelled.set()

        return StreamingResponse(content(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})

    return routes
