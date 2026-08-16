"""Snippet library — verified scripts, tagged and searchable."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Snippet, get_session
from app.schemas import SnippetCreate, SnippetRead

router = APIRouter(prefix="/snippets", tags=["snippets"])


def to_read(row: Snippet) -> SnippetRead:
    return SnippetRead(
        id=row.id,
        title=row.title,
        code=row.code,
        target=row.target,
        tags=row.tags,
        notes=row.notes,
        created_at=row.created_at,
    )


@router.get("", response_model=list[SnippetRead])
async def list_snippets(
    target: str | None = Query(default=None),
    tag: str | None = Query(default=None, description="single tag, with or without '#'"),
    search: str | None = Query(default=None, description="matches title and notes"),
    session: AsyncSession = Depends(get_session),
) -> list[SnippetRead]:
    statement = select(Snippet).order_by(Snippet.created_at.desc())
    if target:
        statement = statement.where(Snippet.target == target)
    rows = list((await session.scalars(statement)).all())

    # Tag and free-text filtering happen in Python: tags live in a JSON column,
    # and library sizes here are in the hundreds. Revisit when that stops being
    # true — the query is the natural place for it once tags are normalised.
    if tag:
        wanted = tag.strip().lstrip("#").lower()
        rows = [row for row in rows if wanted in row.tags]
    if search:
        needle = search.strip().lower()
        rows = [
            row
            for row in rows
            if needle in row.title.lower() or needle in row.notes.lower()
        ]
    return [to_read(row) for row in rows]


@router.post("", response_model=SnippetRead, status_code=201)
async def create_snippet(
    payload: SnippetCreate, session: AsyncSession = Depends(get_session)
) -> SnippetRead:
    row = Snippet(
        title=payload.title,
        code=payload.code,
        target=payload.target.value,
        notes=payload.notes,
    )
    row.tags = payload.tags
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return to_read(row)


@router.get("/{snippet_id}", response_model=SnippetRead)
async def get_snippet(
    snippet_id: int, session: AsyncSession = Depends(get_session)
) -> SnippetRead:
    row = await session.get(Snippet, snippet_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Snippet not found")
    return to_read(row)


@router.delete("/{snippet_id}", status_code=204)
async def delete_snippet(
    snippet_id: int, session: AsyncSession = Depends(get_session)
) -> None:
    row = await session.get(Snippet, snippet_id)
    if row is None:
        raise HTTPException(status_code=404, detail="Snippet not found")
    await session.delete(row)
    await session.commit()
