export type SeatState = 'AVAILABLE' | 'HELD' | 'SOLD';

export interface SeatDto {
  seatId: string;
  row: string;
  number: number;
  state: SeatState;
  version: number;
}
export interface SectionDto {
  sectionId: string;
  index: number;
  label: string;
  priceCents: number;
  seats: SeatDto[];
}
export interface SeatmapDto {
  concertId: string;
  asOfMs: number;
  sections: SectionDto[];
}
export interface StatsDto {
  available: number;
  held: number;
  sold: number;
  saleStatus: 'OPEN' | 'CLOSED';
}
export interface SeatDiff {
  seatId: string;
  sectionId?: string;
  state: SeatState;
  version: number;
}
export interface LeaderboardRow {
  nickname: string;
  tickets: number;
  fastestMs: number;
}
export interface FeedItem {
  name: string;
  detail: string;
  orderId?: string;
  at: number;
}
export interface Me {
  buyerId: string;
  nickname: string;
}
export type OrderStatus =
  | 'SUBMITTING'
  | 'PLACED'
  | 'HELD'
  | 'PAYING'
  | 'CONFIRMING'
  | 'CONFIRMED'
  | 'REFUNDING'
  | 'REJECTED'
  | 'CANCELLED'
  | 'EXPIRED';

/** `order` message delivered through the SSE `me` event. */
export interface OrderMessage {
  type: 'order';
  buyerId: string;
  orderId: string;
  status: OrderStatus;
  reason: string | null;
  heldUntilMs: number | null;
}
export interface NotifyMessage {
  type: 'notify';
  buyerId: string;
  level: 'info' | 'success' | 'warn' | 'error';
  text: string;
}
export type MeMessage = OrderMessage | NotifyMessage;

export interface PaymentConfig {
  mode: 'OK' | 'FLAKY' | 'SLOW' | 'PAUSED';
  failureRate?: number;
  latencyMs?: number;
}
export interface BotStatus {
  running: boolean;
  active: number;
  placed: number;
  confirmed: number;
  rejected: number;
  errors: number;
}
export interface ResetStatus {
  done: boolean;
  pending: string[];
}
