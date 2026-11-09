export const money = (cents: number) =>
  new Intl.NumberFormat('es', { style: 'currency', currency: 'USD' }).format(cents / 100);

export const secondsLeft = (untilMs: number, nowMs: number) => Math.max(0, Math.ceil((untilMs - nowMs) / 1000));

export const formatDuration = (ms: number) => (ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`);

export const clockTime = (ms: number) => new Date(ms).toLocaleTimeString('es', { hour12: false });
