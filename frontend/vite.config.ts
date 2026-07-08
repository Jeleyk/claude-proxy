import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: proxy API + proxy datapath to the Kotlin backend on :8787.
// Build: emit straight into the backend's static resources so one jar serves everything.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8787',
      '/v1': 'http://127.0.0.1:8787',
      '/healthz': 'http://127.0.0.1:8787',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
});
