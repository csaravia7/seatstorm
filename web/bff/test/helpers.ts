import { buildApp } from '../src/app.js';
import { loadConfig, type Config } from '../src/config.js';

export interface Call {
  url: string;
  init: RequestInit;
}

export function fakeFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const impl = (async (url: string | URL | Request, init: RequestInit = {}) => {
    calls.push({ url: String(url), init });
    return handler(String(url), init);
  }) as typeof fetch;
  return { impl, calls };
}

export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

export async function makeApp(handler: Parameters<typeof fakeFetch>[0] = () => json({}), over: Partial<Config> = {}) {
  const ff = fakeFetch(handler);
  const cfg = { ...loadConfig({ SESSION_SECRET: 'test-secret', CONSOLE_PIN: '1234', ADMIN_TOKEN: 'tok' }), ...over };
  const built = await buildApp(cfg, { fetchImpl: ff.impl, logger: false });
  return { ...built, cfg, calls: ff.calls };
}

export async function login(app: Awaited<ReturnType<typeof makeApp>>['app'], nickname = 'marco', headers: Record<string, string> = {}) {
  const res = await app.inject({ method: 'POST', url: '/api/session', payload: { nickname }, headers });
  const setCookie = res.cookies.find((c) => c.name === 'ss_sid');
  return { res, cookie: setCookie ? `ss_sid=${setCookie.value}` : '', body: res.json() as { buyerId: string; nickname: string } };
}
