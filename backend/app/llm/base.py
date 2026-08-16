"""Provider-agnostic chat interface.

The PRD requires the MVP to run on a hosted code model while staying ready for
a fully offline Ollama deployment. Both are expressed as this one protocol, so
the API layer never learns which one it is talking to.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Literal, Protocol, runtime_checkable

Role = Literal["user", "assistant"]


@dataclass(frozen=True)
class ChatMessage:
    role: Role
    content: str


@dataclass(frozen=True)
class ProviderHealth:
    name: str
    model: str
    ready: bool
    detail: str = ""


class ProviderError(RuntimeError):
    """Raised when the model is unreachable, misconfigured, or declines."""


@runtime_checkable
class LLMProvider(Protocol):
    name: str
    model: str

    def stream(
        self, *, system: str, messages: list[ChatMessage], max_tokens: int
    ) -> AsyncIterator[str]:
        """Yield response text incrementally.

        Streaming is not a UI nicety here: a full Revit plugin can run to
        thousands of tokens, and a non-streaming request that long risks an
        HTTP timeout on both providers.
        """
        ...

    async def health(self) -> ProviderHealth: ...
