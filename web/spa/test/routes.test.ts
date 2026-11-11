import { describe, expect, it } from 'vitest';
import { pathForOrder, sectionFromPath, sectionPath } from '../src/lib/routes';

describe('journey routes', () => {
  it('maps sections to /mapa/<tipo>/<id> and back', () => {
    expect(sectionPath('PALCO-4')).toBe('/mapa/palco/4');
    expect(sectionPath('PLATEA-A')).toBe('/mapa/platea/a');
    expect(sectionFromPath('/mapa/palco/4')).toBe('PALCO-4');
    expect(sectionFromPath('/mapa/grada/n/')).toBe('GRADA-N');
    expect(sectionFromPath('/mapa/palco/9')).toBeNull();
    expect(sectionFromPath('/mapa')).toBeNull();
  });
  it('maps order status to the step URL', () => {
    expect(pathForOrder(undefined)).toBeNull();
    for (const s of ['SUBMITTING', 'PLACED', 'HELD']) expect(pathForOrder(s)).toBe('/reservar');
    for (const s of ['PAYING', 'CONFIRMING', 'REFUNDING']) expect(pathForOrder(s)).toBe('/payment');
    expect(pathForOrder('CONFIRMED')).toBe('/result');
    expect(pathForOrder('EXPIRED')).toBe('/result/expired');
    expect(pathForOrder('CANCELLED')).toBe('/result/cancelled');
  });
});
