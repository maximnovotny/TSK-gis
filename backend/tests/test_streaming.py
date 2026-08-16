"""The SSE path, exercised without touching a real model."""

from __future__ import annotations

import os

os.environ.setdefault("DATABASE_URL", "sqlite+aiosqlite:///./test_elys.db")

from collections.abc import AsyncIterator

import pytest
from fastapi.testclient import TestClient

from app.api import generate as generate_module
from app.llm.base import ChatMessage, ProviderError, ProviderHealth
from app.main import create_app


class FakeProvider:
    name = "fake"
    model = "fake-model"

    def __init__(self, *, pieces: list[str] | None = None, fail: str | None = None) -> None:
        self._pieces = pieces or []
        self._fail = fail
        self.last_system: str | None = None
        self.last_prompt: str | None = None

    async def stream(
        self, *, system: str, messages: list[ChatMessage], max_tokens: int
    ) -> AsyncIterator[str]:
        self.last_system = system
        self.last_prompt = messages[-1].content
        for piece in self._pieces:
            yield piece
        if self._fail:
            raise ProviderError(self._fail)

    async def health(self) -> ProviderHealth:
        return ProviderHealth(self.name, self.model, True)


@pytest.fixture
def client() -> TestClient:
    with TestClient(create_app()) as test_client:
        yield test_client


def install(monkeypatch: pytest.MonkeyPatch, provider: FakeProvider) -> FakeProvider:
    monkeypatch.setattr(generate_module, "get_provider", lambda: provider)
    return provider


def parse_events(body: str) -> list[tuple[str, str]]:
    events: list[tuple[str, str]] = []
    for block in body.strip().split("\n\n"):
        lines = block.splitlines()
        if len(lines) < 2:
            continue
        events.append((lines[0].removeprefix("event: "), lines[1].removeprefix("data: ")))
    return events


def test_generate_streams_tokens_then_done(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    provider = install(monkeypatch, FakeProvider(pieces=["(defun ", "c:GRID ", "())"]))

    response = client.post(
        "/api/generate",
        json={"target": "autolisp", "instruction": "draw a construction grid"},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")

    events = parse_events(response.text)
    kinds = [kind for kind, _ in events]
    assert kinds[0] == "start"
    assert kinds[-1] == "done"
    assert kinds.count("token") == 3

    # The target's rules must reach the model, not just the user's sentence.
    assert "defun c:" in (provider.last_system or "")
    assert "construction grid" in (provider.last_prompt or "")


def test_provider_failure_mid_stream_is_reported_as_an_event(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    install(monkeypatch, FakeProvider(pieces=["(defun "], fail="Ollama is unreachable"))

    response = client.post(
        "/api/generate", json={"target": "pyrevit", "instruction": "list all walls"}
    )
    # Headers are long gone by the time the model fails, so the client learns
    # about it from an error event rather than a status code.
    assert response.status_code == 200
    events = parse_events(response.text)
    assert events[-1][0] == "error"
    assert "unreachable" in events[-1][1]


def test_fix_mode_sends_code_and_trace(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    provider = install(monkeypatch, FakeProvider(pieces=["fixed"]))

    response = client.post(
        "/api/generate",
        json={
            "mode": "fix",
            "target": "csharp_revit",
            "previous_code": "doc.Delete(id);",
            "error_log": "Autodesk.Revit.Exceptions.InvalidOperationException",
        },
    )
    assert response.status_code == 200
    prompt = provider.last_prompt or ""
    assert "doc.Delete(id);" in prompt
    assert "InvalidOperationException" in prompt
