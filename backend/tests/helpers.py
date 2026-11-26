"""Small helpers shared by backend tests."""

from __future__ import annotations

import time

from fastapi.testclient import TestClient

from prodl_core.models import TERMINAL_STATUSES


def wait_for_task(client: TestClient, task_id: str, timeout: float = 30.0) -> dict:
    """Poll the snapshot endpoint until the task reaches a terminal status."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get(f"/api/tasks/{task_id}")
        assert response.status_code == 200
        snapshot = response.json()
        if snapshot["status"] in TERMINAL_STATUSES:
            return snapshot
        time.sleep(0.05)
    raise AssertionError(f"task {task_id} did not finish within {timeout}s")
