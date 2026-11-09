import { useEffect, useMemo, useState } from 'react';
import QRCode from 'qrcode';
import { api } from '../api/endpoints';
import { SeatMap } from '../components/SeatMap';
import { Countdown } from '../components/Countdown';
import { useLive } from '../live/useLive';
import { syncSeatmap } from '../live/sync';
import { countSeats, useMapStore } from '../state/mapStore';
import { clockTime, formatDuration } from '../lib/format';

export function StagePage() {
  useLive('stage');
  const seats = useMapStore((s) => s.seats);
  const sale = useMapStore((s) => s.saleStatus);
  const openingUntil = useMapStore((s) => s.openingUntil);
  const feed = useMapStore((s) => s.feed);
  const board = useMapStore((s) => s.leaderboard);
  const connected = useMapStore((s) => s.connected);
  const counts = useMemo(() => countSeats(seats), [seats]);
  const [qr, setQr] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);

  useEffect(() => {
    void QRCode.toDataURL(location.origin, { margin: 1, width: 220, color: { dark: '#0b0b1a', light: '#ffffff' } }).then(setQr);
  }, []);

  // Local counters are verified against /api/stats every 10 s; on mismatch the seatmap is reloaded.
  useEffect(() => {
    const check = async () => {
      try {
        const st = await api.stats();
        const local = countSeats(useMapStore.getState().seats);
        useMapStore.getState().setSale(st.saleStatus, false);
        if (useMapStore.getState().seats.size > 0 && (local.available !== st.available || local.held !== st.held || local.sold !== st.sold)) {
          await syncSeatmap();
        }
      } catch {
        // next tick
      }
    };
    const t = setInterval(() => void check(), 10_000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (!openingUntil) return;
    setOpening(true);
    const t = setTimeout(() => setOpening(false), Math.max(0, openingUntil - Date.now()));
    return () => clearTimeout(t);
  }, [openingUntil]);

  return (
    <div className="stage">
      <header className="stage-head">
        <h1 className="brand">Neon Tide · Arena Norte</h1>
        <div className={`sale-banner ${sale === 'OPEN' ? 'sale-open' : 'sale-closed'}`} role="status">
          {sale === 'OPEN' ? 'VENTA ABIERTA' : sale === 'CLOSED' ? 'VENTA CERRADA' : '…'}
        </div>
        {!connected && <span className="badge badge-warn">Sin conexión en vivo</span>}
      </header>

      <div className="stage-body">
        <div className="stage-map">
          <SeatMap large />
          {opening && openingUntil && (
            <div className="overlay overlay-stage" role="alert">
              <p>¡La venta abre en…</p>
              <Countdown untilMs={openingUntil} label="Apertura de venta" />
            </div>
          )}
        </div>

        <aside className="stage-side">
          <div className="counters">
            <div className="counter counter-available"><span data-testid="c-available">{counts.available}</span><small>Disponibles</small></div>
            <div className="counter counter-held"><span data-testid="c-held">{counts.held}</span><small>Retenidos</small></div>
            <div className="counter counter-sold"><span data-testid="c-sold">{counts.sold}</span><small>Vendidos</small></div>
          </div>

          <section aria-label="Ranking">
            <h2>Ranking</h2>
            <ol className="board">
              {board.length === 0 && <li className="muted">Aún sin compras</li>}
              {board.map((r, i) => (
                <li key={`${r.nickname}-${i}`}><strong>{r.nickname}</strong> · {r.tickets} 🎟 · {formatDuration(r.fastestMs)}</li>
              ))}
            </ol>
          </section>

          <section aria-label="Eventos en vivo">
            <h2>En vivo</h2>
            <ul className="feed">
              {feed.map((f, i) => (
                <li key={`${f.at}-${i}`}><time>{clockTime(f.at)}</time> <b>{f.name}</b> {f.detail}</li>
              ))}
            </ul>
          </section>

          <section className="join-qr" aria-label="Unirse">
            {qr && <img src={qr} alt="QR para unirse" width={220} height={220} />}
            <p>{location.origin}</p>
          </section>
        </aside>
      </div>
    </div>
  );
}
