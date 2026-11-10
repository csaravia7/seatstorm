import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearIdempotencyKey, idempotencyKeyFor } from '../src/lib/idempotency';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('idempotencyKeyFor', () => {
  beforeEach(() => clearIdempotencyKey());
  afterEach(() => vi.unstubAllGlobals());

  it('reuses the key for the same seats and changes it for different seats', () => {
    const a = idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A01']);
    expect(idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A01'])).toBe(a);
    expect(idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A02'])).not.toBe(a);
  });

  it('works over plain http, where crypto.randomUUID does not exist (insecure context)', () => {
    vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) });
    const key = idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A01']);
    expect(key).toMatch(UUID_V4);
    expect(idempotencyKeyFor('PLATEA-A', ['PLATEA-A:A01'])).toBe(key);
  });
});
