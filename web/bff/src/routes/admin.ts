import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.js';
import { apiError } from '../errors.js';
import { ADMIN_COOKIE, safeEqual, signAdmin } from '../session.js';

export function adminRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { cfg, up, relay } = ctx;
  const adm = { 'x-admin-token': cfg.adminToken };

  app.post<{ Body: { pin?: unknown } }>(
    '/admin/login',
    { config: { rateLimit: { max: 10, timeWindow: 60_000, keyGenerator: (req: { ip: string }) => req.ip } } },
    async (req, reply) => {
      const pin = typeof req.body?.pin === 'string' ? req.body.pin : '';
      if (!safeEqual(pin, cfg.consolePin)) {
        return reply.code(401).send(apiError('BAD_ADMIN_TOKEN', 'PIN incorrecto.'));
      }
      reply.setCookie(ADMIN_COOKIE, signAdmin(cfg.sessionSecret), {
        httpOnly: true,
        sameSite: 'lax',
        path: '/',
        secure: req.headers['x-forwarded-proto'] === 'https',
        maxAge: 12 * 3600,
      });
      return { ok: true };
    },
  );

  // Everything below requires the ss_admin cookie.
  app.register(async (guarded) => {
    guarded.addHook('onRequest', async (req, reply) => {
      if (req.url.startsWith('/admin/login')) return;
      if (!ctx.requireAdmin(req, reply)) return reply;
    });

    guarded.get('/admin/me', async () => ({ ok: true }));

    for (const action of ['open', 'close']) {
      guarded.post(`/admin/sale/${action}`, (_req, reply) =>
        relay(reply, up.call(`${cfg.catalogUrl}/admin/sale/${action}`, { method: 'POST', headers: adm, body: {} })),
      );
    }

    guarded.get('/admin/payment', (_req, reply) => relay(reply, up.call(`${cfg.paymentUrl}/admin/mode`, { headers: adm })));
    guarded.put('/admin/payment', (req, reply) =>
      relay(reply, up.call(`${cfg.paymentUrl}/admin/mode`, { method: 'PUT', headers: adm, body: req.body ?? {} })),
    );

    guarded.post('/admin/reset', (_req, reply) =>
      relay(reply, up.call(`${cfg.catalogUrl}/admin/reset`, { method: 'POST', headers: adm, body: {} })),
    );
    guarded.get<{ Params: { id: string } }>('/admin/reset/:id', (req, reply) =>
      relay(reply, up.call(`${cfg.catalogUrl}/admin/reset/${encodeURIComponent(req.params.id)}`, { headers: adm })),
    );

    for (const action of ['start', 'stop']) {
      guarded.post(`/admin/bots/${action}`, (req, reply) =>
        relay(reply, up.call(`${cfg.botsUrl}/bots/${action}`, { method: 'POST', headers: adm, body: req.body ?? {} })),
      );
    }
    guarded.get('/admin/bots/status', (_req, reply) => relay(reply, up.call(`${cfg.botsUrl}/bots/status`, { headers: adm })));
  });
}
