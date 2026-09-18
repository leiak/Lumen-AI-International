import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// https://vitejs.dev/config/
// NOTE: Backend exposes endpoints under `/api/v1/...` (see lumen-parent SecurityConfig).
//       The frontend axios baseURL is `/api/v1` (see Task 8.2 request.ts), so the
//       proxy maps `/api/v1` → http://localhost:8080. We also proxy `/swagger-ui`
//       and `/v3/api-docs` for Swagger UI access during dev.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/swagger-ui': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/v3/api-docs': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});