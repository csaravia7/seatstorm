import type { Config } from '../config.js';
import type { Upstream } from '../clients/upstream.js';
import type { Hub } from './hub.js';

/** Polls seatmap-query's leaderboard every 2 s, only while a stage view is connected. */
export class LeaderboardPoller {
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly cfg: Config,
    private readonly up: Upstream,
    private readonly hub: Hub,
  ) {}

  start(): void {
    this.timer = setInterval(() => void this.tick(), this.cfg.leaderboardMs);
    this.timer.unref();
  }

  stop(): void {
    clearInterval(this.timer);
  }

  async tick(): Promise<void> {
    if (this.hub.stageCount() === 0) return;
    try {
      const url = `${this.cfg.queryUrl}/api/leaderboard?concertId=${this.cfg.defaultConcertId}&limit=10`;
      const res = await this.up.call(url);
      if (res.status === 200) this.hub.sendToStage('leaderboard', JSON.parse(res.text));
    } catch {
      // Best effort: the next tick retries.
    }
  }
}
