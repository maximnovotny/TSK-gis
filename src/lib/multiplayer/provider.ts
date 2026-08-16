import type { GameAction, GameState } from "@/lib/engine/state";

/**
 * The seam between the game engine and whatever syncs it.
 *
 * Phase 2 of the plan swaps the implementation for Liveblocks (or Supabase
 * Realtime) without touching a component: the engine only ever sees this
 * interface. A Liveblocks provider maps `dispatch` onto a broadcast event and
 * `onState` onto the storage/presence subscription.
 */
export interface SyncProvider {
  /** Broadcast an action to every peer, including the sender. */
  dispatch(action: GameAction): void;
  /** Called whenever the local copy of the shared state changes. */
  subscribe(listener: (state: GameState) => void): () => void;
  /** Current local snapshot. */
  getState(): GameState;
  disconnect(): void;
}

export type ProviderOptions = {
  roomId: string;
  selfId: string;
  initial: GameState;
  /** Deterministic fold; identical on every peer. */
  reduce: (state: GameState, action: GameAction) => GameState;
};
