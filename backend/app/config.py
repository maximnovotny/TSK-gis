"""Runtime configuration.

Everything that differs between a laptop, a Coolify-managed VPS and a fully
air-gapped on-premise install lives here, so switching from the cloud model to
a local Ollama box is an environment change rather than a code change.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

ProviderName = Literal["anthropic", "ollama"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    app_env: Literal["dev", "prod"] = "dev"
    api_prefix: str = "/api"

    # Comma-separated in the environment, e.g. "https://elys.example.com".
    cors_origins: str = "http://localhost:5173"

    # --- model routing -----------------------------------------------------
    # MVP runs on the hosted model; `ollama` is the same interface pointed at a
    # quantised local model for installs that must never reach the internet.
    llm_provider: ProviderName = "anthropic"

    anthropic_api_key: str = ""
    # The PRD was written against Claude 3.5 Sonnet, which has since been
    # retired. Opus 5 is the current default; set ANTHROPIC_MODEL to
    # claude-sonnet-5 to trade some capability for cost.
    anthropic_model: str = "claude-opus-5"
    anthropic_effort: Literal["low", "medium", "high", "xhigh", "max"] = "high"

    ollama_base_url: str = "http://ollama:11434"
    ollama_model: str = "gemma3:12b"

    max_output_tokens: int = 16000

    # --- storage -----------------------------------------------------------
    # SQLite by default so `uvicorn app.main:app` works with no services running;
    # compose overrides this with the PostgreSQL DSN.
    database_url: str = "sqlite+aiosqlite:///./elys.db"

    qdrant_url: str = "http://qdrant:6333"
    qdrant_collection: str = "elys_docs"

    # --- limits ------------------------------------------------------------
    # Hybrid input accepts Revit parameter dumps and CAD logs; these are text,
    # so a generous character cap is a better guard than a byte cap.
    max_attachment_chars: int = 200_000
    max_attachments: int = 8

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
