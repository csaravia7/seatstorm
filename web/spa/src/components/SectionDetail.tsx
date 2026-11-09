import { useMapStore } from '../state/mapStore';
import { usePurchaseStore } from '../state/purchaseStore';
import { MAX_SEATS_PER_ORDER, ROWS, SEATS_PER_ROW, STATE_LABEL, parseSeatId, seatAriaLabel, seatIdOf, sectionLabel } from '../lib/venue';
import { money } from '../lib/format';

function SeatButton({ seatId, mine }: { seatId: string; mine: boolean }) {
  const entry = useMapStore((s) => s.seats.get(seatId));
  const toggle = usePurchaseStore((s) => s.toggleSeat);
  const showToast = usePurchaseStore((s) => s.showToast);
  const state = entry?.state ?? 'AVAILABLE';
  const selectable = state === 'AVAILABLE' || mine;
  const p = parseSeatId(seatId)!;
  return (
    <button
      type="button"
      className={`seat-btn ${mine ? 'seat-btn-mine' : `seat-btn-${state.toLowerCase()}`}`}
      aria-label={seatAriaLabel(seatId, state, mine)}
      aria-pressed={mine}
      disabled={!selectable}
      onClick={() => {
        const r = toggle(seatId);
        if (r === 'max') showToast('warn', `Máximo ${MAX_SEATS_PER_ORDER} asientos por compra.`);
        if (r === 'other-section') showToast('warn', 'Solo puedes comprar asientos de una misma sección.');
      }}
    >
      {p.number}
    </button>
  );
}

export function SectionDetail({ sectionId, onBack }: { sectionId: string; onBack: () => void }) {
  const price = useMapStore((s) => s.priceBySection[sectionId]);
  const selection = usePurchaseStore((s) => s.selection);
  const mineSet = new Set(selection.seatIds);
  return (
    <section className="section-detail" aria-label={`Asientos de ${sectionLabel(sectionId)}`}>
      <div className="section-head">
        <button type="button" className="btn btn-ghost" onClick={onBack}>← Mapa</button>
        <h2>{sectionLabel(sectionId)}</h2>
        {price !== undefined && <span className="price">{money(price)} c/u</span>}
      </div>
      <p className="legend">
        <i className="dot dot-available" /> {STATE_LABEL.AVAILABLE} <i className="dot dot-held" /> {STATE_LABEL.HELD}{' '}
        <i className="dot dot-sold" /> {STATE_LABEL.SOLD} <i className="dot dot-mine" /> tuyo
      </p>
      {ROWS.map((row) => (
        <div key={row} className="seat-row">
          <span className="row-label" aria-hidden="true">{row}</span>
          <div className="seat-grid">
            {Array.from({ length: SEATS_PER_ROW }, (_, i) => seatIdOf(sectionId, row, i + 1)).map((id) => (
              <SeatButton key={id} seatId={id} mine={mineSet.has(id)} />
            ))}
          </div>
        </div>
      ))}
    </section>
  );
}
