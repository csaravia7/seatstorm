-- Seed generated from io.seatstorm.contracts.Sections / VenueLayout (a test keeps them in sync).
INSERT INTO concert (concert_id, name, venue, starts_at, sale_status)
VALUES ('neon-tide-2026', 'Neon Tide · Arena Norte', 'Arena Norte', '2026-12-12 21:00:00+00', 'CLOSED')
ON CONFLICT (concert_id) DO NOTHING;

INSERT INTO section (concert_id, section_id, section_index, label, price_cents, rows, seats_per_row) VALUES
  ('neon-tide-2026', 'PLATEA-A', 0, 'Platea A', 9000, 3, 10),
  ('neon-tide-2026', 'PLATEA-B', 1, 'Platea B', 9000, 3, 10),
  ('neon-tide-2026', 'PLATEA-C', 2, 'Platea C', 9000, 3, 10),
  ('neon-tide-2026', 'PLATEA-D', 3, 'Platea D', 9000, 3, 10),
  ('neon-tide-2026', 'PALCO-1', 4, 'Palco 1', 15000, 3, 10),
  ('neon-tide-2026', 'PALCO-2', 5, 'Palco 2', 15000, 3, 10),
  ('neon-tide-2026', 'PALCO-3', 6, 'Palco 3', 15000, 3, 10),
  ('neon-tide-2026', 'PALCO-4', 7, 'Palco 4', 15000, 3, 10),
  ('neon-tide-2026', 'GRADA-N', 8, 'Grada N', 4500, 3, 10),
  ('neon-tide-2026', 'GRADA-S', 9, 'Grada S', 4500, 3, 10),
  ('neon-tide-2026', 'GRADA-E', 10, 'Grada E', 4500, 3, 10),
  ('neon-tide-2026', 'GRADA-O', 11, 'Grada O', 4500, 3, 10)
ON CONFLICT (concert_id, section_id) DO NOTHING;
