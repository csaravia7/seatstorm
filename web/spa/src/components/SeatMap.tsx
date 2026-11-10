import { memo, useMemo } from 'react';
import { useMapStore } from '../state/mapStore';
import { useConcertName } from '../state/concertStore';
import { STAGE_CENTER, VENUE_SECTIONS, VIEW, labelPos, seatAriaLabel, sectionSeatPositions, type SeatPos } from '../lib/venue';

const SectionSeats = VENUE_SECTIONS.map((s) => ({ section: s, seats: sectionSeatPositions(s) }));

const SeatRect = memo(function SeatRect({ pos, mine }: { pos: SeatPos; mine: boolean }) {
  const entry = useMapStore((s) => s.seats.get(pos.seatId));
  const state = entry?.state ?? 'AVAILABLE';
  const cls = mine ? 'seat seat-mine' : `seat seat-${state.toLowerCase()}`;
  return (
    <rect
      className={cls}
      x={pos.x}
      y={pos.y}
      width={pos.size}
      height={pos.size}
      rx={2}
      data-seat={pos.seatId}
      data-state={mine ? 'MINE' : state}
      role="img"
      aria-label={seatAriaLabel(pos.seatId, state, mine)}
    />
  );
});

export interface SeatMapProps {
  /** Seat ids shown as "mine" (blue): current selection or active order. */
  mine?: ReadonlySet<string>;
  onSectionClick?: (sectionId: string) => void;
  activeSection?: string | null;
  large?: boolean;
}

const EMPTY: ReadonlySet<string> = new Set();

export function SeatMap({ mine = EMPTY, onSectionClick, activeSection, large }: SeatMapProps) {
  const concertName = useConcertName();
  const labels = useMemo(() => VENUE_SECTIONS.map((s) => ({ s, ...labelPos(s.index) })), []);
  return (
    <svg
      className={`seatmap${large ? ' seatmap-large' : ''}`}
      viewBox={`0 0 ${VIEW} ${VIEW}`}
      role="group"
      aria-label={`Mapa del recinto ${concertName}`}
    >
      <circle cx={STAGE_CENTER.x} cy={STAGE_CENTER.y} r={210} className="stage-ring" />
      <rect x={STAGE_CENTER.x - 110} y={STAGE_CENTER.y - 55} width={220} height={110} rx={14} className="stage-box" />
      <text x={STAGE_CENTER.x} y={STAGE_CENTER.y + 8} textAnchor="middle" className="stage-label">ESCENARIO</text>

      {SectionSeats.map(({ section, seats }) => (
        <g key={section.sectionId} className={activeSection === section.sectionId ? 'section section-active' : 'section'}>
          {seats.map((p) => (
            <SeatRect key={p.seatId} pos={p} mine={mine.has(p.seatId)} />
          ))}
        </g>
      ))}

      {labels.map(({ s, x, y }) => (
        <g
          key={s.sectionId}
          className={onSectionClick ? 'section-hit' : undefined}
          onClick={onSectionClick ? () => onSectionClick(s.sectionId) : undefined}
          role={onSectionClick ? 'button' : undefined}
          tabIndex={onSectionClick ? 0 : undefined}
          aria-label={onSectionClick ? `Ver asientos de ${s.label}` : undefined}
          onKeyDown={onSectionClick ? (e) => (e.key === 'Enter' || e.key === ' ') && onSectionClick(s.sectionId) : undefined}
        >
          <circle cx={x} cy={y} r={onSectionClick ? 34 : 28} className="section-badge" />
          <text x={x} y={y + 5} textAnchor="middle" className="section-text">{s.label}</text>
        </g>
      ))}
    </svg>
  );
}
