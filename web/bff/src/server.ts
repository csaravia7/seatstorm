import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { kafkaFactory, startUiConsumer } from './kafka/uiConsumer.js';

const cfg = loadConfig();
const { app, hub, poller } = await buildApp(cfg);

hub.start();
poller.start();

const consumer = cfg.kafkaBootstrap
  ? startUiConsumer(cfg, hub, kafkaFactory(cfg.kafkaBootstrap), {
      info: (m) => app.log.info(m),
      warn: (m) => app.log.warn(m),
    })
  : undefined;
if (!consumer) app.log.warn('KAFKA_BOOTSTRAP not set: live updates disabled');

const shutdown = async () => {
  poller.stop();
  hub.stop();
  await consumer?.stop();
  await app.close();
  process.exit(0);
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

await app.listen({ host: '0.0.0.0', port: cfg.port });
