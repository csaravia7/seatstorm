import { request } from './client';
import type {
  BotStatus,
  LeaderboardRow,
  Me,
  PaymentConfig,
  ResetStatus,
  SeatmapDto,
  StatsDto,
} from './types';

export const CONCERT_ID = 'neon-tide-2026';

export const api = {
  createSession: (nickname: string) => request<Me>('POST', '/api/session', { nickname }),
  me: () => request<Me>('GET', '/api/me'),
  config: () => request<{ kafkaUiUrl: string; dynatraceUrl: string }>('GET', '/api/config'),
  seatmap: () => request<SeatmapDto>('GET', `/api/seatmap?concertId=${CONCERT_ID}`),
  stats: () => request<StatsDto>('GET', `/api/stats?concertId=${CONCERT_ID}`),
  leaderboard: () => request<LeaderboardRow[]>('GET', `/api/leaderboard?concertId=${CONCERT_ID}&limit=5`),
  createOrder: (sectionId: string, seatIds: string[], idempotencyKey: string) =>
    request<{ orderId: string; status: string }>(
      'POST',
      '/api/orders',
      { concertId: CONCERT_ID, sectionId, seatIds },
      { 'Idempotency-Key': idempotencyKey },
    ),
  pay: (orderId: string) => request<{ status: string }>('POST', `/api/orders/${orderId}/pay`, { method: 'CARD_SIM' }),
  cancel: (orderId: string) => request<{ status: string }>('POST', `/api/orders/${orderId}/cancel`, {}),
  order: (orderId: string) => request<Record<string, unknown>>('GET', `/api/orders/${orderId}`),
  myOrders: () => request<Record<string, unknown>[]>('GET', '/api/my/orders'),

  adminLogin: (pin: string) => request<{ ok: true }>('POST', '/admin/login', { pin }),
  adminMe: () => request<{ ok: true }>('GET', '/admin/me'),
  saleOpen: () => request<unknown>('POST', '/admin/sale/open', {}),
  saleClose: () => request<unknown>('POST', '/admin/sale/close', {}),
  getPayment: () => request<PaymentConfig>('GET', '/admin/payment'),
  setPayment: (cfg: PaymentConfig) => request<PaymentConfig>('PUT', '/admin/payment', cfg),
  reset: () => request<{ resetId: string }>('POST', '/admin/reset', {}),
  resetStatus: (id: string) => request<ResetStatus>('GET', `/admin/reset/${encodeURIComponent(id)}`),
  botsStart: (b: { count: number; rampSeconds: number; profile: string; payProbability: number }) =>
    request<BotStatus>('POST', '/admin/bots/start', b),
  botsStop: () => request<BotStatus>('POST', '/admin/bots/stop', {}),
  botsStatus: () => request<BotStatus>('GET', '/admin/bots/status'),
};
