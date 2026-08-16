import { z } from "zod";

/**
 * The GameConfig is the contract between the AI pipeline and the game engine.
 *
 * Two layers:
 *  - `StorySchema`   — what the LLM produces (narrative + logic, no assets).
 *  - `GameConfigSchema` — what the engine consumes (story + resolved asset URLs).
 *
 * The engine never knows how a game was produced. Anything that validates
 * against GameConfigSchema is playable, whether it came from Claude, a fixture,
 * or a human author.
 */

const id = z
  .string()
  .min(1)
  .max(64)
  .regex(/^[a-z0-9_-]+$/, "ids are lowercase kebab/snake case");

/** Percentage of the viewport, 0–100, so hotspots survive any resolution. */
const percent = z.number().min(0).max(100);

export const HotspotKind = z.enum(["item", "puzzle", "exit", "note"]);
export type HotspotKind = z.infer<typeof HotspotKind>;

export const HotspotSchema = z.object({
  id,
  /** Shown on hover / as the accessible name. */
  label: z.string().min(1).max(80),
  kind: HotspotKind,
  /** Item id, puzzle id, or room id depending on `kind`. Empty for `note`. */
  targetId: z.string().max(64).default(""),
  /** Free text revealed for `note` hotspots (and as flavour elsewhere). */
  text: z.string().max(400).default(""),
  /** Item ids a player must already hold before this hotspot reacts. */
  requiresItemIds: z.array(id).max(8).default([]),
  /** Hidden until some puzzle reveals it. */
  hiddenUntilRevealed: z.boolean().default(false),
  x: percent,
  y: percent,
  width: percent,
  height: percent,
});
export type Hotspot = z.infer<typeof HotspotSchema>;

export const RoomSchema = z.object({
  id,
  name: z.string().min(1).max(80),
  /** Read aloud when the team first enters the room. */
  description: z.string().min(1).max(600),
  /** Prompt handed to the asset engine to draw the background. */
  backgroundPrompt: z.string().min(1).max(400),
  /** Filled in by the compiler. Empty string before assets are generated. */
  backgroundUrl: z.string().default(""),
  /** Rooms other than the first are locked until a puzzle unlocks them. */
  lockedByDefault: z.boolean().default(false),
  hotspots: z.array(HotspotSchema).max(12).default([]),
});
export type Room = z.infer<typeof RoomSchema>;

export const ItemSchema = z.object({
  id,
  name: z.string().min(1).max(60),
  description: z.string().min(1).max(300),
  /** Single emoji used as the placeholder icon glyph. */
  emoji: z.string().min(1).max(8),
  iconPrompt: z.string().min(1).max(200),
  iconUrl: z.string().default(""),
});
export type Item = z.infer<typeof ItemSchema>;

export const PuzzleKind = z.enum(["code", "choice", "order"]);
export type PuzzleKind = z.infer<typeof PuzzleKind>;

export const PuzzleRewardSchema = z.object({
  itemIds: z.array(id).max(6).default([]),
  unlocksRoomIds: z.array(id).max(6).default([]),
  revealsHotspotIds: z.array(id).max(8).default([]),
});
export type PuzzleReward = z.infer<typeof PuzzleRewardSchema>;

export const PuzzleSchema = z.object({
  id,
  title: z.string().min(1).max(80),
  /** The riddle itself, shown in the puzzle modal. */
  prompt: z.string().min(1).max(800),
  kind: PuzzleKind,
  /**
   * `code`   — free text, compared after normalisation.
   * `choice` — one of `choices` must be picked; the answer is the option's text.
   * `order`  — `choices` arranged correctly; the answer is them joined by "|".
   */
  choices: z.array(z.string().min(1).max(120)).max(8).default([]),
  /** Plaintext solution. Stripped before the config reaches the browser. */
  answer: z.string().min(1).max(200),
  hint: z.string().min(1).max(300),
  solvedText: z.string().min(1).max(400),
  requiresItemIds: z.array(id).max(8).default([]),
  reward: PuzzleRewardSchema.default({
    itemIds: [],
    unlocksRoomIds: [],
    revealsHotspotIds: [],
  }),
});
export type Puzzle = z.infer<typeof PuzzleSchema>;

/** What the Story Engine (LLM) must return. Assets are still empty here. */
export const StorySchema = z.object({
  title: z.string().min(1).max(120),
  /** Briefing shown in the lobby. */
  intro: z.string().min(1).max(1200),
  /** Shown on the end screen when the team escapes. */
  outro: z.string().min(1).max(800),
  theme: z.string().min(1).max(80),
  rooms: z.array(RoomSchema).min(1).max(4),
  items: z.array(ItemSchema).max(16).default([]),
  puzzles: z.array(PuzzleSchema).min(1).max(8),
  /** Solving these ends the game. Usually the last puzzle only. */
  winPuzzleIds: z.array(id).min(1).max(8),
});
export type Story = z.infer<typeof StorySchema>;

export const GameConfigSchema = StorySchema.extend({
  id: z.string().min(1),
  /** Format version — bump when the engine's expectations change. */
  version: z.literal(1),
  timeLimitSeconds: z.number().int().min(300).max(5400),
  minPlayers: z.number().int().min(1).max(10),
  maxPlayers: z.number().int().min(1).max(10),
  createdAt: z.string(),
  /** Echo of the creator's form input, for the dashboard and analytics. */
  brief: z
    .object({
      teamName: z.string().default(""),
      members: z.array(z.string()).default([]),
      industry: z.string().default(""),
      genre: z.string().default(""),
      theme: z.string().default(""),
      inJokes: z.string().default(""),
    })
    .default({
      teamName: "",
      members: [],
      industry: "",
      genre: "",
      theme: "",
      inJokes: "",
    }),
});
export type GameConfig = z.infer<typeof GameConfigSchema>;

/**
 * The shape actually shipped to players: identical to GameConfig except every
 * `answer` is replaced by a SHA-256 hash of its normalised form. Players can
 * inspect the payload all they like without finding the solutions.
 */
export const PublicPuzzleSchema = PuzzleSchema.omit({ answer: true }).extend({
  answerHash: z.string().length(64),
});
export type PublicPuzzle = z.infer<typeof PublicPuzzleSchema>;

export const PublicGameConfigSchema = GameConfigSchema.omit({
  puzzles: true,
}).extend({
  puzzles: z.array(PublicPuzzleSchema),
});
export type PublicGameConfig = z.infer<typeof PublicGameConfigSchema>;

/**
 * Structural checks Zod can't express: every reference must resolve, and the
 * game must be finishable. The pipeline retries generation when this fails.
 */
export function validateGameGraph(story: Story): string[] {
  const errors: string[] = [];
  const roomIds = new Set(story.rooms.map((r) => r.id));
  const itemIds = new Set(story.items.map((i) => i.id));
  const puzzleIds = new Set(story.puzzles.map((p) => p.id));
  const hotspotIds = new Set(
    story.rooms.flatMap((r) => r.hotspots.map((h) => h.id)),
  );

  if (story.rooms.filter((r) => !r.lockedByDefault).length === 0) {
    errors.push("at least one room must be unlocked at the start");
  }

  for (const room of story.rooms) {
    for (const hotspot of room.hotspots) {
      const where = `room "${room.id}" hotspot "${hotspot.id}"`;
      if (hotspot.kind === "item" && !itemIds.has(hotspot.targetId)) {
        errors.push(`${where} points at unknown item "${hotspot.targetId}"`);
      }
      if (hotspot.kind === "puzzle" && !puzzleIds.has(hotspot.targetId)) {
        errors.push(`${where} points at unknown puzzle "${hotspot.targetId}"`);
      }
      if (hotspot.kind === "exit" && !roomIds.has(hotspot.targetId)) {
        errors.push(`${where} points at unknown room "${hotspot.targetId}"`);
      }
      for (const need of hotspot.requiresItemIds) {
        if (!itemIds.has(need)) {
          errors.push(`${where} requires unknown item "${need}"`);
        }
      }
    }
  }

  for (const puzzle of story.puzzles) {
    const where = `puzzle "${puzzle.id}"`;
    if (puzzle.kind !== "code" && puzzle.choices.length < 2) {
      errors.push(`${where} is a ${puzzle.kind} puzzle but has no choices`);
    }
    if (puzzle.kind === "choice" && !puzzle.choices.includes(puzzle.answer)) {
      errors.push(`${where} answer is not one of its choices`);
    }
    for (const need of puzzle.requiresItemIds) {
      if (!itemIds.has(need)) errors.push(`${where} requires unknown item "${need}"`);
    }
    for (const gain of puzzle.reward.itemIds) {
      if (!itemIds.has(gain)) errors.push(`${where} rewards unknown item "${gain}"`);
    }
    for (const room of puzzle.reward.unlocksRoomIds) {
      if (!roomIds.has(room)) errors.push(`${where} unlocks unknown room "${room}"`);
    }
    for (const spot of puzzle.reward.revealsHotspotIds) {
      if (!hotspotIds.has(spot)) {
        errors.push(`${where} reveals unknown hotspot "${spot}"`);
      }
    }
  }

  for (const winner of story.winPuzzleIds) {
    if (!puzzleIds.has(winner)) {
      errors.push(`winPuzzleIds references unknown puzzle "${winner}"`);
    }
  }

  const reachable = reachablePuzzleIds(story);
  for (const winner of story.winPuzzleIds) {
    if (!reachable.has(winner)) {
      errors.push(`winning puzzle "${winner}" is unreachable — the game cannot be finished`);
    }
  }

  return errors;
}

/**
 * Forward simulation of a perfect team: repeatedly collect everything reachable
 * and solve everything whose item requirements are met, until nothing new
 * happens. Catches deadlocks where a puzzle needs an item locked behind itself.
 */
function reachablePuzzleIds(story: Story): Set<string> {
  const openRooms = new Set(
    story.rooms.filter((r) => !r.lockedByDefault).map((r) => r.id),
  );
  const revealed = new Set<string>();
  const heldItems = new Set<string>();
  const solved = new Set<string>();

  for (let pass = 0; pass < story.puzzles.length + story.rooms.length + 1; pass++) {
    let progressed = false;

    for (const room of story.rooms) {
      if (!openRooms.has(room.id)) continue;
      for (const hotspot of room.hotspots) {
        if (hotspot.hiddenUntilRevealed && !revealed.has(hotspot.id)) continue;
        if (!hotspot.requiresItemIds.every((i) => heldItems.has(i))) continue;
        // Exits are navigation only: at runtime a room opens when a puzzle
        // unlocks it, so the simulation must not treat a door as a key.
        if (hotspot.kind === "item" && !heldItems.has(hotspot.targetId)) {
          heldItems.add(hotspot.targetId);
          progressed = true;
        }
      }
    }

    for (const puzzle of story.puzzles) {
      if (solved.has(puzzle.id)) continue;
      const inOpenRoom = story.rooms.some(
        (r) =>
          openRooms.has(r.id) &&
          r.hotspots.some(
            (h) =>
              h.kind === "puzzle" &&
              h.targetId === puzzle.id &&
              (!h.hiddenUntilRevealed || revealed.has(h.id)),
          ),
      );
      if (!inOpenRoom) continue;
      if (!puzzle.requiresItemIds.every((i) => heldItems.has(i))) continue;

      solved.add(puzzle.id);
      progressed = true;
      puzzle.reward.itemIds.forEach((i) => heldItems.add(i));
      puzzle.reward.unlocksRoomIds.forEach((r) => openRooms.add(r));
      puzzle.reward.revealsHotspotIds.forEach((h) => revealed.add(h));
    }

    if (!progressed) break;
  }

  return solved;
}
