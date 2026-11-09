import { create } from 'zustand';
import type { Me, OrderMessage } from '../api/types';
import { MAX_SEATS_PER_ORDER, parseSeatId } from '../lib/venue';
import { applySubmitted, reduceOrder, type ActiveOrder } from './orderLogic';

export interface Toast {
  id: number;
  level: 'info' | 'success' | 'warn' | 'error';
  text: string;
}

export type SelectResult = 'added' | 'removed' | 'max' | 'other-section';

export interface PurchaseState {
  me: Me | null;
  sessionChecked: boolean;
  selection: { sectionId: string | null; seatIds: string[] };
  order: ActiveOrder | null;
  toast: Toast | null;
  /** Notice shown on the map after an order ended (e.g. "Alguien llegó antes"). */
  notice: string | null;

  setMe(me: Me | null): void;
  toggleSeat(seatId: string): SelectResult;
  clearSelection(): void;
  startSubmit(totalCents: number): void;
  submitted(orderId: string): void;
  setOrder(o: ActiveOrder | null): void;
  patchOrder(p: Partial<ActiveOrder>): void;
  applyOrderMessage(msg: OrderMessage): void;
  showToast(level: Toast['level'], text: string): void;
  clearToast(): void;
  setNotice(n: string | null): void;
  /** Demo reset: drop the active order and selection. */
  resetAll(): void;
}

let toastId = 0;

export const usePurchaseStore = create<PurchaseState>((set, get) => ({
  me: null,
  sessionChecked: false,
  selection: { sectionId: null, seatIds: [] },
  order: null,
  toast: null,
  notice: null,

  setMe: (me) => set({ me, sessionChecked: true }),

  toggleSeat(seatId) {
    const p = parseSeatId(seatId);
    if (!p) return 'other-section';
    const { selection } = get();
    if (selection.seatIds.includes(seatId)) {
      const seatIds = selection.seatIds.filter((s) => s !== seatId);
      set({ selection: { sectionId: seatIds.length ? selection.sectionId : null, seatIds } });
      return 'removed';
    }
    if (selection.sectionId && selection.sectionId !== p.sectionId && selection.seatIds.length > 0) return 'other-section';
    if (selection.seatIds.length >= MAX_SEATS_PER_ORDER) return 'max';
    set({ selection: { sectionId: p.sectionId, seatIds: [...selection.seatIds, seatId] } });
    return 'added';
  },

  clearSelection: () => set({ selection: { sectionId: null, seatIds: [] } }),

  startSubmit(totalCents) {
    const { selection } = get();
    set({
      notice: null,
      order: {
        orderId: null,
        status: 'SUBMITTING',
        reason: null,
        heldUntilMs: null,
        sectionId: selection.sectionId ?? '',
        seatIds: selection.seatIds,
        totalCents,
      },
    });
  },

  submitted: (orderId) => set({ order: applySubmitted(get().order, orderId) }),
  setOrder: (order) => set({ order }),
  patchOrder: (p) => set({ order: get().order ? { ...get().order!, ...p } : null }),
  applyOrderMessage: (msg) => set({ order: reduceOrder(get().order, msg) }),

  showToast: (level, text) => set({ toast: { id: ++toastId, level, text } }),
  clearToast: () => set({ toast: null }),
  setNotice: (notice) => set({ notice }),
  resetAll: () => set({ order: null, selection: { sectionId: null, seatIds: [] }, notice: 'La demo se reinició. ¡Vuelve a elegir tus asientos!' }),
}));
