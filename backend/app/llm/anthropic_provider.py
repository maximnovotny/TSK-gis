"""Hosted-model provider (MVP default)."""

from __future__ import annotations

from collections.abc import AsyncIterator

from anthropic import APIError, AsyncAnthropic

from .base import ChatMessage, ProviderError, ProviderHealth


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, *, api_key: str, model: str, effort: str = "high") -> None:
        self.model = model
        self._effort = effort
        self._api_key = api_key
        # A bare client also picks up an `ant auth login` profile, so an empty
        # api_key is not proof that no credentials exist.
        self._client = AsyncAnthropic(api_key=api_key) if api_key else AsyncAnthropic()

    async def stream(
        self, *, system: str, messages: list[ChatMessage], max_tokens: int
    ) -> AsyncIterator[str]:
        try:
            async with self._client.messages.stream(
                model=self.model,
                max_tokens=max_tokens,
                system=system,
                output_config={"effort": self._effort},
                messages=[{"role": m.role, "content": m.content} for m in messages],
            ) as stream:
                async for text in stream.text_stream:
                    yield text

                final = await stream.get_final_message()

            # A safety decline arrives as a successful response, not an error.
            if final.stop_reason == "refusal":
                raise ProviderError(
                    "The model declined this request. Rephrase the prompt or "
                    "remove sensitive content from the attached files."
                )
            if final.stop_reason == "max_tokens":
                yield (
                    "\n\n;; --- OUTPUT TRUNCATED: the script hit the token limit. "
                    "Split the task into smaller routines and generate them separately. ---"
                )
        except APIError as exc:  # network, auth, rate limit, overload
            raise ProviderError(f"Model request failed: {exc}") from exc

    async def health(self) -> ProviderHealth:
        ready = bool(self._api_key)
        return ProviderHealth(
            name=self.name,
            model=self.model,
            ready=ready,
            detail="" if ready else "ANTHROPIC_API_KEY is not set",
        )
