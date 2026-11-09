import type { FastifyInstance } from 'fastify';
import type { Ctx } from '../app.js';
import { sseFrame, type LiveConnection, type View } from '../live/hub.js';
import { apiError } from '../errors.js';

let nextId = 1;

export function liveRoutes(app: FastifyInstance, ctx: Ctx): void {
  app.get<{ Querystring: { view?: string } }>('/live', { config: { rateLimit: false } }, (req, reply) => {
    const view: View = req.query.view === 'stage' ? 'stage' : 'public';
    const buyerId = ctx.session(req)?.b ?? null;

    if (ctx.hub.size >= ctx.cfg.maxSseConnections) {
      return reply.code(503).send(apiError('UPSTREAM_UNAVAILABLE', 'Sala llena. Inténtalo en un momento.'));
    }

    reply.hijack();
    const raw = reply.raw;
    raw.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });

    const conn: LiveConnection = {
      id: nextId++,
      buyerId,
      view,
      write: (chunk) => void raw.write(chunk),
      queued: () => raw.writableLength,
      close: () => raw.end(),
    };
    if (!ctx.hub.add(conn)) {
      raw.end();
      return;
    }
    raw.on('close', () => ctx.hub.remove(conn));
    conn.write(sseFrame('hello', { buyerId, serverTimeMs: Date.now() }));
  });
}
