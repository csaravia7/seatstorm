CREATE TABLE ticket (
  ticket_id UUID PRIMARY KEY, order_id UUID NOT NULL UNIQUE, buyer_id TEXT NOT NULL,
  concert_id TEXT NOT NULL, seat_ids TEXT[] NOT NULL, qr_payload TEXT NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
