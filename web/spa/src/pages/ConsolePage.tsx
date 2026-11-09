import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { BotStatus, PaymentConfig } from '../api/types';

const errText = (e: unknown) => (e instanceof ApiError ? e.message : 'No hay respuesta del servidor.');

function Login({ onOk }: { onOk: () => void }) {
  const [pin, setPin] = useState('');
  const [err, setErr] = useState<string | null>(null);
  return (
    <main className="screen screen-center">
      <h1>Consola del presentador</h1>
      <form
        className="card"
        onSubmit={async (e) => {
          e.preventDefault();
          try {
            await api.adminLogin(pin);
            onOk();
          } catch (x) {
            setErr(errText(x));
          }
        }}
      >
        <label htmlFor="pin">PIN</label>
        <input id="pin" type="password" inputMode="numeric" value={pin} onChange={(e) => setPin(e.target.value)} autoComplete="off" />
        {err && <p className="error" role="alert">{err}</p>}
        <button type="submit" className="btn btn-primary">Entrar</button>
      </form>
    </main>
  );
}

function SalePanel() {
  const [msg, setMsg] = useState('');
  const act = async (f: () => Promise<unknown>, ok: string) => {
    try {
      await f();
      setMsg(ok);
    } catch (e) {
      setMsg(errText(e));
    }
  };
  return (
    <section className="panel" aria-label="Venta">
      <h2>Venta</h2>
      <div className="actions">
        <button className="btn btn-primary" onClick={() => void act(api.saleOpen, 'Venta abierta')}>Abrir venta</button>
        <button className="btn" onClick={() => void act(api.saleClose, 'Venta cerrada')}>Cerrar venta</button>
      </div>
      <p role="status" className="muted">{msg}</p>
    </section>
  );
}

function PaymentPanel() {
  const [cfg, setCfg] = useState<PaymentConfig>({ mode: 'OK', failureRate: 0.15, latencyMs: 1500 });
  const [msg, setMsg] = useState('');
  useEffect(() => {
    api.getPayment().then(setCfg).catch(() => setMsg('No se pudo leer el modo de pago.'));
  }, []);
  const apply = async () => {
    try {
      // Show exactly what the service stored, not what we sent.
      setCfg(await api.setPayment({ mode: cfg.mode, failureRate: cfg.failureRate, latencyMs: cfg.latencyMs }));
      setMsg(`Modo aplicado`);
    } catch (e) {
      setMsg(errText(e));
    }
  };
  return (
    <section className="panel" aria-label="Pago">
      <h2>Pasarela de pago</h2>
      <div className="seg" role="radiogroup" aria-label="Modo de pago">
        {(['OK', 'FLAKY', 'SLOW', 'PAUSED'] as const).map((m) => (
          <button key={m} role="radio" aria-checked={cfg.mode === m} className={`btn ${cfg.mode === m ? 'btn-primary' : ''}`} onClick={() => setCfg({ ...cfg, mode: m })}>{m}</button>
        ))}
      </div>
      <label>Tasa de fallos: {Math.round((cfg.failureRate ?? 0) * 100)} %
        <input type="range" min={0} max={1} step={0.05} value={cfg.failureRate ?? 0} onChange={(e) => setCfg({ ...cfg, failureRate: Number(e.target.value) })} />
      </label>
      <label>Latencia (ms)
        <input type="number" min={0} max={30000} value={cfg.latencyMs ?? 0} onChange={(e) => setCfg({ ...cfg, latencyMs: Number(e.target.value) })} />
      </label>
      <button className="btn btn-primary" onClick={() => void apply()}>Aplicar</button>
      <p role="status" className="muted">Actual: <b>{cfg.mode}</b>{cfg.mode === 'FLAKY' ? ` · fallos ${Math.round((cfg.failureRate ?? 0) * 100)} %` : ''}{cfg.mode === 'SLOW' ? ` · ${cfg.latencyMs} ms` : ''} {msg}</p>
    </section>
  );
}

function BotsPanel() {
  const [count, setCount] = useState(200);
  const [profile, setProfile] = useState<'burst' | 'steady' | 'greedy'>('burst');
  const [pay, setPay] = useState(0.85);
  const [msg, setMsg] = useState('');
  const { data, refetch } = useQuery<BotStatus>({ queryKey: ['bots'], queryFn: api.botsStatus, refetchInterval: 1000, retry: false });
  const start = async () => {
    try {
      await api.botsStart({ count, rampSeconds: profile === 'steady' ? 30 : 2, profile, payProbability: pay });
      setMsg('');
      void refetch();
    } catch (e) {
      setMsg(errText(e));
    }
  };
  return (
    <section className="panel" aria-label="Bots">
      <h2>Bots</h2>
      <label>Cantidad: {count}
        <input type="range" min={10} max={1000} step={10} value={count} onChange={(e) => setCount(Number(e.target.value))} />
      </label>
      <label>Perfil
        <select value={profile} onChange={(e) => setProfile(e.target.value as typeof profile)}>
          <option value="burst">Ráfaga (burst)</option>
          <option value="steady">Constante (steady)</option>
          <option value="greedy">Codiciosos (greedy)</option>
        </select>
      </label>
      <label>Probabilidad de pagar: {Math.round(pay * 100)} %
        <input type="range" min={0} max={1} step={0.05} value={pay} onChange={(e) => setPay(Number(e.target.value))} />
      </label>
      <div className="actions">
        <button className="btn btn-primary" onClick={() => void start()}>Iniciar</button>
        <button className="btn" onClick={() => void api.botsStop().then(() => refetch()).catch((e) => setMsg(errText(e)))}>Detener</button>
      </div>
      {msg && <p className="error" role="alert">{msg}</p>}
      <p role="status" className="muted" data-testid="bot-status">
        {data ? `${data.running ? 'En marcha' : 'Detenidos'} · activos ${data.active} · pedidos ${data.placed} · confirmados ${data.confirmed} · rechazados ${data.rejected} · errores ${data.errors}` : 'Sin estado'}
      </p>
    </section>
  );
}

function ResetPanel() {
  const [confirming, setConfirming] = useState(false);
  const [progress, setProgress] = useState<{ total: number; pending: string[]; done: boolean } | null>(null);
  const [msg, setMsg] = useState('');
  const timer = useRef<ReturnType<typeof setInterval>>(undefined);
  useEffect(() => () => clearInterval(timer.current), []);

  const run = async () => {
    setConfirming(false);
    try {
      const { resetId } = await api.reset();
      setProgress({ total: 0, pending: [], done: false });
      clearInterval(timer.current);
      timer.current = setInterval(async () => {
        try {
          const st = await api.resetStatus(resetId);
          setProgress((p) => ({ total: Math.max(p?.total ?? 0, st.pending.length), pending: st.pending, done: st.done }));
          if (st.done) clearInterval(timer.current);
        } catch {
          // keep polling
        }
      }, 1000);
    } catch (e) {
      setMsg(errText(e));
    }
  };
  const pct = progress ? (progress.done ? 100 : progress.total ? Math.round(((progress.total - progress.pending.length) / progress.total) * 100) : 0) : 0;
  return (
    <section className="panel" aria-label="Reset">
      <h2>Reset</h2>
      {!confirming ? (
        <button className="btn btn-danger" onClick={() => setConfirming(true)}>Reiniciar demo</button>
      ) : (
        <div role="alertdialog" aria-label="Confirmar reset">
          <p>Esto borra todas las órdenes y libera los 360 asientos. ¿Continuar?</p>
          <div className="actions">
            <button className="btn btn-danger" onClick={() => void run()}>Sí, reiniciar</button>
            <button className="btn" onClick={() => setConfirming(false)}>No</button>
          </div>
        </div>
      )}
      {progress && (
        <div>
          <progress max={100} value={pct} aria-label="Avance del reset" />
          <p role="status" className="muted">{progress.done ? 'Reset completado' : `Esperando: ${progress.pending.join(', ') || '…'}`}</p>
        </div>
      )}
      {msg && <p className="error" role="alert">{msg}</p>}
    </section>
  );
}

function LinksPanel() {
  const { data } = useQuery({ queryKey: ['config'], queryFn: api.config });
  return (
    <section className="panel" aria-label="Enlaces">
      <h2>Enlaces</h2>
      <ul className="links">
        <li><a href="/stage" target="_blank" rel="noreferrer">Pantalla del escenario</a></li>
        {data?.kafkaUiUrl && <li><a href={data.kafkaUiUrl} target="_blank" rel="noreferrer">Kafka UI</a></li>}
        {data?.dynatraceUrl && <li><a href={data.dynatraceUrl} target="_blank" rel="noreferrer">Dynatrace</a></li>}
        {data && !data.kafkaUiUrl && !data.dynatraceUrl && <li className="muted">Sin enlaces configurados</li>}
      </ul>
    </section>
  );
}

export function ConsolePage() {
  const qc = useQueryClient();
  const { data, isPending, refetch } = useQuery({ queryKey: ['admin-me'], queryFn: () => api.adminMe().then(() => true).catch(() => false), retry: false });
  if (isPending) return <main className="screen screen-center"><div className="spinner" /></main>;
  if (!data) return <Login onOk={() => { void qc.invalidateQueries({ queryKey: ['admin-me'] }); void refetch(); }} />;
  return (
    <main className="console">
      <h1>Consola del presentador</h1>
      <div className="panels">
        <SalePanel />
        <PaymentPanel />
        <BotsPanel />
        <ResetPanel />
        <LinksPanel />
      </div>
    </main>
  );
}
