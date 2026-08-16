from __future__ import annotations

import os

os.environ.setdefault("DATABASE_URL", "sqlite+aiosqlite:///./test_elys.db")

import pytest
from fastapi.testclient import TestClient

from app.main import create_app


@pytest.fixture(scope="module")
def client() -> TestClient:
    with TestClient(create_app()) as test_client:
        yield test_client


def test_health_reports_provider_and_retriever(client: TestClient) -> None:
    response = client.get("/api/health")
    assert response.status_code == 200
    body = response.json()
    assert body["provider"]["name"] in {"anthropic", "ollama"}
    # The null retriever must never claim to be ready — a retriever that
    # silently returns nothing while reporting healthy is how ungrounded
    # answers reach engineers.
    assert body["retriever"] == "null"
    assert body["retriever_ready"] is False


def test_targets_are_listed(client: TestClient) -> None:
    values = {t["value"] for t in client.get("/api/targets").json()}
    assert {"autolisp", "pyrevit", "dynamo_python", "csharp_revit"} == values


def test_generate_rejects_empty_request(client: TestClient) -> None:
    response = client.post("/api/generate", json={"target": "autolisp", "instruction": "  "})
    assert response.status_code == 422


def test_fix_mode_requires_code_and_error_log(client: TestClient) -> None:
    response = client.post(
        "/api/generate",
        json={"mode": "fix", "target": "autolisp", "previous_code": "(defun c:X ())"},
    )
    assert response.status_code == 422
    assert "error output" in response.json()["detail"]


def test_attachment_size_is_capped(client: TestClient) -> None:
    response = client.post(
        "/api/generate",
        json={
            "target": "pyrevit",
            "instruction": "rename parameters",
            "attachments": [{"filename": "dump.csv", "content": "x" * 200_001}],
        },
    )
    assert response.status_code == 413


def test_snippet_lifecycle_and_filtering(client: TestClient) -> None:
    created = client.post(
        "/api/snippets",
        json={
            "title": "Reduce DTM vertices",
            "code": "(defun c:RDP () (princ))",
            "target": "autolisp",
            "tags": ["#Civil3D", "dtm", "Civil3D"],
            "notes": "Ramer-Douglas-Peucker on selected polylines",
        },
    )
    assert created.status_code == 201
    snippet = created.json()
    # Tags are normalised: '#' stripped, lowercased, duplicates dropped.
    assert snippet["tags"] == ["civil3d", "dtm"]

    assert client.get("/api/snippets", params={"tag": "#Civil3D"}).json()
    assert client.get("/api/snippets", params={"tag": "revit"}).json() == []
    assert client.get("/api/snippets", params={"search": "peucker"}).json()
    assert client.get("/api/snippets", params={"target": "pyrevit"}).json() == []

    assert client.delete(f"/api/snippets/{snippet['id']}").status_code == 204
    assert client.get(f"/api/snippets/{snippet['id']}").status_code == 404
