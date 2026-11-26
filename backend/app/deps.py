"""Shared FastAPI dependencies and URL helpers."""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request

from .config import Settings
from .schemas import TaskAccepted
from .tasks import Task, TaskManager


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_tasks(request: Request) -> TaskManager:
    return request.app.state.tasks


SettingsDep = Annotated[Settings, Depends(get_settings)]
TasksDep = Annotated[TaskManager, Depends(get_tasks)]


def accepted(task: Task, settings: Settings) -> TaskAccepted:
    prefix = settings.api_prefix
    return TaskAccepted(
        task_id=task.id,
        kind=task.kind,
        status=task.status,
        created_at=task.created_at,
        websocket_url=f"{prefix}/ws/progress/{task.id}",
        sse_url=f"{prefix}/progress/{task.id}",
        task_url=f"{prefix}/tasks/{task.id}",
        file_url=f"{prefix}/files/{task.id}",
    )
