// defineConfig comes from vitest/config, not vite: only that one accepts the `test` block
// below. Importing it from 'vite' leaves `test` unknown to the compiler and fails the
// typecheck, since tsconfig type-checks this file too.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import path from 'node:path';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt', // a supervisor mid-entry must never be reloaded out from under
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'Nirman',
        short_name: 'Nirman',
        description: 'Attendance, materials, expenses and daily reports for construction sites',
        theme_color: '#14181D',
        background_color: '#FFFFFF',
        display: 'standalone',
        orientation: 'portrait',
        start_url: '/',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-512-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        // API responses are never cached: stale stock or attendance is worse than no data.
        navigateFallbackDenylist: [/^\/api/],
        runtimeCaching: [
          {
            urlPattern: /\/api\/v1\/(materials|workers|sites|units)/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'reference-data',
              networkTimeoutSeconds: 5,
              expiration: { maxEntries: 200, maxAgeSeconds: 60 * 60 * 24 },
            },
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    host: true,
    // The app calls a relative /api/v1 in dev; the backend answers on 8080.
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    globals: true,
    // Vitest owns src/, Playwright owns e2e/. Without this, Vitest's default glob picks up
    // the .spec.ts files under e2e/ and fails on Playwright's imports.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    // Vitest's 5s default is not enough for the screens that open a MUI dialog or drawer:
    // in jsdom those render the whole component tree twice over, and under the parallel
    // load of a full run a test that takes ~2s alone can take three times that. The tests
    // were passing individually and failing in the suite, which is a machine speed problem
    // rather than a slow assertion, so the limit moves rather than the tests.
    testTimeout: 20_000,
  },
});
