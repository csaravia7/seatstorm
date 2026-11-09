CREATE TABLE outbox (
  id             BIGSERIAL PRIMARY KEY,
  event_id       UUID        NOT NULL UNIQUE,
  topic          TEXT        NOT NULL,
  msg_key        TEXT        NOT NULL,
  partition_hint INT,
  event_type     TEXT        NOT NULL,        -- simple class name of the Avro record
  payload_json   TEXT        NOT NULL,        -- Avro JSON encoding
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at        TIMESTAMPTZ
);
CREATE INDEX outbox_pending_idx ON outbox (id) WHERE sent_at IS NULL;

CREATE TABLE inbox (
  consumer     TEXT        NOT NULL,
  event_id     UUID        NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
