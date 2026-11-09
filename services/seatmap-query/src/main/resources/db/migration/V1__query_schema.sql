CREATE TABLE seat_view (
  concert_id TEXT NOT NULL, seat_id TEXT NOT NULL, section_id TEXT NOT NULL,
  row_label CHAR(1) NOT NULL, seat_number INT NOT NULL,
  state TEXT NOT NULL, version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (concert_id, seat_id)
);
CREATE INDEX seat_view_section_idx ON seat_view (concert_id, section_id);

CREATE TABLE order_view (
  order_id UUID PRIMARY KEY, buyer_id TEXT NOT NULL, nickname TEXT NOT NULL, concert_id TEXT NOT NULL,
  section_id TEXT NOT NULL, seat_ids TEXT[] NOT NULL, total_cents INT NOT NULL,
  status TEXT NOT NULL, reason TEXT, held_until TIMESTAMPTZ,
  placed_at TIMESTAMPTZ NOT NULL, confirmed_at TIMESTAMPTZ,
  ticket_id UUID, ticket_qr TEXT,
  last_event_ms BIGINT NOT NULL DEFAULT 0,     -- guards status updates against out-of-order delivery
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX order_view_buyer_idx ON order_view (buyer_id, placed_at DESC);

CREATE TABLE order_timeline (
  id BIGSERIAL PRIMARY KEY, order_id UUID NOT NULL, event_type TEXT NOT NULL,
  detail JSONB NOT NULL DEFAULT '{}', occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX order_timeline_idx ON order_timeline (order_id, occurred_at);

CREATE TABLE sale_view (concert_id TEXT PRIMARY KEY, status TEXT NOT NULL);
