import { useEffect, useState } from 'react';
import { useMapStore } from '../state/mapStore';
import { secondsLeft } from '../lib/format';

/** Seconds left until `untilMs` (server clock). Calls onZero once when it reaches 0. */
export function useSecondsLeft(untilMs: number | null, onZero?: () => void): number | null {
  const offset = useMapStore((s) => s.clockOffsetMs);
  const calc = () => (untilMs === null ? null : secondsLeft(untilMs, Date.now() + offset));
  const [left, setLeft] = useState<number | null>(calc);
  useEffect(() => {
    setLeft(calc());
    if (untilMs === null) return;
    let fired = false;
    const t = setInterval(() => {
      const v = secondsLeft(untilMs, Date.now() + offset);
      setLeft(v);
      if (v === 0 && !fired) {
        fired = true;
        onZero?.();
      }
    }, 250);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [untilMs, offset]);
  return left;
}

export function Countdown({ untilMs, onZero, label }: { untilMs: number | null; onZero?: () => void; label?: string }) {
  const left = useSecondsLeft(untilMs, onZero);
  if (left === null) return null;
  return (
    <div className="countdown" role="timer" aria-live="off" aria-label={label ?? 'Tiempo restante'}>
      <span className="countdown-n">{left}</span>
      <span className="countdown-u">s</span>
    </div>
  );
}
