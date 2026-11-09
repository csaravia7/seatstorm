CREATE TABLE concert (
  concert_id TEXT PRIMARY KEY, name TEXT NOT NULL, venue TEXT NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL, sale_status TEXT NOT NULL DEFAULT 'CLOSED' CHECK (sale_status IN ('OPEN','CLOSED'))
);
CREATE TABLE section (
  concert_id TEXT NOT NULL REFERENCES concert, section_id TEXT NOT NULL, section_index INT NOT NULL,
  label TEXT NOT NULL, price_cents INT NOT NULL, rows INT NOT NULL, seats_per_row INT NOT NULL,
  PRIMARY KEY (concert_id, section_id)
);
CREATE TABLE reset_run (
  reset_id TEXT PRIMARY KEY, requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  pending TEXT[] NOT NULL, done BOOLEAN NOT NULL DEFAULT FALSE
);
