// One URL per step of the buyer journey, so the observability tool (Dynatrace RUM) sees each step as its own page:
//   /            join (nickname)         /espera            sale not open yet
//   /mapa        venue map               /mapa/palco/4      section detail (seat selection)
//   /reservar    placing the order + seats held (Pagar)
//   /payment     payment in progress     /result            ticket (+ /result/expired, /result/cancelled)
import { VENUE_SECTIONS } from './venue';

export const PATHS = {
  join: '/',
  waiting: '/espera',
  map: '/mapa',
  reserve: '/reservar',
  payment: '/payment',
  result: '/result',
} as const;

/** PALCO-4 -> /mapa/palco/4 */
export function sectionPath(sectionId: string): string {
  const [kind, id] = sectionId.toLowerCase().split('-');
  return kind && id ? `${PATHS.map}/${kind}/${id}` : PATHS.map;
}

/** /mapa/palco/4 -> PALCO-4 (null when it is not a known section) */
export function sectionFromPath(pathname: string): string | null {
  const m = /^\/mapa\/([a-z]+)\/([a-z0-9]+)\/?$/i.exec(pathname);
  if (!m) return null;
  const id = `${m[1]!.toUpperCase()}-${m[2]!.toUpperCase()}`;
  return VENUE_SECTIONS.some((s) => s.sectionId === id) ? id : null;
}

/** Path for an order in `status` (null order = browsing the map, the section stays in the URL). */
export function pathForOrder(status: string | undefined, reason?: string | null): string | null {
  switch (status) {
    case undefined:
      return null;
    case 'SUBMITTING':
    case 'PLACED':
    case 'HELD':
      return PATHS.reserve;
    case 'PAYING':
    case 'CONFIRMING':
    case 'REFUNDING':
      return PATHS.payment;
    case 'CONFIRMED':
      return PATHS.result;
    case 'EXPIRED':
      return `${PATHS.result}/expired`;
    case 'CANCELLED':
      return `${PATHS.result}/cancelled`;
    default:
      return null;
  }
}
