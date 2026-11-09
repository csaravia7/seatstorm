import { Kafka, logLevel } from 'kafkajs';
import type { Config } from '../config.js';
import type { Hub } from '../live/hub.js';

export const UI_TOPIC = 'ui.events';

export function decodeUiMessage(raw: Buffer | string | null | undefined): Record<string, unknown> | null {
  if (raw == null) return null;
  try {
    const v = JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf8'));
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Minimal slice of the kafkajs Consumer used here (lets tests inject a fake). */
export interface ConsumerLike {
  connect(): Promise<void>;
  subscribe(args: { topic: string; fromBeginning: boolean }): Promise<void>;
  run(args: { eachMessage: (p: { message: { value: Buffer | null } }) => Promise<void> }): Promise<void>;
  disconnect(): Promise<void>;
}

export type ConsumerFactory = (groupId: string) => ConsumerLike;

export const kafkaFactory =
  (bootstrap: string): ConsumerFactory =>
  (groupId) =>
    new Kafka({
      clientId: 'seatstorm-bff',
      brokers: bootstrap.split(',').map((s) => s.trim()),
      logLevel: logLevel.WARN,
    }).consumer({ groupId }) as unknown as ConsumerLike;

export interface UiConsumer {
  stop(): Promise<void>;
}

/**
 * Consumes ui.events with a groupId unique per replica, so every replica gets every message.
 * Connection failures are retried in the background: the BFF stays up without Kafka.
 */
export function startUiConsumer(
  cfg: Pick<Config, 'hostname'>,
  hub: Hub,
  factory: ConsumerFactory,
  log: { info(m: string): void; warn(m: string): void },
  retryMs = 5000,
): UiConsumer {
  let stopped = false;
  let consumer: ConsumerLike | undefined;
  let retry: NodeJS.Timeout | undefined;

  const attempt = async (): Promise<void> => {
    if (stopped) return;
    consumer = factory(`bff-${cfg.hostname}`);
    try {
      await consumer.connect();
      await consumer.subscribe({ topic: UI_TOPIC, fromBeginning: false });
      await consumer.run({
        eachMessage: async ({ message }) => {
          const msg = decodeUiMessage(message.value);
          if (msg) hub.handleUiEvent(msg);
        },
      });
      log.info(`consuming ${UI_TOPIC} as bff-${cfg.hostname}`);
    } catch (err) {
      log.warn(`kafka consumer failed (${(err as Error).message}); retrying in ${retryMs} ms`);
      await consumer.disconnect().catch(() => undefined);
      if (!stopped) retry = setTimeout(() => void attempt(), retryMs);
    }
  };
  void attempt();

  return {
    async stop() {
      stopped = true;
      clearTimeout(retry);
      await consumer?.disconnect().catch(() => undefined);
    },
  };
}
