import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import badwords from './badwords.json' with { type: 'json' };

export const SESSION_COOKIE = 'ss_sid';
export const ADMIN_COOKIE = 'ss_admin';
const ADMIN_TTL_MS = 12 * 3600 * 1000;

export interface SessionData {
  /** buyerId */
  b: string;
  /** nickname */
  n: string;
  /** issued-at (ms) */
  iat: number;
}

const b64 = (s: string | Buffer) => Buffer.from(s).toString('base64url');

function mac(payload: string, secret: string): string {
  return createHmac('sha256', secret).update(payload).digest('base64url');
}

/** `base64url(JSON) + "." + base64url(HMAC_SHA256(secret, payload))` as in spec 8.2. */
export function signPayload(data: object, secret: string): string {
  const payload = b64(JSON.stringify(data));
  return `${payload}.${mac(payload, secret)}`;
}

export function verifyPayload<T>(value: string | undefined, secret: string): T | null {
  if (!value) return null;
  const dot = value.indexOf('.');
  if (dot <= 0 || dot === value.length - 1) return null;
  const payload = value.slice(0, dot);
  const given = Buffer.from(value.slice(dot + 1));
  const expected = Buffer.from(mac(payload, secret));
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null;
  try {
    return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as T;
  } catch {
    return null;
  }
}

export function signSession(data: SessionData, secret: string): string {
  return signPayload(data, secret);
}

export function verifySession(value: string | undefined, secret: string): SessionData | null {
  const d = verifyPayload<SessionData>(value, secret);
  if (!d || typeof d.b !== 'string' || typeof d.n !== 'string' || typeof d.iat !== 'number') return null;
  return d;
}

export function signAdmin(secret: string, now = Date.now()): string {
  return signPayload({ a: 1, iat: now }, secret);
}

export function verifyAdmin(value: string | undefined, secret: string, now = Date.now()): boolean {
  const d = verifyPayload<{ a: number; iat: number }>(value, secret);
  return !!d && d.a === 1 && typeof d.iat === 'number' && now - d.iat < ADMIN_TTL_MS;
}

export const newBuyerId = () => `b_${randomBytes(4).toString('hex')}`;

/** Constant-time string comparison (length-independent timing via HMAC digests). */
export function safeEqual(a: string, b: string): boolean {
  const k = randomBytes(16);
  const ha = createHmac('sha256', k).update(a).digest();
  const hb = createHmac('sha256', k).update(b).digest();
  return timingSafeEqual(ha, hb);
}

const nicknameSchema = z.string().regex(/^[A-Za-z0-9_-]{3,16}$/);

function normalize(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[0@]/g, 'o')
    .replace(/[1!|]/g, 'i')
    .replace(/3/g, 'e')
    .replace(/[4]/g, 'a')
    .replace(/[5$]/g, 's')
    .replace(/7/g, 't');
}

export function hasBadWord(nickname: string): boolean {
  const flat = normalize(nickname).replace(/[_-]/g, '');
  if (badwords.substring.some((w) => flat.includes(w))) return true;
  const tokens = normalize(nickname).split(/[_-]+/);
  return tokens.some((t) => badwords.exact.includes(t));
}

export type NicknameResult = { ok: true; nickname: string } | { ok: false; message: string };

export function validateNickname(input: unknown): NicknameResult {
  const parsed = nicknameSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: 'El apodo debe tener entre 3 y 16 caracteres: letras, números, _ o -.' };
  }
  if (hasBadWord(parsed.data)) return { ok: false, message: 'Ese apodo no está permitido. Elige otro.' };
  return { ok: true, nickname: parsed.data };
}
