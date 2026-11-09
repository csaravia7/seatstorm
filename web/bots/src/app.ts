import Fastify from 'fastify';
import { z } from 'zod';
import { Swarm, type SwarmDeps } from './swarm.js';

const startSchema = z.object({
  count: z.number().int().min(1).max(2000),
  rampSeconds: z.number().min(0).max(600).optional(),
  profile: z.enum(['burst', 'steady', 'greedy']).default('burst'),
  payProbability: z.number().min(0).max(1).default(0.85),
});

const DEFAULT_RAMP = { burst: 2, steady: 30, greedy: 2 } as const;

export function buildApp(deps: SwarmDeps, logger = true, swarm = new Swarm(deps)) {
  const app = Fastify({ logger });

  app.get('/healthz', async () => ({ status: 'ok' }));

  app.post('/bots/start', async (req, reply) => {
    const p = startSchema.safeParse(req.body);
    if (!p.success) {
      return reply.code(400).send({ code: 'VALIDATION_ERROR', message: 'Parámetros de bots inválidos.', details: { issues: p.error.issues.map((i) => i.message) } });
    }
    const o = { ...p.data, rampSeconds: p.data.rampSeconds ?? DEFAULT_RAMP[p.data.profile] };
    if (!swarm.start(o)) {
      return reply.code(409).send({ code: 'INVALID_STATE', message: 'Ya hay bots en ejecución.', details: {} });
    }
    return reply.code(202).send(swarm.status());
  });

  app.post('/bots/stop', async () => swarm.stop());
  app.get('/bots/status', async () => swarm.status());

  return { app, swarm };
}
