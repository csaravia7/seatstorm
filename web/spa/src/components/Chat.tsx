import { useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { SseParser } from '../lib/sse';
import { adoptOrder, restoreActiveOrder } from '../state/flow';

export const SUGGESTIONS = ['¿Por qué perdí mi asiento?', '¿Quedan 2 asientos juntos en platea?', 'Resérvame 2 asientos juntos en Palco 4', '¿Cómo funciona esto?'];
export const CHAT_FALLBACK = 'Ahora mismo no puedo responder. Puedes seguir comprando: el resto de la demo funciona con normalidad.';

interface Msg {
  role: 'user' | 'assistant';
  text: string;
  error?: boolean;
}

export function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const lastUser = useRef<string>('');
  const navigate = useNavigate();

  // UI actions requested by the assistant's tools. Order steps are driven by state (the URL follows it), map pages by URL.
  function runAction(raw: string) {
    try {
      const a = JSON.parse(raw) as { type?: string; path?: string; orderId?: string };
      if (a.type !== 'navigate' || !a.path) return;
      if (a.orderId) void adoptOrder(a.orderId);
      else if (a.path.startsWith('/mapa')) void navigate(a.path);
      else void restoreActiveOrder();
    } catch {
      // ignore malformed action
    }
  }

  async function send(text: string) {
    const message = text.trim();
    if (!message || busy) return;
    lastUser.current = message;
    setInput('');
    setBusy(true);
    setMsgs((m) => [...m.filter((x) => !x.error), { role: 'user', text: message }, { role: 'assistant', text: '' }]);
    const append = (t: string) =>
      setMsgs((m) => {
        const copy = [...m];
        const last = copy[copy.length - 1]!;
        copy[copy.length - 1] = { ...last, text: last.text + t };
        return copy;
      });
    const fail = (t: string) =>
      setMsgs((m) => {
        const copy = [...m];
        copy[copy.length - 1] = { role: 'assistant', text: t, error: true };
        return copy;
      });

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message }),
      });
      if (!res.ok || !res.body) {
        fail(res.status === 429 ? 'Vas muy rápido. Espera un momento y vuelve a intentarlo.' : CHAT_FALLBACK);
        return;
      }
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      const parser = new SseParser();
      let done = false;
      while (!done) {
        const r = await reader.read();
        if (r.done) break;
        for (const ev of parser.push(dec.decode(r.value, { stream: true }))) {
          if (ev.event === 'token') append(ev.data);
          else if (ev.event === 'action') runAction(ev.data);
          else if (ev.event === 'error') {
            fail(ev.data || CHAT_FALLBACK);
            done = true;
          } else if (ev.event === 'done') done = true;
        }
      }
    } catch {
      fail(CHAT_FALLBACK);
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return (
      <button type="button" className="chat-fab" aria-label="Abrir asistente" onClick={() => setOpen(true)}>
        💬
      </button>
    );
  }
  const hasError = msgs.some((m) => m.error);
  return (
    <aside className="chat-panel" aria-label="Asistente">
      <header>
        <strong>Asistente SeatStorm</strong>
        <button type="button" className="btn btn-ghost" aria-label="Cerrar asistente" onClick={() => setOpen(false)}>✕</button>
      </header>
      <div className="chat-log" aria-live="polite">
        {msgs.length === 0 && (
          <div className="chat-suggestions">
            {SUGGESTIONS.map((s) => (
              <button key={s} type="button" className="chip" onClick={() => void send(s)}>{s}</button>
            ))}
          </div>
        )}
        {msgs.map((m, i) => (
          <p key={i} className={`bubble bubble-${m.role}${m.error ? ' bubble-error' : ''}`}>{m.text || '…'}</p>
        ))}
        {hasError && !busy && (
          <button type="button" className="btn" onClick={() => void send(lastUser.current)}>Reintentar</button>
        )}
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void send(input);
        }}
      >
        <input
          value={input}
          maxLength={500}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Escribe tu pregunta"
          aria-label="Mensaje"
        />
        <button type="submit" className="btn btn-primary" disabled={busy || !input.trim()}>Enviar</button>
      </form>
    </aside>
  );
}
