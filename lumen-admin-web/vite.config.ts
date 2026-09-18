import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// https://vitejs.dev/config/
// NOTE: Backend exposes endpoints under `/api/v1/...` (see lumen-parent SecurityConfig).
//       The frontend axios baseURL is `/api/v1` (see Task 8.2 request.ts), so the
//       proxy maps `/api/v1` → http://localhost:8080. We also proxy `/swagger-ui`
//       and `/v3/api-docs` for Swagger UI access during dev.
//
// manualChunks splits the heavy vendor surface (react / antd / pro / umi)
// into separate chunks. The page modules themselves are already lazy-loaded
// via React.lazy in App.tsx, so the initial payload stays small.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons'],
          pro: ['@ant-design/pro-components'],
          umi: ['@umijs/max'],
        },
      },
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
