// Fixed venue layout (spec 5.1). Duplicated here on purpose: the bots must not depend on the backend modules.
export const SECTIONS = [
  'PLATEA-A', 'PLATEA-B', 'PLATEA-C', 'PLATEA-D',
  'PALCO-1', 'PALCO-2', 'PALCO-3', 'PALCO-4',
  'GRADA-N', 'GRADA-S', 'GRADA-E', 'GRADA-O',
] as const;
export const ROWS = ['A', 'B', 'C'] as const;
export const SEATS_PER_ROW = 10;
export const CONCERT_ID = 'neon-tide-2026';

export const seatId = (section: string, row: string, n: number) => `${section}:${row}${String(n).padStart(2, '0')}`;

export type Rng = () => number;
const int = (rng: Rng, lo: number, hi: number) => lo + Math.floor(rng() * (hi - lo + 1));

export type Profile = 'burst' | 'steady' | 'greedy';

/** Picks 1..4 adjacent seats of a single row. `greedy` biases towards the first rows of PLATEA. */
export function pickSeats(profile: Profile, rng: Rng): { sectionId: string; seatIds: string[] } {
  if (profile === 'greedy') {
    const sectionId = SECTIONS[int(rng, 0, 3)]!;
    const row = ROWS[int(rng, 0, 1)]!;
    const qty = int(rng, 1, 2);
    const start = int(rng, 1, 4);
    return { sectionId, seatIds: Array.from({ length: qty }, (_, i) => seatId(sectionId, row, start + i)) };
  }
  const sectionId = SECTIONS[int(rng, 0, SECTIONS.length - 1)]!;
  const row = ROWS[int(rng, 0, ROWS.length - 1)]!;
  const qty = int(rng, 1, 4);
  const start = int(rng, 1, SEATS_PER_ROW - qty + 1);
  return { sectionId, seatIds: Array.from({ length: qty }, (_, i) => seatId(sectionId, row, start + i)) };
}
