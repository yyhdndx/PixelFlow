import asyncio
import json
import queue
import threading
import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field

from ..agents.search_stream import PendingSearches, search_events
from ..clients.java import AuthenticationError, UpstreamError


class StreamRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    question: str = Field(min_length=1, max_length=200)
    session_id: str | None = Field(default=None, max_length=36)


class Confirmation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    request_id: str = Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    trace_id: str = Field(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    digest: str = Field(pattern="^[a-f0-9]{64}$")


def router(settings, upstream, store):
    routes = APIRouter()
    pending = PendingSearches()
    bearer = HTTPBearer(auto_error=False)

    def identity(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        try:
            started = time.monotonic()
            return upstream.me(credentials.credentials), credentials.credentials, started
        except AuthenticationError:
            raise HTTPException(401, "Invalid user session") from None
        except UpstreamError:
            raise HTTPException(503, "Identity service unavailable") from None

    @routes.post("/agent/search/stream")
    def stream(body: StreamRequest, request: Request, actor=Depends(identity)):
        if not body.question.strip():
            raise HTTPException(400, "Question must not be blank")
        if body.session_id:
            try:
                uuid.UUID(body.session_id)
            except ValueError:
                raise HTTPException(400, "Invalid session") from None
        try:
            request_id = str(uuid.UUID(request.headers.get("X-Request-Id", "")))
            budget = int(request.headers.get("X-Remaining-Budget-Ms", "45000"))
            if not 1 <= budget <= 55000:
                raise ValueError()
            deadline = actor[2] + min(45, budget / 1000)
            if time.monotonic() >= deadline:
                raise ValueError()
            item = pending.reserve(request_id, actor[0], deadline)
        except ValueError:
            raise HTTPException(503, "Search request unavailable") from None

        frames = queue.Queue(maxsize=8)

        def put(value):
            while not item["cancelled"].is_set() and time.monotonic() < item["deadline"]:
                try:
                    frames.put(value, timeout=0.1)
                    return True
                except queue.Full:
                    continue
            return False

        def produce():
            total = 0
            events = search_events(question=body.question, session_id=body.session_id,
                user=actor[0], token=actor[1], upstream=upstream, settings=settings,
                store=store, request_id=request_id, item=item)
            try:
                for name, data in events:
                    frame = ("event: " + name + "\ndata: " + json.dumps(data, ensure_ascii=False,
                             separators=(",", ":")) + "\n\n").encode()
                    total += len(frame)
                    if len(frame) > 16384 or total > 262144 or not put(frame):
                        return
            finally:
                events.close()
                put(None)

        async def content():
            prepared = False
            next_heartbeat = time.monotonic() + 5
            threading.Thread(target=produce, name="search-stream", daemon=True).start()
            try:
                while time.monotonic() < item["deadline"] and not item["cancelled"].is_set():
                    if await request.is_disconnected():
                        break
                    try:
                        frame = frames.get_nowait()
                    except queue.Empty:
                        if time.monotonic() >= next_heartbeat:
                            yield b": heartbeat\n\n"
                            next_heartbeat = time.monotonic() + 5
                        await asyncio.sleep(0.05)
                        continue
                    if frame is None:
                        break
                    if frame.startswith(b"event: prepared\n"):
                        prepared = True
                    yield frame
            finally:
                if not prepared:
                    pending.cancel(request_id, actor[0])

        return StreamingResponse(content(), media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})

    @routes.post("/agent/search/stream/confirm")
    def confirm(body: Confirmation, actor=Depends(identity)):
        try:
            return pending.confirm(str(body.request_id), actor[0], str(body.trace_id),
                                   body.digest, upstream, actor[1], store)
        except Exception:
            raise HTTPException(503, "Search confirmation unavailable") from None

    @routes.delete("/agent/search/stream/{request_id}")
    def cancel(request_id: uuid.UUID, actor=Depends(identity)):
        pending.cancel(str(request_id), actor[0])
        return {"cancelled": True}

    return routes
