import { createHash } from 'node:crypto';
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import cookie from '@fastify/cookie';
import rateLimit from '@fastify/rate-limit';
import type { Config } from './config.js';
import { Upstream, UpstreamError, type FetchLike, type UpstreamResult } from './clients/upstream.js';
import { Hub } from './live/hub.js';
import { LeaderboardPoller } from './live/leaderboard.js';
import { apiError, BAD_ADMIN, NO_SESSION, UPSTREAM_DOWN } from './errors.js';
import { SESSION_COOKIE, ADMIN_COOKIE, verifyAdmin, verifySession, safeEqual, type SessionData } from './session.js';
import { sessionRoutes } from './routes/session.js';
import { proxyRoutes } from './routes/proxy.js';
import { liveRoutes } from './routes/live.js';
import { adminRoutes } from './routes/admin.js';
import { chatRoutes } from './routes/chat.js';

export interface AppDeps {
  fetchImpl?: FetchLike;
  hub?: Hub;
  logger?: boolean;
}

export interface Ctx {
  cfg: Config;
  up: Upstream;
  hub: Hub;
  fetchImpl: FetchLike;
  session(req: FastifyRequest): SessionData | null;
  isAdmin(req: FastifyRequest): boolean;
  /** Returns the session or replies 401. */
  requireSession(req: FastifyRequest, reply: FastifyReply): SessionData | null;
  requireAdmin(req: FastifyRequest, reply: FastifyReply): boolean;
  relay(reply: FastifyReply, p: Promise<UpstreamResult>): Promise<FastifyReply>;
  buyerHeaders(s: SessionData): Record<string, string>;
  sessionIdOf(req: FastifyRequest): string;
}

export interface BuiltApp {
  app: FastifyInstance;
  hub: Hub;
  poller: LeaderboardPoller;
}

export async function buildApp(cfg: Config, deps: AppDeps = {}): Promise<BuiltApp> {
  const fetchImpl = deps.fetchImpl ?? fetch;
  const app = Fastify({
    logger: deps.logger === false ? false : { level: process.env.LOG_LEVEL ?? 'info' },
    trustProxy: true,
  });
  const up = new Upstream(fetchImpl, cfg.upstreamTimeoutMs);
  const hub = deps.hub ?? new Hub({ maxConnections: cfg.maxSseConnections, seatFlushMs: cfg.seatFlushMs, pingMs: cfg.pingMs });
  const poller = new LeaderboardPoller(cfg, up, hub);

  await app.register(cookie);

  const session = (req: FastifyRequest) => verifySession(req.cookies[SESSION_COOKIE], cfg.sessionSecret);

  await app.register(rateLimit, {
    global: true,
    max: 20,
    timeWindow: 1000,
    keyGenerator: (req) => session(req)?.b ?? req.ip,
    errorResponseBuilder: (_req, ctx) => ({
      statusCode: 429,
      code: 'RATE_LIMITED',
      message: 'Demasiadas peticiones. Espera un momento.',
      details: { retryAfterMs: ctx.ttl },
    }),
  });

  app.setErrorHandler((err: Error & { statusCode?: number; code?: string; details?: Record<string, unknown> }, _req, reply) => {
    const status = err.statusCode ?? 500;
    if (status === 429) return reply.code(429).send(apiError('RATE_LIMITED', err.message, err.details ?? {}));
    if (status >= 400 && status < 500) return reply.code(status).send(apiError('VALIDATION_ERROR', 'Petición inválida.'));
    app.log.error(err);
    return reply.code(500).send(apiError('INTERNAL_ERROR', 'Error interno.'));
  });

  const ctx: Ctx = {
    cfg,
    up,
    hub,
    fetchImpl,
    session,
    isAdmin: (req) => verifyAdmin(req.cookies[ADMIN_COOKIE], cfg.sessionSecret),
    requireSession(req, reply) {
      const s = session(req);
      if (!s) void reply.code(401).send(NO_SESSION);
      return s;
    },
    requireAdmin(req, reply) {
      const ok = verifyAdmin(req.cookies[ADMIN_COOKIE], cfg.sessionSecret);
      if (!ok) void reply.code(401).send(BAD_ADMIN);
      return ok;
    },
    async relay(reply, p) {
      try {
        const r = await p;
        if (r.replay) reply.header('Idempotent-Replay', r.replay);
        return reply.code(r.status).type(r.contentType).send(r.text);
      } catch (err) {
        if (err instanceof UpstreamError) return reply.code(503).send(UPSTREAM_DOWN);
        throw err;
      }
    },
    buyerHeaders: (s) => ({ 'x-buyer-id': s.b, 'x-buyer-nickname': encodeURIComponent(s.n) }),
    sessionIdOf: (req) => createHash('sha256').update(req.cookies[SESSION_COOKIE] ?? '').digest('hex').slice(0, 16),
  };

  app.get('/healthz', { config: { rateLimit: false } }, async () => ({ status: 'ok' }));
  app.get('/api/config', async () => ({ kafkaUiUrl: cfg.kafkaUiUrl, dynatraceUrl: cfg.dynatraceUrl }));

  await app.register(async (i) => sessionRoutes(i, ctx));
  await app.register(async (i) => proxyRoutes(i, ctx));
  await app.register(async (i) => liveRoutes(i, ctx));
  await app.register(async (i) => adminRoutes(i, ctx));
  await app.register(async (i) => chatRoutes(i, ctx));

  return { app, hub, poller };
}

export { safeEqual };
