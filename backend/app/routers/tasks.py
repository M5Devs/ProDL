"""Task inspection plus real-time progress over WebSocket and SSE."""

from __future__ import annotations

import time

from fastapi import APIRouter, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse

from prodl_core.models import TERMINAL_STATUSES

from ..deps import TasksDep
from ..schemas import TaskSnapshot
from ..services import media as media_service
from ..tasks import Task, TaskManager

router = APIRouter(tags=["tasks"])

KEEPALIVE_SECONDS = 20.0


def _snapshot(task: Task) -> TaskSnapshot:
    return TaskSnapshot(**media_service.stats_for(task))


def _require(tasks: TaskManager, task_id: str) -> Task:
    task = tasks.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Unknown task_id")
    return task


@router.get("/tasks/{task_id}", response_model=TaskSnapshot, summary="Task snapshot")
async def task_snapshot(task_id: str, tasks: TasksDep) -> TaskSnapshot:
    return _snapshot(_require(tasks, task_id))


@router.post("/tasks/{task_id}/cancel", response_model=TaskSnapshot, summary="Cancel a task")
async def cancel_task(task_id: str, tasks: TasksDep) -> TaskSnapshot:
    task = _require(tasks, task_id)
    tasks.cancel(task_id)
    return _snapshot(task)


# ── WebSocket ───────────────────────────────────────────────────────────────
@router.websocket("/ws/progress/{task_id}")
async def progress_websocket(websocket: WebSocket, task_id: str) -> None:
    tasks: TaskManager = websocket.app.state.tasks
    await websocket.accept()

    task = tasks.get(task_id)
    if task is None:
        await websocket.send_json(
            {"type": "error", "task_id": task_id, "error": "Unknown task_id"}
        )
        await websocket.close(code=4404)
        return

    try:
        async with tasks.subscribe(task) as subscription:
            if subscription.snapshot is not None:
                await websocket.send_json(subscription.snapshot.model_dump(mode="json"))
            if subscription.done:
                await websocket.close(code=1000)
                return

            while True:
                event = await subscription.next(timeout=KEEPALIVE_SECONDS)
                if event is None:
                    await websocket.send_json({"type": "ping", "timestamp": time.time()})
                    continue
                await websocket.send_json(event.model_dump(mode="json"))
                if event.status in TERMINAL_STATUSES:
                    await websocket.close(code=1000)
                    return
    except WebSocketDisconnect:
        return
    except RuntimeError:  # socket already closed by the peer
        return


# ── Server-Sent Events ──────────────────────────────────────────────────────
@router.get("/progress/{task_id}", summary="Progress stream (SSE)")
async def progress_sse(task_id: str, request: Request, tasks: TasksDep) -> StreamingResponse:
    task = _require(tasks, task_id)

    async def event_source():
        async with tasks.subscribe(task) as subscription:
            if subscription.snapshot is not None:
                yield _sse(subscription.snapshot.model_dump_json())
            if subscription.done:
                return
            while True:
                if await request.is_disconnected():
                    return
                event = await subscription.next(timeout=KEEPALIVE_SECONDS)
                if event is None:
                    yield ": keep-alive\n\n"
                    continue
                yield _sse(event.model_dump_json())
                if event.status in TERMINAL_STATUSES:
                    return

    return StreamingResponse(
        event_source(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


def _sse(payload: str) -> str:
    return f"event: progress\ndata: {payload}\n\n"
