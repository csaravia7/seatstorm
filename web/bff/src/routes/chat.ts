import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.js';
import { sseFrame } from '../live/hub.js';
import { apiError, UPSTREAM_DOWN } from '../errors.js';

export const CHAT_FALLBACK =
  'Ahora mismo no puedo responder. Puedes seguir comprando: el resto de la demo funciona con normalidad.';

const SSE_HEADERS = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache',
  Connection: 'keep-alive',
  'X-Accel-Buffering': 'no',
};

export function chatRoutes(app: FastifyInstance, ctx: Ctx): void {
  app.post<{ Body: { message?: unknown } }>(
    '/api/chat',
    { config: { rateLimit: { max: 6, timeWindow: 60_000 } } },
    async (req, reply) => {
      const s = ctx.requireSession(req, reply);
      if (!s) return reply;
      const message = req.body?.message;
      if (typeof message !== 'string' || message.trim() === '' || message.length > 500) {
        return reply.code(400).send(apiError('VALIDATION_ERROR', 'El mensaje debe tener entre 1 y 500 caracteres.'));
      }

      const fail = () => {
        reply.hijack();
        reply.raw.writeHead(200, SSE_HEADERS);
        reply.raw.write(sseFrame('error', CHAT_FALLBACK));
        reply.raw.end();
        return reply;
      };

      const ac = new AbortController();
      const headerTimer = setTimeout(() => ac.abort(), 15_000);
      let res: Response;
      try {
        res = await ctx.fetchImpl(`${ctx.cfg.agentUrl}/api/chat`, {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            accept: 'text/event-stream',
            'x-buyer-id': s.b,
            'x-session-id': ctx.sessionIdOf(req),
          },
          body: JSON.stringify({ message }),
          signal: ac.signal,
        });
      } catch {
        return fail();
      } finally {
        clearTimeout(headerTimer);
      }

      if (res.status >= 500 || !res.body) return fail();
      if (res.status !== 200) {
        // 400 / 429 from the agent: pass the JSON error through.
        return reply.code(res.status).type('application/json').send(await res.text().catch(() => JSON.stringify(UPSTREAM_DOWN)));
      }

      reply.hijack();
      reply.raw.writeHead(200, SSE_HEADERS);
      const reader = res.body.getReader();
      reply.raw.on('close', () => void reader.cancel().catch(() => undefined));
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          reply.raw.write(value);
        }
      } catch {
        if (!reply.raw.writableEnded) reply.raw.write(sseFrame('error', CHAT_FALLBACK));
      }
      reply.raw.end();
      return reply;
    },
  );
}
