import { useEffect, useMemo, useState } from "react";
import { Button } from "@heroui/react";
import ContextPanel from "./components/ContextPanel";
import EditorPane from "./components/EditorPane";
import SnippetLibrary from "./components/SnippetLibrary";
import {
  api,
  streamGeneration,
  type Health,
  type Attachment,
  type ScriptTarget,
  type Snippet,
  type TargetOption,
} from "./api";

type StreamMeta = { provider: string; model: string; contextChunks: number };

const FALLBACK_TARGETS: TargetOption[] = [
  { value: "autolisp", label: "AutoLISP (AutoCAD / Civil 3D)" },
  { value: "pyrevit", label: "pyRevit" },
  { value: "dynamo_python", label: "Dynamo Python" },
  { value: "csharp_revit", label: "C# Revit API" },
];

export default function App() {
  const [health, setHealth] = useState<Health | null>(null);
  const [targets, setTargets] = useState<TargetOption[]>(FALLBACK_TARGETS);

  const [tab, setTab] = useState<"context" | "library">("context");
  const [target, setTarget] = useState<ScriptTarget>("autolisp");
  const [mode, setMode] = useState<"generate" | "fix">("generate");
  const [instruction, setInstruction] = useState("");
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [errorLog, setErrorLog] = useState("");

  const [code, setCode] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamError, setStreamError] = useState<string | null>(null);
  const [meta, setMeta] = useState<StreamMeta | null>(null);
  const [abort, setAbort] = useState<(() => void) | null>(null);

  const [libraryToken, setLibraryToken] = useState(0);
  const [saveOpen, setSaveOpen] = useState(false);
  const [saveTitle, setSaveTitle] = useState("");
  const [saveTags, setSaveTags] = useState("");
  const [saveNotes, setSaveNotes] = useState("");
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    api.health().then(setHealth).catch(() => setHealth(null));
    api.targets().then(setTargets).catch(() => undefined);
  }, []);

  const providerLine = useMemo(() => {
    if (!health) return "API nedostupné";
    const { provider, retriever_ready } = health;
    const rag = retriever_ready ? "RAG aktivní" : "RAG neindexován";
    return `${provider.name} · ${provider.model} · ${rag}`;
  }, [health]);

  function run() {
    setStreamError(null);
    setMeta(null);

    const isFix = mode === "fix";
    if (isFix && !code.trim()) {
      setStreamError("V editoru není žádný kód k opravě.");
      return;
    }

    // A fix rewrites the buffer; a fresh generation starts from an empty one.
    const previous = code;
    setCode(isFix ? "" : "");
    setIsStreaming(true);

    const cancel = streamGeneration(
      {
        mode,
        target,
        instruction,
        attachments: isFix ? [] : attachments,
        previous_code: isFix ? previous : "",
        error_log: isFix ? errorLog : "",
      },
      {
        onStart: setMeta,
        onToken: (text) => setCode((current) => current + text),
        onError: (message) => {
          setStreamError(message);
          // Losing the failing script would cost the engineer their context,
          // so a failed fix restores what was in the editor.
          if (isFix) setCode(previous);
        },
        onDone: () => {
          setIsStreaming(false);
          setAbort(null);
        },
      },
    );
    setAbort(() => cancel);
  }

  async function saveSnippet() {
    setSaveError(null);
    try {
      await api.createSnippet({
        title: saveTitle.trim() || `Skript ${new Date().toLocaleString("cs")}`,
        code,
        target,
        tags: saveTags.split(/[,\s]+/).filter(Boolean),
        notes: saveNotes,
      });
      setSaveOpen(false);
      setSaveTitle("");
      setSaveTags("");
      setSaveNotes("");
      setLibraryToken((token) => token + 1);
      setTab("library");
    } catch (cause) {
      setSaveError(cause instanceof Error ? cause.message : "Uložení selhalo");
    }
  }

  function loadSnippet(snippet: Snippet) {
    setCode(snippet.code);
    setTarget(snippet.target as ScriptTarget);
    setMeta(null);
    setStreamError(null);
    setTab("context");
  }

  const field =
    "w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-200 " +
    "placeholder:text-slate-600 focus:border-slate-500 focus:outline-none";

  return (
    <div className="flex h-full flex-col bg-slate-950">
      <header className="flex items-center gap-4 border-b border-slate-800 px-4 py-2.5">
        <span className="text-sm font-semibold tracking-[0.2em] text-slate-200">ELYS</span>
        <span className="text-[11px] text-slate-600">AI CAD/BIM Script Builder</span>
        <div className="ml-auto flex items-center gap-2">
          <span
            className={[
              "size-1.5 rounded-full",
              !health
                ? "bg-signal-error"
                : health.status === "ok"
                  ? "bg-signal-ok"
                  : "bg-signal-busy",
            ].join(" ")}
            aria-hidden
          />
          <span className="font-mono text-[11px] text-slate-500">{providerLine}</span>
        </div>
      </header>

      {health?.status === "degraded" && (
        <div className="border-b border-slate-800 bg-slate-850 px-4 py-1.5 text-[11px] text-signal-busy">
          Model není připraven: {health.provider.detail || "zkontrolujte konfiguraci"}.
          Knihovna skriptů funguje dál.
        </div>
      )}

      <main className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[380px_1fr]">
        <aside className="flex min-h-0 flex-col border-r border-slate-800 bg-slate-950">
          <div className="flex border-b border-slate-800">
            {(["context", "library"] as const).map((value) => (
              <button
                key={value}
                type="button"
                onClick={() => setTab(value)}
                className={[
                  "flex-1 px-3 py-2 text-xs transition-colors",
                  tab === value
                    ? "border-b border-slate-400 text-slate-200"
                    : "text-slate-500 hover:text-slate-300",
                ].join(" ")}
              >
                {value === "context" ? "Zadání" : "Knihovna"}
              </button>
            ))}
          </div>

          <div className="min-h-0 flex-1">
            {tab === "context" ? (
              <ContextPanel
                targets={targets}
                target={target}
                onTargetChange={setTarget}
                mode={mode}
                onModeChange={setMode}
                instruction={instruction}
                onInstructionChange={setInstruction}
                attachments={attachments}
                onAttachmentsChange={setAttachments}
                errorLog={errorLog}
                onErrorLogChange={setErrorLog}
                isStreaming={isStreaming}
                onSubmit={run}
                onAbort={() => abort?.()}
                hasCode={Boolean(code.trim())}
              />
            ) : (
              <SnippetLibrary refreshToken={libraryToken} onLoad={loadSnippet} />
            )}
          </div>
        </aside>

        <EditorPane
          code={code}
          onCodeChange={setCode}
          target={target}
          isStreaming={isStreaming}
          error={streamError}
          meta={meta}
          onSave={() => setSaveOpen(true)}
        />
      </main>

      {saveOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 p-4">
          <div className="w-full max-w-md space-y-3 rounded-lg border border-slate-700 bg-slate-900 p-5">
            <h2 className="text-sm text-slate-200">Uložit do knihovny</h2>
            <input
              className={field}
              placeholder="Název skriptu"
              value={saveTitle}
              onChange={(event) => setSaveTitle(event.target.value)}
            />
            <input
              className={field}
              placeholder="Tagy oddělené čárkou, např. #Civil3D, dtm"
              value={saveTags}
              onChange={(event) => setSaveTags(event.target.value)}
            />
            <textarea
              className={`${field} resize-y`}
              rows={3}
              placeholder="Poznámka — kde byl skript ověřen, na jaké verzi…"
              value={saveNotes}
              onChange={(event) => setSaveNotes(event.target.value)}
            />
            {saveError && <p className="text-xs text-signal-error">{saveError}</p>}
            <div className="flex justify-end gap-2 pt-1">
              <Button variant="ghost" onPress={() => setSaveOpen(false)}>
                Zrušit
              </Button>
              <Button onPress={() => void saveSnippet()}>Uložit</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
