import Editor, { type Monaco } from "@monaco-editor/react";
import { Button, Spinner } from "@heroui/react";
import { EDITOR_LANGUAGE, FILE_EXTENSION, type ScriptTarget } from "../api";

type Props = {
  code: string;
  onCodeChange: (value: string) => void;
  target: ScriptTarget;
  isStreaming: boolean;
  error: string | null;
  meta: { provider: string; model: string; contextChunks: number } | null;
  onSave: () => void;
};

/** Monaco theme in the same silver-slate register as the shell. */
function defineTheme(monaco: Monaco) {
  monaco.editor.defineTheme("elys-slate", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "comment", foreground: "6b7482", fontStyle: "italic" },
      { token: "string", foreground: "a9bcae" },
      { token: "keyword", foreground: "c3cbd8" },
      { token: "number", foreground: "bfa98f" },
    ],
    colors: {
      "editor.background": "#12151a",
      "editor.foreground": "#c8ced7",
      "editorLineNumber.foreground": "#3b424c",
      "editorLineNumber.activeForeground": "#7c8593",
      "editor.selectionBackground": "#2a3038",
      "editor.lineHighlightBackground": "#171b21",
      "editorCursor.foreground": "#a4acb8",
      "editorIndentGuide.background1": "#1d222a",
    },
  });
  monaco.editor.setTheme("elys-slate");
}

export default function EditorPane({
  code,
  onCodeChange,
  target,
  isStreaming,
  error,
  meta,
  onSave,
}: Props) {
  function download() {
    const blob = new Blob([code], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `elys-script.${FILE_EXTENSION[target]}`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  return (
    <section className="flex h-full min-w-0 flex-col bg-slate-900">
      <header className="flex items-center gap-3 border-b border-slate-800 px-4 py-2">
        <span className="font-mono text-[11px] uppercase tracking-wide text-slate-500">
          {EDITOR_LANGUAGE[target]} · elys-script.{FILE_EXTENSION[target]}
        </span>

        {isStreaming && (
          <span className="flex items-center gap-2 text-[11px] text-signal-busy">
            <Spinner className="size-3" />
            generuji…
          </span>
        )}

        {meta && !isStreaming && (
          <span className="text-[11px] text-slate-600">
            {meta.model}
            {meta.contextChunks > 0
              ? ` · ${meta.contextChunks} bloků dokumentace`
              : " · bez RAG kontextu"}
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
          <Button
            size="sm"
            variant="ghost"
            isDisabled={!code}
            onPress={() => void navigator.clipboard.writeText(code)}
          >
            Kopírovat
          </Button>
          <Button size="sm" variant="ghost" isDisabled={!code} onPress={download}>
            Stáhnout
          </Button>
          <Button size="sm" variant="secondary" isDisabled={!code || isStreaming} onPress={onSave}>
            Uložit do knihovny
          </Button>
        </div>
      </header>

      {error && (
        <div className="border-b border-slate-800 bg-slate-850 px-4 py-2 text-xs text-signal-error">
          {error}
        </div>
      )}

      <div className="min-h-0 flex-1">
        <Editor
          language={EDITOR_LANGUAGE[target]}
          value={code}
          theme="elys-slate"
          beforeMount={defineTheme}
          onChange={(value) => onCodeChange(value ?? "")}
          options={{
            fontSize: 13,
            fontFamily: "var(--font-mono)",
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            smoothScrolling: true,
            renderLineHighlight: "line",
            padding: { top: 12, bottom: 12 },
            // While tokens stream in, a read-only buffer avoids the cursor
            // fighting the incoming text.
            readOnly: isStreaming,
            wordWrap: "on",
            tabSize: 2,
          }}
        />
      </div>
    </section>
  );
}
