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

/** crypto.randomUUID only exists in secure contexts (HTTPS / localhost); the demo is also served over plain http://<ip>. */
function newKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = ((b[6] ?? 0) & 0x0f) | 0x40; // RFC 4122 version 4
  b[8] = ((b[8] ?? 0) & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Same seats -> same key, so a network retry replays the same order instead of creating another. */
export function idempotencyKeyFor(sectionId: string, seatIds: string[]): string {
  const fp = fingerprint(sectionId, seatIds);
  const cur = read();
  if (cur && cur.fingerprint === fp) return cur.key;
  const key = newKey();
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
