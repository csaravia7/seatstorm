import type { FastifyInstance } from 'fastify';
import { randomUUID } from 'node:crypto';
import type { Ctx } from '../app.js';
import { apiError } from '../errors.js';
import { SESSION_COOKIE, newBuyerId, safeEqual, signSession, validateNickname } from '../session.js';

export function sessionRoutes(app: FastifyInstance, ctx: Ctx): void {
  app.post<{ Body: { nickname?: unknown } }>(
    '/api/session',
    {
      config: {
        // 300/min per IP: lots of people share one NAT address at a venue.
        rateLimit: {
          max: 300,
          timeWindow: 60_000,
          keyGenerator: (req: { ip: string; headers: Record<string, unknown> }) => {
            // Load tools (bot-swarm, k6) present the admin token and are not throttled per IP.
            const t = req.headers['x-bot-token'];
            return typeof t === 'string' && safeEqual(t, ctx.cfg.adminToken) ? `bot:${randomUUID()}` : req.ip;
          },
        },
      },
    },
    async (req, reply) => {
      const res = validateNickname(req.body?.nickname);
      if (!res.ok) return reply.code(400).send(apiError('VALIDATION_ERROR', res.message));
      // Keep the buyerId when the browser already has a valid session (page reloads).
      const buyerId = ctx.session(req)?.b ?? newBuyerId();
      const value = signSession({ b: buyerId, n: res.nickname, iat: Date.now() }, ctx.cfg.sessionSecret);
      reply.setCookie(SESSION_COOKIE, value, {
        httpOnly: true,
        sameSite: 'lax',
        path: '/',
        secure: req.headers['x-forwarded-proto'] === 'https',
        maxAge: 24 * 3600,
      });
      return { buyerId, nickname: res.nickname };
    },
  );

  app.get('/api/me', async (req, reply) => {
    const s = ctx.requireSession(req, reply);
    if (!s) return reply;
    return { buyerId: s.b, nickname: s.n };
  });
}
