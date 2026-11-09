import { describe, expect, it, vi } from 'vitest';
import { decodeUiMessage, startUiConsumer, type ConsumerLike } from '../src/kafka/uiConsumer.js';
import { Hub } from '../src/live/hub.js';

const log = { info: vi.fn(), warn: vi.fn() };

describe('ui.events consumer', () => {
  it('decodes JSON and ignores garbage', () => {
    expect(decodeUiMessage(Buffer.from('{"type":"reset"}'))).toEqual({ type: 'reset' });
    expect(decodeUiMessage('nope')).toBeNull();
    expect(decodeUiMessage('[1]')).toBeNull();
    expect(decodeUiMessage(null)).toBeNull();
  });

  it('uses one groupId per replica, reads only new messages and dispatches to the hub', async () => {
    const hub = new Hub({ maxConnections: 5, seatFlushMs: 100, pingMs: 1000 });
    const spy = vi.spyOn(hub, 'handleUiEvent');
    let handler!: (p: { message: { value: Buffer | null } }) => Promise<void>;
    const groups: string[] = [];
    const subscribe = vi.fn(async () => undefined);
    const factory = (g: string): ConsumerLike => {
      groups.push(g);
      return {
        connect: async () => undefined,
        subscribe,
        run: async ({ eachMessage }) => { handler = eachMessage; },
        disconnect: async () => undefined,
      };
    };
    const c1 = startUiConsumer({ hostname: 'pod-a' }, hub, factory, log);
    const c2 = startUiConsumer({ hostname: 'pod-b' }, hub, factory, log);
    await new Promise((r) => setTimeout(r, 10));
    expect(groups).toEqual(['bff-pod-a', 'bff-pod-b']);
    expect(subscribe).toHaveBeenCalledWith({ topic: 'ui.events', fromBeginning: false });
    await handler({ message: { value: Buffer.from('{"type":"sale","status":"OPEN"}') } });
    await handler({ message: { value: Buffer.from('not json') } });
    expect(spy).toHaveBeenCalledTimes(1);
    await c1.stop();
    await c2.stop();
  });

  it('retries when the broker is unavailable', async () => {
    const hub = new Hub({ maxConnections: 5, seatFlushMs: 100, pingMs: 1000 });
    let n = 0;
    const factory = (): ConsumerLike => ({
      connect: async () => { if (n++ === 0) throw new Error('down'); },
      subscribe: async () => undefined,
      run: async () => undefined,
      disconnect: async () => undefined,
    });
    const c = startUiConsumer({ hostname: 'x' }, hub, factory, log, 10);
    await new Promise((r) => setTimeout(r, 60));
    expect(n).toBe(2);
    await c.stop();
  });
});
