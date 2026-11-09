export type FetchLike = typeof fetch;

export class UpstreamError extends Error {}

export interface UpstreamResult {
  status: number;
  contentType: string;
  replay: string | null;
  text: string;
}

export interface CallOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: unknown;
}

/** Thin fetch wrapper with a hard timeout; network failures become UpstreamError. */
export class Upstream {
  constructor(
    private readonly fetchImpl: FetchLike,
    private readonly timeoutMs: number,
  ) {}

  async call(url: string, opts: CallOptions = {}): Promise<UpstreamResult> {
    const headers: Record<string, string> = { accept: 'application/json', ...opts.headers };
    let body: string | undefined;
    if (opts.body !== undefined) {
      body = JSON.stringify(opts.body);
      headers['content-type'] = 'application/json';
    }
    try {
      const res = await this.fetchImpl(url, {
        method: opts.method ?? 'GET',
        headers,
        body,
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      return {
        status: res.status,
        contentType: res.headers.get('content-type') ?? 'application/json',
        replay: res.headers.get('idempotent-replay'),
        text: await res.text(),
      };
    } catch (err) {
      throw new UpstreamError(err instanceof Error ? err.message : String(err));
    }
  }
}
