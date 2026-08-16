"""Retrieval seam (M3).

Generation already routes every request through a retriever, so switching from
the null implementation to Qdrant in milestone 3 touches this package only. The
null retriever returns nothing and says so in health output — it never fabricates
context, because a plausible-looking wrong API signature is the single most
expensive failure this product can ship.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class DocChunk:
    source: str
    title: str
    text: str
    score: float = 0.0


@runtime_checkable
class Retriever(Protocol):
    name: str

    async def retrieve(self, *, query: str, target: str, limit: int = 6) -> list[DocChunk]: ...

    async def ready(self) -> bool: ...


class NullRetriever:
    """Used until the Autodesk documentation index exists."""

    name = "null"

    async def retrieve(self, *, query: str, target: str, limit: int = 6) -> list[DocChunk]:
        return []

    async def ready(self) -> bool:
        return False


def get_retriever() -> Retriever:
    return NullRetriever()
