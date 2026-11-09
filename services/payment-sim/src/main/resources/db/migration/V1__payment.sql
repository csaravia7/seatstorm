CREATE TABLE payment (
  payment_id UUID PRIMARY KEY, order_id UUID NOT NULL UNIQUE, amount_cents INT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('AUTHORIZED','FAILED','REFUNDED')),
  reason TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE payment_config (
  id INT PRIMARY KEY CHECK (id = 1),
  mode TEXT NOT NULL DEFAULT 'OK' CHECK (mode IN ('OK','FLAKY','SLOW','PAUSED')),
  failure_rate NUMERIC(3,2) NOT NULL DEFAULT 0.15, latency_ms INT NOT NULL DEFAULT 1500,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO payment_config (id) VALUES (1);
