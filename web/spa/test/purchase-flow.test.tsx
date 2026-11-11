import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
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
    render(<MemoryRouter><PublicPage /></MemoryRouter>);
    expect(await screen.findByText('Tus asientos están retenidos')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pagar' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    expect(screen.getByRole('timer')).toBeInTheDocument();
  });

  it('HELD falls to "Se acabó el tiempo" when the countdown reaches zero', async () => {
    usePurchaseStore.setState({ order: held(400) });
    render(<MemoryRouter><PublicPage /></MemoryRouter>);
    expect(await screen.findByText('Se acabó el tiempo', {}, { timeout: 3000 })).toBeInTheDocument();
  });

  it('REJECTED returns to the map with the notice', async () => {
    usePurchaseStore.setState({ order: { ...held(0), status: 'PLACED', heldUntilMs: null } });
    render(<MemoryRouter><PublicPage /></MemoryRouter>);
    act(() => handleMe({ type: 'order', buyerId: 'b_1', orderId: 'o1', status: 'REJECTED', reason: 'SEAT_TAKEN', heldUntilMs: null }));
    expect(await screen.findByText(/Alguien llegó antes/)).toBeInTheDocument();
    expect(screen.getByLabelText('Mapa del recinto Neon Tide · Arena Norte')).toBeInTheDocument();
    expect(usePurchaseStore.getState().order).toBeNull();
  });

  it('PAYING shows progress, then CONFIRMED shows the ticket QR and vibrates', async () => {
    usePurchaseStore.setState({ order: { ...held(0), status: 'PAYING', heldUntilMs: null } });
    render(<MemoryRouter><PublicPage /></MemoryRouter>);
    expect(await screen.findByText('Procesando tu pago…')).toBeInTheDocument();
    act(() => handleMe({ type: 'order', buyerId: 'b_1', orderId: 'o1', status: 'CONFIRMED', reason: null, heldUntilMs: null }));
    expect(await screen.findByText('¡Listo! Tu entrada')).toBeInTheDocument();
    expect(await screen.findByAltText('Código QR de tu entrada', {}, { timeout: 3000 })).toBeInTheDocument();
    expect(navigator.vibrate).toHaveBeenCalledTimes(1);
  });

  it('a reset event drops the active order and returns to the map', async () => {
    usePurchaseStore.setState({ order: held(30_000) });
    render(<MemoryRouter><PublicPage /></MemoryRouter>);
    act(() => usePurchaseStore.getState().resetAll());
    expect(await screen.findByText(/La demo se reinició/)).toBeInTheDocument();
  });

  it('shows the waiting room while the sale is closed', async () => {
    useMapStore.setState({ saleStatus: 'CLOSED' });
    render(<MemoryRouter><PublicPage /></MemoryRouter>);
    expect(await screen.findByText('La venta abre pronto')).toBeInTheDocument();
  });
});

function Path() {
  return <output data-testid="path">{useLocation().pathname}</output>;
}

describe('URL follows the journey', () => {
  const at = (initial: string) =>
    render(<MemoryRouter initialEntries={[initial]}><PublicPage /><Path /></MemoryRouter>);
  const path = () => screen.getByTestId('path').textContent;

  it('map -> /mapa, keeps a valid section deep link', async () => {
    at('/');
    await vi.waitFor(() => expect(path()).toBe('/mapa'));
    cleanup();
    at('/mapa/palco/4');
    await screen.findByText('← Mapa');
    expect(path()).toBe('/mapa/palco/4');
  });

  it('HELD -> /reservar, PAYING -> /payment, CONFIRMED -> /result', async () => {
    usePurchaseStore.setState({ order: held(30_000) });
    at('/mapa');
    await vi.waitFor(() => expect(path()).toBe('/reservar'));
    act(() => usePurchaseStore.getState().patchOrder({ status: 'PAYING' }));
    await vi.waitFor(() => expect(path()).toBe('/payment'));
    act(() => usePurchaseStore.getState().patchOrder({ status: 'CONFIRMED' }));
    await vi.waitFor(() => expect(path()).toBe('/result'));
  });
});
