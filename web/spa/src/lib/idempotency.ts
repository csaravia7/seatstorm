const KEY = 'ss.idem';

interface Stored {
  fingerprint: string;
  key: string;
}

const fingerprint = (sectionId: string, seatIds: string[]) => `${sectionId}|${[...seatIds].sort().join(',')}`;

function read(): Stored | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Stored) : null;
  } catch {
    return null;
  }
}

/** Same seats -> same key, so a network retry replays the same order instead of creating another. */
export function idempotencyKeyFor(sectionId: string, seatIds: string[]): string {
  const fp = fingerprint(sectionId, seatIds);
  const cur = read();
  if (cur && cur.fingerprint === fp) return cur.key;
  const key = crypto.randomUUID();
  try {
    sessionStorage.setItem(KEY, JSON.stringify({ fingerprint: fp, key } satisfies Stored));
  } catch {
    // storage unavailable: the key still works for this attempt
  }
  return key;
}

export function clearIdempotencyKey(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // ignore
  }
}
