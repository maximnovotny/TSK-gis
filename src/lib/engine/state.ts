import type { Hotspot, PublicGameConfig, PublicPuzzle } from "@/lib/schema/gameConfig";

/**
 * The entire shared game state, and a pure reducer over it.
 *
 * Every mutation goes through `gameReducer`. That is what makes multiplayer
 * cheap: peers broadcast actions, each peer folds them into its own copy, and
 * because the reducer is deterministic they converge. Swapping the transport
 * (BroadcastChannel today, Liveblocks tomorrow) changes nothing here.
 */

export type Player = {
  id: string;
  name: string;
  color: string;
  /** Cursor position in viewport percentages; null before first move. */
  cursor: { x: number; y: number } | null;
  isHost: boolean;
  joinedAt: number;
};

export type GameStatus = "lobby" | "running" | "escaped" | "timeout";

export type LogEntry = {
  id: string;
  at: number;
  playerId: string;
  text: string;
};

export type GameState = {
  status: GameStatus;
  players: Record<string, Player>;
  /** Room the whole team is looking at — this game moves as one group. */
  currentRoomId: string;
  unlockedRoomIds: string[];
  /** Shared inventory: picked up by one player, visible to all. */
  inventoryItemIds: string[];
  /** Hotspots consumed (an item taken from the table is gone for everyone). */
  takenHotspotIds: string[];
  revealedHotspotIds: string[];
  solvedPuzzleIds: string[];
  /** Who solved what — the end screen's "MVP" stat. */
  solvedBy: Record<string, string>;
  hintsUsedPuzzleIds: string[];
  startedAt: number | null;
  endedAt: number | null;
  log: LogEntry[];
};

export type GameAction =
  | { type: "player/join"; player: Player }
  | { type: "player/leave"; playerId: string }
  | { type: "player/cursor"; playerId: string; x: number; y: number }
  | { type: "game/start"; at: number }
  | { type: "game/timeout"; at: number }
  | { type: "room/enter"; roomId: string; playerId: string }
  | { type: "hotspot/take"; hotspotId: string; itemId: string; playerId: string }
  | { type: "puzzle/solve"; puzzleId: string; playerId: string; at: number }
  | { type: "puzzle/hint"; puzzleId: string; playerId: string }
  | { type: "state/replace"; state: GameState };

export function initialState(config: PublicGameConfig): GameState {
  const firstOpen = config.rooms.find((r) => !r.lockedByDefault) ?? config.rooms[0];
  return {
    status: "lobby",
    players: {},
    currentRoomId: firstOpen.id,
    unlockedRoomIds: config.rooms.filter((r) => !r.lockedByDefault).map((r) => r.id),
    inventoryItemIds: [],
    takenHotspotIds: [],
    revealedHotspotIds: [],
    solvedPuzzleIds: [],
    solvedBy: {},
    hintsUsedPuzzleIds: [],
    startedAt: null,
    endedAt: null,
    log: [],
  };
}

const MAX_LOG = 60;

function withLog(
  state: GameState,
  playerId: string,
  text: string,
  at: number,
): LogEntry[] {
  const entry: LogEntry = { id: `${at}-${playerId}-${state.log.length}`, at, playerId, text };
  return [...state.log, entry].slice(-MAX_LOG);
}

function unique(values: string[]): string[] {
  return Array.from(new Set(values));
}

export function gameReducer(
  state: GameState,
  action: GameAction,
  config: PublicGameConfig,
): GameState {
  switch (action.type) {
    case "state/replace":
      return action.state;

    case "player/join": {
      if (state.players[action.player.id]) return state;
      const isFirst = Object.keys(state.players).length === 0;
      const player = { ...action.player, isHost: action.player.isHost || isFirst };
      return {
        ...state,
        players: { ...state.players, [player.id]: player },
        log: withLog(state, player.id, `${player.name} joined`, player.joinedAt),
      };
    }

    case "player/leave": {
      const leaving = state.players[action.playerId];
      if (!leaving) return state;
      const players = { ...state.players };
      delete players[action.playerId];
      // Hosting passes to whoever has been here longest, so the game can start.
      if (leaving.isHost) {
        const heir = Object.values(players).sort((a, b) => a.joinedAt - b.joinedAt)[0];
        if (heir) players[heir.id] = { ...heir, isHost: true };
      }
      return { ...state, players };
    }

    case "player/cursor": {
      const player = state.players[action.playerId];
      if (!player) return state;
      return {
        ...state,
        players: {
          ...state.players,
          [action.playerId]: { ...player, cursor: { x: action.x, y: action.y } },
        },
      };
    }

    case "game/start":
      if (state.status !== "lobby") return state;
      return { ...state, status: "running", startedAt: action.at };

    case "game/timeout":
      if (state.status !== "running") return state;
      return { ...state, status: "timeout", endedAt: action.at };

    case "room/enter": {
      if (!state.unlockedRoomIds.includes(action.roomId)) return state;
      if (state.currentRoomId === action.roomId) return state;
      const room = config.rooms.find((r) => r.id === action.roomId);
      return {
        ...state,
        currentRoomId: action.roomId,
        log: withLog(
          state,
          action.playerId,
          `moved the team to ${room?.name ?? action.roomId}`,
          Date.now(),
        ),
      };
    }

    case "hotspot/take": {
      if (state.status !== "running") return state;
      if (state.takenHotspotIds.includes(action.hotspotId)) return state;
      const item = config.items.find((i) => i.id === action.itemId);
      return {
        ...state,
        takenHotspotIds: [...state.takenHotspotIds, action.hotspotId],
        inventoryItemIds: unique([...state.inventoryItemIds, action.itemId]),
        log: withLog(
          state,
          action.playerId,
          `picked up ${item?.name ?? action.itemId}`,
          Date.now(),
        ),
      };
    }

    case "puzzle/solve": {
      if (state.status !== "running") return state;
      if (state.solvedPuzzleIds.includes(action.puzzleId)) return state;
      const puzzle = config.puzzles.find((p) => p.id === action.puzzleId);
      if (!puzzle) return state;

      const solvedPuzzleIds = [...state.solvedPuzzleIds, puzzle.id];
      const escaped = config.winPuzzleIds.every((id) => solvedPuzzleIds.includes(id));

      return {
        ...state,
        solvedPuzzleIds,
        solvedBy: { ...state.solvedBy, [puzzle.id]: action.playerId },
        inventoryItemIds: unique([...state.inventoryItemIds, ...puzzle.reward.itemIds]),
        unlockedRoomIds: unique([
          ...state.unlockedRoomIds,
          ...puzzle.reward.unlocksRoomIds,
        ]),
        revealedHotspotIds: unique([
          ...state.revealedHotspotIds,
          ...puzzle.reward.revealsHotspotIds,
        ]),
        status: escaped ? "escaped" : state.status,
        endedAt: escaped ? action.at : state.endedAt,
        log: withLog(state, action.playerId, `solved “${puzzle.title}”`, action.at),
      };
    }

    case "puzzle/hint": {
      if (state.hintsUsedPuzzleIds.includes(action.puzzleId)) return state;
      const puzzle = config.puzzles.find((p) => p.id === action.puzzleId);
      return {
        ...state,
        hintsUsedPuzzleIds: [...state.hintsUsedPuzzleIds, action.puzzleId],
        log: withLog(
          state,
          action.playerId,
          `took a hint for “${puzzle?.title ?? action.puzzleId}”`,
          Date.now(),
        ),
      };
    }

    default:
      return state;
  }
}

/* ------------------------------ selectors ------------------------------ */

export function visibleHotspots(state: GameState, config: PublicGameConfig): Hotspot[] {
  const room = config.rooms.find((r) => r.id === state.currentRoomId);
  if (!room) return [];
  return room.hotspots.filter((h) => {
    if (state.takenHotspotIds.includes(h.id)) return false;
    if (h.hiddenUntilRevealed && !state.revealedHotspotIds.includes(h.id)) return false;
    return true;
  });
}

export function isHotspotUnlocked(state: GameState, hotspot: Hotspot): boolean {
  return hotspot.requiresItemIds.every((i) => state.inventoryItemIds.includes(i));
}

/** Open puzzles in the current room — drives the shared task list. */
export function openTasks(
  state: GameState,
  config: PublicGameConfig,
): { puzzle: PublicPuzzle; roomName: string }[] {
  return config.puzzles
    .filter((p) => !state.solvedPuzzleIds.includes(p.id))
    .map((puzzle) => {
      const room = config.rooms.find((r) =>
        r.hotspots.some((h) => h.kind === "puzzle" && h.targetId === puzzle.id),
      );
      return { puzzle, roomName: room?.name ?? "" , roomId: room?.id ?? "" };
    })
    .filter((t) => state.unlockedRoomIds.includes(t.roomId))
    .map(({ puzzle, roomName }) => ({ puzzle, roomName }));
}

export function secondsRemaining(state: GameState, config: PublicGameConfig, now: number): number {
  if (!state.startedAt) return config.timeLimitSeconds;
  const end = state.endedAt ?? now;
  return Math.max(0, config.timeLimitSeconds - Math.floor((end - state.startedAt) / 1000));
}

export function formatClock(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/** End-screen stats: who solved most, how long it took, hints burned. */
export function summarize(state: GameState, config: PublicGameConfig) {
  const perPlayer = Object.values(state.players).map((player) => ({
    player,
    solved: Object.values(state.solvedBy).filter((id) => id === player.id).length,
  }));
  perPlayer.sort((a, b) => b.solved - a.solved);
  const elapsed =
    state.startedAt && state.endedAt
      ? Math.floor((state.endedAt - state.startedAt) / 1000)
      : config.timeLimitSeconds;
  return {
    perPlayer,
    mvp: perPlayer[0]?.solved ? perPlayer[0].player : null,
    elapsedSeconds: elapsed,
    solvedCount: state.solvedPuzzleIds.length,
    puzzleCount: config.puzzles.length,
    hintsUsed: state.hintsUsedPuzzleIds.length,
  };
}
