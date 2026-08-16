import { useEffect, useState } from "react";
import { Button, Chip } from "@heroui/react";
import { api, type Snippet } from "../api";

type Props = {
  /** Bumped by the parent after a save so the list refetches. */
  refreshToken: number;
  onLoad: (snippet: Snippet) => void;
};

const field =
  "w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-sm text-slate-200 " +
  "placeholder:text-slate-600 focus:border-slate-500 focus:outline-none";

export default function SnippetLibrary({ refreshToken, onLoad }: Props) {
  const [snippets, setSnippets] = useState<Snippet[]>([]);
  const [search, setSearch] = useState("");
  const [activeTag, setActiveTag] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setIsLoading(true);
    api
      .listSnippets({ search: search || undefined, tag: activeTag ?? undefined })
      .then((rows) => {
        if (!cancelled) {
          setSnippets(rows);
          setError(null);
        }
      })
      .catch((cause: Error) => !cancelled && setError(cause.message))
      .finally(() => !cancelled && setIsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [search, activeTag, refreshToken]);

  const allTags = Array.from(new Set(snippets.flatMap((s) => s.tags))).sort();

  async function remove(id: number) {
    try {
      await api.deleteSnippet(id);
      setSnippets((rows) => rows.filter((row) => row.id !== id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Smazání selhalo");
    }
  }

  return (
    <div className="flex h-full flex-col gap-3 overflow-y-auto p-4">
      <input
        className={field}
        placeholder="Hledat v názvech a poznámkách…"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />

      {(activeTag || allTags.length > 0) && (
        <div className="flex flex-wrap gap-1.5">
          {activeTag && (
            <button
              type="button"
              onClick={() => setActiveTag(null)}
              className="rounded-full border border-slate-600 px-2 py-0.5 text-[11px] text-slate-300"
            >
              #{activeTag} ×
            </button>
          )}
          {!activeTag &&
            allTags.map((tag) => (
              <button
                key={tag}
                type="button"
                onClick={() => setActiveTag(tag)}
                className="rounded-full border border-slate-700 px-2 py-0.5 text-[11px] text-slate-400 hover:border-slate-500 hover:text-slate-200"
              >
                #{tag}
              </button>
            ))}
        </div>
      )}

      {error && <p className="text-xs text-signal-error">{error}</p>}

      {!isLoading && snippets.length === 0 && (
        <p className="mt-6 text-center text-xs text-slate-600">
          {search || activeTag
            ? "Nic neodpovídá filtru."
            : "Knihovna je prázdná. Ověřený skript uložte tlačítkem v editoru."}
        </p>
      )}

      <ul className="space-y-2">
        {snippets.map((snippet) => (
          <li
            key={snippet.id}
            className="rounded-md border border-slate-800 bg-slate-900/60 p-3 hover:border-slate-700"
          >
            <div className="flex items-start gap-2">
              <div className="min-w-0 flex-1">
                <h3 className="truncate text-sm text-slate-200">{snippet.title}</h3>
                <p className="mt-0.5 font-mono text-[11px] text-slate-600">
                  {snippet.target} · {new Date(snippet.created_at).toLocaleDateString("cs")}
                </p>
              </div>
              <Button size="sm" variant="ghost" onPress={() => onLoad(snippet)}>
                Otevřít
              </Button>
            </div>

            {snippet.notes && (
              <p className="mt-2 line-clamp-2 text-xs text-slate-400">{snippet.notes}</p>
            )}

            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              {snippet.tags.map((tag) => (
                <Chip key={tag} className="bg-slate-800 text-[11px] text-slate-400">
                  #{tag}
                </Chip>
              ))}
              <button
                type="button"
                onClick={() => void remove(snippet.id)}
                className="ml-auto text-[11px] text-slate-600 hover:text-signal-error"
              >
                Smazat
              </button>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
