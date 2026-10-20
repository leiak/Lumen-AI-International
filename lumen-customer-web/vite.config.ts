// `vitest/config` re-exports vite's defineConfig with the `test` key typed,
// so the Vitest block below type-checks under `tsc -b`.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { port: 5174, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test-setup.ts',
  },
});