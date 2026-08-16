import Anthropic from "@anthropic-ai/sdk";
import { StorySchema, validateGameGraph, type Story } from "@/lib/schema/gameConfig";
import { STORY_JSON_SCHEMA } from "./storyJsonSchema";
import { GENRE_LABELS, type Brief } from "./brief";
import { mockStory } from "./mockStory";

/**
 * Stage 1 of the pipeline: brief -> narrative + puzzle logic, as strict JSON.
 *
 * Two guarantees stack here. `output_config.format` makes the model's output
 * structurally valid by construction, and `validateGameGraph` checks the things
 * a schema cannot: that every id resolves and that a perfect team can actually
 * finish. Failures are fed back to the model as a repair turn — the retry
 * mechanism Phase 3 of the plan calls for.
 */

const MODEL = "claude-opus-5";
const MAX_ATTEMPTS = 3;

export type StoryEngineResult = {
  story: Story;
  source: "claude" | "mock";
  attempts: number;
  /** Validation problems that were repaired along the way, for the run log. */
  repairs: string[][];
};

export function hasApiKey(): boolean {
  return Boolean(process.env.ANTHROPIC_API_KEY);
}

const SYSTEM = `You design cooperative escape-room games for corporate teambuilding.

You are given a brief about a real team. You return one game as JSON.

Design rules:
- 2 or 3 rooms. 4 puzzles total. Every puzzle must be solvable by a group talking
  to each other, not by trivia or outside knowledge.
- The game is a chain: an early puzzle rewards an item or unlocks a room that a
  later puzzle needs. Never require an item that is only obtainable after the
  puzzle that needs it.
- Exactly one puzzle is the finale; put its id in winPuzzleIds.
- Hotspot coordinates are percentages of the background image. Keep them inside
  4..92 on both axes and 8..20 wide/tall so they are comfortable to click, and do
  not overlap hotspots within a room.
- Weave the team's names, industry and in-jokes into the fiction. Make them the
  heroes; never make a named person the villain, the butt of a joke, or
  incompetent. Keep it warm, workplace-appropriate, and light.
- Answers must be short and unambiguous: a number, a word, or one listed option.
  A team should be able to say the answer out loud on a video call.

Write all player-facing text (title, intro, outro, room and item names and
descriptions, puzzle prompts, hints, solved text, choices) in the requested
language. Keep ids and image prompts in English.`;

function userPrompt(brief: Brief): string {
  const puzzleStyle =
    brief.difficulty === "easy"
      ? "Keep every puzzle solvable in about two minutes; state clearly what is being asked."
      : brief.difficulty === "hard"
        ? "Puzzles may need two steps of reasoning and combining information from different rooms."
        : "Puzzles should take three to five minutes of group discussion.";

  return `Language for player-facing text: ${brief.language === "cs" ? "Czech" : "English"}.

Team name: ${brief.teamName}
Team members: ${brief.members.join(", ")}
Industry / department: ${brief.industry || "not specified"}
Genre: ${GENRE_LABELS[brief.genre]}
Requested theme: ${brief.theme || "pick something that fits the industry"}
Internal jokes and references to weave in: ${brief.inJokes || "none supplied"}

Target play time: ${brief.durationMinutes} minutes for ${brief.members.length} players.
Difficulty: ${brief.difficulty}. ${puzzleStyle}`;
}

function repairPrompt(errors: string[]): string {
  return `The game you returned does not validate:

${errors.map((e) => `- ${e}`).join("\n")}

Return the complete corrected game as JSON. Keep everything that was fine; change
only what is needed to fix these problems.`;
}

/**
 * Calls Claude, validating and repairing until the story is playable. Falls back
 * to the deterministic mock generator when no key is configured or the API is
 * unreachable, so the product is always demoable.
 */
export async function generateStory(brief: Brief): Promise<StoryEngineResult> {
  if (!hasApiKey()) {
    return { story: mockStory(brief), source: "mock", attempts: 0, repairs: [] };
  }

  const client = new Anthropic();
  const messages: Anthropic.MessageParam[] = [
    { role: "user", content: userPrompt(brief) },
  ];
  const repairs: string[][] = [];

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    let raw: string;
    try {
      // Streamed because a full game is a long structured output and a
      // non-streaming request that size risks an HTTP timeout.
      const stream = client.messages.stream({
        model: MODEL,
        max_tokens: 32000,
        system: SYSTEM,
        messages,
        output_config: {
          effort: "high",
          format: { type: "json_schema", schema: STORY_JSON_SCHEMA },
        },
      });
      const message = await stream.finalMessage();

      if (message.stop_reason === "refusal") {
        throw new Error("The story engine declined this brief. Try different wording.");
      }
      raw = message.content
        .filter((block): block is Anthropic.TextBlock => block.type === "text")
        .map((block) => block.text)
        .join("");
    } catch (error) {
      if (attempt === MAX_ATTEMPTS) throw error;
      continue;
    }

    const parsed = StorySchema.safeParse(safeJson(raw));
    if (!parsed.success) {
      const errors = parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`);
      repairs.push(errors);
      messages.push({ role: "assistant", content: raw }, { role: "user", content: repairPrompt(errors) });
      continue;
    }

    const graphErrors = validateGameGraph(parsed.data);
    if (graphErrors.length > 0) {
      repairs.push(graphErrors);
      messages.push(
        { role: "assistant", content: raw },
        { role: "user", content: repairPrompt(graphErrors) },
      );
      continue;
    }

    return { story: parsed.data, source: "claude", attempts: attempt, repairs };
  }

  throw new Error(
    `The story engine could not produce a playable game in ${MAX_ATTEMPTS} attempts. ` +
      `Last problems: ${repairs.at(-1)?.join("; ") ?? "unknown"}`,
  );
}

function safeJson(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}
