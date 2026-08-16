from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, field_validator

from app.prompts import ScriptTarget


class Attachment(BaseModel):
    """A dropped file, read as text in the browser and posted inline.

    Revit parameter dumps and CAD logs are text; sending them as JSON keeps the
    endpoint uniform and avoids a multipart path that would need its own limits.
    """

    filename: str = Field(max_length=255)
    content: str

    @field_validator("filename")
    @classmethod
    def _strip_path(cls, value: str) -> str:
        # The name is echoed into the prompt; keep the leaf only.
        return value.replace("\\", "/").rsplit("/", maxsplit=1)[-1] or "attachment"


class GenerateRequest(BaseModel):
    mode: Literal["generate", "fix"] = "generate"
    target: ScriptTarget
    instruction: str = Field(default="", max_length=8000)
    attachments: list[Attachment] = Field(default_factory=list)

    # Debugging loop — required when mode is "fix".
    previous_code: str = Field(default="", max_length=200_000)
    error_log: str = Field(default="", max_length=50_000)

    def validate_for_mode(self) -> str | None:
        """Return a human-readable problem, or None when the request is usable."""
        if self.mode == "generate" and not self.instruction.strip() and not self.attachments:
            return "Describe what the script should do, or attach a file to work from."
        if self.mode == "fix":
            if not self.previous_code.strip():
                return "Fixing a script needs the script that failed."
            if not self.error_log.strip():
                return "Fixing a script needs the error output from the CAD session."
        return None


class SnippetCreate(BaseModel):
    title: str = Field(min_length=1, max_length=160)
    code: str = Field(min_length=1, max_length=200_000)
    target: ScriptTarget
    tags: list[str] = Field(default_factory=list, max_length=12)
    notes: str = Field(default="", max_length=2000)

    @field_validator("tags")
    @classmethod
    def _normalise_tags(cls, values: list[str]) -> list[str]:
        seen: list[str] = []
        for raw in values:
            tag = raw.strip().lstrip("#").lower()
            if tag and tag not in seen:
                seen.append(tag)
        return seen


class SnippetRead(BaseModel):
    id: int
    title: str
    code: str
    target: str
    tags: list[str]
    notes: str
    created_at: datetime


class ProviderStatus(BaseModel):
    name: str
    model: str
    ready: bool
    detail: str = ""


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    app_env: str
    provider: ProviderStatus
    retriever: str
    retriever_ready: bool
