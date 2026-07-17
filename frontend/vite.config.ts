import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: proxy the API + datapath to the Kotlin service on :8787. In Spec B the /gateway
// target flips to the Go gateway's dev port; the service keeps /api + /healthz.
// Build: emit to ./dist — the nginx image serves it (no longer baked into the jar).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8787',
      '/gateway': 'http://127.0.0.1:8787',
      '/v1': 'http://127.0.0.1:8787',
      '/healthz': 'http://127.0.0.1:8787',
      // Routing gateways run as their own processes (go run ./cmd/openai|anthropic) in dev.
      '/routing/openai': { target: 'http://127.0.0.1:9100', rewrite: (p) => p.replace(/^\/routing\/openai/, '') },
      '/routing/anthropic': { target: 'http://127.0.0.1:9200', rewrite: (p) => p.replace(/^\/routing\/anthropic/, '') },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
