export type View = 'public' | 'stage';

export interface LiveConnection {
  id: number;
  buyerId: string | null;
  view: View;
  write(chunk: string): void;
  /** Bytes queued but not yet flushed to the socket. */
  queued(): number;
  close(): void;
}

export interface SeatDiff {
  type?: 'seat';
  concertId?: string;
  sectionId?: string;
  seatId: string;
  state: string;
  version: number;
  at?: number;
}

export const MAX_QUEUED_BYTES = 1024 * 1024;

export const sseFrame = (event: string, data: unknown): string =>
  `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

export interface HubOptions {
  maxConnections: number;
  seatFlushMs: number;
  pingMs: number;
}

/**
 * Registry of SSE connections plus the fan-out rules of spec 8.2:
 * seat diffs are coalesced per seat (highest version wins) and flushed on a timer,
 * order/notify go only to the owning buyer, feed only to stage views.
 */
export class Hub {
  private readonly conns = new Set<LiveConnection>();
  private readonly byBuyer = new Map<string, Set<LiveConnection>>();
  private pending = new Map<string, SeatDiff>();
  private flushTimer?: NodeJS.Timeout;
  private pingTimer?: NodeJS.Timeout;

  constructor(private readonly opts: HubOptions) {}

  get size(): number {
    return this.conns.size;
  }

  stageCount(): number {
    let n = 0;
    for (const c of this.conns) if (c.view === 'stage') n++;
    return n;
  }

  start(): void {
    this.flushTimer = setInterval(() => this.flushSeats(), this.opts.seatFlushMs);
    this.pingTimer = setInterval(() => this.ping(), this.opts.pingMs);
    this.flushTimer.unref();
    this.pingTimer.unref();
  }

  stop(): void {
    clearInterval(this.flushTimer);
    clearInterval(this.pingTimer);
    for (const c of [...this.conns]) {
      this.remove(c);
      c.close();
    }
  }

  /** Returns false when the connection limit has been reached. */
  add(conn: LiveConnection): boolean {
    if (this.conns.size >= this.opts.maxConnections) return false;
    this.conns.add(conn);
    if (conn.buyerId) {
      let set = this.byBuyer.get(conn.buyerId);
      if (!set) this.byBuyer.set(conn.buyerId, (set = new Set()));
      set.add(conn);
    }
    return true;
  }

  remove(conn: LiveConnection): void {
    this.conns.delete(conn);
    if (conn.buyerId) {
      const set = this.byBuyer.get(conn.buyerId);
      set?.delete(conn);
      if (set && set.size === 0) this.byBuyer.delete(conn.buyerId);
    }
  }

  /** Entry point for decoded `ui.events` messages. */
  handleUiEvent(msg: Record<string, unknown>): void {
    switch (msg.type) {
      case 'seat':
        this.queueSeat(msg as unknown as SeatDiff);
        break;
      case 'sale':
        this.broadcast('sale', { status: msg.status });
        break;
      case 'reset':
        // Pending diffs belong to the previous run of the demo.
        this.pending.clear();
        this.broadcast('reset', {});
        break;
      case 'order':
      case 'notify':
        if (typeof msg.buyerId === 'string') this.sendToBuyer(msg.buyerId, 'me', msg);
        break;
      case 'feed':
        this.sendToStage('feed', msg);
        break;
      default:
        break;
    }
  }

  queueSeat(diff: SeatDiff): void {
    if (typeof diff.seatId !== 'string' || typeof diff.version !== 'number') return;
    const cur = this.pending.get(diff.seatId);
    if (!cur || diff.version > cur.version) this.pending.set(diff.seatId, diff);
  }

  flushSeats(): void {
    if (this.pending.size === 0) return;
    const diffs = [...this.pending.values()].map((d) => ({
      seatId: d.seatId,
      sectionId: d.sectionId,
      state: d.state,
      version: d.version,
    }));
    this.pending = new Map();
    this.broadcast('seat', diffs);
  }

  ping(): void {
    this.each(() => true, ': ping\n\n');
  }

  broadcast(event: string, data: unknown): void {
    this.each(() => true, sseFrame(event, data));
  }

  sendToStage(event: string, data: unknown): void {
    this.each((c) => c.view === 'stage', sseFrame(event, data));
  }

  sendToBuyer(buyerId: string, event: string, data: unknown): void {
    const set = this.byBuyer.get(buyerId);
    if (!set) return;
    const frame = sseFrame(event, data);
    for (const c of [...set]) this.deliver(c, frame);
  }

  private each(pred: (c: LiveConnection) => boolean, frame: string): void {
    for (const c of [...this.conns]) if (pred(c)) this.deliver(c, frame);
  }

  private deliver(c: LiveConnection, frame: string): void {
    if (c.queued() > MAX_QUEUED_BYTES) {
      // Slow consumer: drop it instead of buffering without bound.
      this.remove(c);
      c.close();
      return;
    }
    try {
      c.write(frame);
    } catch {
      this.remove(c);
      c.close();
    }
  }
}
