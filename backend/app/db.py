"""Database wiring.

SQLAlchemy async over SQLite locally and PostgreSQL in compose. Schema is
created on startup rather than via migrations — deliberate for the MVP, and the
first thing to replace with Alembic once the snippet model stops moving.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime

from sqlalchemy import DateTime, Integer, String, Text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.config import get_settings


class Base(DeclarativeBase):
    pass


class Snippet(Base):
    __tablename__ = "snippets"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    title: Mapped[str] = mapped_column(String(160))
    code: Mapped[str] = mapped_column(Text)
    target: Mapped[str] = mapped_column(String(32), index=True)
    # Tags are a small, denormalised list; JSON keeps SQLite and PostgreSQL on
    # the same code path. Promote to a join table when tag search needs indexes.
    tags_json: Mapped[str] = mapped_column(Text, default="[]")
    notes: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(UTC)
    )

    @property
    def tags(self) -> list[str]:
        try:
            loaded = json.loads(self.tags_json)
        except json.JSONDecodeError:
            return []
        return [str(tag) for tag in loaded] if isinstance(loaded, list) else []

    @tags.setter
    def tags(self, values: list[str]) -> None:
        self.tags_json = json.dumps(values)


_settings = get_settings()
engine = create_async_engine(_settings.database_url, future=True)
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def init_db() -> None:
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)


async def get_session() -> AsyncIterator[AsyncSession]:
    async with SessionLocal() as session:
        yield session
