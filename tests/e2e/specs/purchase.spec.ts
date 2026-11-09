import { expect, test } from '@playwright/test';
import { joinAs, resetDemo, watchConsole } from './helpers.js';

// Full purchase flow (US-E8-10): join -> open sale from the console -> pick 2 seats -> buy -> pay -> ticket.
test('unirse, abrir la venta, comprar 2 asientos, pagar y ver la entrada', async ({ browser, request, baseURL }) => {
  await resetDemo(request);

  // 1. The attendee joins and waits for the sale.
  const buyer = await browser.newContext({ baseURL, locale: 'es-ES', hasTouch: true });
  const phone = await buyer.newPage();
  const problems = watchConsole(phone);
  await joinAs(phone, `pw${Date.now().toString(36).slice(-6)}`);
  await expect(phone.getByText('La venta abre pronto')).toBeVisible();

  // 2. The presenter opens the sale from /console.
  const presenter = await browser.newContext({ baseURL, locale: 'es-ES' });
  const console_ = await presenter.newPage();
  await console_.goto('/console');
  await console_.getByLabel('PIN').fill(process.env.CONSOLE_PIN ?? '4321');
  await console_.getByRole('button', { name: 'Entrar' }).click();
  await expect(console_.getByRole('heading', { name: 'Venta' })).toBeVisible();
  await console_.getByRole('button', { name: 'Abrir venta' }).click();
  await expect(console_.getByText('Venta abierta')).toBeVisible();

  // 3. 3 s countdown, then the map; choose 2 seats of one section.
  await expect(phone.getByLabel('Mapa del recinto Neon Tide · Arena Norte')).toBeVisible({ timeout: 20_000 });
  await phone.getByRole('button', { name: 'Ver asientos de Grada N' }).click();
  const free = phone.getByRole('button', { name: /fila A, asiento \d+, disponible/ });
  await free.first().click();
  await free.first().click();
  await expect(phone.getByTestId('qty')).toHaveText('2');
  await expect(phone.getByTestId('total')).toContainText('90'); // 2 x 45.00 USD

  // 4. Buy: seats are held.
  await phone.getByRole('button', { name: 'Comprar' }).click();
  await expect(phone.getByText('Tus asientos están retenidos')).toBeVisible();
  await expect(phone.getByRole('timer')).toBeVisible();

  // 5. Pay, 6. ticket with QR.
  await phone.getByRole('button', { name: 'Pagar' }).click();
  await expect(phone.getByText('¡Listo! Tu entrada')).toBeVisible({ timeout: 30_000 });
  await expect(phone.getByAltText('Código QR de tu entrada')).toBeVisible({ timeout: 15_000 });

  expect(problems, 'no console errors / CSP violations').toEqual([]);
  await buyer.close();
  await presenter.close();
});

test('la pantalla del escenario carga sin errores de CSP', async ({ page }) => {
  const problems = watchConsole(page);
  await page.goto('/stage');
  await expect(page.getByText('Neon Tide · Arena Norte')).toBeVisible();
  await expect(page.getByAltText('QR para unirse')).toBeVisible();
  await expect(page.getByTestId('c-available')).not.toHaveText('');
  expect(problems).toEqual([]);
});

test('el PIN incorrecto no abre la consola', async ({ page }) => {
  await page.goto('/console');
  await page.getByLabel('PIN').fill('0000');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await expect(page.getByRole('alert')).toContainText('PIN incorrecto');
});
