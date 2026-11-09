CREATE TABLE orders (
  order_id UUID PRIMARY KEY, buyer_id TEXT NOT NULL, nickname TEXT NOT NULL,
  concert_id TEXT NOT NULL, section_id TEXT NOT NULL, seat_ids TEXT[] NOT NULL,
  total_cents INT NOT NULL, currency CHAR(3) NOT NULL DEFAULT 'USD',
  status TEXT NOT NULL, reason TEXT, held_until TIMESTAMPTZ, payment_id TEXT,
  idempotency_key TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (buyer_id, idempotency_key)
);
CREATE UNIQUE INDEX one_active_order_per_buyer ON orders (buyer_id)
  WHERE status IN ('PLACED','HELD','PAYING','CONFIRMING','REFUNDING');
CREATE TABLE sale_state (concert_id TEXT PRIMARY KEY, status TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
