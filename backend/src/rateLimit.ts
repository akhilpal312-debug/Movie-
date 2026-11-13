/**
 * Sliding-window limiter keyed by installation id.
 *
 * The goal is not merely abuse prevention: a runaway client that opens sessions in a
 * loop would burn the owner's Gemini quota, so the broker refuses instead of minting.
 */
export class RateLimiter {
  private readonly hits = new Map<string, number[]>();

  constructor(
    private readonly limitPerMinute: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Records a request and reports whether it is allowed. */
  tryConsume(key: string): boolean {
    const now = this.now();
    const windowStart = now - 60_000;
    const existing = (this.hits.get(key) ?? []).filter((stamp) => stamp > windowStart);

    if (existing.length >= this.limitPerMinute) {
      this.hits.set(key, existing);
      return false;
    }
    existing.push(now);
    this.hits.set(key, existing);
    this.prune(windowStart);
    return true;
  }

  /** Seconds a caller should wait, for the Retry-After header. */
  retryAfterSeconds(key: string): number {
    const stamps = this.hits.get(key) ?? [];
    const oldest = stamps[0];
    if (oldest === undefined) return 1;
    return Math.max(1, Math.ceil((oldest + 60_000 - this.now()) / 1000));
  }

  private prune(windowStart: number): void {
    if (this.hits.size < 500) return;
    for (const [key, stamps] of this.hits) {
      if (stamps.every((stamp) => stamp <= windowStart)) this.hits.delete(key);
    }
  }
}
