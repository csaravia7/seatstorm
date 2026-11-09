import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const bff = process.env.BFF_URL ?? 'http://localhost:3000';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': bff,
      '/admin': bff,
      '/live': bff,
    },
  },
  build: { outDir: 'dist', sourcemap: false },
  test: {
    environment: 'jsdom',
    setupFiles: ['./test/setup.ts'],
    css: false,
  },
});
