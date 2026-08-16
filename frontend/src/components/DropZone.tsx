import { useRef, useState } from "react";
import { Chip } from "@heroui/react";
import type { Attachment } from "../api";

/**
 * Hybrid input: Revit parameter exports, CAD logs and CSV dumps are dropped
 * here and read as text in the browser, so the request stays a single JSON body.
 */

const ACCEPTED = ".json,.csv,.txt,.log,.xml,.md,.lsp,.py,.cs";
const MAX_CHARS_PER_FILE = 100_000;

type Props = {
  attachments: Attachment[];
  onChange: (next: Attachment[]) => void;
  disabled?: boolean;
};

export default function DropZone({ attachments, onChange, disabled }: Props) {
  const [isOver, setIsOver] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  async function ingest(files: FileList | null) {
    if (!files?.length) return;
    setProblem(null);
    const next: Attachment[] = [...attachments];

    for (const file of Array.from(files)) {
      const text = await file.text();
      if (text.length > MAX_CHARS_PER_FILE) {
        // Truncating silently would let the model reason about half a log and
        // sound confident about it, so the cut is stated in the file itself.
        setProblem(
          `${file.name} je delší než ${MAX_CHARS_PER_FILE.toLocaleString("cs")} znaků a byl zkrácen.`,
        );
        next.push({
          filename: file.name,
          content: `${text.slice(0, MAX_CHARS_PER_FILE)}\n\n[…zkráceno, soubor pokračuje]`,
        });
      } else {
        next.push({ filename: file.name, content: text });
      }
    }
    onChange(next);
  }

  return (
    <div className="space-y-2">
      <div
        onDragOver={(event) => {
          event.preventDefault();
          if (!disabled) setIsOver(true);
        }}
        onDragLeave={() => setIsOver(false)}
        onDrop={(event) => {
          event.preventDefault();
          setIsOver(false);
          if (!disabled) void ingest(event.dataTransfer.files);
        }}
        onClick={() => !disabled && inputRef.current?.click()}
        className={[
          "cursor-pointer rounded-md border border-dashed px-3 py-4 text-center text-xs transition-colors",
          isOver
            ? "border-slate-400 bg-slate-800/60 text-slate-200"
            : "border-slate-700 bg-slate-900/40 text-slate-400 hover:border-slate-600",
          disabled ? "pointer-events-none opacity-50" : "",
        ].join(" ")}
      >
        Přetáhněte parametry z Revitu, výpis logu nebo CSV
        <div className="mt-1 text-[11px] text-slate-600">{ACCEPTED}</div>
      </div>

      <input
        ref={inputRef}
        type="file"
        multiple
        accept={ACCEPTED}
        className="hidden"
        onChange={(event) => {
          void ingest(event.target.files);
          event.target.value = "";
        }}
      />

      {problem && <p className="text-[11px] text-signal-error">{problem}</p>}

      {attachments.length > 0 && (
        <ul className="flex flex-wrap gap-1.5">
          {attachments.map((file, index) => (
            <li key={`${file.filename}-${index}`}>
              <Chip className="bg-slate-800 text-slate-300">
                <span className="font-mono text-[11px]">{file.filename}</span>
                <span className="ml-1.5 text-slate-500">
                  {Math.ceil(file.content.length / 1000)}k
                </span>
                <button
                  type="button"
                  aria-label={`Odebrat ${file.filename}`}
                  className="ml-2 text-slate-500 hover:text-slate-200"
                  onClick={() =>
                    onChange(attachments.filter((_, position) => position !== index))
                  }
                >
                  ×
                </button>
              </Chip>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
