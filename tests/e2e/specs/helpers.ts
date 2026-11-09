import { expect, type APIRequestContext, type Browser, type Page } from '@playwright/test';

export const CONSOLE_PIN = process.env.CONSOLE_PIN ?? '4321';

/** Presenter session via API (cookie ss_admin lives in the request context). */
export async function adminApi(request: APIRequestContext) {
  const login = await request.post('/admin/login', { data: { pin: CONSOLE_PIN } });
  expect(login.status(), 'presenter login').toBe(200);
}

/** Close the sale and reset the demo so every run starts from 360 green seats. */
export async function resetDemo(request: APIRequestContext) {
  await adminApi(request);
  await request.post('/admin/sale/close');
  const r = await request.post('/admin/reset');
  expect(r.status()).toBe(202);
  const { resetId } = await r.json();
  await expect
    .poll(async () => (await (await request.get(`/admin/reset/${resetId}`)).json()).done, { timeout: 45_000, intervals: [500, 1000] })
    .toBe(true);
}

export async function joinAs(page: Page, nickname: string) {
  await page.goto('/');
  await page.getByLabel('Elige tu apodo').fill(nickname);
  await page.getByRole('button', { name: 'Entrar' }).click();
}

/** Collects console errors and CSP violations of a page. */
export function watchConsole(page: Page): string[] {
  const problems: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`console: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
  return problems;
}

export async function newPage(browser: Browser, baseURL: string | undefined) {
  const context = await browser.newContext({ baseURL, locale: 'es-ES' });
  return { context, page: await context.newPage() };
}
