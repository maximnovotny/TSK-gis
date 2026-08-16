import { Button } from "@heroui/react";
import DropZone from "./DropZone";
import type { Attachment, ScriptTarget, TargetOption } from "../api";

/**
 * Left column: everything that shapes the request.
 *
 * Form controls are native elements styled to the silver-slate palette rather
 * than HeroUI's react-aria inputs — in a dense tool the native select and
 * textarea keep browser keyboard behaviour and never float a popover over the
 * editor. HeroUI supplies the buttons, chips and surfaces.
 */

type Props = {
  targets: TargetOption[];
  target: ScriptTarget;
  onTargetChange: (target: ScriptTarget) => void;
  mode: "generate" | "fix";
  onModeChange: (mode: "generate" | "fix") => void;
  instruction: string;
  onInstructionChange: (value: string) => void;
  attachments: Attachment[];
  onAttachmentsChange: (next: Attachment[]) => void;
  errorLog: string;
  onErrorLogChange: (value: string) => void;
  isStreaming: boolean;
  onSubmit: () => void;
  onAbort: () => void;
  hasCode: boolean;
};

const label = "text-[11px] font-medium uppercase tracking-wide text-slate-500";
const field =
  "w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-200 " +
  "placeholder:text-slate-600 focus:border-slate-500 focus:outline-none";

export default function ContextPanel({
  targets,
  target,
  onTargetChange,
  mode,
  onModeChange,
  instruction,
  onInstructionChange,
  attachments,
  onAttachmentsChange,
  errorLog,
  onErrorLogChange,
  isStreaming,
  onSubmit,
  onAbort,
  hasCode,
}: Props) {
  return (
    <div className="flex h-full flex-col gap-4 overflow-y-auto p-4">
      <div className="space-y-1.5">
        <label className={label} htmlFor="target">
          Cílové prostředí
        </label>
        <select
          id="target"
          className={field}
          value={target}
          disabled={isStreaming}
          onChange={(event) => onTargetChange(event.target.value as ScriptTarget)}
        >
          {targets.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      <div className="grid grid-cols-2 gap-1 rounded-md border border-slate-700 bg-slate-900 p-1">
        {(["generate", "fix"] as const).map((value) => (
          <button
            key={value}
            type="button"
            disabled={isStreaming}
            onClick={() => onModeChange(value)}
            className={[
              "rounded px-2 py-1.5 text-xs transition-colors disabled:opacity-50",
              mode === value
                ? "bg-slate-700 text-slate-100"
                : "text-slate-400 hover:text-slate-200",
            ].join(" ")}
          >
            {value === "generate" ? "Generovat" : "Opravit chybu"}
          </button>
        ))}
      </div>

      <div className="space-y-1.5">
        <label className={label} htmlFor="instruction">
          {mode === "fix" ? "Doplňující poznámky (volitelné)" : "Zadání"}
        </label>
        <textarea
          id="instruction"
          rows={mode === "fix" ? 3 : 8}
          className={`${field} resize-y font-mono text-[13px] leading-relaxed`}
          placeholder={
            mode === "fix"
              ? "Např. musí to fungovat i v Civil 3D 2024"
              : "Např. rutina, která redukuje vertexy vybraných 3D polylinií algoritmem Ramer-Douglas-Peucker s tolerancí zadanou uživatelem a vypíše počet odebraných bodů."
          }
          value={instruction}
          disabled={isStreaming}
          onChange={(event) => onInstructionChange(event.target.value)}
        />
      </div>

      {mode === "fix" && (
        <div className="space-y-1.5">
          <label className={label} htmlFor="errorlog">
            Chybové hlášení z CADu
          </label>
          <textarea
            id="errorlog"
            rows={7}
            className={`${field} resize-y font-mono text-[12px] leading-relaxed`}
            placeholder="Vložte stack trace nebo hlášku z příkazové řádky…"
            value={errorLog}
            disabled={isStreaming}
            onChange={(event) => onErrorLogChange(event.target.value)}
          />
          {!hasCode && (
            <p className="text-[11px] text-signal-error">
              V editoru není žádný kód k opravě — vygenerujte skript nebo ho vložte.
            </p>
          )}
        </div>
      )}

      {mode === "generate" && (
        <div className="space-y-1.5">
          <span className={label}>Přílohy</span>
          <DropZone
            attachments={attachments}
            onChange={onAttachmentsChange}
            disabled={isStreaming}
          />
        </div>
      )}

      <div className="mt-auto pt-2">
        {isStreaming ? (
          <Button className="w-full" variant="secondary" onPress={onAbort}>
            Zastavit generování
          </Button>
        ) : (
          <Button className="w-full" onPress={onSubmit}>
            {mode === "fix" ? "Opravit skript" : "Vygenerovat skript"}
          </Button>
        )}
      </div>
    </div>
  );
}
