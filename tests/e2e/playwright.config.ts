import { defineConfig, devices } from '@playwright/test';

// Runs against a live stack (`make demo-up`). First time: `npx playwright install chromium`.
//   BASE_URL     web entrypoint (nginx), default http://localhost:8080
//   CONSOLE_PIN  presenter PIN, default 4321
export default defineConfig({
  testDir: './specs',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    locale: 'es-ES',
  },
  projects: [
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
});
