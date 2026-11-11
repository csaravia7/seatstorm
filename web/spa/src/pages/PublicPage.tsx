import { useEffect, useMemo, useRef, useState } from 'react';
import QRCode from 'qrcode';
import { useLocation, useNavigate } from 'react-router';
import { PATHS, pathForOrder, sectionFromPath, sectionPath } from '../lib/routes';
import { api } from '../api/endpoints';
import { ApiError } from '../api/client';
import { SeatMap } from '../components/SeatMap';
import { SectionDetail } from '../components/SectionDetail';
import { Countdown, useSecondsLeft } from '../components/Countdown';
import { ChatWidget } from '../components/Chat';
import { ToastHost } from '../components/Toast';
import { useLive } from '../live/useLive';
import { useConcertName } from '../state/concertStore';
import { useMapStore } from '../state/mapStore';
import { usePurchaseStore } from '../state/purchaseStore';
import { buy, cancel, fetchTicket, handleMe, pay, restoreActiveOrder, startOrderWatcher } from '../state/flow';
import { clearIdempotencyKey } from '../lib/idempotency';
import { money } from '../lib/format';
import { sectionLabel, parseSeatId } from '../lib/venue';
import { outcomeNotice } from '../state/orderLogic';

const NICK_RE = /^[A-Za-z0-9_-]{3,16}$/;

export function JoinScreen() {
  const setMe = usePurchaseStore((s) => s.setMe);
  const concertName = useConcertName();
  const [nick, setNick] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!NICK_RE.test(nick)) {
      setError('El apodo debe tener entre 3 y 16 caracteres: letras, números, _ o -.');
      return;
    }
    setError(null);
    setBusy(true);
    try {
      setMe(await api.createSession(nick));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'No pudimos conectar. Inténtalo de nuevo.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="screen screen-center">
      <h1 className="brand">{concertName}</h1>
      <p className="muted">360 asientos · una sola noche</p>
      <form className="card" onSubmit={submit} noValidate>
        <label htmlFor="nick">Elige tu apodo</label>
        <input
          id="nick"
          value={nick}
          onChange={(e) => setNick(e.target.value)}
          autoComplete="off"
          autoCapitalize="off"
          maxLength={16}
          placeholder="p. ej. marco_77"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? 'nick-error' : undefined}
        />
        {error && <p id="nick-error" className="error" role="alert">{error}</p>}
        <button type="submit" className="btn btn-primary" disabled={busy}>Entrar</button>
      </form>
    </main>
  );
}

function WaitingRoom() {
  const concertName = useConcertName();
  return (
    <main className="screen screen-center">
      <p className="event-name">{concertName}</p>
      <div className="pulse" aria-hidden="true" />
      <h1>La venta abre pronto</h1>
      <p className="muted">Mantén esta pantalla abierta. Te avisaremos en cuanto abramos.</p>
    </main>
  );
}

function OpeningOverlay({ until }: { until: number }) {
  return (
    <div className="overlay" role="alert">
      <p>¡La venta abre en…</p>
      <Countdown untilMs={until} label="Apertura de venta" />
    </div>
  );
}

function Spinner({ text }: { text: string }) {
  return (
    <main className="screen screen-center" aria-busy="true">
      <div className="spinner" aria-hidden="true" />
      <p>{text}</p>
    </main>
  );
}

function HeldScreen() {
  const concertName = useConcertName();
  const order = usePurchaseStore((s) => s.order)!;
  const patch = usePurchaseStore((s) => s.patchOrder);
  const [busy, setBusy] = useState(false);
  const left = useSecondsLeft(order.heldUntilMs, () => {
    // Time ran out without paying: show "Se acabó el tiempo" until the server confirms.
    if (usePurchaseStore.getState().order?.status === 'HELD') patch({ status: 'EXPIRED', reason: 'HOLD_EXPIRED' });
  });
  return (
    <main className="screen screen-center">
      <p className="event-name">{concertName}</p>
      <h1>Tus asientos están retenidos</h1>
      <Countdown untilMs={order.heldUntilMs} />
      <p>{sectionLabel(order.sectionId)} · {order.seatIds.map((s) => parseSeatId(s)?.row + String(parseSeatId(s)?.number).padStart(2, '0')).join(', ')}</p>
      <p className="total">{money(order.totalCents)}</p>
      <div className="actions">
        <button
          type="button"
          className="btn btn-primary"
          disabled={busy || left === 0}
          onClick={async () => {
            setBusy(true);
            await pay();
            setBusy(false);
          }}
        >
          Pagar
        </button>
        <button type="button" className="btn" disabled={busy} onClick={() => void cancel()}>Cancelar</button>
      </div>
    </main>
  );
}

function TicketScreen({ onDone }: { onDone: () => void }) {
  const concertName = useConcertName();
  const order = usePurchaseStore((s) => s.order)!;
  const [qr, setQr] = useState<string | null>(null);
  useEffect(() => {
    if (!order.ticketQr) return;
    void QRCode.toDataURL(order.ticketQr, { margin: 1, width: 280 }).then(setQr);
  }, [order.ticketQr]);
  return (
    <main className="screen screen-center ticket">
      <h1>¡Listo! Tu entrada</h1>
      <p className="event-name">{concertName}</p>
      <p>{sectionLabel(order.sectionId)}</p>
      <p className="seats">{order.seatIds.map((s) => `${parseSeatId(s)?.row}${String(parseSeatId(s)?.number).padStart(2, '0')}`).join(' · ')}</p>
      <p className="total">{money(order.totalCents)}</p>
      {qr ? <img className="qr" src={qr} alt="Código QR de tu entrada" width={280} height={280} /> : <div className="spinner" aria-label="Generando tu QR" />}
      <button type="button" className="btn" onClick={onDone}>Volver al mapa</button>
    </main>
  );
}

function MessageScreen({ title, text, onDone }: { title: string; text: string; onDone: () => void }) {
  return (
    <main className="screen screen-center">
      <h1>{title}</h1>
      <p className="muted">{text}</p>
      <button type="button" className="btn btn-primary" onClick={onDone}>Volver al mapa</button>
    </main>
  );
}

function MapScreen() {
  // The open section lives in the URL (/mapa/palco/4) so it is a page of its own and survives reload/back.
  const navigate = useNavigate();
  const sectionId = sectionFromPath(useLocation().pathname);
  const setSectionId = (id: string | null) => void navigate(id ? sectionPath(id) : PATHS.map);
  const selection = usePurchaseStore((s) => s.selection);
  const notice = usePurchaseStore((s) => s.notice);
  const setNotice = usePurchaseStore((s) => s.setNotice);
  const me = usePurchaseStore((s) => s.me);
  const connected = useMapStore((s) => s.connected);
  const mine = useMemo(() => new Set(selection.seatIds), [selection.seatIds]);
  const concertName = useConcertName();

  return (
    <main className="screen map-screen">
      <header className="topbar">
        <h1 className="event-title">{concertName}</h1>
        <span className="who">Hola, <strong>{me?.nickname}</strong></span>
        {!connected && <span className="badge badge-warn">Reconectando…</span>}
      </header>
      {notice && (
        <div className="notice" role="alert">
          {notice} <button type="button" className="btn btn-ghost" onClick={() => setNotice(null)} aria-label="Cerrar aviso">✕</button>
        </div>
      )}
      {sectionId ? (
        <SectionDetail sectionId={sectionId} onBack={() => setSectionId(null)} />
      ) : (
        <>
          <p className="muted center">Toca una sección para ver sus asientos</p>
          <SeatMap mine={mine} onSectionClick={setSectionId} />
        </>
      )}
      <BuyBar />
    </main>
  );
}

function BuyBar() {
  const selection = usePurchaseStore((s) => s.selection);
  const clear = usePurchaseStore((s) => s.clearSelection);
  const prices = useMapStore((s) => s.priceBySection);
  if (selection.seatIds.length === 0) return null;
  const total = (selection.sectionId ? (prices[selection.sectionId] ?? 0) : 0) * selection.seatIds.length;
  return (
    <div className="buybar" role="region" aria-label="Resumen de compra">
      <div>
        <strong data-testid="qty">{selection.seatIds.length}</strong> {selection.seatIds.length === 1 ? 'asiento' : 'asientos'} · {sectionLabel(selection.sectionId ?? '')}
        <div className="total" data-testid="total">{money(total)}</div>
      </div>
      <button type="button" className="btn btn-ghost" onClick={clear}>Limpiar</button>
      <button type="button" className="btn btn-primary" onClick={() => void buy()} disabled={total <= 0}>Comprar</button>
    </div>
  );
}

export function PublicPage() {
  const me = usePurchaseStore((s) => s.me);
  const checked = usePurchaseStore((s) => s.sessionChecked);
  const setMe = usePurchaseStore((s) => s.setMe);
  const order = usePurchaseStore((s) => s.order);
  const setOrder = usePurchaseStore((s) => s.setOrder);
  const clearSelection = usePurchaseStore((s) => s.clearSelection);
  const setNotice = usePurchaseStore((s) => s.setNotice);
  const resetAll = usePurchaseStore((s) => s.resetAll);
  const sale = useMapStore((s) => s.saleStatus);
  const openingUntil = useMapStore((s) => s.openingUntil);
  const [opening, setOpening] = useState(false);
  const [restored, setRestored] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    let alive = true;
    api.me().then((m) => alive && setMe(m)).catch(() => alive && setMe(null));
    return () => { alive = false; };
  }, [setMe]);

  useEffect(() => {
    if (!checked) return;
    if (!me) {
      setRestored(true);
      return;
    }
    void restoreActiveOrder().finally(() => setRestored(true));
  }, [me, checked]);

  // Fallback to the live channel: poll the order while it is in flight (see flow.ts).
  useEffect(() => startOrderWatcher(), []);

  useLive('public', {
    onMe: handleMe,
    onReset: () => {
      clearIdempotencyKey();
      resetAll();
    },
  });

  useEffect(() => {
    if (!openingUntil) return;
    setOpening(true);
    const t = setTimeout(() => setOpening(false), Math.max(0, openingUntil - Date.now()));
    return () => clearTimeout(t);
  }, [openingUntil]);

  const status = order?.status;
  const reason = order?.reason;
  const orderId = order?.orderId;

  // Terminal states that send the buyer straight back to the map.
  useEffect(() => {
    if (!order) return;
    const notice = outcomeNotice(order);
    if (status === 'REJECTED' || (status === 'CANCELLED' && notice)) {
      clearIdempotencyKey();
      setNotice(notice);
      clearSelection();
      setOrder(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, reason]);

  useEffect(() => {
    if (status !== 'CONFIRMED' || !orderId) return;
    clearIdempotencyKey();
    navigator.vibrate?.(200);
    void fetchTicket(orderId);
  }, [status, orderId]);

  const backToMap = () => {
    clearIdempotencyKey();
    clearSelection();
    setOrder(null);
  };

  // The URL follows the journey state (never the other way round), one history entry per step.
  const firstSync = useRef(true);
  const wanted = !checked || !restored
    ? null
    : !me
      ? PATHS.join
      : (pathForOrder(status, reason) ?? (sale === 'CLOSED' && !order ? PATHS.waiting : sectionFromPath(location.pathname) ? location.pathname : PATHS.map));
  useEffect(() => {
    if (!wanted || wanted === location.pathname) return;
    void navigate(wanted, { replace: firstSync.current });
    firstSync.current = false;
  }, [wanted, location.pathname, navigate]);
  useEffect(() => {
    if (wanted) firstSync.current = false;
  }, [wanted]);

  if (!checked) return <Spinner text="Cargando…" />;
  if (!me) return <JoinScreen />;

  let body: React.ReactNode;
  if (order) {
    switch (order.status) {
      case 'SUBMITTING':
      case 'PLACED':
        body = <Spinner text="Reservando tus asientos…" />;
        break;
      case 'HELD':
        body = <HeldScreen />;
        break;
      case 'PAYING':
      case 'CONFIRMING':
        body = <Spinner text="Procesando tu pago…" />;
        break;
      case 'REFUNDING':
        body = <Spinner text="Tu retención venció; estamos reembolsando tu pago…" />;
        break;
      case 'CONFIRMED':
        body = <TicketScreen onDone={backToMap} />;
        break;
      case 'EXPIRED':
        body = <MessageScreen title="Se acabó el tiempo" text="Tus asientos volvieron a estar disponibles." onDone={backToMap} />;
        break;
      case 'CANCELLED':
        body = <MessageScreen title="Compra cancelada" text={order.reason === 'HOLD_EXPIRED' ? 'La retención venció antes de confirmar. Reembolsamos tu pago.' : 'Tu compra fue cancelada.'} onDone={backToMap} />;
        break;
      default:
        body = <MapScreen />;
    }
  } else if (sale === 'CLOSED') {
    body = <WaitingRoom />;
  } else {
    body = <MapScreen />;
  }

  return (
    <>
      {body}
      {opening && openingUntil && !order && <OpeningOverlay until={openingUntil} />}
      <ToastHost />
      <ChatWidget />
    </>
  );
}
