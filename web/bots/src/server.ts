import { buildApp } from './app.js';

const { app, swarm } = buildApp({
  bffUrl: (process.env.TARGET_URL ?? process.env.BFF_URL ?? 'http://localhost:3000').replace(/\/+$/, ''),
  adminToken: process.env.ADMIN_TOKEN ?? 'change-me-admin',
});

const shutdown = async () => {
  swarm.stop();
  await app.close();
  process.exit(0);
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

await app.listen({ host: '0.0.0.0', port: Number(process.env.PORT ?? 3100) });
