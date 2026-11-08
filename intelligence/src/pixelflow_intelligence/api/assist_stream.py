import asyncio
import json
import queue
import threading
import time

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

from ..agents.assist_stream import assist_events
from ..clients.java import AuthenticationError, UpstreamError


class AssistDraft(BaseModel):
    title: str = ""
    content: str = ""
    tags: list[str] = Field(default_factory=list)


class CreateAssistStreamRequest(BaseModel):
    draft: AssistDraft
    goal: str
    prompt: str = ""
    audience: str = ""
    previous_result: AssistDraft | None = None


def router(settings, upstream, store):
    routes = APIRouter()
    slots = threading.BoundedSemaphore(2)
    bearer = HTTPBearer(auto_error=False)

    @routes.post("/agent/create-assist/stream")
    def stream(body: CreateAssistStreamRequest, request: Request,
               credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
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
        except AuthenticationError:
            raise HTTPException(401, "Invalid session") from None
        except UpstreamError:
            raise HTTPException(503, "Identity unavailable") from None
        if body.goal not in ("improve_title", "suggest_tags", "polish_content", "write_content"):
            raise HTTPException(422, "Streaming supports a single-field goal")
        if not (body.draft.title.strip() or body.draft.content.strip() or body.prompt.strip()):
            raise HTTPException(422, "Draft or writing instructions required")
        if body.goal == "polish_content" and not body.draft.content.strip():
            raise HTTPException(422, "Polishing requires draft content")
        if body.previous_result is not None and not body.prompt.strip():
            raise HTTPException(422, "Revision instructions required")
        if not settings.deepseek_api_key or not slots.acquire(blocking=False):
            raise HTTPException(503, "Creation assist unavailable")

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
            events = assist_events(
                draft=body.draft.model_dump(), goal=body.goal, prompt=body.prompt,
                audience=body.audience,
                previous=body.previous_result.model_dump() if body.previous_result is not None else None,
                token=credentials.credentials, upstream=upstream, settings=settings, user=user,
                cancelled=cancelled, deadline=deadline)
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
            threading.Thread(target=produce, name="assist-stream", daemon=True).start()
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
