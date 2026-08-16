/**
 * Answers travel to the browser as hashes, never plaintext, so the network tab
 * is not a walkthrough. Both server and client hash with the same normalisation,
 * so verification stays local (no round-trip per guess) while staying opaque.
 *
 * This is deterrence, not security: a determined player can still brute-force a
 * three-digit code. That is fine — the stake is a teambuilding game, and the
 * alternative (server-side checking) costs a request on every keystroke-length
 * guess and breaks offline play.
 */

const DIACRITICS = /[̀-ͯ]/g;

export function normalizeAnswer(raw: string): string {
  return raw
    .normalize("NFD")
    .replace(DIACRITICS, "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim();
}

function subtle(): SubtleCrypto {
  const c = globalThis.crypto;
  if (!c?.subtle) {
    throw new Error("Web Crypto is unavailable; cannot hash puzzle answers");
  }
  return c.subtle;
}

export async function hashAnswer(raw: string): Promise<string> {
  const bytes = new TextEncoder().encode(normalizeAnswer(raw));
  const digest = await subtle().digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function checkAnswer(
  guess: string,
  answerHash: string,
): Promise<boolean> {
  if (!guess.trim()) return false;
  return (await hashAnswer(guess)) === answerHash;
}

/** `order` puzzles compare a sequence; join with a separator players can't type. */
export const ORDER_SEPARATOR = "|";

export function serializeOrder(values: string[]): string {
  return values.join(ORDER_SEPARATOR);
}
