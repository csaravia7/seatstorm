import { expect, test } from '@playwright/test';
import { joinAs, resetDemo } from './helpers.js';

// Scene 2 ("Duelo"): two buyers click Comprar on the same seat; exactly one gets it.
test('dos compradores, un asiento: uno retiene y el otro oye "Alguien llegó antes"', async ({ browser, request, baseURL }) => {
  await resetDemo(request);
  await request.post('/admin/sale/open');

  const open = async (name: string) => {
    const ctx = await browser.newContext({ baseURL, locale: 'es-ES' });
    const page = await ctx.newPage();
    await joinAs(page, name);
    await expect(page.getByLabel('Mapa del recinto Neon Tide · Arena Norte')).toBeVisible({ timeout: 20_000 });
    await page.getByRole('button', { name: 'Ver asientos de Platea A' }).click();
    await page.getByLabel('Platea A, fila A, asiento 1, disponible').click();
    return { ctx, page };
  };
  const [a, b] = [await open('duelo-a'), await open('duelo-b')];

  await Promise.all([
    a.page.getByRole('button', { name: 'Comprar' }).click(),
    b.page.getByRole('button', { name: 'Comprar' }).click(),
  ]);

  const outcome = (p: typeof a.page) =>
    Promise.race([
      p.getByText('Tus asientos están retenidos').waitFor({ timeout: 20_000 }).then(() => 'won'),
      p.getByText(/Alguien llegó antes/).waitFor({ timeout: 20_000 }).then(() => 'lost'),
    ]);
  const results = (await Promise.all([outcome(a.page), outcome(b.page)])).sort();
  expect(results).toEqual(['lost', 'won']);
  await a.ctx.close();
  await b.ctx.close();
});
