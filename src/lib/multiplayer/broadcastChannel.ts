import type { GameAction, GameState } from "@/lib/engine/state";
import type { ProviderOptions, SyncProvider } from "./provider";

/**
 * Zero-infrastructure sync over BroadcastChannel: every browser tab on the same
 * origin sharing a room id sees the same game. Good enough to build and demo the
 * whole cooperative loop (shared inventory, live cursors, one team, one state)
 * before any Liveblocks key exists.
 *
 * Limits, stated plainly: same-origin same-device only, no persistence, and
 * conflict resolution is "last action wins" — a late joiner adopts the first
 * snapshot it is offered. Real remote play is the Liveblocks provider, which
 * implements the same `SyncProvider` interface.
 */

type Envelope =
  | { kind: "action"; from: string; action: GameAction }
  | { kind: "snapshot-request"; from: string }
  | { kind: "snapshot"; from: string; to: string; state: GameState };

export class BroadcastChannelProvider implements SyncProvider {
  private channel: BroadcastChannel | null;
  private state: GameState;
  private listeners = new Set<(state: GameState) => void>();
  private readonly selfId: string;
  private readonly reduce: (state: GameState, action: GameAction) => GameState;
  private adoptedSnapshot = false;

  constructor({ roomId, selfId, initial, reduce }: ProviderOptions) {
    this.selfId = selfId;
    this.state = initial;
    this.reduce = reduce;
    this.channel =
      typeof BroadcastChannel === "undefined"
        ? null
        : new BroadcastChannel(`teamquest:${roomId}`);

    if (this.channel) {
      this.channel.onmessage = (event: MessageEvent<Envelope>) =>
        this.receive(event.data);
      this.channel.postMessage({ kind: "snapshot-request", from: selfId } satisfies Envelope);
    }
  }

  private receive(message: Envelope) {
    if (message.kind === "action") {
      if (message.from === this.selfId) return; // already applied locally
      this.setState(this.reduce(this.state, message.action));
      return;
    }

    if (message.kind === "snapshot-request") {
      if (message.from === this.selfId) return;
      this.channel?.postMessage({
        kind: "snapshot",
        from: this.selfId,
        to: message.from,
        state: this.state,
      } satisfies Envelope);
      return;
    }

    if (message.kind === "snapshot") {
      if (message.to !== this.selfId || this.adoptedSnapshot) return;
      this.adoptedSnapshot = true;
      // Keep our own player record — the peer's snapshot predates our join.
      const self = this.state.players[this.selfId];
      const merged: GameState = self
        ? { ...message.state, players: { ...message.state.players, [self.id]: self } }
        : message.state;
      this.setState(merged);
    }
  }

  private setState(next: GameState) {
    this.state = next;
    this.listeners.forEach((listener) => listener(next));
  }

  dispatch(action: GameAction) {
    this.setState(this.reduce(this.state, action));
    this.channel?.postMessage({ kind: "action", from: this.selfId, action } satisfies Envelope);
  }

  subscribe(listener: (state: GameState) => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getState() {
    return this.state;
  }

  disconnect() {
    this.channel?.close();
    this.channel = null;
    this.listeners.clear();
  }
}
