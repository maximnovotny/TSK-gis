from __future__ import annotations

from fastapi import APIRouter

from app.config import get_settings
from app.llm.registry import get_provider
from app.prompts import TARGET_LABELS
from app.rag.base import get_retriever
from app.schemas import HealthResponse, ProviderStatus

router = APIRouter(tags=["meta"])


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    settings = get_settings()
    provider_health = await get_provider().health()
    retriever = get_retriever()
    retriever_ready = await retriever.ready()

    return HealthResponse(
        # Degraded, not failing: the API is up and the library works even when
        # the model is unreachable. The UI shows the reason instead of a blank
        # screen, which matters on an on-prem box where Ollama may be down.
        status="ok" if provider_health.ready else "degraded",
        app_env=settings.app_env,
        provider=ProviderStatus(
            name=provider_health.name,
            model=provider_health.model,
            ready=provider_health.ready,
            detail=provider_health.detail,
        ),
        retriever=retriever.name,
        retriever_ready=retriever_ready,
    )


@router.get("/targets")
async def targets() -> list[dict[str, str]]:
    """Script targets the UI offers, labelled for humans."""
    return [{"value": target.value, "label": label} for target, label in TARGET_LABELS.items()]
