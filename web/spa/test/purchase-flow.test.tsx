import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { PublicPage } from '../src/pages/PublicPage';
import { useMapStore } from '../src/state/mapStore';
import { usePurchaseStore } from '../src/state/purchaseStore';
import { handleMe } from '../src/state/flow';

class FakeEventSource {
  static CLOSED = 2;
  readyState = 1;
  onerror: (() => void) | null = null;
  addEventListener() {}
  close() {}
  constructor(public url: string) {}
}

const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status });

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource);
  vi.stubGlobal('navigator', { ...navigator, vibrate: vi.fn() });
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input);
    if (url.endsWith('/api/me')) return json({ buyerId: 'b_1', nickname: 'marco' });
    if (url.includes('/api/my/orders')) return json([{ orderId: 'o1', status: 'CONFIRMED', ticketQr: 'SEATSTORM-TICKET-1' }]);
    return json({}, 404);
  });
  useMapStore.setState({ saleStatus: 'OPEN', connected: true, priceBySection: { 'PLATEA-A': 9000 }, seats: new Map() });
  usePurchaseStore.setState({ me: { buyerId: 'b_1', nickname: 'marco' }, sessionChecked: true, selection: { sectionId: null, seatIds: [] }, order: null, notice: null, toast: null });
});
afterEach(() => vi.unstubAllGlobals());

const held = (ms: number) => ({
  orderId: 'o1', status: 'HELD' as const, reason: null, heldUntilMs: Date.now() + ms,
  sectionId: 'PLATEA-A', seatIds: ['PLATEA-A:B07', 'PLATEA-A:B08'], totalCents: 18000,
});

describe('purchase states', () => {
  it('HELD shows a countdown with Pagar and Cancelar', async () => {
    usePurchaseStore.setState({ order: held(30_000) });
    render(<PublicPage />);
    expect(await screen.findByText('Tus asientos están retenidos')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pagar' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    expect(screen.getByRole('timer')).toBeInTheDocument();
  });

  it('HELD falls to "Se acabó el tiempo" when the countdown reaches zero', async () => {
    usePurchaseStore.setState({ order: held(400) });
    render(<PublicPage />);
    expect(await screen.findByText('Se acabó el tiempo', {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it('REJECTED returns to the map with the notice', async () => {
    usePurchaseStore.setState({ order: { ...held(0), status: 'PLACED', heldUntilMs: null } });
    render(<PublicPage />);
    act(() => handleMe({ type: 'order', buyerId: 'b_1', orderId: 'o1', status: 'REJECTED', reason: 'SEAT_TAKEN', heldUntilMs: null }));
    expect(await screen.findByText(/Alguien llegó antes/)).toBeInTheDocument();
    expect(screen.getByLabelText('Mapa del recinto Neon Tide · Arena Norte')).toBeInTheDocument();
    expect(usePurchaseStore.getState().order).toBeNull();
  });

  it('PAYING shows progress, then CONFIRMED shows the ticket QR and vibrates', async () => {
    usePurchaseStore.setState({ order: { ...held(0), status: 'PAYING', heldUntilMs: null } });
    render(<PublicPage />);
    expect(await screen.findByText('Procesando tu pago…')).toBeInTheDocument();
    act(() => handleMe({ type: 'order', buyerId: 'b_1', orderId: 'o1', status: 'CONFIRMED', reason: null, heldUntilMs: null }));
    expect(await screen.findByText('¡Listo! Tu entrada')).toBeInTheDocument();
    expect(await screen.findByAltText('Código QR de tu entrada', {}, { timeout: 3000 })).toBeInTheDocument();
    expect(navigator.vibrate).toHaveBeenCalledTimes(1);
  });

  it('a reset event drops the active order and returns to the map', async () => {
    usePurchaseStore.setState({ order: held(30_000) });
    render(<PublicPage />);
    act(() => usePurchaseStore.getState().resetAll());
    expect(await screen.findByText(/La demo se reinició/)).toBeInTheDocument();
  });

  it('shows the waiting room while the sale is closed', async () => {
    useMapStore.setState({ saleStatus: 'CLOSED' });
    render(<PublicPage />);
    expect(await screen.findByText('La venta abre pronto')).toBeInTheDocument();
  });
});
