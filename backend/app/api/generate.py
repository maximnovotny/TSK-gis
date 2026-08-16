"""Generation and the debugging loop, streamed over SSE."""

from __future__ import annotations

import json
from collections.abc import AsyncIterator

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

from app.config import get_settings
from app.llm.base import ChatMessage, ProviderError
from app.llm.registry import get_provider
from app.prompts import build_fix_prompt, build_generate_prompt, build_system_prompt
from app.rag.base import get_retriever
from app.schemas import GenerateRequest

router = APIRouter(tags=["generate"])


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/generate")
async def generate(request: GenerateRequest) -> StreamingResponse:
    settings = get_settings()

    problem = request.validate_for_mode()
    if problem:
        raise HTTPException(status_code=422, detail=problem)

    if len(request.attachments) > settings.max_attachments:
        raise HTTPException(
            status_code=413,
            detail=f"At most {settings.max_attachments} files per request.",
        )
    total_chars = sum(len(a.content) for a in request.attachments)
    if total_chars > settings.max_attachment_chars:
        raise HTTPException(
            status_code=413,
            detail=(
                f"Attached files total {total_chars} characters, over the "
                f"{settings.max_attachment_chars} limit. Trim the log or export "
                "a smaller parameter set."
            ),
        )

    provider = get_provider()
    retriever = get_retriever()

    query = request.instruction or request.error_log
    context = await retriever.retrieve(query=query, target=request.target.value)

    if request.mode == "fix":
        user_prompt = build_fix_prompt(
            previous_code=request.previous_code,
            error_log=request.error_log,
            instruction=request.instruction,
            context=context,
        )
    else:
        user_prompt = build_generate_prompt(
            instruction=request.instruction,
            attachments=[(a.filename, a.content) for a in request.attachments],
            context=context,
        )

    system = build_system_prompt(request.target)

    async def event_stream() -> AsyncIterator[str]:
        yield sse(
            "start",
            {
                "target": request.target.value,
                "mode": request.mode,
                "provider": provider.name,
                "model": provider.model,
                "contextChunks": len(context),
            },
        )
        try:
            async for piece in provider.stream(
                system=system,
                messages=[ChatMessage(role="user", content=user_prompt)],
                max_tokens=settings.max_output_tokens,
            ):
                yield sse("token", {"text": piece})
        except ProviderError as exc:
            # The stream has already begun, so the failure is reported as an
            # event rather than an HTTP status the client can no longer see.
            yield sse("error", {"message": str(exc)})
            return
        yield sse("done", {})

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
