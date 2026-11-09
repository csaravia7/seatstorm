import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Ctx } from '../app.js';

const search = (req: FastifyRequest) => new URL(req.url, 'http://x').search;

export function proxyRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { cfg, up, relay } = ctx;

  // Public read endpoints (the stage screen has no session).
  app.get('/api/concert', (_req, reply) => relay(reply, up.call(`${cfg.catalogUrl}/api/concert`)));

  for (const path of ['seatmap', 'stats', 'leaderboard']) {
    app.get(`/api/${path}`, (req, reply) => {
      const q = new URLSearchParams(search(req));
      if (!q.has('concertId')) q.set('concertId', cfg.defaultConcertId);
      return relay(reply, up.call(`${cfg.queryUrl}/api/${path}?${q}`));
    });
  }

  app.get<{ Params: { id: string } }>('/api/orders/:id/timeline', (req, reply) => {
    if (!ctx.requireSession(req, reply)) return reply;
    return relay(reply, up.call(`${cfg.queryUrl}/api/orders/${encodeURIComponent(req.params.id)}/timeline`));
  });

  // Order endpoints: authenticated, with buyer headers.
  app.post(
    '/api/orders',
    { config: { rateLimit: { max: 3, timeWindow: 10_000 } } },
    (req, reply) => {
      const s = ctx.requireSession(req, reply);
      if (!s) return reply;
      const headers = ctx.buyerHeaders(s);
      const key = req.headers['idempotency-key'];
      if (typeof key === 'string') headers['idempotency-key'] = key;
      return relay(reply, up.call(`${cfg.orderUrl}/api/orders`, { method: 'POST', headers, body: req.body ?? {} }));
    },
  );

  for (const action of ['pay', 'cancel']) {
    app.post<{ Params: { id: string } }>(`/api/orders/:id/${action}`, (req, reply) => {
      const s = ctx.requireSession(req, reply);
      if (!s) return reply;
      return relay(
        reply,
        up.call(`${cfg.orderUrl}/api/orders/${encodeURIComponent(req.params.id)}/${action}`, {
          method: 'POST',
          headers: ctx.buyerHeaders(s),
          body: req.body ?? {},
        }),
      );
    });
  }

  app.get<{ Params: { id: string } }>('/api/orders/:id', (req, reply) => {
    const s = ctx.requireSession(req, reply);
    if (!s) return reply;
    return relay(reply, up.call(`${cfg.orderUrl}/api/orders/${encodeURIComponent(req.params.id)}`, { headers: ctx.buyerHeaders(s) }));
  });

  app.get('/api/my/orders', (req, reply) => {
    const s = ctx.requireSession(req, reply);
    if (!s) return reply;
    return relay(reply, up.call(`${cfg.queryUrl}/api/buyers/${encodeURIComponent(s.b)}/orders?limit=5`, { headers: ctx.buyerHeaders(s) }));
  });
}
