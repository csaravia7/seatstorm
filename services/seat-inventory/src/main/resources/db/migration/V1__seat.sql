CREATE TABLE seat (
  concert_id TEXT NOT NULL, seat_id TEXT NOT NULL, section_id TEXT NOT NULL,
  row_label CHAR(1) NOT NULL, seat_number INT NOT NULL, price_cents INT NOT NULL,
  state TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (state IN ('AVAILABLE','HELD','SOLD')),
  order_id UUID, held_until TIMESTAMPTZ,
  buyer_id TEXT,   -- extension of spec 6.3: SeatsSold/SeatsHeld carry buyerId, ConfirmSeats does not
  version BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (concert_id, seat_id),
  CHECK ( (state='AVAILABLE' AND order_id IS NULL AND held_until IS NULL)
       OR (state='HELD' AND order_id IS NOT NULL AND held_until IS NOT NULL)
       OR (state='SOLD' AND order_id IS NOT NULL) )
);
CREATE INDEX seat_expiry_idx  ON seat (held_until) WHERE state = 'HELD';
CREATE INDEX seat_order_idx   ON seat (order_id);
CREATE INDEX seat_section_idx ON seat (concert_id, section_id);
