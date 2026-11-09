import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { SeatMap } from '../src/components/SeatMap';
import { SectionDetail } from '../src/components/SectionDetail';
import { JoinScreen } from '../src/pages/PublicPage';
import { useMapStore } from '../src/state/mapStore';
import { usePurchaseStore } from '../src/state/purchaseStore';
import { seatAriaLabel, VENUE_SECTIONS } from '../src/lib/venue';

beforeEach(() => {
  useMapStore.setState({ seats: new Map(), priceBySection: { 'PLATEA-A': 9000 } });
  usePurchaseStore.setState({ selection: { sectionId: null, seatIds: [] }, me: null, toast: null });
  vi.restoreAllMocks();
});

describe('SeatMap', () => {
  it('renders 12 sections and 360 seats with descriptive aria-labels', () => {
    const { container } = render(<SeatMap />);
    expect(container.querySelectorAll('rect[data-seat]')).toHaveLength(360);
    expect(VENUE_SECTIONS).toHaveLength(12);
    expect(screen.getByLabelText('Platea A, fila B, asiento 7, disponible')).toBeInTheDocument();
    expect(seatAriaLabel('GRADA-N:C10', 'SOLD')).toBe('Grada N, fila C, asiento 10, vendido');
  });

  it('changes color on a newer version and ignores an older one', () => {
    const { container } = render(<SeatMap />);
    const rect = () => container.querySelector('rect[data-seat="PLATEA-A:B07"]')!;
    act(() => useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:B07', state: 'HELD', version: 12 }]));
    expect(rect().getAttribute('data-state')).toBe('HELD');
    expect(rect().getAttribute('aria-label')).toBe('Platea A, fila B, asiento 7, retenido');
    act(() => useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:B07', state: 'AVAILABLE', version: 11 }]));
    expect(rect().getAttribute('data-state')).toBe('HELD');
    act(() => useMapStore.getState().applyDiffs([{ seatId: 'PLATEA-A:B07', state: 'SOLD', version: 13 }]));
    expect(rect().getAttribute('data-state')).toBe('SOLD');
  });

  it('paints my seats blue', () => {
    const { container } = render(<SeatMap mine={new Set(['PLATEA-A:A01'])} />);
    expect(container.querySelector('rect[data-seat="PLATEA-A:A01"]')!.getAttribute('data-state')).toBe('MINE');
  });
});

describe('SectionDetail selection', () => {
  it('limits to 4 seats with a warning and disables sold seats', async () => {
    useMapStore.setState({ seats: new Map([['PLATEA-A:A10', { state: 'SOLD', version: 1 }]]) });
    render(<SectionDetail sectionId="PLATEA-A" onBack={() => undefined} />);
    const user = userEvent.setup();
    for (const n of [1, 2, 3, 4]) await user.click(screen.getByLabelText(new RegExp(`fila A, asiento ${n}, disponible`)));
    expect(usePurchaseStore.getState().selection.seatIds).toHaveLength(4);
    await user.click(screen.getByLabelText(/fila A, asiento 5, disponible/));
    expect(usePurchaseStore.getState().selection.seatIds).toHaveLength(4);
    expect(usePurchaseStore.getState().toast?.text).toMatch(/Máximo 4/);
    expect(screen.getByLabelText(/fila A, asiento 10, vendido/)).toBeDisabled();
  });
});

describe('JoinScreen', () => {
  it('shows a validation error without reloading and does not call the API', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    render(<JoinScreen />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Elige tu apodo'), 'ab');
    await user.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(screen.getByRole('alert')).toHaveTextContent(/entre 3 y 16/);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('creates the session with a valid nickname', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ buyerId: 'b_1', nickname: 'marco' }), { status: 200 }));
    render(<JoinScreen />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Elige tu apodo'), 'marco');
    await user.click(screen.getByRole('button', { name: 'Entrar' }));
    await vi.waitFor(() => expect(usePurchaseStore.getState().me?.nickname).toBe('marco'));
  });

  it('shows the server-side error (bad word) inline', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ code: 'VALIDATION_ERROR', message: 'Ese apodo no está permitido.' }), { status: 400 }));
    render(<JoinScreen />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Elige tu apodo'), 'malapalabra');
    await user.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('no está permitido');
    within(document.body);
  });
});

describe('static assets', () => {
  it('index.html references no external host', () => {
    const html = readFileSync(resolve(__dirname, '../index.html'), 'utf8');
    const urls = [...html.matchAll(/(?:src|href)=["']([^"']+)["']/g)].map((m) => m[1]!);
    for (const u of urls) expect(u, u).not.toMatch(/^(https?:)?\/\//);
    expect(html).not.toMatch(/https?:\/\//);
  });

  it('source files do not hard-code external hosts', () => {
    const css = readFileSync(resolve(__dirname, '../src/styles.css'), 'utf8');
    expect(css).not.toMatch(/url\(\s*['"]?https?:/);
    expect(css).not.toMatch(/@import/);
  });
});
