"""In-memory task registry with fan-out progress streaming.

yt-dlp and ffmpeg run in a small thread pool; their progress callbacks fire on
worker threads, so publishing hops back onto the event loop with
``call_soon_threadsafe`` before touching any asyncio object.
"""

from __future__ import annotations

import asyncio
import threading
import time
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any, Callable

from prodl_core.models import (
    TERMINAL_STATUSES,
    FileRef,
    ProgressEvent,
    TaskStatus,
)

MAX_EVENTS_PER_TASK = 200


@dataclass
class Task:
    id: str
    kind: str
    status: TaskStatus = "queued"
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    meta: dict[str, Any] = field(default_factory=dict)
    error: str | None = None
    file: FileRef | None = None
    last_event: ProgressEvent | None = None
    events: deque[ProgressEvent] = field(
        default_factory=lambda: deque(maxlen=MAX_EVENTS_PER_TASK)
    )
    seq: int = 0
    cancel_event: threading.Event = field(default_factory=threading.Event)
    subscribers: set[asyncio.Queue] = field(default_factory=set)
    loop: asyncio.AbstractEventLoop | None = None
    loop_thread: int | None = None

    @property
    def done(self) -> bool:
        return self.status in TERMINAL_STATUSES


class Subscription:
    """Async iterator over a task's progress events."""

    def __init__(self, task: Task):
        self.task = task
        self.queue: asyncio.Queue[ProgressEvent] = asyncio.Queue(maxsize=256)
        self._skip_through = task.seq

    async def __aenter__(self) -> "Subscription":
        self.task.subscribers.add(self.queue)
        return self

    async def __aexit__(self, *exc_info) -> None:
        self.task.subscribers.discard(self.queue)

    @property
    def snapshot(self) -> ProgressEvent | None:
        return self.task.last_event

    @property
    def done(self) -> bool:
        return self.task.done

    async def next(self, timeout: float | None = None) -> ProgressEvent | None:
        """Return the next event, ``None`` on timeout."""
        try:
            if timeout is None:
                event = await self.queue.get()
            else:
                event = await asyncio.wait_for(self.queue.get(), timeout)
        except (asyncio.TimeoutError, TimeoutError):
            return None
        if event.seq is not None and event.seq <= self._skip_through:
            return await self.next(timeout)
        return event


class TaskManager:
    def __init__(
        self,
        *,
        max_tasks: int = 200,
        ttl_seconds: int = 3600,
        max_workers: int = 4,
    ) -> None:
        self.max_tasks = max_tasks
        self.ttl_seconds = ttl_seconds
        self._tasks: dict[str, Task] = {}
        self._executor = ThreadPoolExecutor(
            max_workers=max_workers, thread_name_prefix="prodl-worker"
        )

    # ── lifecycle ───────────────────────────────────────────────────────────
    def create(self, kind: str, *, meta: dict[str, Any] | None = None) -> Task:
        self.prune()
        task = Task(id=uuid.uuid4().hex, kind=kind, meta=meta or {})
        try:
            task.loop = asyncio.get_running_loop()
            task.loop_thread = threading.get_ident()
        except RuntimeError:  # pragma: no cover - created outside a loop
            task.loop = None
        self._tasks[task.id] = task
        self._trim()
        return task

    def get(self, task_id: str) -> Task | None:
        return self._tasks.get(task_id)

    def require(self, task_id: str) -> Task:
        task = self._tasks.get(task_id)
        if task is None:
            raise KeyError(task_id)
        return task

    def delete(self, task_id: str) -> Task | None:
        return self._tasks.pop(task_id, None)

    def all(self) -> list[Task]:
        return list(self._tasks.values())

    def _trim(self) -> None:
        if len(self._tasks) <= self.max_tasks:
            return
        ordered = sorted(self._tasks.values(), key=lambda t: t.created_at)
        for task in ordered[: len(self._tasks) - self.max_tasks]:
            if task.done:
                self._tasks.pop(task.id, None)

    def prune(self) -> None:
        cutoff = time.time() - self.ttl_seconds
        for task_id, task in list(self._tasks.items()):
            if task.done and task.updated_at < cutoff:
                self._tasks.pop(task_id, None)

    def submit(self, fn: Callable[..., Any], *args: Any) -> None:
        self._executor.submit(fn, *args)

    def shutdown(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)

    # ── publishing ──────────────────────────────────────────────────────────
    def publish(self, task_id: str, event: ProgressEvent) -> None:
        """Thread-safe publish; callable from yt-dlp/ffmpeg worker threads."""
        task = self._tasks.get(task_id)
        if task is None:
            return
        event.task_id = task_id
        if task.loop is not None and threading.get_ident() != task.loop_thread:
            task.loop.call_soon_threadsafe(self._publish_now, task, event)
        else:
            self._publish_now(task, event)

    def _publish_now(self, task: Task, event: ProgressEvent) -> None:
        task.seq += 1
        event.seq = task.seq
        event.timestamp = event.timestamp or time.time()
        task.last_event = event
        task.updated_at = time.time()
        if event.status in TERMINAL_STATUSES or event.status in {
            "downloading",
            "postprocessing",
            "converting",
        }:
            task.status = event.status
        if event.error:
            task.error = event.error
        if event.file:
            task.file = event.file
        task.events.append(event)
        for queue in list(task.subscribers):
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                pass

    def finish(
        self,
        task_id: str,
        *,
        file: FileRef | None = None,
        message: str = "Completed",
        status: TaskStatus = "finished",
    ) -> None:
        self.publish(
            task_id,
            ProgressEvent(
                status=status,
                percent=100.0 if status == "finished" else None,
                message=message,
                file=file,
                timestamp=time.time(),
            ),
        )

    def fail(self, task_id: str, error: str) -> None:
        self.publish(
            task_id,
            ProgressEvent(status="error", error=error, message=error, timestamp=time.time()),
        )

    def mark_canceled(self, task_id: str) -> None:
        self.publish(
            task_id,
            ProgressEvent(
                status="canceled", message="Canceled by client", timestamp=time.time()
            ),
        )

    def cancel(self, task_id: str) -> bool:
        task = self._tasks.get(task_id)
        if task is None or task.done:
            return False
        task.cancel_event.set()
        return True

    # ── streaming ───────────────────────────────────────────────────────────
    def subscribe(self, task: Task) -> Subscription:
        return Subscription(task)
