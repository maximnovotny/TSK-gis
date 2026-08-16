"""On-premise provider for installs that must stay off the internet.

Speaks Ollama's /api/chat NDJSON stream. Kept deliberately dependency-free
(httpx only) so an air-gapped deployment does not need a vendor SDK.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator

import httpx

from .base import ChatMessage, ProviderError, ProviderHealth


class OllamaProvider:
    name = "ollama"

    def __init__(self, *, base_url: str, model: str, timeout: float = 600.0) -> None:
        self.model = model
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    async def stream(
        self, *, system: str, messages: list[ChatMessage], max_tokens: int
    ) -> AsyncIterator[str]:
        payload = {
            "model": self.model,
            "stream": True,
            "options": {"num_predict": max_tokens},
            "messages": [
                {"role": "system", "content": system},
                *({"role": m.role, "content": m.content} for m in messages),
            ],
        }
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                async with client.stream(
                    "POST", f"{self._base_url}/api/chat", json=payload
                ) as response:
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        if not line.strip():
                            continue
                        try:
                            chunk = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        if chunk.get("error"):
                            raise ProviderError(str(chunk["error"]))
                        piece = chunk.get("message", {}).get("content", "")
                        if piece:
                            yield piece
                        if chunk.get("done"):
                            break
        except httpx.HTTPError as exc:
            raise ProviderError(f"Ollama at {self._base_url} is unreachable: {exc}") from exc

    async def health(self) -> ProviderHealth:
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                response = await client.get(f"{self._base_url}/api/tags")
                response.raise_for_status()
                installed = {m.get("name", "") for m in response.json().get("models", [])}
        except httpx.HTTPError as exc:
            return ProviderHealth(self.name, self.model, False, f"unreachable: {exc}")

        if self.model not in installed:
            return ProviderHealth(
                self.name,
                self.model,
                False,
                f"model not pulled; run `ollama pull {self.model}`",
            )
        return ProviderHealth(self.name, self.model, True)
