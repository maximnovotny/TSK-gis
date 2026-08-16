export type ScriptTarget =
  | "autolisp"
  | "pyrevit"
  | "dynamo_python"
  | "csharp_revit";

export type TargetOption = { value: ScriptTarget; label: string };

export type Health = {
  status: "ok" | "degraded";
  app_env: string;
  provider: { name: string; model: string; ready: boolean; detail: string };
  retriever: string;
  retriever_ready: boolean;
};

export type Snippet = {
  id: number;
  title: string;
  code: string;
  target: string;
  tags: string[];
  notes: string;
  created_at: string;
};

export type Attachment = { filename: string; content: string };

export type GenerateRequest = {
  mode: "generate" | "fix";
  target: ScriptTarget;
  instruction: string;
  attachments: Attachment[];
  previous_code?: string;
  error_log?: string;
};

export type StreamHandlers = {
  onStart?: (meta: { provider: string; model: string; contextChunks: number }) => void;
  onToken: (text: string) => void;
  onError: (message: string) => void;
  onDone: () => void;
};

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const detail = await response
      .json()
      .then((body) => body.detail as string)
      .catch(() => response.statusText);
    throw new Error(detail || `Request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  health: () => fetch("/api/health").then(json<Health>),
  targets: () => fetch("/api/targets").then(json<TargetOption[]>),

  listSnippets: (params: { target?: string; tag?: string; search?: string } = {}) => {
    const query = new URLSearchParams(
      Object.entries(params).filter(([, v]) => Boolean(v)) as [string, string][],
    );
    return fetch(`/api/snippets?${query}`).then(json<Snippet[]>);
  },

  createSnippet: (body: {
    title: string;
    code: string;
    target: ScriptTarget;
    tags: string[];
    notes?: string;
  }) =>
    fetch("/api/snippets", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).then(json<Snippet>),

  deleteSnippet: async (id: number) => {
    const response = await fetch(`/api/snippets/${id}`, { method: "DELETE" });
    if (!response.ok) throw new Error("Snippet could not be deleted");
  },
};

/**
 * Streams a generation run.
 *
 * EventSource cannot POST, so the SSE frames are parsed off a fetch body. The
 * returned function aborts the run — an engineer who sees the script going the
 * wrong way should not have to wait out a 16k-token response.
 */
export function streamGeneration(
  request: GenerateRequest,
  handlers: StreamHandlers,
): () => void {
  const controller = new AbortController();

  void (async () => {
    let response: Response;
    try {
      response = await fetch("/api/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request),
        signal: controller.signal,
      });
    } catch (error) {
      if (!controller.signal.aborted) {
        handlers.onError(error instanceof Error ? error.message : "Network error");
      }
      handlers.onDone();
      return;
    }

    // Validation failures arrive before the stream opens, as a normal status.
    if (!response.ok || !response.body) {
      const detail = await response
        .json()
        .then((body) => body.detail as string)
        .catch(() => `Request failed (${response.status})`);
      handlers.onError(detail);
      handlers.onDone();
      return;
    }

    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = "";

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += value;

        // Frames are separated by a blank line; keep any partial tail.
        const frames = buffer.split("\n\n");
        buffer = frames.pop() ?? "";

        for (const frame of frames) {
          const eventLine = frame.split("\n").find((l) => l.startsWith("event: "));
          const dataLine = frame.split("\n").find((l) => l.startsWith("data: "));
          if (!eventLine || !dataLine) continue;

          const event = eventLine.slice("event: ".length);
          let data: Record<string, unknown> = {};
          try {
            data = JSON.parse(dataLine.slice("data: ".length));
          } catch {
            continue;
          }

          if (event === "start") {
            handlers.onStart?.({
              provider: String(data.provider ?? ""),
              model: String(data.model ?? ""),
              contextChunks: Number(data.contextChunks ?? 0),
            });
          } else if (event === "token") {
            handlers.onToken(String(data.text ?? ""));
          } else if (event === "error") {
            handlers.onError(String(data.message ?? "Generation failed"));
          }
        }
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        handlers.onError(error instanceof Error ? error.message : "Stream interrupted");
      }
    } finally {
      handlers.onDone();
    }
  })();

  return () => controller.abort();
}

/** Monaco language ids for each target. */
export const EDITOR_LANGUAGE: Record<ScriptTarget, string> = {
  autolisp: "scheme", // Monaco has no AutoLISP mode; Scheme highlights s-expressions
  pyrevit: "python",
  dynamo_python: "python",
  csharp_revit: "csharp",
};

export const FILE_EXTENSION: Record<ScriptTarget, string> = {
  autolisp: "lsp",
  pyrevit: "py",
  dynamo_python: "py",
  csharp_revit: "cs",
};
