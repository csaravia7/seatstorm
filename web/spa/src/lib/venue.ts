// Static venue geometry and labels (spec 5.1). Prices always come from the API.
export interface VenueSection {
  sectionId: string;
  index: number;
  label: string;
}

export const VENUE_SECTIONS: VenueSection[] = [
  ['PLATEA-A', 'Platea A'], ['PLATEA-B', 'Platea B'], ['PLATEA-C', 'Platea C'], ['PLATEA-D', 'Platea D'],
  ['PALCO-1', 'Palco 1'], ['PALCO-2', 'Palco 2'], ['PALCO-3', 'Palco 3'], ['PALCO-4', 'Palco 4'],
  ['GRADA-N', 'Grada N'], ['GRADA-S', 'Grada S'], ['GRADA-E', 'Grada E'], ['GRADA-O', 'Grada O'],
].map(([sectionId, label], index) => ({ sectionId: sectionId!, label: label!, index }));

export const ROWS = ['A', 'B', 'C'] as const;
export const SEATS_PER_ROW = 10;
export const MAX_SEATS_PER_ORDER = 4;

export const sectionLabel = (sectionId: string) => VENUE_SECTIONS.find((s) => s.sectionId === sectionId)?.label ?? sectionId;

export const seatIdOf = (sectionId: string, row: string, n: number) => `${sectionId}:${row}${String(n).padStart(2, '0')}`;

export function parseSeatId(seatId: string): { sectionId: string; row: string; number: number } | null {
  const m = /^(.+):([A-Z])(\d{2})$/.exec(seatId);
  return m ? { sectionId: m[1]!, row: m[2]!, number: Number(m[3]) } : null;
}

export const STATE_LABEL: Record<string, string> = { AVAILABLE: 'disponible', HELD: 'retenido', SOLD: 'vendido' };

export function seatAriaLabel(seatId: string, state: string, mine = false): string {
  const p = parseSeatId(seatId);
  if (!p) return seatId;
  const st = mine ? 'seleccionado por ti' : (STATE_LABEL[state] ?? state);
  return `${sectionLabel(p.sectionId)}, fila ${p.row}, asiento ${p.number}, ${st}`;
}

// SVG ring layout: stage in the centre, 12 sections around it, index 0 at the top going clockwise.
export const VIEW = 1000;
const CX = 500;
const CY = 500;
const ROW_RADIUS = [300, 336, 372];
const SEAT_SIZE = 11;
const SECTION_SPAN = 30; // degrees
const SECTION_GAP = 4;

export interface SeatPos {
  seatId: string;
  x: number;
  y: number;
  size: number;
}

const rad = (deg: number) => (deg * Math.PI) / 180;

export function sectionAngle(index: number): number {
  return index * SECTION_SPAN; // 0 = top
}

export function labelPos(index: number): { x: number; y: number } {
  const a = rad(sectionAngle(index) + SECTION_SPAN / 2 - 90);
  return { x: CX + 425 * Math.cos(a), y: CY + 425 * Math.sin(a) };
}

export function sectionSeatPositions(section: VenueSection): SeatPos[] {
  const start = sectionAngle(section.index) + SECTION_GAP / 2;
  const step = (SECTION_SPAN - SECTION_GAP) / SEATS_PER_ROW;
  const out: SeatPos[] = [];
  ROWS.forEach((row, r) => {
    for (let n = 1; n <= SEATS_PER_ROW; n++) {
      const a = rad(start + step * (n - 0.5) - 90);
      out.push({
        seatId: seatIdOf(section.sectionId, row, n),
        x: CX + ROW_RADIUS[r]! * Math.cos(a) - SEAT_SIZE / 2,
        y: CY + ROW_RADIUS[r]! * Math.sin(a) - SEAT_SIZE / 2,
        size: SEAT_SIZE,
      });
    }
  });
  return out;
}

export const STAGE_CENTER = { x: CX, y: CY };
