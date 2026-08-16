from __future__ import annotations

from functools import lru_cache

from app.config import Settings, get_settings

from .anthropic_provider import AnthropicProvider
from .base import LLMProvider
from .ollama_provider import OllamaProvider


def build_provider(settings: Settings) -> LLMProvider:
    if settings.llm_provider == "ollama":
        return OllamaProvider(
            base_url=settings.ollama_base_url, model=settings.ollama_model
        )
    return AnthropicProvider(
        api_key=settings.anthropic_api_key,
        model=settings.anthropic_model,
        effort=settings.anthropic_effort,
    )


@lru_cache
def get_provider() -> LLMProvider:
    return build_provider(get_settings())
