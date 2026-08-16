/**
 * JSON Schema handed to the model via `output_config.format`, so the Story
 * Engine can only emit shapes the game engine understands. It mirrors
 * `StorySchema` in src/lib/schema/gameConfig.ts — Zod still validates the
 * result afterwards, because structured outputs guarantee the shape, not the
 * semantics (ids resolving, the game being finishable).
 *
 * Deliberately free of the constraints structured outputs does not support
 * (minLength/maxLength/minimum/maximum); those are enforced by Zod on the way in.
 */

const hotspot = {
  type: "object",
  properties: {
    id: { type: "string", description: "lowercase, kebab-case, unique in the game" },
    label: { type: "string", description: "short label shown on hover" },
    kind: { type: "string", enum: ["item", "puzzle", "exit", "note"] },
    targetId: {
      type: "string",
      description:
        "item id for kind=item, puzzle id for kind=puzzle, room id for kind=exit, empty string for kind=note",
    },
    text: { type: "string", description: "flavour text; the whole payload for kind=note" },
    requiresItemIds: { type: "array", items: { type: "string" } },
    hiddenUntilRevealed: {
      type: "boolean",
      description: "true if a puzzle reward must reveal this hotspot first",
    },
    x: { type: "number", description: "left edge, 0-100 percent of the background" },
    y: { type: "number", description: "top edge, 0-100 percent" },
    width: { type: "number", description: "0-100 percent; 8-20 is a comfortable click target" },
    height: { type: "number", description: "0-100 percent" },
  },
  required: [
    "id",
    "label",
    "kind",
    "targetId",
    "text",
    "requiresItemIds",
    "hiddenUntilRevealed",
    "x",
    "y",
    "width",
    "height",
  ],
  additionalProperties: false,
} as const;

const room = {
  type: "object",
  properties: {
    id: { type: "string" },
    name: { type: "string" },
    description: { type: "string", description: "2-3 sentences setting the scene" },
    backgroundPrompt: {
      type: "string",
      description: "image prompt for the room background, in English, no text in image",
    },
    lockedByDefault: { type: "boolean" },
    hotspots: { type: "array", items: hotspot },
  },
  required: ["id", "name", "description", "backgroundPrompt", "lockedByDefault", "hotspots"],
  additionalProperties: false,
} as const;

const item = {
  type: "object",
  properties: {
    id: { type: "string" },
    name: { type: "string" },
    description: { type: "string" },
    emoji: { type: "string", description: "exactly one emoji representing the item" },
    iconPrompt: { type: "string", description: "image prompt for the item icon, in English" },
  },
  required: ["id", "name", "description", "emoji", "iconPrompt"],
  additionalProperties: false,
} as const;

const puzzle = {
  type: "object",
  properties: {
    id: { type: "string" },
    title: { type: "string" },
    prompt: { type: "string", description: "the riddle as the players read it" },
    kind: { type: "string", enum: ["code", "choice", "order"] },
    choices: {
      type: "array",
      items: { type: "string" },
      description:
        "empty for kind=code; 3-5 options for kind=choice; the items to sort for kind=order",
    },
    answer: {
      type: "string",
      description:
        "kind=code: the literal solution. kind=choice: must equal one of choices verbatim. kind=order: the choices in correct order joined by |",
    },
    hint: { type: "string" },
    solvedText: { type: "string", description: "what the team reads on success" },
    requiresItemIds: { type: "array", items: { type: "string" } },
    reward: {
      type: "object",
      properties: {
        itemIds: { type: "array", items: { type: "string" } },
        unlocksRoomIds: { type: "array", items: { type: "string" } },
        revealsHotspotIds: { type: "array", items: { type: "string" } },
      },
      required: ["itemIds", "unlocksRoomIds", "revealsHotspotIds"],
      additionalProperties: false,
    },
  },
  required: [
    "id",
    "title",
    "prompt",
    "kind",
    "choices",
    "answer",
    "hint",
    "solvedText",
    "requiresItemIds",
    "reward",
  ],
  additionalProperties: false,
} as const;

export const STORY_JSON_SCHEMA = {
  type: "object",
  properties: {
    title: { type: "string" },
    intro: { type: "string", description: "the briefing the team reads in the lobby" },
    outro: { type: "string", description: "the payoff after the last puzzle" },
    theme: { type: "string" },
    rooms: { type: "array", items: room },
    items: { type: "array", items: item },
    puzzles: { type: "array", items: puzzle },
    winPuzzleIds: {
      type: "array",
      items: { type: "string" },
      description: "solving all of these ends the game; usually just the final puzzle",
    },
  },
  required: ["title", "intro", "outro", "theme", "rooms", "items", "puzzles", "winPuzzleIds"],
  additionalProperties: false,
} as const;
